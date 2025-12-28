package com.ipcam

import android.util.Size
import androidx.camera.core.CameraSelector

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
    
    // Adaptive quality
    fun setAdaptiveQualityEnabled(enabled: Boolean)
    
    // Streaming mode
    fun getStreamingMode(): StreamingMode
    fun setStreamingMode(mode: StreamingMode)
}
