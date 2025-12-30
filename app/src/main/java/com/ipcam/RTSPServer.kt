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
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000 // 2 Mbps
) {
    private var serverSocket: ServerSocket? = null
    private var encoder: MediaCodec? = null
    private val sessions = ConcurrentHashMap<String, RTSPSession>()
    private val sessionIdCounter = AtomicInteger(0)
    private val isRunning = AtomicBoolean(false)
    private val isEncoding = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    @Volatile private var encoderName: String = "unknown"
    @Volatile private var isHardwareEncoder: Boolean = false
    @Volatile private var lastError: String? = null
    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    
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
        
        fun sendRTP(nalUnit: ByteArray, isKeyFrame: Boolean) {
            try {
                if (clientAddress == null || clientRtpPort == 0) {
                    return
                }
                
                val rtpPackets = packetizeNALUnit(nalUnit, isKeyFrame)
                rtpSocket?.let { socket ->
                    if (!socket.isClosed) {
                        rtpPackets.forEach { packet ->
                            val dgPacket = DatagramPacket(
                                packet,
                                packet.size,
                                clientAddress,
                                clientRtpPort
                            )
                            socket.send(dgPacket)
                        }
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
            // Initialize encoder
            if (!initializeEncoder()) {
                Log.e(TAG, "Failed to initialize encoder")
                return false
            }
            
            // Start server socket
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            
            // Start accepting connections
            serverJob = serverScope.launch {
                acceptConnections()
            }
            
            Log.i(TAG, "RTSP server started on port $port")
            Log.i(TAG, "Encoder: $encoderName (hardware: $isHardwareEncoder)")
            Log.i(TAG, "Configuration: ${width}x${height} @ ${fps}fps, ${bitrate}bps")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server", e)
            lastError = "Server start failed: ${e.message}"
            cleanup()
            return false
        }
    }
    
    /**
     * Initialize H.264 encoder
     */
    private fun initializeEncoder(): Boolean {
        try {
            encoder = selectBestEncoder()
            
            val colorFormat = getSupportedColorFormat()
            if (colorFormat == -1) {
                Log.e(TAG, "No supported color format found")
                return false
            }
            
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )
            
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            
            isEncoding.set(true)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encoder", e)
            lastError = "Encoder init failed: ${e.message}"
            return false
        }
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
                
                for (format in colorFormats) {
                    when (format) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                            return format
                        }
                    }
                }
                
                if (colorFormats.isNotEmpty()) {
                    return colorFormats[0]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting color format", e)
        }
        return -1
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
    
    private fun handleDescribe(writer: BufferedWriter, url: String, headers: Map<String, String>) {
        val cseq = headers["cseq"] ?: "0"
        
        // Wait briefly for SPS/PPS to be available (encoder needs to start first)
        var retries = 0
        while ((sps == null || pps == null) && retries < 50) {
            Thread.sleep(100)
            retries++
        }
        
        if (sps == null || pps == null) {
            Log.w(TAG, "SPS/PPS not available yet, sending error")
            writer.write("RTSP/1.0 500 Internal Server Error\r\n")
            writer.write("CSeq: $cseq\r\n")
            writer.write("\r\n")
            writer.flush()
            return
        }
        
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
        // Example: RTP/AVP;unicast;client_port=5000-5001
        // or: RTP/AVP/TCP;unicast;interleaved=0-1
        
        try {
            if (transport.contains("TCP", ignoreCase = true) || transport.contains("interleaved")) {
                // TCP interleaved mode - not fully supported yet, return error
                writer.write("RTSP/1.0 461 Unsupported Transport\r\n")
                writer.write("CSeq: $cseq\r\n")
                writer.write("\r\n")
                writer.flush()
                return
            }
            
            // Parse UDP client ports
            val portPattern = Regex("client_port=(\\d+)-(\\d+)")
            val match = portPattern.find(transport)
            if (match != null) {
                session.clientRtpPort = match.groupValues[1].toInt()
                session.clientRtcpPort = match.groupValues[2].toInt()
                session.clientAddress = session.socket.inetAddress
                
                // Allocate server RTP/RTCP ports
                session.rtpSocket = DatagramSocket()
                session.serverRtpPort = session.rtpSocket!!.localPort
                session.rtcpSocket = DatagramSocket()
                session.serverRtcpPort = session.rtcpSocket!!.localPort
                
                session.state = SessionState.READY
                
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
        if (!isEncoding.get() || encoder == null) return false
        
        try {
            // Validate dimensions
            if (image.width != width || image.height != height) {
                if (lastError != "Resolution mismatch: ${image.width}x${image.height}") {
                    Log.e(TAG, "Image resolution mismatch: ${image.width}x${image.height} vs ${width}x${height}")
                    lastError = "Resolution mismatch: ${image.width}x${image.height}"
                }
                return false
            }
            
            // Get input buffer
            val inputBufferIndex = encoder?.dequeueInputBuffer(TIMEOUT_US) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder?.getInputBuffer(inputBufferIndex)
                
                if (inputBuffer != null) {
                    fillInputBuffer(inputBuffer, image)
                    
                    val presentationTimeUs = frameCount.get() * 1_000_000L / fps
                    encoder?.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        inputBuffer.position(),
                        presentationTimeUs,
                        0
                    )
                    frameCount.incrementAndGet()
                }
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
            
            // Initialize reusable buffers
            if (yDataBuffer == null) {
                yDataBuffer = ByteArray(width * height)
            }
            if (yRowBuffer == null || yRowBuffer!!.size < yRowStride) {
                yRowBuffer = ByteArray(yRowStride)
            }
            
            // Copy Y plane
            yBuffer.rewind()
            if (yRowStride == width && yPixelStride == 1) {
                val ySize = width * height
                yBuffer.get(yDataBuffer!!, 0, ySize)
                buffer.put(yDataBuffer!!, 0, ySize)
            } else {
                var destOffset = 0
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    val bytesToRead = minOf(yRowStride, yBuffer.remaining())
                    yBuffer.get(yRowBuffer!!, 0, bytesToRead)
                    System.arraycopy(yRowBuffer!!, 0, yDataBuffer!!, destOffset, width)
                    destOffset += width
                }
                buffer.put(yDataBuffer!!, 0, width * height)
            }
            
            // Copy UV planes interleaved
            val uvHeight = height / 2
            val uvWidth = width / 2
            val uvSize = uvWidth * uvHeight * 2
            
            if (uvDataBuffer == null) {
                uvDataBuffer = ByteArray(uvSize)
            }
            if (uvRowBuffer == null || uvRowBuffer!!.size < uvRowStride) {
                uvRowBuffer = ByteArray(uvRowStride)
            }
            
            uBuffer.rewind()
            vBuffer.rewind()
            
            if (uvPixelStride == 2 && uvRowStride == width) {
                uBuffer.get(uvDataBuffer!!, 0, minOf(uvSize, uBuffer.remaining()))
                buffer.put(uvDataBuffer!!, 0, uvSize)
            } else {
                var uvDestOffset = 0
                for (row in 0 until uvHeight) {
                    uBuffer.position(row * uvRowStride)
                    vBuffer.position(row * uvRowStride)
                    
                    if (uBuffer.remaining() > 0 && vBuffer.remaining() > 0) {
                        val bytesToRead = minOf(uvRowStride, uBuffer.remaining())
                        uBuffer.get(uvRowBuffer!!, 0, bytesToRead)
                        vBuffer.position(row * uvRowStride)
                        
                        for (col in 0 until uvWidth) {
                            val uIndex = col * uvPixelStride
                            val vIndex = col * uvPixelStride
                            
                            if (uIndex < uvRowBuffer!!.size && uvDestOffset < uvDataBuffer!!.size) {
                                uvDataBuffer!![uvDestOffset++] = uvRowBuffer!![uIndex]
                            }
                            
                            vBuffer.position(row * uvRowStride + vIndex)
                            if (vBuffer.hasRemaining() && uvDestOffset < uvDataBuffer!!.size) {
                                uvDataBuffer!![uvDestOffset++] = vBuffer.get()
                            }
                        }
                    }
                }
                buffer.put(uvDataBuffer!!, 0, minOf(uvDestOffset, uvSize))
            }
            
            buffer.flip()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error filling input buffer", e)
            lastError = "Buffer fill failed: ${e.message}"
        }
    }
    
    /**
     * Drain encoder output
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        var iterations = 0
        val maxIterations = 10
        
        while (iterations++ < maxIterations) {
            val outputBufferIndex = encoder?.dequeueOutputBuffer(bufferInfo, 0) ?: MediaCodec.INFO_TRY_AGAIN_LATER
            
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
        return ServerMetrics(
            encoderName = encoderName,
            isHardware = isHardwareEncoder,
            activeSessions = sessions.size,
            playingSessions = sessions.values.count { it.state == SessionState.PLAYING },
            framesEncoded = frameCount.get(),
            lastError = lastError
        )
    }
    
    data class ServerMetrics(
        val encoderName: String,
        val isHardware: Boolean,
        val activeSessions: Int,
        val playingSessions: Int,
        val framesEncoded: Long,
        val lastError: String?
    )
}
