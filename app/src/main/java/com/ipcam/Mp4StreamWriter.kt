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
        
        isRunning.set(false)
        
        if (isCodecStarted) {
            try {
                // Note: We don't call signalEndOfInputStream() when using a Preview surface
                // because the camera provides the surface input, not us directly.
                // Just drain any remaining frames
                drainEncoder(true)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping encoder", e)
            }
        }
        
        Log.d(TAG, "MP4 encoder stopped")
    }
    
    /**
     * Release all resources
     */
    fun release() {
        stop()
        
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
                    Log.d(TAG, "Encoder output format changed: $newFormat")
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
                        
                        // Add to queue for streaming
                        // Keep a rolling buffer: if queue is full, remove oldest frame
                        val frame = EncodedFrame(data, isKeyFrame, bufferInfo.presentationTimeUs)
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
    fun generateInitSegment(): ByteArray {
        // For a production implementation, use a proper MP4 muxer library
        // This is a simplified version for demonstration
        val output = ByteArrayOutputStream()
        
        // Write ftyp box
        output.write(FTYP_HEADER)
        
        // In a real implementation, you'd also write:
        // - moov box with track information
        // - codec configuration (SPS/PPS from MediaFormat)
        
        return output.toByteArray()
    }
    
    /**
     * Get codec configuration data (SPS/PPS)
     * This is needed for the MP4 initialization segment
     */
    fun getCodecConfig(): ByteArray? {
        val format = mediaCodec?.outputFormat ?: return null
        
        // Get CSD (Codec Specific Data) which contains SPS/PPS for H.264
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        
        if (csd0 == null && csd1 == null) {
            return null
        }
        
        val output = ByteArrayOutputStream()
        
        csd0?.let {
            val data = ByteArray(it.remaining())
            it.get(data)
            output.write(data)
        }
        
        csd1?.let {
            val data = ByteArray(it.remaining())
            it.get(data)
            output.write(data)
        }
        
        return output.toByteArray()
    }
    
    /**
     * Check if encoder is running
     */
    fun isRunning(): Boolean = isRunning.get()
}
