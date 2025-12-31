package com.ipcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.*
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RTSP Server implementation for hardware-accelerated H.264 streaming
 * 
 * Implementation follows RTSP_RECOMMENDATION.md architecture:
 * - RTSP protocol handler (DESCRIBE, SETUP, PLAY, TEARDOWN)
 * - RTP packetizer for H.264 NAL units
 * - MediaCodec hardware encoder integration
 * - Multi-client session management
 * 
 * Industry standard for IP cameras compatible with:
 * - VLC, FFmpeg, and all major media players
 * - Professional NVR software (ZoneMinder, Shinobi, Blue Iris, MotionEye)
 * - Lower latency than HLS (~500ms-1s vs 6-12s)
 * - No container format issues (streams raw H.264 over RTP)
 */
class RTSPServer(
    private val port: Int = 8554, // Standard RTSP port
    private var width: Int = 1920,
    private var height: Int = 1080,
    private val fps: Int = 30,
    initialBitrate: Int = calculateBitrate(width, height) // Dynamic based on resolution
) {
    private var serverSocket: ServerSocket? = null
    private var encoder: MediaCodec? = null
    private val sessions = ConcurrentHashMap<String, RTSPSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    private val isEncoding = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val droppedFrameCount = AtomicLong(0)
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    @Volatile private var encoderName: String = "unknown"
    @Volatile private var isHardwareEncoder: Boolean = false
    @Volatile private var lastError: String? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile private var encoderColorFormat: Int = -1
    @Volatile private var encoderColorFormatName: String = "unknown"
    
    // Encoder configuration (mutable for runtime changes)
    @Volatile private var bitrate: Int = initialBitrate
    @Volatile private var bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
    @Volatile private var bitrateModeName: String = "VBR"
    
    // Frame timing control
    @Volatile private var lastFrameTimeNs: Long = 0
    @Volatile private var lastQueueFullLogTimeMs: Long = 0
    private val logThrottleMs = 5000L // Log at most every 5 seconds
    @Volatile private var streamStartTimeMs: Long = 0
    
    // Reusable buffers
    private var yDataBuffer: ByteArray? = null
    private var uvDataBuffer: ByteArray? = null
    private var yRowBuffer: ByteArray? = null
    private var uvRowBuffer: ByteArray? = null
    
    companion object {
        private const val TAG = "RTSPServer"
        private const val TIMEOUT_US = 10_000L
        private const val RTP_VERSION = 2
        private const val RTP_PT_H264 = 96 // Dynamic payload type for H.264
        
        /**
         * Calculate appropriate bitrate based on resolution
         * Uses industry-standard bitrate guidelines for H.264 streaming
         */
        fun calculateBitrate(width: Int, height: Int): Int {
            val pixels = width * height
            return when {
                // 4K: 2160p (3840x2160)
                pixels >= 3840 * 2160 -> 12_000_000  // 12 Mbps
                // 1440p (2560x1440)
                pixels >= 2560 * 1440 -> 8_000_000   // 8 Mbps
                // 1080p (1920x1080)
                pixels >= 1920 * 1080 -> 5_000_000   // 5 Mbps
                // 720p (1280x720)
                pixels >= 1280 * 720 -> 3_000_000    // 3 Mbps
                // 480p (854x480 or 640x480)
                pixels >= 640 * 480 -> 1_500_000     // 1.5 Mbps
                // Lower resolutions
                else -> 1_000_000                     // 1 Mbps
            }
        }
        
        /**
         * Check if hardware H.264 encoder is available
         */
        fun isHardwareEncoderAvailable(): Boolean {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                val isHardware = !codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                                !codecInfo.name.contains("c2.android", ignoreCase = true)
                
                if (isHardware) {
                    Log.d(TAG, "Hardware H.264 encoder found: ${codecInfo.name}")
                    return true
                }
            }
            return false
        }
    }
    
    /**
     * RTSP session for a connected client
     */
    private inner class RTSPSession(
        val sessionId: String,
        val socket: Socket,
        @Volatile var state: SessionState = SessionState.INIT
    ) {
        var clientAddress: InetAddress? = null
        var clientRtpPort: Int = 0
        var clientRtcpPort: Int = 0
        var rtpSocket: DatagramSocket? = null
        var rtcpSocket: DatagramSocket? = null
        var serverRtpPort: Int = 0
        var serverRtcpPort: Int = 0
        var sequenceNumber = 0
        var timestamp: Long = 0
        val ssrc = (Math.random() * Int.MAX_VALUE).toInt()
        
        // TCP interleaved mode support
        var useTCP: Boolean = false
        var interleavedRtpChannel: Int = 0
        var interleavedRtcpChannel: Int = 1
        private val tcpOutputStream: OutputStream? get() = if (useTCP && !socket.isClosed) socket.getOutputStream() else null
        
        fun sendRTP(nalUnit: ByteArray, isKeyFrame: Boolean) {
            try {
                val rtpPackets = packetizeNALUnit(nalUnit, isKeyFrame)
                
                if (useTCP) {
                    // TCP interleaved mode - send over RTSP socket
                    tcpOutputStream?.let { stream ->
                        var sentCount = 0
                        rtpPackets.forEach { packet ->
                            // RFC 2326 Section 10.12: Interleaved Binary Data
                            // Format: $ <channel> <length_msb> <length_lsb> <data>
                            val header = byteArrayOf(
                                0x24, // '$' marker
                                interleavedRtpChannel.toByte(),
                                (packet.size shr 8).toByte(), // length MSB
                                (packet.size and 0xFF).toByte() // length LSB
                            )
                            synchronized(stream) {
                                stream.write(header)
                                stream.write(packet)
                                stream.flush()
                                sentCount++
                            }
                        }
                        if (sequenceNumber == 0) {
                            Log.d(TAG, "TCP: Sent first ${sentCount} RTP packets for session $sessionId, keyframe=$isKeyFrame")
                        }
                    } ?: run {
                        Log.w(TAG, "TCP stream not available for session $sessionId")
                    }
                } else {
                    // UDP mode - send via DatagramSocket
                    if (clientAddress == null || clientRtpPort == 0) {
                        Log.w(TAG, "UDP: clientAddress or port not set for session $sessionId")
                        return
                    }
                    
                    val socket = rtpSocket
                    if (socket == null) {
                        Log.w(TAG, "UDP: rtpSocket is null for session $sessionId")
                        return
                    }
                    
                    if (socket.isClosed) {
                        Log.w(TAG, "UDP: socket closed for session $sessionId")
                        return
                    }
                    
                    var sentCount = 0
                    rtpPackets.forEach { packet ->
                        val dgPacket = DatagramPacket(
                            packet,
                            packet.size,
                            clientAddress,
                            clientRtpPort
                        )
                        socket.send(dgPacket)
                        sentCount++
                    }
                    
                    if (sequenceNumber < 5) {
                        Log.d(TAG, "UDP: Sent ${sentCount} RTP packets to ${clientAddress}:${clientRtpPort} for session $sessionId, keyframe=$isKeyFrame, seq=$sequenceNumber")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending RTP packet for session $sessionId", e)
            }
        }
        
        private fun packetizeNALUnit(nalUnit: ByteArray, isKeyFrame: Boolean): List<ByteArray> {
            val maxPayloadSize = 1400 // MTU - headers
            val packets = mutableListOf<ByteArray>()
            
            if (nalUnit.size <= maxPayloadSize) {
                // Single NAL unit mode
                val rtpPacket = createRTPPacket(nalUnit, marker = true)
                packets.add(rtpPacket)
            } else {
                // Fragmentation Unit (FU-A) mode for large NAL units
                val nalHeader = nalUnit[0]
                val nalType = (nalHeader.toInt() and 0x1F)
                val fuIndicator = ((nalHeader.toInt() and 0xE0) or 28).toByte() // FU-A type
                
                var offset = 1
                var isFirst = true
                var isLast = false
                
                while (offset < nalUnit.size) {
                    val fragmentSize = minOf(maxPayloadSize, nalUnit.size - offset)
                    isLast = (offset + fragmentSize >= nalUnit.size)
                    
                    val fuHeader = (
                        (if (isFirst) 0x80 else 0) or
                        (if (isLast) 0x40 else 0) or
                        nalType
                    ).toByte()
                    
                    val payload = ByteArray(2 + fragmentSize)
                    payload[0] = fuIndicator
                    payload[1] = fuHeader
                    System.arraycopy(nalUnit, offset, payload, 2, fragmentSize)
                    
                    val rtpPacket = createRTPPacket(payload, marker = isLast)
                    packets.add(rtpPacket)
                    
                    offset += fragmentSize
                    isFirst = false
                }
            }
            
            return packets
        }
        
        private fun createRTPPacket(payload: ByteArray, marker: Boolean): ByteArray {
            val packet = ByteArray(12 + payload.size) // RTP header (12 bytes) + payload
            
            // Byte 0: Version (2), Padding (0), Extension (0), CSRC count (0)
            packet[0] = (RTP_VERSION shl 6).toByte()
            
            // Byte 1: Marker bit, Payload type
            packet[1] = ((if (marker) 1 shl 7 else 0) or RTP_PT_H264).toByte()
            
            // Bytes 2-3: Sequence number
            packet[2] = (sequenceNumber shr 8).toByte()
            packet[3] = (sequenceNumber and 0xFF).toByte()
            sequenceNumber++
            
            // Bytes 4-7: Timestamp (90kHz clock for video)
            val ts = (frameCount.get() * 90000L / fps).toInt()
            packet[4] = (ts shr 24).toByte()
            packet[5] = (ts shr 16).toByte()
            packet[6] = (ts shr 8).toByte()
            packet[7] = (ts and 0xFF).toByte()
            
            // Bytes 8-11: SSRC
            packet[8] = (ssrc shr 24).toByte()
            packet[9] = (ssrc shr 16).toByte()
            packet[10] = (ssrc shr 8).toByte()
            packet[11] = (ssrc and 0xFF).toByte()
            
            // Payload
            System.arraycopy(payload, 0, packet, 12, payload.size)
            
            return packet
        }
    }
    
    enum class SessionState {
        INIT, READY, PLAYING
    }
    
    /**
     * Start RTSP server
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "RTSP server already running")
            return false
        }
        
        try {
            // Detect encoder and color format early for web UI display
            detectEncoderCapabilities()
            
            // Mark encoding as enabled (encoder will be created on first frame)
            isEncoding.set(true)
            
            // Start server socket
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            
            // Start accepting connections
            serverJob = serverScope.launch {
                acceptConnections()
            }
            
            Log.i(TAG, "RTSP server started on port $port")
            Log.i(TAG, "Encoder: $encoderName (hardware: $isHardwareEncoder), Color format: $encoderColorFormatName")
            Log.i(TAG, "Encoder will be initialized on first frame")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            lastError = "Server start failed: ${e.message}"
            cleanup()
            return false
        }
    }
    
    /**
     * Detect encoder capabilities early for web UI display
     * This doesn't actually create the encoder, just queries capabilities
     */
    private fun detectEncoderCapabilities() {
        try {
            // Select best encoder (sets encoderName and isHardwareEncoder)
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                if (!codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                    !codecInfo.name.contains("c2.android", ignoreCase = true)) {
                    encoderName = codecInfo.name
                    isHardwareEncoder = true
                    
                    // Detect color format
                    val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val colorFormats = capabilities.colorFormats
                    
                    Log.d(TAG, "Available color formats for $encoderName:")
                    colorFormats.forEach { format ->
                        Log.d(TAG, "  - ${getColorFormatName(format)} (0x${Integer.toHexString(format)})")
                    }
                    
                    // Prefer NV12 (YUV420 semi-planar)
                    for (format in colorFormats) {
                        if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                            encoderColorFormat = format
                            encoderColorFormatName = getColorFormatName(format)
                            Log.i(TAG, "Selected preferred format: $encoderColorFormatName")
                            return
                        }
                    }
                    
                    // Try other supported formats
                    for (format in colorFormats) {
                        when (format) {
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                                encoderColorFormat = format
                                encoderColorFormatName = getColorFormatName(format)
                                Log.i(TAG, "Selected format: $encoderColorFormatName")
                                return
                            }
                        }
                    }
                    
                    // Fallback to first available
                    if (colorFormats.isNotEmpty()) {
                        encoderColorFormat = colorFormats[0]
                        encoderColorFormatName = getColorFormatName(colorFormats[0])
                        Log.w(TAG, "Using fallback format: $encoderColorFormatName")
                    }
                    return
                }
            }
            
            // Software encoder fallback
            Log.w(TAG, "Hardware encoder not found, will use software fallback")
            encoderName = "software"
            isHardwareEncoder = false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting encoder capabilities", e)
            encoderName = "detection failed"
            isHardwareEncoder = false
        }
    }
    
    /**
     * Initialize H.264 encoder
     */
    private fun initializeEncoder(): Boolean {
        try {
            Log.d(TAG, "Initializing encoder for ${width}x${height}")
            encoder = selectBestEncoder()
            
            val colorFormat = getSupportedColorFormat()
            if (colorFormat == -1) {
                Log.e(TAG, "No supported color format found")
                return false
            }
            
            encoderColorFormat = colorFormat
            encoderColorFormatName = getColorFormatName(colorFormat)
            Log.i(TAG, "Using color format: $encoderColorFormatName (0x${Integer.toHexString(colorFormat)})")
            
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )
            
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
            
            // Set baseline profile for maximum compatibility
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            
            Log.i(TAG, "Encoder configuration: ${width}x${height} @ ${fps}fps, bitrate=${bitrate} bps (${bitrate / 1_000_000} Mbps), mode=$bitrateModeName, format=$encoderColorFormatName")
            Log.d(TAG, "Full MediaFormat: $format")
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            Log.i(TAG, "Encoder started successfully: $encoderName (hardware: $isHardwareEncoder)")
            
            isEncoding.set(true)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            lastError = "Encoder init failed: ${e.message}"
            return false
        }
    }
    
    /**
     * Recreate encoder with new dimensions
     */
    private fun recreateEncoder(newWidth: Int, newHeight: Int): Boolean {
        Log.i(TAG, "Recreating encoder with dimensions: ${newWidth}x${newHeight}")
        
        // Update dimensions
        width = newWidth
        height = newHeight
        
        // Recalculate bitrate for new resolution
        bitrate = calculateBitrate(width, height)
        Log.i(TAG, "Adjusted bitrate for ${width}x${height}: $bitrate bps (${bitrate / 1_000_000} Mbps)")
        
        // Recreate buffers
        yDataBuffer = null
        uvDataBuffer = null
        yRowBuffer = null
        uvRowBuffer = null
        
        // Initialize new encoder
        return initializeEncoder()
    }
    
    /**
     * Select best available H.264 encoder
     */
    private fun selectBestEncoder(): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
            
            if (!codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                !codecInfo.name.contains("c2.android", ignoreCase = true)) {
                encoderName = codecInfo.name
                isHardwareEncoder = true
                Log.d(TAG, "Selected hardware encoder: $encoderName")
                return MediaCodec.createByCodecName(codecInfo.name)
            }
        }
        
        Log.w(TAG, "Hardware encoder not found, using software fallback")
        encoderName = "software"
        isHardwareEncoder = false
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }
    
    /**
     * Get supported color format
     */
    private fun getSupportedColorFormat(): Int {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                if (codecInfo.name != encoderName) continue
                
                val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val colorFormats = capabilities.colorFormats
                
                Log.d(TAG, "Available color formats for $encoderName:")
                colorFormats.forEach { format ->
                    Log.d(TAG, "  - ${getColorFormatName(format)} (0x${Integer.toHexString(format)})")
                }
                
                // Prefer NV12 (YUV420 semi-planar) as it's most common and efficient
                for (format in colorFormats) {
                    if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                        Log.i(TAG, "Selected preferred format: COLOR_FormatYUV420SemiPlanar (NV12)")
                        return format
                    }
                }
                
                // Try other supported formats
                for (format in colorFormats) {
                    when (format) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                            Log.i(TAG, "Selected format: ${getColorFormatName(format)}")
                            return format
                        }
                    }
                }
                
                // Fallback to first available format
                if (colorFormats.isNotEmpty()) {
                    Log.w(TAG, "Using fallback format: ${getColorFormatName(colorFormats[0])}")
                    return colorFormats[0]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting color format", e)
        }
        return -1
    }
    
    /**
     * Get human-readable name for color format
     */
    private fun getColorFormatName(format: Int): String {
        return when (format) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "COLOR_FormatYUV420Flexible"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "COLOR_FormatYUV420Planar (I420)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "COLOR_FormatYUV420SemiPlanar (NV12)"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "COLOR_FormatYUV420PackedPlanar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "COLOR_FormatYUV420PackedSemiPlanar (NV21)"
            0x7F420888 -> "COLOR_FormatYUV420Flexible (Android)"
            else -> "Unknown (0x${Integer.toHexString(format)})"
        }
    }
    
    /**
     * Accept incoming RTSP connections
     */
    private suspend fun acceptConnections() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept()
                if (clientSocket != null) {
                    serverScope.launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                }
            }
        }
    }
    
    /**
     * Handle RTSP client connection
     */
    private suspend fun handleClient(socket: Socket) {
        val sessionId = "session${sessionIdCounter.incrementAndGet()}"
        Log.d(TAG, "New RTSP client connected: $sessionId from ${socket.inetAddress}")
        
        val session = RTSPSession(sessionId, socket)
        sessions[sessionId] = session
        
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            
            while (isRunning.get() && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isEmpty()) continue
                
                val parts = requestLine.split(" ")
                if (parts.size < 3) continue
                
                val method = parts[0]
                val url = parts[1]
                
                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val headerParts = line!!.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].trim().lowercase()] = headerParts[1].trim()
                    }
                }
                
                // Handle RTSP methods
                when (method) {
                    "OPTIONS" -> handleOptions(writer, headers)
                    "DESCRIBE" -> handleDescribe(writer, url, headers)
                    "SETUP" -> handleSetup(writer, session, headers)
                    "PLAY" -> handlePlay(writer, session, headers)
                    "PAUSE" -> handlePause(writer, session, headers)
                    "TEARDOWN" -> handleTeardown(writer, session, headers)
                    else -> sendResponse(writer, "405 Method Not Allowed", headers["cseq"] ?: "0")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client $sessionId", e)
        } finally {
            sessions.remove(sessionId)
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
            Log.d(TAG, "Client disconnected: $sessionId")
        }
    }
    
    private fun handleOptions(writer: BufferedWriter, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Public: DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private suspend fun handleDescribe(writer: BufferedWriter, url: String, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        Log.d(TAG, "DESCRIBE request received, waiting for SPS/PPS...")
        
        // Wait briefly for SPS/PPS to be available (encoder needs to start first)
        var retries = 0
        val maxRetries = 150 // 150 * 100ms = 15 seconds max wait
        while ((sps == null || pps == null) && retries < maxRetries) {
            if (retries % 10 == 0) {
                Log.d(TAG, "Waiting for SPS/PPS... attempt ${retries}/${maxRetries}, encoder=${encoder != null}, encoding=${isEncoding.get()}, frames=${frameCount.get()}")
            }
            kotlinx.coroutines.delay(100)
            retries++
        }
        
        if (sps == null || pps == null) {
            Log.w(TAG, "SPS/PPS not available after ${retries * 100}ms. Encoder=${encoder != null}, Encoding=${isEncoding.get()}, Frames=${frameCount.get()}")
            writer.write("RTSP/1.0 500 Internal Server Error\r\n")
            writer.write("CSeq: $cseq\r\n")
            writer.write("Content-Type: text/plain\r\n")
            writer.write("\r\n")
            writer.write("Encoder not ready. SPS=${sps != null}, PPS=${pps != null}, Frames=${frameCount.get()}. Please ensure camera is streaming.\r\n")
            writer.flush()
            return
        }
        
        Log.i(TAG, "SPS/PPS available after ${retries * 100}ms, generating SDP")
        
        // Generate SDP (Session Description Protocol)
        val spsBase64 = android.util.Base64.encodeToString(sps!!, android.util.Base64.NO_WRAP)
        val ppsBase64 = android.util.Base64.encodeToString(pps!!, android.util.Base64.NO_WRAP)
        
        val sdp = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=IP_Cam RTSP Stream
            c=IN IP4 0.0.0.0
            t=0 0
            a=tool:IP_Cam RTSP Server
            a=type:broadcast
            a=control:*
            a=range:npt=0-
            m=video 0 RTP/AVP $RTP_PT_H264
            a=rtpmap:$RTP_PT_H264 H264/90000
            a=fmtp:$RTP_PT_H264 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=$spsBase64,$ppsBase64
            a=control:track0
        """.trimIndent()
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Content-Type: application/sdp\r\n")
        writer.write("Content-Length: ${sdp.length}\r\n")
        writer.write("\r\n")
        writer.write(sdp)
        writer.flush()
    }
    
    private fun handleSetup(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        val transport = headers["transport"] ?: ""
        
        // Parse transport header for client ports
        // Example UDP: RTP/AVP;unicast;client_port=5000-5001
        // Example TCP: RTP/AVP/TCP;unicast;interleaved=0-1
        
        try {
            if (transport.contains("TCP", ignoreCase = true) || transport.contains("interleaved")) {
                // TCP interleaved mode
                val interleavedPattern = Regex("interleaved=(\\d+)-(\\d+)")
                val match = interleavedPattern.find(transport)
                
                if (match != null) {
                    session.useTCP = true
                    session.interleavedRtpChannel = match.groupValues[1].toInt()
                    session.interleavedRtcpChannel = match.groupValues[2].toInt()
                    session.state = SessionState.READY
                    
                    val responseTransport = "RTP/AVP/TCP;unicast;interleaved=${session.interleavedRtpChannel}-${session.interleavedRtcpChannel}"
                    
                    Log.d(TAG, "SETUP TCP transport for session ${session.sessionId}: interleaved=${session.interleavedRtpChannel}-${session.interleavedRtcpChannel}")
                    
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: ${session.sessionId}\r\n")
                    writer.write("Transport: $responseTransport\r\n")
                    writer.write("\r\n")
                    writer.flush()
                } else {
                    // TCP requested but no interleaved channels specified, use defaults
                    session.useTCP = true
                    session.interleavedRtpChannel = 0
                    session.interleavedRtcpChannel = 1
                    session.state = SessionState.READY
                    
                    val responseTransport = "RTP/AVP/TCP;unicast;interleaved=0-1"
                    
                    Log.d(TAG, "SETUP TCP transport for session ${session.sessionId}: interleaved=0-1 (default)")
                    
                    writer.write("RTSP/1.0 200 OK\r\n")
                    writer.write("CSeq: $cseq\r\n")
                    writer.write("Session: ${session.sessionId}\r\n")
                    writer.write("Transport: $responseTransport\r\n")
                    writer.write("\r\n")
                    writer.flush()
                }
                return
            }
            
            // Parse UDP client ports
            val portPattern = Regex("client_port=(\\d+)-(\\d+)")
            val match = portPattern.find(transport)
            if (match != null) {
                session.useTCP = false
                session.clientRtpPort = match.groupValues[1].toInt()
                session.clientRtcpPort = match.groupValues[2].toInt()
                session.clientAddress = session.socket.inetAddress
                
                // Allocate server RTP/RTCP ports
                session.rtpSocket = DatagramSocket()
                session.serverRtpPort = session.rtpSocket!!.localPort
                session.rtcpSocket = DatagramSocket()
                session.serverRtcpPort = session.rtcpSocket!!.localPort
                
                session.state = SessionState.READY
                
                Log.d(TAG, "SETUP UDP transport for session ${session.sessionId}: client=${session.clientRtpPort}-${session.clientRtcpPort}, server=${session.serverRtpPort}-${session.serverRtcpPort}")
                
                val responseTransport = "RTP/AVP;unicast;client_port=${session.clientRtpPort}-${session.clientRtcpPort};" +
                                      "server_port=${session.serverRtpPort}-${session.serverRtcpPort}"
                
                writer.write("RTSP/1.0 200 OK\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("Session: ${session.sessionId}\r\n")
                writer.write("Transport: $responseTransport\r\n")
                writer.write("\r\n")
                writer.flush()
            } else {
                writer.write("RTSP/1.0 400 Bad Request\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("\r\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in SETUP", e)
            writer.write("RTSP/1.0 500 Internal Server Error\r\n")
            writer.write("CSeq: $cseq\r\n")
            writer.write("\r\n")
            writer.flush()
        }
    }
    
    private fun handlePlay(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        session.state = SessionState.PLAYING
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("RTP-Info: url=track0;seq=0;rtptime=0\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun handleTeardown(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        session.state = SessionState.INIT
        
        // Close RTP/RTCP sockets
        try {
            session.rtpSocket?.close()
            session.rtcpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session sockets", e)
        }
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun handlePause(writer: BufferedWriter, session: RTSPSession, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        session.state = SessionState.READY
        
        writer.write("RTSP/1.0 200 OK\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("Session: ${session.sessionId}\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    private fun sendResponse(writer: BufferedWriter, status: String, cseq: String) {
        writer.write("RTSP/1.0 $status\r\n")
        writer.write("CSeq: $cseq\r\n")
        writer.write("\r\n")
        writer.flush()
    }
    
    /**
     * Encode frame from camera
     */
    fun encodeFrame(image: ImageProxy): Boolean {
        if (!isEncoding.get()) {
            return false
        }
        
        try {
            // === Encoder Initialization/Recreation ===
            // Check if encoder needs to be (re)created due to resolution mismatch
            if (encoder == null || image.width != width || image.height != height) {
                if (encoder != null) {
                    Log.i(TAG, "Resolution changed from ${width}x${height} to ${image.width}x${image.height}, recreating encoder")
                    try {
                        encoder?.stop()
                        encoder?.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping old encoder", e)
                    }
                    encoder = null
                    sps = null
                    pps = null
                }
                
                // Recreate encoder with actual frame dimensions
                if (!recreateEncoder(image.width, image.height)) {
                    return false
                }
            }
            
            // Log first successful frame
            if (frameCount.get() == 0L) {
                Log.i(TAG, "Encoding first frame: ${image.width}x${image.height} @ ${fps} fps")
                streamStartTimeMs = System.currentTimeMillis()
                lastFrameTimeNs = System.nanoTime()
            }
            
            // === Encode Frame ===
            // Get input buffer with timeout
            val inputBufferIndex = encoder?.dequeueInputBuffer(TIMEOUT_US) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder?.getInputBuffer(inputBufferIndex)
                
                if (inputBuffer != null) {
                    fillInputBuffer(inputBuffer, image)
                    
                    val presentationTimeUs = frameCount.get() * 1_000_000L / fps
                    val bufferSize = inputBuffer.remaining() // Size of data after flip()
                    encoder?.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        bufferSize,
                        presentationTimeUs,
                        0
                    )
                    frameCount.incrementAndGet()
                    
                    // Update timing for FPS calculation
                    lastFrameTimeNs = System.nanoTime()
                    
                    if (frameCount.get() == 1L) {
                        Log.i(TAG, "First frame queued with size: $bufferSize bytes")
                    }
                }
            } else {
                // Encoder input queue full - this is normal under load
                val currentTimeMs = System.currentTimeMillis()
                if (currentTimeMs - lastQueueFullLogTimeMs > logThrottleMs) {
                    Log.d(TAG, "Encoder input buffer unavailable (queue full), ${droppedFrameCount.get()} total frames dropped")
                    lastQueueFullLogTimeMs = currentTimeMs
                }
                droppedFrameCount.incrementAndGet()
                return false
            }
            
            // Retrieve encoded output
            drainEncoder()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame", e)
            lastError = "Frame encoding failed: ${e.message}"
            return false
        }
    }
    
    /**
     * Fill input buffer with YUV data
     * Converts YUV_420_888 from camera to encoder's expected format (NV12/I420)
     */
    private fun fillInputBuffer(buffer: ByteBuffer, image: ImageProxy) {
        try {
            buffer.clear()
            
            val planes = image.planes
            if (planes.size < 3) {
                Log.e(TAG, "Invalid image format: expected 3 planes, got ${planes.size}")
                return
            }
            
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            
            // Create duplicate buffers to avoid corrupting shared ImageProxy buffers
            val yBuffer = yPlane.buffer.duplicate()
            val uBuffer = uPlane.buffer.duplicate()
            val vBuffer = vPlane.buffer.duplicate()
            
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            
            // Log format details on first frame
            if (frameCount.get() == 0L) {
                Log.i(TAG, "YUV_420_888 format details:")
                Log.i(TAG, "  Y: ${width}x${height}, rowStride=$yRowStride, pixelStride=$yPixelStride")
                Log.i(TAG, "  U: ${width/2}x${height/2}, rowStride=$uvRowStride, pixelStride=$uvPixelStride")
                Log.i(TAG, "  V: ${width/2}x${height/2}, rowStride=$uvRowStride, pixelStride=$uvPixelStride")
                Log.i(TAG, "  Encoder expects: $encoderColorFormatName")
            }
            
            // Initialize reusable buffers
            if (yDataBuffer == null) {
                yDataBuffer = ByteArray(width * height)
            }
            if (yRowBuffer == null || yRowBuffer!!.size < yRowStride) {
                yRowBuffer = ByteArray(yRowStride)
            }
            
            // === Copy Y plane ===
            yBuffer.rewind()
            if (yRowStride == width && yPixelStride == 1) {
                // Contiguous Y plane - fast path
                val ySize = width * height
                yBuffer.get(yDataBuffer!!, 0, ySize)
                buffer.put(yDataBuffer!!, 0, ySize)
            } else {
                // Non-contiguous Y plane - copy row by row
                var destOffset = 0
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    val bytesToRead = minOf(yRowStride, yBuffer.remaining())
                    yBuffer.get(yRowBuffer!!, 0, bytesToRead)
                    
                    if (yPixelStride == 1) {
                        // Packed pixels - simple copy
                        System.arraycopy(yRowBuffer!!, 0, yDataBuffer!!, destOffset, width)
                    } else {
                        // Sparse pixels - extract every nth pixel
                        for (col in 0 until width) {
                            yDataBuffer!![destOffset + col] = yRowBuffer!![col * yPixelStride]
                        }
                    }
                    destOffset += width
                }
                buffer.put(yDataBuffer!!, 0, width * height)
            }
            
            // === Copy UV planes ===
            val uvHeight = height / 2
            val uvWidth = width / 2
            
            if (uvDataBuffer == null) {
                uvDataBuffer = ByteArray(uvWidth * uvHeight * 2)
            }
            if (uvRowBuffer == null || uvRowBuffer!!.size < uvRowStride) {
                uvRowBuffer = ByteArray(uvRowStride)
            }
            
            uBuffer.rewind()
            vBuffer.rewind()
            
            // Convert based on encoder's expected format
            when (encoderColorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                    // NV12: Y plane + interleaved UV (UVUVUV...)
                    convertToNV12(buffer, uBuffer, vBuffer, uvWidth, uvHeight, uvRowStride, uvPixelStride)
                }
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> {
                    // NV21: Y plane + interleaved VU (VUVUVU...)
                    convertToNV21(buffer, uBuffer, vBuffer, uvWidth, uvHeight, uvRowStride, uvPixelStride)
                }
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    // I420: Y plane + U plane + V plane
                    convertToI420(buffer, uBuffer, vBuffer, uvWidth, uvHeight, uvRowStride, uvPixelStride)
                }
                else -> {
                    // COLOR_FormatYUV420Flexible or unknown - try NV12 as most common
                    Log.w(TAG, "Unknown color format, assuming NV12")
                    convertToNV12(buffer, uBuffer, vBuffer, uvWidth, uvHeight, uvRowStride, uvPixelStride)
                }
            }
            
            buffer.flip()
            
            if (frameCount.get() == 0L) {
                Log.i(TAG, "Filled input buffer: ${buffer.remaining()} bytes")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error filling input buffer", e)
            lastError = "Buffer fill failed: ${e.message}"
        }
    }
    
    /**
     * Convert YUV_420_888 UV planes to NV12 format (interleaved UVUV)
     */
    private fun convertToNV12(
        buffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        uvWidth: Int,
        uvHeight: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ) {
        var uvDestOffset = 0
        
        // Note: When uvPixelStride == 2, the U and V planes are already interleaved
        // in semi-planar format, but they're in SEPARATE buffers (U has UVUV, V has VUVU)
        // We need to extract from both or use the fact that U buffer already contains
        // the interleaved data if the stride matches.
        
        // Check if U buffer already contains properly interleaved NV12 data
        // This happens when the camera outputs NV12 directly
        // UV planes are half the resolution of Y plane, so rowStride should be width/2 for packed UV
        if (uvPixelStride == 2 && uvRowStride == uvWidth && uBuffer.remaining() >= uvWidth * uvHeight * 2) {
            // U buffer contains interleaved UV data in NV12 format - fast path
            val uvSize = uvWidth * uvHeight * 2
            uBuffer.get(uvDataBuffer!!, 0, minOf(uvSize, uBuffer.remaining()))
            buffer.put(uvDataBuffer!!, 0, uvSize)
        } else {
            // Manual interleaving required
            for (row in 0 until uvHeight) {
                uBuffer.position(row * uvRowStride)
                vBuffer.position(row * uvRowStride)
                
                for (col in 0 until uvWidth) {
                    // Write U sample
                    val uPos = col * uvPixelStride
                    if (uBuffer.position() + uPos < uBuffer.limit()) {
                        uvDataBuffer!![uvDestOffset++] = uBuffer.get(uBuffer.position() + uPos)
                    } else {
                        uvDataBuffer!![uvDestOffset++] = 128.toByte() // Neutral chroma
                    }
                    
                    // Write V sample
                    val vPos = col * uvPixelStride
                    if (vBuffer.position() + vPos < vBuffer.limit()) {
                        uvDataBuffer!![uvDestOffset++] = vBuffer.get(vBuffer.position() + vPos)
                    } else {
                        uvDataBuffer!![uvDestOffset++] = 128.toByte() // Neutral chroma
                    }
                }
            }
            buffer.put(uvDataBuffer!!, 0, uvDestOffset)
        }
    }
    
    /**
     * Convert YUV_420_888 UV planes to NV21 format (interleaved VUVU)
     */
    private fun convertToNV21(
        buffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        uvWidth: Int,
        uvHeight: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ) {
        var uvDestOffset = 0
        
        // Convert to NV21 - interleave V and U (reverse of NV12)
        for (row in 0 until uvHeight) {
            uBuffer.position(row * uvRowStride)
            vBuffer.position(row * uvRowStride)
            
            for (col in 0 until uvWidth) {
                // Write V sample first (NV21)
                val vPos = col * uvPixelStride
                if (vBuffer.position() + vPos < vBuffer.limit()) {
                    uvDataBuffer!![uvDestOffset++] = vBuffer.get(vBuffer.position() + vPos)
                } else {
                    uvDataBuffer!![uvDestOffset++] = 128.toByte()
                }
                
                // Write U sample second
                val uPos = col * uvPixelStride
                if (uBuffer.position() + uPos < uBuffer.limit()) {
                    uvDataBuffer!![uvDestOffset++] = uBuffer.get(uBuffer.position() + uPos)
                } else {
                    uvDataBuffer!![uvDestOffset++] = 128.toByte()
                }
            }
        }
        buffer.put(uvDataBuffer!!, 0, uvDestOffset)
    }
    
    /**
     * Convert YUV_420_888 UV planes to I420 format (planar U then V)
     */
    private fun convertToI420(
        buffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        uvWidth: Int,
        uvHeight: Int,
        uvRowStride: Int,
        uvPixelStride: Int
    ) {
        // I420: separate U and V planes
        var destOffset = 0
        
        // Copy U plane
        for (row in 0 until uvHeight) {
            uBuffer.position(row * uvRowStride)
            for (col in 0 until uvWidth) {
                val uPos = col * uvPixelStride
                if (uBuffer.position() + uPos < uBuffer.limit()) {
                    uvDataBuffer!![destOffset++] = uBuffer.get(uBuffer.position() + uPos)
                } else {
                    uvDataBuffer!![destOffset++] = 128.toByte()
                }
            }
        }
        
        // Copy V plane
        for (row in 0 until uvHeight) {
            vBuffer.position(row * uvRowStride)
            for (col in 0 until uvWidth) {
                val vPos = col * uvPixelStride
                if (vBuffer.position() + vPos < vBuffer.limit()) {
                    uvDataBuffer!![destOffset++] = vBuffer.get(vBuffer.position() + vPos)
                } else {
                    uvDataBuffer!![destOffset++] = 128.toByte()
                }
            }
        }
        
        buffer.put(uvDataBuffer!!, 0, destOffset)
    }
    
    /**
     * Drain encoder output
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        var iterations = 0
        val maxIterations = 10
        
        // Use longer timeout for first few frames to catch format change event
        val timeout = if (frameCount.get() <= 3L && (sps == null || pps == null)) {
            10000L // 10ms timeout for first frames to ensure format change is caught
        } else {
            0L // Non-blocking for subsequent frames
        }
        
        while (iterations++ < maxIterations) {
            val outputBufferIndex = encoder?.dequeueOutputBuffer(bufferInfo, timeout) ?: MediaCodec.INFO_TRY_AGAIN_LATER
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder?.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                    
                    // Extract SPS and PPS from format
                    try {
                        newFormat?.let { format ->
                            if (format.containsKey("csd-0")) {
                                val csd0 = format.getByteBuffer("csd-0")
                                sps = ByteArray(csd0!!.remaining())
                                csd0.get(sps!!)
                                Log.i(TAG, "Extracted SPS: ${sps!!.size} bytes")
                            }
                            if (format.containsKey("csd-1")) {
                                val csd1 = format.getByteBuffer("csd-1")
                                pps = ByteArray(csd1!!.remaining())
                                csd1.get(pps!!)
                                Log.i(TAG, "Extracted PPS: ${pps!!.size} bytes")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error extracting SPS/PPS", e)
                    }
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = encoder?.getOutputBuffer(outputBufferIndex)
                    
                    if (encodedData != null && bufferInfo.size > 0) {
                        // Extract NAL units and send to active sessions
                        val nalUnits = extractNALUnits(encodedData, bufferInfo)
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        sessions.values.forEach { session ->
                            if (session.state == SessionState.PLAYING) {
                                nalUnits.forEach { nalUnit ->
                                    session.sendRTP(nalUnit, isKeyFrame)
                                }
                            }
                        }
                    }
                    
                    encoder?.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Extract NAL units from encoded buffer
     */
    private fun extractNALUnits(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        
        buffer.position(bufferInfo.offset)
        buffer.limit(bufferInfo.offset + bufferInfo.size)
        
        val data = ByteArray(bufferInfo.size)
        buffer.get(data)
        
        // Find NAL units by start codes (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i+1] == 0.toByte()) {
                val startCodeLength = when {
                    data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                    data[i+2] == 1.toByte() -> 3
                    else -> 0
                }
                
                if (startCodeLength > 0) {
                    // Find next start code or end of buffer
                    var nextStart = i + startCodeLength
                    while (nextStart < data.size - 3) {
                        if (data[nextStart] == 0.toByte() && data[nextStart+1] == 0.toByte() &&
                            (data[nextStart+2] == 1.toByte() || 
                             (data[nextStart+2] == 0.toByte() && nextStart < data.size - 4 && data[nextStart+3] == 1.toByte()))) {
                            break
                        }
                        nextStart++
                    }
                    
                    if (nextStart > i + startCodeLength) {
                        val nalUnit = data.copyOfRange(i + startCodeLength, 
                            if (nextStart < data.size - 3) nextStart else data.size)
                        nalUnits.add(nalUnit)
                    }
                    
                    i = nextStart
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        
        return nalUnits
    }
    
    /**
     * Stop RTSP server
     */
    fun stop() {
        if (!isRunning.get()) return
        
        isRunning.set(false)
        isEncoding.set(false)
        
        try {
            // Close all sessions
            sessions.values.forEach { session ->
                try {
                    session.socket.close()
                    session.rtpSocket?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
            sessions.clear()
            
            // Stop server
            serverJob?.cancel()
            serverSocket?.close()
            serverSocket = null
            
            // Stop encoder
            encoder?.stop()
            encoder?.release()
            encoder = null
            
            Log.i(TAG, "RTSP server stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            lastError = "Server stop failed: ${e.message}"
        } finally {
            cleanup()
        }
    }
    
    /**
     * Cleanup resources
     */
    private fun cleanup() {
        yDataBuffer = null
        uvDataBuffer = null
        yRowBuffer = null
        uvRowBuffer = null
        sps = null
        pps = null
    }
    
    /**
     * Check if server is alive
     */
    fun isAlive(): Boolean = isRunning.get() && encoder != null
    
    /**
     * Get server metrics
     */
    fun getMetrics(): ServerMetrics {
        // Calculate encoded FPS (successful encodes) based on time elapsed
        // Note: This shows encoding rate, not input frame rate
        // Input frame rate would be higher when including dropped frames
        val encodedFps = if (streamStartTimeMs > 0 && frameCount.get() > 0) {
            val elapsedSec = (System.currentTimeMillis() - streamStartTimeMs) / 1000.0
            if (elapsedSec > 0) {
                (frameCount.get() / elapsedSec).toFloat()
            } else {
                0f
            }
        } else {
            0f
        }
        
        return ServerMetrics(
            encoderName = encoderName,
            isHardware = isHardwareEncoder,
            colorFormat = encoderColorFormatName,
            colorFormatHex = if (encoderColorFormat != -1) "0x${Integer.toHexString(encoderColorFormat)}" else "unknown",
            resolution = "${width}x${height}",
            bitrateMbps = bitrate / 1_000_000f,
            bitrateMode = bitrateModeName,
            activeSessions = sessions.size,
            playingSessions = sessions.values.count { it.state == SessionState.PLAYING },
            framesEncoded = frameCount.get(),
            droppedFrames = droppedFrameCount.get(),
            targetFps = fps,
            encodedFps = encodedFps, // Rate of successful encodes (excludes drops)
            lastError = lastError
        )
    }
    
    /**
     * Set bitrate at runtime (requires encoder recreation)
     */
    fun setBitrate(newBitrate: Int): Boolean {
        if (newBitrate <= 0) {
            Log.e(TAG, "Invalid bitrate: $newBitrate")
            return false
        }
        
        Log.i(TAG, "Changing bitrate from $bitrate to $newBitrate bps")
        bitrate = newBitrate
        
        // Recreate encoder if it's already running
        if (encoder != null && isEncoding.get()) {
            return recreateEncoder(width, height)
        }
        
        return true
    }
    
    /**
     * Set bitrate mode at runtime (VBR/CBR/CQ)
     */
    fun setBitrateMode(mode: String): Boolean {
        val newMode = when (mode.uppercase()) {
            "VBR" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            "CBR" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            "CQ" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ
            else -> {
                Log.e(TAG, "Invalid bitrate mode: $mode")
                return false
            }
        }
        
        Log.i(TAG, "Changing bitrate mode from $bitrateModeName to $mode")
        bitrateMode = newMode
        bitrateModeName = mode.uppercase()
        
        // Recreate encoder if it's already running
        if (encoder != null && isEncoding.get()) {
            return recreateEncoder(width, height)
        }
        
        return true
    }
    
    data class ServerMetrics(
        val encoderName: String,
        val isHardware: Boolean,
        val colorFormat: String,
        val colorFormatHex: String,
        val resolution: String,
        val bitrateMbps: Float,
        val bitrateMode: String,
        val activeSessions: Int,
        val playingSessions: Int,
        val framesEncoded: Long,
        val droppedFrames: Long,
        val targetFps: Int,
        val encodedFps: Float, // Actual encoding rate (successful frames/sec)
        val lastError: String?
    )
}
