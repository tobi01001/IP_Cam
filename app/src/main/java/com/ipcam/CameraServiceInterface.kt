package com.ipcam

import android.util.Size
import androidx.camera.core.CameraSelector
import java.io.File

/**
 * Interface for HttpServer to communicate with CameraService.
 * This interface decouples the HTTP server from the camera service implementation,
 * maintaining a clean separation of concerns and enabling independent testing.
 */
interface CameraServiceInterface {
    // Camera operations
    fun getCurrentCamera(): CameraSelector
    fun switchCamera(cameraSelector: CameraSelector)
    fun toggleFlashlight(): Boolean
    fun isFlashlightAvailable(): Boolean
    fun isFlashlightEnabled(): Boolean
    fun isCameraActive(): Boolean
    fun enableCamera(): Boolean
    
    // Frame operations
    fun getLastFrameJpegBytes(): ByteArray?
    
    // Resolution operations
    fun getSupportedResolutions(): List<Size>
    fun getSelectedResolution(): Size?
    fun getSelectedResolutionLabel(): String
    fun setResolutionAndRebind(resolution: Size?)
    fun sizeLabel(size: Size): String
    
    // Camera settings
    fun setCameraOrientation(orientation: String)
    fun setRotation(rotation: Int)
    fun setShowResolutionOverlay(show: Boolean)
    
    // OSD overlay settings
    fun setShowDateTimeOverlay(show: Boolean)
    fun getShowDateTimeOverlay(): Boolean
    fun setShowBatteryOverlay(show: Boolean)
    fun getShowBatteryOverlay(): Boolean
    fun setShowFpsOverlay(show: Boolean)
    fun getShowFpsOverlay(): Boolean
    fun getCurrentFps(): Float
    fun setTargetMjpegFps(fps: Int)
    fun getTargetMjpegFps(): Int
    fun setTargetRtspFps(fps: Int)
    fun getTargetRtspFps(): Int
    
    // Server operations
    fun getServerUrl(): String
    fun getActiveConnectionsCount(): Int
    fun getMaxConnections(): Int
    fun setMaxConnections(max: Int): Boolean
    fun restartServer()
    
    // Bandwidth and monitoring
    fun recordBytesSent(clientId: Long, bytes: Long)
    fun removeClient(clientId: Long)
    fun getDetailedStats(): String
    fun getCameraStateJson(): String

    fun getCameraStateDeltaJson(): String?
    fun recordMjpegFrameServed() // Track MJPEG streaming FPS

    fun initializeLastBroadcastState()

    // Adaptive quality
    fun setAdaptiveQualityEnabled(enabled: Boolean)
    
    // RTSP streaming operations
    fun enableRTSPStreaming(): Boolean
    fun disableRTSPStreaming()
    fun isRTSPEnabled(): Boolean
    fun getRTSPMetrics(): RTSPServer.ServerMetrics?
    fun getRTSPUrl(): String
    fun setRTSPBitrate(bitrate: Int): Boolean
    fun setRTSPBitrateMode(mode: String): Boolean
    
    // Battery management operations
    fun isStreamingAllowed(): Boolean
    fun getBatteryMode(): String
    fun overrideBatteryLimit(): Boolean
    fun getBatteryCriticalPercent(): Int
    fun getBatteryLowPercent(): Int
    fun getBatteryRecoveryPercent(): Int
    
    // Device identification
    fun getDeviceName(): String
    fun setDeviceName(name: String)
}
