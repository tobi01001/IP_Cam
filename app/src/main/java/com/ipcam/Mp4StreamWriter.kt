package com.ipcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MP4 stream writer that encodes camera frames to H.264 and outputs fragmented MP4.
 * Uses hardware-accelerated MediaCodec for efficient encoding.
 * Outputs fragmented MP4 (fMP4) format suitable for HTTP streaming.
 */
class Mp4StreamWriter(
    private val resolution: Size,
    private val bitrate: Int = 2_000_000, // 2 Mbps default
    private val frameRate: Int = 30,
    private val iFrameInterval: Int = 2 // I-frame every 2 seconds
) {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private val encodedDataQueue = LinkedBlockingQueue<EncodedFrame>(100)
    private var trackIndex = -1
    private var isCodecStarted = false
    private var frameDropCount = 0 // Track dropped frames for logging
    
    // Cache the output format when it becomes available (contains SPS/PPS)
    @Volatile private var cachedOutputFormat: MediaFormat? = null
    @Volatile private var formatChangeReceived = AtomicBoolean(false)
    
    companion object {
        private const val TAG = "Mp4StreamWriter"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val TIMEOUT_USEC = 10000L
        
        // fMP4 header bytes (simplified version for streaming)
        // In production, you'd use a proper MP4 muxer library
        private val FTYP_HEADER = byteArrayOf(
            0x00, 0x00, 0x00, 0x20, // size
            0x66, 0x74, 0x79, 0x70, // 'ftyp'
            0x69, 0x73, 0x6F, 0x6D, // 'isom'
            0x00, 0x00, 0x02, 0x00, // minor version
            0x69, 0x73, 0x6F, 0x6D, // compatible brand: isom
            0x69, 0x73, 0x6F, 0x32, // compatible brand: iso2
            0x61, 0x76, 0x63, 0x31, // compatible brand: avc1
            0x6D, 0x70, 0x34, 0x31  // compatible brand: mp41
        )
    }
    
    data class EncodedFrame(
        val data: ByteArray,
        val isKeyFrame: Boolean,
        val presentationTimeUs: Long
    )
    
    /**
     * Initialize the MediaCodec encoder
     */
    @Throws(IOException::class)
    fun initialize() {
        Log.d(TAG, "Initializing MP4 encoder: ${resolution.width}x${resolution.height} @ ${frameRate}fps, ${bitrate}bps")
        
        val format = MediaFormat.createVideoFormat(MIME_TYPE, resolution.width, resolution.height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        
        try {
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            
            Log.d(TAG, "MediaCodec initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaCodec", e)
            release()
            throw IOException("Failed to initialize H.264 encoder", e)
        }
    }
    
    /**
     * Start encoding
     */
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "Encoder already running")
            return
        }
        
        mediaCodec?.start()
        isCodecStarted = true
        isRunning.set(true)
        Log.d(TAG, "MP4 encoder started")
    }
    
    /**
     * Stop encoding
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        // Set flag first to stop processing loop
        isRunning.set(false)
        
        // Clear cached format
        cachedOutputFormat = null
        formatChangeReceived.set(false)
        
        // Give processing coroutine time to exit cleanly
        // The encoder processing coroutine checks isRunning() and will stop
        // Don't call drainEncoder here - it conflicts with the processing coroutine
        
        Log.d(TAG, "MP4 encoder stopped (waiting for processing loop to exit)")
    }
    
    /**
     * Release all resources
     */
    fun release() {
        // Stop first (sets isRunning = false)
        stop()
        
        // Wait a bit for processing coroutine to exit
        Thread.sleep(50)
        
        inputSurface?.release()
        inputSurface = null
        
        if (isCodecStarted) {
            try {
                mediaCodec?.stop()
                isCodecStarted = false
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping codec", e)
            }
        }
        
        mediaCodec?.release()
        mediaCodec = null
        
        encodedDataQueue.clear()
        Log.d(TAG, "MP4 encoder released")
    }
    
    /**
     * Get the input surface for encoding
     * Connect this to CameraX's surface output
     */
    fun getInputSurface(): Surface? = inputSurface
    
    /**
     * Drain encoded data from the codec
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) {
                        break // No output available yet
                    }
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    cachedOutputFormat = newFormat
                    formatChangeReceived.set(true)
                    Log.d(TAG, "Encoder output format changed and cached: $newFormat")
                }
                outputBufferIndex < 0 -> {
                    Log.w(TAG, "Unexpected result from dequeueOutputBuffer: $outputBufferIndex")
                }
                else -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null) {
                        // Check if this is a key frame (I-frame)
                        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        // Copy the encoded data
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(data, 0, bufferInfo.size)
                        
                        // Convert from Annex B (start codes) to AVCC (length-prefixed) format
                        // This is CRITICAL for MP4 playback
                        val avccData = convertAnnexBToAvcc(data)
                        
                        // Add to queue for streaming
                        // Keep a rolling buffer: if queue is full, remove oldest frame
                        val frame = EncodedFrame(avccData, isKeyFrame, bufferInfo.presentationTimeUs)
                        if (!encodedDataQueue.offer(frame)) {
                            // Queue is full - remove oldest frame and add new one
                            encodedDataQueue.poll() // Remove oldest
                            encodedDataQueue.offer(frame) // Add newest
                            if (frameDropCount++ % 30 == 0) { // Log every 30 drops to avoid spam
                                Log.w(TAG, "Encoded frame queue full, maintaining rolling buffer (dropped $frameDropCount frames total)")
                            }
                        }
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "End of stream reached")
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Process encoder output and make encoded frames available
     * Should be called periodically to drain the encoder
     */
    fun processEncoderOutput() {
        if (!isRunning.get() || !isCodecStarted) {
            return
        }
        
        drainEncoder(false)
    }
    
    /**
     * Get the next encoded frame from the queue
     * Returns null if no frame is available
     */
    fun getNextEncodedFrame(): EncodedFrame? {
        return encodedDataQueue.poll()
    }
    
    /**
     * Check if frames are available
     */
    fun hasEncodedFrames(): Boolean = encodedDataQueue.isNotEmpty()
    
    /**
     * Generate fMP4 initialization segment
     * This should be sent once at the start of the stream
     */
    fun generateInitSegment(): ByteArray? {
        val output = ByteArrayOutputStream()
        
        // Get codec configuration (SPS/PPS) from MediaCodec
        val codecConfig = getCodecConfig()
        if (codecConfig == null) {
            Log.e(TAG, "Cannot generate init segment: codec config not available yet")
            return null
        }
        
        Log.d(TAG, "Generating init segment with codec config size: ${codecConfig.size} bytes")
        
        // Write ftyp box
        output.write(FTYP_HEADER)
        
        // Create moov box with proper track information
        // This is critical for MP4 playback
        try {
            val moovBox = createMoovBox(codecConfig)
            output.write(moovBox)
            Log.d(TAG, "Init segment generated successfully: ftyp=${FTYP_HEADER.size}B + moov=${moovBox.size}B = ${output.size()}B total")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create moov box", e)
            return null
        }
        
        return output.toByteArray()
    }
    
    /**
     * Create moov box with track information for H.264 video
     */
    private fun createMoovBox(codecConfig: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val moovContent = ByteArrayOutputStream()
        
        // mvhd box (movie header) - simplified
        val mvhdSize = 108
        moovContent.writeInt32(mvhdSize)
        moovContent.writeBoxType("mvhd")
        moovContent.writeInt32(0) // version and flags
        moovContent.writeInt32(0) // creation time
        moovContent.writeInt32(0) // modification time
        moovContent.writeInt32(frameRate) // timescale
        moovContent.writeInt32(0) // duration (unknown for live stream)
        moovContent.writeInt32(0x00010000) // rate (1.0 in 16.16 fixed point)
        moovContent.write(byteArrayOf(0x01, 0x00)) // volume (1.0 in 8.8 fixed point)
        moovContent.write(ByteArray(10)) // reserved
        // Unity matrix
        moovContent.writeInt32(0x00010000) // a
        moovContent.writeInt32(0) // b
        moovContent.writeInt32(0) // u
        moovContent.writeInt32(0) // c
        moovContent.writeInt32(0x00010000) // d
        moovContent.writeInt32(0) // v
        moovContent.writeInt32(0) // x
        moovContent.writeInt32(0) // y
        moovContent.writeInt32(0x40000000) // w (1.0 in 2.30 fixed point)
        moovContent.write(ByteArray(24)) // pre_defined
        moovContent.writeInt32(2) // next_track_ID
        
        // trak box (track)
        val trakContent = ByteArrayOutputStream()
        
        // tkhd box (track header)
        trakContent.writeInt32(92)
        trakContent.writeBoxType("tkhd")
        trakContent.writeInt32(0x00000007) // version 0, flags: track enabled, in movie, in preview
        trakContent.writeInt32(0) // creation time
        trakContent.writeInt32(0) // modification time
        trakContent.writeInt32(1) // track ID
        trakContent.writeInt32(0) // reserved
        trakContent.writeInt32(0) // duration
        trakContent.write(ByteArray(8)) // reserved
        trakContent.writeInt32(0) // layer
        trakContent.writeInt32(0) // alternate group
        trakContent.writeInt32(0) // volume (0 for video)
        trakContent.writeInt32(0) // reserved
        // Unity matrix
        trakContent.writeInt32(0x00010000)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0x00010000)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0)
        trakContent.writeInt32(0x40000000)
        trakContent.writeInt32(resolution.width shl 16) // width in 16.16 fixed point
        trakContent.writeInt32(resolution.height shl 16) // height in 16.16 fixed point
        
        // mdia box (media)
        val mdiaContent = ByteArrayOutputStream()
        
        // mdhd box (media header)
        mdiaContent.writeInt32(32)
        mdiaContent.writeBoxType("mdhd")
        mdiaContent.writeInt32(0) // version and flags
        mdiaContent.writeInt32(0) // creation time
        mdiaContent.writeInt32(0) // modification time
        mdiaContent.writeInt32(frameRate) // timescale
        mdiaContent.writeInt32(0) // duration
        mdiaContent.writeInt32(0x55c40000) // language: 'und' (undetermined)
        mdiaContent.writeInt32(0) // quality
        
        // hdlr box (handler reference)
        mdiaContent.writeInt32(45)
        mdiaContent.writeBoxType("hdlr")
        mdiaContent.writeInt32(0) // version and flags
        mdiaContent.writeInt32(0) // pre_defined
        mdiaContent.writeBoxType("vide") // handler type: video
        mdiaContent.write(ByteArray(12)) // reserved
        mdiaContent.write("VideoHandler".toByteArray(Charsets.US_ASCII))
        mdiaContent.write(0) // null terminator
        
        // minf box (media information)
        val minfContent = ByteArrayOutputStream()
        
        // vmhd box (video media header)
        minfContent.writeInt32(20)
        minfContent.writeBoxType("vmhd")
        minfContent.writeInt32(0x00000001) // version 0, flags 1
        minfContent.writeInt32(0) // graphicsmode and opcolor
        minfContent.writeInt32(0) // opcolor continued
        
        // dinf box (data information)
        val dinfContent = ByteArrayOutputStream()
        // dref box (data reference)
        dinfContent.writeInt32(28)
        dinfContent.writeBoxType("dref")
        dinfContent.writeInt32(0) // version and flags
        dinfContent.writeInt32(1) // entry count
        // url entry
        dinfContent.writeInt32(12)
        dinfContent.writeBoxType("url ")
        dinfContent.writeInt32(0x00000001) // version 0, flags: self-contained
        
        val dinfBytes = dinfContent.toByteArray()
        minfContent.writeInt32(8 + dinfBytes.size)
        minfContent.writeBoxType("dinf")
        minfContent.write(dinfBytes)
        
        // stbl box (sample table)
        val stblContent = ByteArrayOutputStream()
        
        // stsd box (sample description)
        val stsdContent = ByteArrayOutputStream()
        stsdContent.writeInt32(0) // version and flags
        stsdContent.writeInt32(1) // entry count
        
        // avc1 sample entry
        val avc1Content = ByteArrayOutputStream()
        avc1Content.write(ByteArray(6)) // reserved
        avc1Content.writeInt32(1) // data reference index
        avc1Content.writeInt32(0) // pre_defined
        avc1Content.writeInt32(0) // reserved
        avc1Content.write(ByteArray(12)) // pre_defined
        avc1Content.writeInt32(resolution.width) // width
        avc1Content.writeInt32(resolution.height) // height
        avc1Content.writeInt32(0x00480000) // horizresolution 72 dpi
        avc1Content.writeInt32(0x00480000) // vertresolution 72 dpi
        avc1Content.writeInt32(0) // reserved
        avc1Content.writeInt32(1) // frame count
        avc1Content.write(ByteArray(32)) // compressor name (32 bytes)
        avc1Content.writeInt32(0x0018) // depth (24-bit)
        avc1Content.writeInt32(0xFFFF) // pre_defined
        
        // avcC box (AVC configuration) - contains SPS/PPS
        avc1Content.writeInt32(8 + codecConfig.size)
        avc1Content.writeBoxType("avcC")
        avc1Content.write(codecConfig)
        
        val avc1Bytes = avc1Content.toByteArray()
        stsdContent.writeInt32(8 + avc1Bytes.size)
        stsdContent.writeBoxType("avc1")
        stsdContent.write(avc1Bytes)
        
        val stsdBytes = stsdContent.toByteArray()
        stblContent.writeInt32(8 + stsdBytes.size)
        stblContent.writeBoxType("stsd")
        stblContent.write(stsdBytes)
        
        // stts box (time to sample)
        stblContent.writeInt32(16)
        stblContent.writeBoxType("stts")
        stblContent.writeInt32(0) // version and flags
        stblContent.writeInt32(0) // entry count
        
        // stsc box (sample to chunk)
        stblContent.writeInt32(16)
        stblContent.writeBoxType("stsc")
        stblContent.writeInt32(0) // version and flags
        stblContent.writeInt32(0) // entry count
        
        // stsz box (sample size)
        stblContent.writeInt32(20)
        stblContent.writeBoxType("stsz")
        stblContent.writeInt32(0) // version and flags
        stblContent.writeInt32(0) // sample size
        stblContent.writeInt32(0) // sample count
        
        // stco box (chunk offset)
        stblContent.writeInt32(16)
        stblContent.writeBoxType("stco")
        stblContent.writeInt32(0) // version and flags
        stblContent.writeInt32(0) // entry count
        
        val stblBytes = stblContent.toByteArray()
        minfContent.writeInt32(8 + stblBytes.size)
        minfContent.writeBoxType("stbl")
        minfContent.write(stblBytes)
        
        val minfBytes = minfContent.toByteArray()
        mdiaContent.writeInt32(8 + minfBytes.size)
        mdiaContent.writeBoxType("minf")
        mdiaContent.write(minfBytes)
        
        val mdiaBytes = mdiaContent.toByteArray()
        trakContent.writeInt32(8 + mdiaBytes.size)
        trakContent.writeBoxType("mdia")
        trakContent.write(mdiaBytes)
        
        val trakBytes = trakContent.toByteArray()
        moovContent.writeInt32(8 + trakBytes.size)
        moovContent.writeBoxType("trak")
        moovContent.write(trakBytes)
        
        // mvex box (movie extends for fragmented MP4)
        val mvexContent = ByteArrayOutputStream()
        
        // trex box (track extends)
        mvexContent.writeInt32(32)
        mvexContent.writeBoxType("trex")
        mvexContent.writeInt32(0) // version and flags
        mvexContent.writeInt32(1) // track_ID
        mvexContent.writeInt32(1) // default_sample_description_index
        mvexContent.writeInt32(0) // default_sample_duration
        mvexContent.writeInt32(0) // default_sample_size
        mvexContent.writeInt32(0) // default_sample_flags
        
        val mvexBytes = mvexContent.toByteArray()
        moovContent.writeInt32(8 + mvexBytes.size)
        moovContent.writeBoxType("mvex")
        moovContent.write(mvexBytes)
        
        // Write final moov box
        val moovBytes = moovContent.toByteArray()
        out.writeInt32(8 + moovBytes.size)
        out.writeBoxType("moov")
        out.write(moovBytes)
        
        return out.toByteArray()
    }
    
    // Helper methods for writing box data
    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
    
    private fun ByteArrayOutputStream.writeBoxType(type: String) {
        write(type.toByteArray(Charsets.US_ASCII))
    }
    
    /**
     * Get codec configuration data (SPS/PPS) in avcC format
     * This is needed for the MP4 initialization segment
     */
    fun getCodecConfig(): ByteArray? {
        // Use cached output format if available (preferred)
        val format = cachedOutputFormat ?: mediaCodec?.outputFormat
        
        if (format == null) {
            Log.w(TAG, "No output format available yet - codec may not have started encoding")
            return null
        }
        
        // Get CSD (Codec Specific Data) which contains SPS/PPS for H.264
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        
        if (csd0 == null) {
            Log.e(TAG, "No csd-0 buffer found in codec output format")
            return null
        }
        
        // csd-0 typically contains SPS, csd-1 typically contains PPS
        // But sometimes both are in csd-0, so we need to check both
        
        // Read csd-0 data
        val csd0Data = ByteArray(csd0.remaining())
        csd0.position(0) // Reset position
        csd0.get(csd0Data)
        
        Log.d(TAG, "Parsing codec config from csd-0 (${csd0Data.size} bytes)")
        
        // Parse SPS and PPS from start codes (0x00 0x00 0x00 0x01)
        val sps = mutableListOf<Byte>()
        val pps = mutableListOf<Byte>()
        
        // Parse csd-0
        var i = 0
        while (i < csd0Data.size) {
            // Find start code
            if (i + 3 < csd0Data.size && 
                csd0Data[i] == 0.toByte() && 
                csd0Data[i + 1] == 0.toByte() && 
                csd0Data[i + 2] == 0.toByte() && 
                csd0Data[i + 3] == 1.toByte()) {
                
                i += 4 // Skip start code
                
                if (i >= csd0Data.size) break
                
                val nalType = csd0Data[i].toInt() and 0x1F
                
                // Find next start code or end of data
                var nalEnd = i
                while (nalEnd + 3 < csd0Data.size) {
                    if (csd0Data[nalEnd] == 0.toByte() && 
                        csd0Data[nalEnd + 1] == 0.toByte() && 
                        csd0Data[nalEnd + 2] == 0.toByte() && 
                        csd0Data[nalEnd + 3] == 1.toByte()) {
                        break
                    }
                    nalEnd++
                }
                
                if (nalEnd == i) {
                    nalEnd = csd0Data.size
                }
                
                // Extract NAL unit
                val nalData = csd0Data.sliceArray(i until nalEnd)
                
                when (nalType) {
                    7 -> sps.addAll(nalData.toList()) // SPS
                    8 -> pps.addAll(nalData.toList()) // PPS
                }
                
                i = nalEnd
            } else {
                i++
            }
        }
        
        // Parse csd-1 if present (often contains PPS)
        if (csd1 != null) {
            val csd1Data = ByteArray(csd1.remaining())
            csd1.position(0)
            csd1.get(csd1Data)
            
            Log.d(TAG, "Parsing codec config from csd-1 (${csd1Data.size} bytes)")
            
            i = 0
            while (i < csd1Data.size) {
                // Find start code
                if (i + 3 < csd1Data.size && 
                    csd1Data[i] == 0.toByte() && 
                    csd1Data[i + 1] == 0.toByte() && 
                    csd1Data[i + 2] == 0.toByte() && 
                    csd1Data[i + 3] == 1.toByte()) {
                    
                    i += 4 // Skip start code
                    
                    if (i >= csd1Data.size) break
                    
                    val nalType = csd1Data[i].toInt() and 0x1F
                    
                    // Find next start code or end of data
                    var nalEnd = i
                    while (nalEnd + 3 < csd1Data.size) {
                        if (csd1Data[nalEnd] == 0.toByte() && 
                            csd1Data[nalEnd + 1] == 0.toByte() && 
                            csd1Data[nalEnd + 2] == 0.toByte() && 
                            csd1Data[nalEnd + 3] == 1.toByte()) {
                            break
                        }
                        nalEnd++
                    }
                    
                    if (nalEnd == i) {
                        nalEnd = csd1Data.size
                    }
                    
                    // Extract NAL unit
                    val nalData = csd1Data.sliceArray(i until nalEnd)
                    
                    when (nalType) {
                        7 -> sps.addAll(nalData.toList()) // SPS
                        8 -> pps.addAll(nalData.toList()) // PPS
                    }
                    
                    i = nalEnd
                } else {
                    i++
                }
            }
        }
        
        if (sps.isEmpty()) {
            Log.e(TAG, "No SPS found in codec config")
            return null
        }
        
        if (pps.isEmpty()) {
            Log.e(TAG, "No PPS found in codec config - this will cause playback failure!")
            // PPS is critical for H.264 decoding, but we'll continue and see if it works
            // Some very old devices might not provide PPS in csd buffers
        }
        
        Log.d(TAG, "Parsed SPS (${sps.size} bytes) and PPS (${pps.size} bytes)")
        
        // Build avcC configuration
        val output = ByteArrayOutputStream()
        
        // configurationVersion
        output.write(1)
        
        // AVCProfileIndication, profile_compatibility, AVCLevelIndication
        if (sps.size >= 3) {
            output.write(sps[0].toInt())
            output.write(sps[1].toInt())
            output.write(sps[2].toInt())
        } else {
            output.write(0x42) // Baseline profile
            output.write(0x00)
            output.write(0x1E) // Level 3.0
        }
        
        // lengthSizeMinusOne (4 bytes length)
        output.write(0xFF)
        
        // numOfSequenceParameterSets
        output.write(0xE1) // 0xE0 | 1
        
        // SPS length
        output.write((sps.size shr 8) and 0xFF)
        output.write(sps.size and 0xFF)
        
        // SPS data
        output.write(sps.toByteArray())
        
        // numOfPictureParameterSets
        output.write(if (pps.isEmpty()) 0 else 1)
        
        if (pps.isNotEmpty()) {
            // PPS length
            output.write((pps.size shr 8) and 0xFF)
            output.write(pps.size and 0xFF)
            
            // PPS data
            output.write(pps.toByteArray())
        }
        
        return output.toByteArray()
    }
    
    /**
     * Convert H.264 data from Annex B format (start codes) to AVCC format (length-prefixed)
     * MP4 containers require AVCC format, but MediaCodec outputs Annex B format
     */
    private fun convertAnnexBToAvcc(annexBData: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var i = 0
        
        while (i < annexBData.size) {
            // Find start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
            var startCodeLength = 0
            
            if (i + 3 < annexBData.size &&
                annexBData[i] == 0.toByte() &&
                annexBData[i + 1] == 0.toByte() &&
                annexBData[i + 2] == 0.toByte() &&
                annexBData[i + 3] == 1.toByte()) {
                startCodeLength = 4
            } else if (i + 2 < annexBData.size &&
                annexBData[i] == 0.toByte() &&
                annexBData[i + 1] == 0.toByte() &&
                annexBData[i + 2] == 1.toByte()) {
                startCodeLength = 3
            }
            
            if (startCodeLength > 0) {
                // Found start code, skip it
                i += startCodeLength
                
                // Find next start code to determine NAL unit size
                var nalEnd = i
                while (nalEnd < annexBData.size) {
                    // Check for 4-byte start code
                    if (nalEnd + 3 < annexBData.size &&
                        annexBData[nalEnd] == 0.toByte() &&
                        annexBData[nalEnd + 1] == 0.toByte() &&
                        annexBData[nalEnd + 2] == 0.toByte() &&
                        annexBData[nalEnd + 3] == 1.toByte()) {
                        break
                    }
                    // Check for 3-byte start code
                    if (nalEnd + 2 < annexBData.size &&
                        annexBData[nalEnd] == 0.toByte() &&
                        annexBData[nalEnd + 1] == 0.toByte() &&
                        annexBData[nalEnd + 2] == 1.toByte()) {
                        break
                    }
                    nalEnd++
                }
                
                // Calculate NAL unit size
                val nalSize = nalEnd - i
                
                if (nalSize > 0) {
                    // Write 4-byte length prefix (big-endian)
                    output.write((nalSize shr 24) and 0xFF)
                    output.write((nalSize shr 16) and 0xFF)
                    output.write((nalSize shr 8) and 0xFF)
                    output.write(nalSize and 0xFF)
                    
                    // Write NAL unit data
                    output.write(annexBData, i, nalSize)
                }
                
                i = nalEnd
            } else {
                // No start code found, this shouldn't happen in valid Annex B data
                // Just copy the rest as-is
                Log.w(TAG, "No start code found at position $i, copying remaining ${annexBData.size - i} bytes")
                output.write(annexBData, i, annexBData.size - i)
                break
            }
        }
        
        return output.toByteArray()
    }
    
    /**
     * Check if encoder is running
     */
    fun isRunning(): Boolean = isRunning.get()
    
    /**
     * Check if codec format (SPS/PPS) is available
     * This is needed before generating the init segment
     */
    fun isFormatAvailable(): Boolean = formatChangeReceived.get()
}
