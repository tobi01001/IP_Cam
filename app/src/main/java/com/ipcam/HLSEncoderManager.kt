package com.ipcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * HLS Encoder Manager - Hardware-accelerated H.264 encoding for HLS streaming
 * 
 * Implementation follows STREAMING_ARCHITECTURE.md requirements REQ-HW-001 through REQ-HW-011:
 * - REQ-HW-001: HLS protocol selection
 * - REQ-HW-002: Hardware encoder detection via MediaCodec
 * - REQ-HW-003: H.264 encoder configuration (2 Mbps, 30 fps, VBR)
 * - REQ-HW-004: Segment management (2-sec segments, 10-segment sliding window)
 * - REQ-HW-005: HTTP endpoints for playlist and segments
 * - REQ-HW-007: Robust error handling and recovery
 * - REQ-HW-008: Performance monitoring
 * 
 * This component operates alongside MJPEG streaming, providing bandwidth-efficient
 * streaming at the cost of higher latency (6-12 seconds).
 */
class HLSEncoderManager(
    private val cacheDir: File,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000, // 2 Mbps
    private val segmentDurationSec: Int = 2
) {
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private val segmentIndex = AtomicInteger(0)
    private val segmentStartTime = AtomicLong(0)
    private val segmentFiles = mutableListOf<File>()
    private val maxSegments = 10
    
    private val isEncoding = AtomicBoolean(false)
    private val frameCount = AtomicLong(0)
    private val encodingTimeMs = AtomicLong(0)
    private val encodedFrameCount = AtomicLong(0)
    
    @Volatile private var encoderName: String = "unknown"
    @Volatile private var isHardwareEncoder: Boolean = false
    @Volatile private var lastError: String? = null
    
    private val segmentLock = Any()
    
    companion object {
        private const val TAG = "HLSEncoderManager"
        private const val TIMEOUT_US = 10_000L // 10ms timeout for encoder operations
        
        /**
         * Check if hardware H.264 encoder is available on the device.
         * REQ-HW-002: Hardware encoder detection
         */
        fun isHardwareEncoderAvailable(): Boolean {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                // Hardware encoders don't contain "OMX.google" or "c2.android"
                val isHardware = !codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                                !codecInfo.name.contains("c2.android", ignoreCase = true)
                
                if (isHardware) {
                    Log.d(TAG, "Hardware encoder found: ${codecInfo.name}")
                    return true
                }
            }
            Log.w(TAG, "No hardware encoder found, software fallback available")
            return false
        }
        
        /**
         * Get detailed encoder capabilities for diagnostics
         */
        fun getEncoderCapabilities(): EncoderCapabilities? {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) continue
                if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
                
                val isHardware = !codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                                !codecInfo.name.contains("c2.android", ignoreCase = true)
                
                if (isHardware) {
                    val capabilities = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    val videoCapabilities = capabilities.videoCapabilities
                    
                    return EncoderCapabilities(
                        codecName = codecInfo.name,
                        isHardware = true,
                        maxWidth = videoCapabilities.supportedWidths.upper,
                        maxHeight = videoCapabilities.supportedHeights.upper,
                        maxFrameRate = videoCapabilities.supportedFrameRates.upper.toInt(),
                        bitrateRange = videoCapabilities.bitrateRange
                    )
                }
            }
            return null
        }
    }
    
    data class EncoderCapabilities(
        val codecName: String,
        val isHardware: Boolean,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFrameRate: Int,
        val bitrateRange: android.util.Range<Int>?
    )
    
    /**
     * Start the HLS encoder
     * REQ-HW-002: Hardware encoder detection and selection
     * REQ-HW-003: Encoder configuration
     */
    fun start(): Boolean {
        if (isEncoding.get()) {
            Log.w(TAG, "Encoder already running")
            return false
        }
        
        try {
            // Try to create hardware encoder first
            // REQ-HW-002: Prefer hardware encoder, fallback to software
            encoder = selectBestEncoder()
            
            // Get supported color format from encoder
            val colorFormat = getSupportedColorFormat()
            if (colorFormat == -1) {
                Log.e(TAG, "No supported color format found for encoder")
                return false
            }
            
            Log.d(TAG, "Using color format: $colorFormat")
            
            // Create output format for H.264
            // REQ-HW-003: H.264 encoder configuration
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height
            )
            
            // Configure encoder settings with supported color format
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second
            
            // Advanced encoder settings for better quality
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, 
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) // Variable bitrate
            
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            
            isEncoding.set(true)
            segmentStartTime.set(System.currentTimeMillis())
            frameCount.set(0)
            encodedFrameCount.set(0)
            encodingTimeMs.set(0)
            
            // Create HLS segment directory
            val segmentDir = File(cacheDir, "hls_segments")
            if (!segmentDir.exists()) {
                segmentDir.mkdirs()
            }
            
            startNewSegment()
            
            Log.i(TAG, "HLS encoder started: $encoderName (hardware: $isHardwareEncoder)")
            Log.i(TAG, "Configuration: ${width}x${height} @ ${fps}fps, ${bitrate}bps, color format: $colorFormat")
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HLS encoder", e)
            lastError = "Encoder start failed: ${e.message}"
            cleanup()
            return false
        }
    }
    
    /**
     * Select the best available encoder (hardware preferred)
     * REQ-HW-002: Hardware encoder detection and selection
     */
    private fun selectBestEncoder(): MediaCodec {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        
        // Try hardware encoder first
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
            
            // Hardware encoders don't contain "OMX.google" or "c2.android"
            if (!codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                !codecInfo.name.contains("c2.android", ignoreCase = true)) {
                encoderName = codecInfo.name
                isHardwareEncoder = true
                Log.d(TAG, "Selected hardware encoder: $encoderName")
                return MediaCodec.createByCodecName(codecInfo.name)
            }
        }
        
        // Fallback to any available H.264 encoder (likely software)
        Log.w(TAG, "Hardware encoder not found, using software fallback")
        encoderName = "software"
        isHardwareEncoder = false
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }
    
    /**
     * Get supported color format from the encoder
     * Returns the first supported format, preferring NV12/NV21
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
                
                // Prefer NV12 or NV21 (semi-planar YUV)
                for (format in colorFormats) {
                    when (format) {
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                            Log.d(TAG, "Using COLOR_FormatYUV420SemiPlanar (NV12)")
                            return format
                        }
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                            Log.d(TAG, "Using COLOR_FormatYUV420Planar (I420)")
                            return format
                        }
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> {
                            Log.d(TAG, "Using COLOR_FormatYUV420Flexible")
                            return format
                        }
                    }
                }
                
                // Return first available format if no preferred format found
                if (colorFormats.isNotEmpty()) {
                    Log.d(TAG, "Using first available color format: ${colorFormats[0]}")
                    return colorFormats[0]
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting supported color format", e)
        }
        return -1
    }
    
    /**
     * Encode a single frame from camera
     * REQ-HW-003: Feed YUV frames to encoder
     */
    fun encodeFrame(image: ImageProxy): Boolean {
        if (!isEncoding.get()) return false
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Validate encoder state
            if (encoder == null) {
                Log.e(TAG, "Encoder is null, cannot encode frame")
                return false
            }
            
            // Get input buffer from encoder
            val inputBufferIndex = encoder?.dequeueInputBuffer(TIMEOUT_US) ?: -1
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder?.getInputBuffer(inputBufferIndex)
                
                if (inputBuffer == null) {
                    Log.e(TAG, "Input buffer is null for index $inputBufferIndex")
                    return false
                }
                
                // Validate buffer size
                val requiredSize = width * height * 3 / 2  // YUV420 size
                if (inputBuffer.capacity() < requiredSize) {
                    Log.e(TAG, "Input buffer too small: ${inputBuffer.capacity()} < $requiredSize")
                    return false
                }
                
                fillInputBuffer(inputBuffer, image)
                
                val presentationTimeUs = frameCount.get() * 1_000_000L / fps
                encoder?.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    inputBuffer.position(),  // Use actual data size
                    presentationTimeUs,
                    0
                )
                frameCount.incrementAndGet()
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Normal - encoder is busy
                return true
            } else {
                Log.w(TAG, "Unexpected buffer index: $inputBufferIndex")
            }
            
            // Retrieve encoded output
            drainEncoder(false)
            
            // Check if segment is complete
            // REQ-HW-004: 2-second segment duration
            val segmentDuration = System.currentTimeMillis() - segmentStartTime.get()
            if (segmentDuration >= segmentDurationSec * 1000L) {
                synchronized(segmentLock) {
                    finalizeSegment()
                    startNewSegment()
                }
            }
            
            // Track encoding time for monitoring
            val encodeTime = System.currentTimeMillis() - startTime
            encodingTimeMs.addAndGet(encodeTime)
            encodedFrameCount.incrementAndGet()
            
            return true
            
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Encoder illegal state", e)
            lastError = "Encoder state error: ${e.message}"
            // Try to recover by stopping encoder
            isEncoding.set(false)
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame", e)
            lastError = "Frame encoding failed: ${e.message}"
            return false
        }
    }
    
    /**
     * Convert YUV_420_888 ImageProxy to NV12 format for encoder
     * REQ-HW-003: Color format conversion
     * 
     * Handles proper stride and padding for YUV planes
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
            
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride
            
            // Copy Y plane with stride handling
            yBuffer.rewind()
            if (yRowStride == width && yPixelStride == 1) {
                // Contiguous memory, direct copy
                val ySize = width * height
                val yBytes = ByteArray(ySize)
                yBuffer.get(yBytes, 0, ySize)
                buffer.put(yBytes)
            } else {
                // Need to handle stride
                val rowData = ByteArray(yRowStride)
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    yBuffer.get(rowData, 0, minOf(yRowStride, yBuffer.remaining()))
                    buffer.put(rowData, 0, width)
                }
            }
            
            // Copy UV planes interleaved for NV12 format (UVUV...)
            val uvHeight = height / 2
            val uvWidth = width / 2
            
            uBuffer.rewind()
            vBuffer.rewind()
            
            if (uvPixelStride == 2 && uvRowStride == width) {
                // Already interleaved (NV12/NV21), can copy directly
                val uvSize = uvWidth * uvHeight * 2
                val uvBytes = ByteArray(uvSize)
                uBuffer.get(uvBytes, 0, minOf(uvSize, uBuffer.remaining()))
                buffer.put(uvBytes, 0, minOf(uvSize, uvBytes.size))
            } else {
                // Need to interleave U and V
                val uvRowData = ByteArray(uvRowStride)
                for (row in 0 until uvHeight) {
                    uBuffer.position(row * uvRowStride)
                    vBuffer.position(row * uvRowStride)
                    
                    val uRemaining = uBuffer.remaining()
                    val vRemaining = vBuffer.remaining()
                    
                    if (uRemaining > 0 && vRemaining > 0) {
                        uBuffer.get(uvRowData, 0, minOf(uvRowStride, uRemaining))
                        vBuffer.position(row * uvRowStride) // Reset position
                        
                        // Interleave U and V values
                        for (col in 0 until uvWidth) {
                            val uIndex = col * uvPixelStride
                            val vIndex = col * uvPixelStride
                            
                            if (uIndex < uvRowData.size && buffer.hasRemaining()) {
                                buffer.put(uvRowData[uIndex])
                            }
                            
                            vBuffer.position(row * uvRowStride + vIndex)
                            if (vBuffer.hasRemaining() && buffer.hasRemaining()) {
                                buffer.put(vBuffer.get())
                            }
                        }
                    }
                }
            }
            
            buffer.flip()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error filling input buffer", e)
            lastError = "Buffer fill failed: ${e.message}"
        }
    }
    
    /**
     * Start a new segment file
     * REQ-HW-004: Segment creation with MPEG-TS format
     */
    private fun startNewSegment() {
        val segmentDir = File(cacheDir, "hls_segments")
        val currentIndex = segmentIndex.getAndIncrement()
        val segmentFile = File(segmentDir, "segment${currentIndex}.ts")
        
        try {
            // Use MPEG-TS format for HLS compatibility
            // MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_2_TS = 8 (added in API 26)
            // For API 24-25, use MP4 format which is also HLS-compatible
            @Suppress("DEPRECATION")
            val outputFormat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // Use numeric constant for MPEG-TS on API 26+
                // This avoids compile-time issues while maintaining runtime correctness
                8 // MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_2_TS
            } else {
                // MP4 fallback for API 24-25
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            
            muxer = MediaMuxer(
                segmentFile.absolutePath,
                outputFormat
            )
            
            // Add video track (output format may not be available immediately)
            // This will be retried in drainEncoder if format changes
            val format = encoder?.outputFormat
            if (format != null) {
                videoTrackIndex = muxer?.addTrack(format) ?: -1
                muxer?.start()
            } else {
                Log.w(TAG, "Encoder output format not yet available, will add track on first frame")
            }
            
            synchronized(segmentFiles) {
                segmentFiles.add(segmentFile)
            }
            
            segmentStartTime.set(System.currentTimeMillis())
            
            Log.d(TAG, "Started new segment: ${segmentFile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting new segment", e)
            lastError = "Segment creation failed: ${e.message}"
        }
    }
    
    /**
     * Finalize current segment and cleanup old segments
     * REQ-HW-004: Sliding window of 10 segments
     */
    private fun finalizeSegment() {
        try {
            drainEncoder(true)
            
            muxer?.stop()
            muxer?.release()
            muxer = null
            
            // Cleanup old segments (keep only last maxSegments)
            // REQ-HW-004: Maintain sliding window
            synchronized(segmentFiles) {
                while (segmentFiles.size > maxSegments) {
                    val oldFile = segmentFiles.removeAt(0)
                    if (oldFile.exists()) {
                        oldFile.delete()
                        Log.d(TAG, "Deleted old segment: ${oldFile.name}")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing segment", e)
            lastError = "Segment finalization failed: ${e.message}"
        }
    }
    
    /**
     * Drain encoder output buffers
     */
    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        
        var iterations = 0
        val maxIterations = if (endOfStream) 100 else 10
        
        while (iterations++ < maxIterations) {
            val outputBufferIndex = encoder?.dequeueOutputBuffer(bufferInfo, 0) ?: MediaCodec.INFO_TRY_AGAIN_LATER
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = encoder?.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = encoder?.getOutputBuffer(outputBufferIndex)
                    
                    if (encodedData != null && bufferInfo.size > 0 && muxer != null) {
                        // Write to muxer
                        muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
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
     * Stop the encoder and cleanup resources
     * REQ-HW-007: Proper cleanup and error handling
     */
    fun stop() {
        if (!isEncoding.get()) return
        
        isEncoding.set(false)
        
        try {
            synchronized(segmentLock) {
                finalizeSegment()
            }
            
            encoder?.stop()
            encoder?.release()
            encoder = null
            
            Log.i(TAG, "HLS encoder stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
            lastError = "Encoder stop failed: ${e.message}"
        } finally {
            cleanup()
        }
    }
    
    /**
     * Cleanup all resources
     */
    private fun cleanup() {
        try {
            muxer?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            // Ignore
        }
        muxer = null
        
        try {
            encoder?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        try {
            encoder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        encoder = null
    }
    
    /**
     * Generate M3U8 playlist for HLS clients
     * REQ-HW-005: M3U8 playlist generation
     */
    fun generatePlaylist(): String {
        val playlist = StringBuilder()
        playlist.append("#EXTM3U\n")
        playlist.append("#EXT-X-VERSION:3\n")
        playlist.append("#EXT-X-TARGETDURATION:$segmentDurationSec\n")
        
        val currentIndex = segmentIndex.get()
        val mediaSequence = maxOf(0, currentIndex - maxSegments)
        playlist.append("#EXT-X-MEDIA-SEQUENCE:$mediaSequence\n")
        
        synchronized(segmentFiles) {
            segmentFiles.forEach { file ->
                if (file.exists()) {
                    playlist.append("#EXTINF:$segmentDurationSec.0,\n")
                    playlist.append("${file.name}\n")
                }
            }
        }
        
        return playlist.toString()
    }
    
    /**
     * Get segment file by name
     * REQ-HW-005: Segment file serving
     */
    fun getSegmentFile(segmentName: String): File? {
        val segmentDir = File(cacheDir, "hls_segments")
        val file = File(segmentDir, segmentName)
        return if (file.exists()) file else null
    }
    
    /**
     * Check if encoder is alive and healthy
     * REQ-HW-007: Health monitoring
     */
    fun isAlive(): Boolean {
        return isEncoding.get() && encoder != null
    }
    
    /**
     * Get performance metrics
     * REQ-HW-008: Performance monitoring
     */
    fun getMetrics(): EncoderMetrics {
        val avgEncodingTime = if (encodedFrameCount.get() > 0) {
            encodingTimeMs.get().toDouble() / encodedFrameCount.get()
        } else {
            0.0
        }
        
        val actualFps = if (encodingTimeMs.get() > 0) {
            encodedFrameCount.get() * 1000.0 / encodingTimeMs.get()
        } else {
            0.0
        }
        
        return EncoderMetrics(
            encoderName = encoderName,
            isHardware = isHardwareEncoder,
            framesEncoded = encodedFrameCount.get(),
            avgEncodingTimeMs = avgEncodingTime,
            actualFps = actualFps,
            activeSegments = segmentFiles.size,
            targetBitrate = bitrate,
            targetFps = fps,
            lastError = lastError
        )
    }
    
    data class EncoderMetrics(
        val encoderName: String,
        val isHardware: Boolean,
        val framesEncoded: Long,
        val avgEncodingTimeMs: Double,
        val actualFps: Double,
        val activeSegments: Int,
        val targetBitrate: Int,
        val targetFps: Int,
        val lastError: String?
    )
}
