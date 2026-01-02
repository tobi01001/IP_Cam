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
     * Initialize the encoder
     */
    fun start() {
        try {
            // Create MediaCodec encoder
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            
            // Configure format
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                
                // Enable hardware encoding with VBR
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )
                
                // Set baseline profile for maximum compatibility
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
            }
            
            // Configure encoder
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // Get input surface for CameraX
            inputSurface = encoder?.createInputSurface()
            
            // Start encoder
            encoder?.start()
            isRunning = true
            
            // Start output draining thread
            startDrainThread()
            
            Log.i(TAG, "H.264 encoder started: ${width}x${height} @ ${fps}fps, ${bitrate/1_000_000}Mbps")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            stop()
        }
    }
    
    /**
     * Stop the encoder
     */
    fun stop() {
        isRunning = false
        
        // Wait for drain thread to finish
        drainThread?.interrupt()
        try {
            drainThread?.join(1000)
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
                    val outputBufferId = encoder?.dequeueOutputBuffer(bufferInfo, 10_000) ?: -1
                    
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
