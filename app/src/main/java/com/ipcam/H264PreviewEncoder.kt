package com.ipcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Custom H.264 encoder that receives frames from CameraX Preview
 * and outputs to RTSP server
 * 
 * Implementation follows CAMERA_EFFICIENCY_ANALYSIS.md architecture:
 * - Hardware-accelerated MediaCodec encoding
 * - Input surface for CameraX Preview integration
 * - Background thread for output draining
 * - NAL unit extraction and delivery to RTSP server
 */
class H264PreviewEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 5_000_000, // 5 Mbps
    private val rtspServer: RTSPServer?
) {
    
    companion object {
        private const val TAG = "H264PreviewEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val I_FRAME_INTERVAL = 2 // seconds
    }
    
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var isRunning = false
    private var drainThread: Thread? = null
    
    /**
     * Get the input surface to attach to CameraX Preview
     */
    fun getInputSurface(): Surface? = inputSurface
    
    /**
     * Initialize the encoder with progressive fallback configurations
     */
    fun start() {
        try {
            // Create MediaCodec encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            
            // Log encoder capabilities for debugging
            logEncoderCapabilities()
            
            // Try multiple configuration strategies with progressively relaxed constraints
            var configured = false
            var configAttempt = 1
            
            // Attempt 1: Full configuration with profile and VBR
            if (!configured) {
                configured = tryConfigureEncoder(
                    attempt = configAttempt++,
                    includeProfile = true,
                    includeBitrateMode = true
                )
            }
            
            // Attempt 2: Without explicit profile (let encoder choose)
            if (!configured) {
                Log.w(TAG, "Retrying without explicit profile constraint")
                configured = tryConfigureEncoder(
                    attempt = configAttempt++,
                    includeProfile = false,
                    includeBitrateMode = true
                )
            }
            
            // Attempt 3: Without bitrate mode (let encoder use default)
            if (!configured) {
                Log.w(TAG, "Retrying without bitrate mode constraint")
                configured = tryConfigureEncoder(
                    attempt = configAttempt++,
                    includeProfile = false,
                    includeBitrateMode = false
                )
            }
            
            if (!configured) {
                throw IllegalStateException("Failed to configure encoder after ${configAttempt - 1} attempts")
            }
            
            // Get input surface for CameraX
            inputSurface = encoder?.createInputSurface()
            
            // Start encoder
            encoder?.start()
            isRunning = true
            
            // Try to extract SPS/PPS early from output format (some encoders provide it immediately)
            // This helps RTSP clients connect faster without waiting for first frame
            val earlyExtraction = tryExtractEarlySPSPPS()
            
            // Start output draining thread
            startDrainThread()
            
            // If early extraction failed, try to force encoder to generate SPS/PPS
            // by requesting an immediate sync frame (I-frame)
            if (!earlyExtraction) {
                try {
                    Log.d(TAG, "Early SPS/PPS extraction failed, requesting immediate I-frame to trigger generation")
                    val bundle = android.os.Bundle()
                    bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    encoder?.setParameters(bundle)
                    Log.d(TAG, "I-frame request sent to encoder")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not request sync frame: ${e.message}")
                }
            }
            
            Log.i(TAG, "H.264 encoder started: ${width}x${height} @ ${fps}fps, ${bitrate/1_000_000}Mbps")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            stop()
        }
    }
    
    /**
     * Try to configure the encoder with specified constraints
     * @return true if configuration succeeded, false otherwise
     */
    private fun tryConfigureEncoder(
        attempt: Int,
        includeProfile: Boolean,
        includeBitrateMode: Boolean
    ): Boolean {
        return try {
            Log.d(TAG, "Configuration attempt #$attempt: profile=$includeProfile, bitrateMode=$includeBitrateMode")
            
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                
                // Low-latency encoding settings for RTSP streaming
                // These settings minimize encoder buffering and reduce latency
                try {
                    // Request low latency mode (Android 10+)
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                    
                    // Disable B-frames for lower latency (baseline profile doesn't use them anyway)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    
                    // Set real-time priority for encoding (Android 10+)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // 0 = realtime
                    
                    Log.d(TAG, "Low-latency encoder settings applied")
                } catch (e: Exception) {
                    // These keys may not be supported on all devices/Android versions
                    Log.d(TAG, "Some low-latency settings not supported: ${e.message}")
                }
                
                // Optional: Enable hardware encoding with VBR
                if (includeBitrateMode) {
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                    )
                }
                
                // Optional: Set baseline profile for maximum compatibility
                if (includeProfile) {
                    setInteger(
                        MediaFormat.KEY_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    )
                }
            }
            
            Log.d(TAG, "Attempting to configure encoder with format: $format")
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            Log.i(TAG, "Encoder configured successfully on attempt #$attempt")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Configuration attempt #$attempt failed: ${e.message}")
            false
        }
    }
    
    /**
     * Log encoder capabilities for debugging
     */
    private fun logEncoderCapabilities() {
        try {
            val encoderInfo = encoder?.codecInfo
            val name = encoderInfo?.name ?: "unknown"
            val isHardware = encoderInfo?.isHardwareAccelerated ?: false
            
            Log.d(TAG, "Encoder: $name (Hardware: $isHardware)")
            Log.d(TAG, "Target resolution: ${width}x${height}, fps: $fps, bitrate: ${bitrate / 1_000_000.0}Mbps")
            
            // Log supported capabilities
            encoderInfo?.getCapabilitiesForType(MIME_TYPE)?.let { caps ->
                val videoCapabilities = caps.videoCapabilities
                if (videoCapabilities != null) {
                    Log.d(TAG, "Supported bitrate range: ${videoCapabilities.bitrateRange}")
                    Log.d(TAG, "Supported width range: ${videoCapabilities.supportedWidths}")
                    Log.d(TAG, "Supported height range: ${videoCapabilities.supportedHeights}")
                    Log.d(TAG, "Supported frame rate range: ${videoCapabilities.supportedFrameRates}")
                }
                
                // Log supported profiles
                val profiles = caps.profileLevels.joinToString(", ") { 
                    "Profile=${it.profile}, Level=${it.level}"
                }
                Log.d(TAG, "Supported profiles: $profiles")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not log encoder capabilities: ${e.message}")
        }
    }
    
    /**
     * Try to extract SPS/PPS from encoder output format immediately after start()
     * Some encoders (especially Qualcomm) provide codec-specific data (CSD) in the
     * output format before processing any frames. This allows RTSP clients to connect
     * immediately without waiting for the first frame.
     * 
     * For encoders that don't provide CSD early (like some Exynos), SPS/PPS will be
     * extracted later from the first codec config buffer in the drain thread.
     * 
     * @return true if SPS/PPS were successfully extracted, false otherwise
     */
    private fun tryExtractEarlySPSPPS(): Boolean {
        try {
            val format = encoder?.outputFormat
            if (format != null) {
                Log.d(TAG, "Checking output format for early SPS/PPS: $format")
                
                var foundSPS = false
                var foundPPS = false
                
                if (format.containsKey("csd-0")) {
                    val csd0 = format.getByteBuffer("csd-0")
                    if (csd0 != null && csd0.remaining() > 0) {
                        val data = ByteArray(csd0.remaining())
                        csd0.get(data)
                        rtspServer?.updateCodecConfig(data)
                        foundSPS = true
                        Log.i(TAG, "Early SPS extracted from output format: ${data.size} bytes")
                    }
                }
                
                if (format.containsKey("csd-1")) {
                    val csd1 = format.getByteBuffer("csd-1")
                    if (csd1 != null && csd1.remaining() > 0) {
                        val data = ByteArray(csd1.remaining())
                        csd1.get(data)
                        rtspServer?.updateCodecConfig(data)
                        foundPPS = true
                        Log.i(TAG, "Early PPS extracted from output format: ${data.size} bytes")
                    }
                }
                
                if (foundSPS && foundPPS) {
                    Log.i(TAG, "Successfully extracted SPS/PPS early - RTSP clients can connect immediately")
                    return true
                } else if (!foundSPS && !foundPPS) {
                    Log.d(TAG, "No CSD buffers in output format yet - will extract from first frame")
                } else {
                    Log.w(TAG, "Partial CSD extraction: SPS=$foundSPS, PPS=$foundPPS")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract early SPS/PPS (will extract from first frame): ${e.message}")
        }
        return false
    }
    
    /**
     * Stop the encoder
     */
    fun stop() {
        isRunning = false
        
        // Wait for drain thread to finish with longer timeout
        drainThread?.interrupt()
        try {
            drainThread?.join(5000) // 5 second timeout for graceful shutdown
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for drain thread")
        }
        drainThread = null
        
        try {
            inputSurface?.release()
            inputSurface = null
            
            encoder?.stop()
            encoder?.release()
            encoder = null
            
            Log.i(TAG, "H.264 encoder stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }
    
    /**
     * Drain encoded output in background thread
     */
    private fun startDrainThread() {
        drainThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (isRunning) {
                try {
                    // Use 1 second timeout for responsive shutdown
                    val outputBufferId = encoder?.dequeueOutputBuffer(bufferInfo, 1_000) ?: -1
                    
                    when {
                        outputBufferId >= 0 -> {
                            val outputBuffer = encoder?.getOutputBuffer(outputBufferId)
                            
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Extract NAL unit
                                val nalUnit = ByteArray(bufferInfo.size)
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.get(nalUnit)
                                
                                // Check for SPS/PPS (codec config)
                                val isCodecConfig = (bufferInfo.flags and 
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                
                                if (isCodecConfig) {
                                    // Parse SPS/PPS
                                    rtspServer?.updateCodecConfig(nalUnit)
                                    Log.d(TAG, "Codec config updated: ${nalUnit.size} bytes")
                                } else {
                                    // Regular frame - send to RTSP
                                    rtspServer?.sendH264Frame(
                                        nalUnit, 
                                        bufferInfo.presentationTimeUs,
                                        isKeyFrame = (bufferInfo.flags and 
                                            MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    )
                                }
                            }
                            
                            encoder?.releaseOutputBuffer(outputBufferId, false)
                        }
                        
                        outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val format = encoder?.outputFormat
                            Log.d(TAG, "Output format changed: $format")
                        }
                    }
                    
                } catch (e: Exception) {
                    if (isRunning) {
                        Log.e(TAG, "Error draining encoder", e)
                    }
                    break
                }
            }
        }.apply {
            name = "H264EncoderDrain"
            start()
        }
    }
}
