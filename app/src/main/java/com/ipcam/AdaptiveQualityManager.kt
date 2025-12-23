package com.ipcam

import android.util.Log
import android.util.Size
import kotlin.math.max
import kotlin.math.min

/**
 * Manages adaptive quality adjustments based on network conditions and system performance.
 * 
 * Dynamically adjusts:
 * - JPEG quality (50-90%)
 * - Frame rate (5-30 fps)
 * - Resolution (optional)
 * 
 * Based on:
 * - Network throughput
 * - CPU usage
 * - Memory pressure
 * - Frame drop rate
 */
class AdaptiveQualityManager(
    private val bandwidthMonitor: BandwidthMonitor,
    private val performanceMetrics: PerformanceMetrics
) {
    
    // Current quality settings
    private var currentJpegQuality = DEFAULT_JPEG_QUALITY
    private var currentFrameDelayMs = DEFAULT_FRAME_DELAY_MS
    private var currentResolutionScale = 1.0
    
    // Per-client quality settings
    private val clientQualitySettings = mutableMapOf<Long, ClientQualitySettings>()
    private val settingsLock = Any()
    
    // Adjustment history for smooth transitions
    private val qualityHistory = mutableListOf<Int>()
    private val frameRateHistory = mutableListOf<Long>()
    
    companion object {
        private const val TAG = "AdaptiveQualityManager"
        
        // Quality ranges
        const val MIN_JPEG_QUALITY = 50
        const val DEFAULT_JPEG_QUALITY = 75
        const val MAX_JPEG_QUALITY = 90
        
        // Frame rate ranges (in ms delay)
        const val MIN_FRAME_DELAY_MS = 33L   // ~30 fps
        const val DEFAULT_FRAME_DELAY_MS = 100L  // ~10 fps
        const val MAX_FRAME_DELAY_MS = 200L  // ~5 fps
        
        // Resolution scales
        const val MIN_RESOLUTION_SCALE = 0.5  // 50% resolution
        const val MAX_RESOLUTION_SCALE = 1.0  // 100% resolution
        
        // Throughput thresholds (Mbps)
        const val THROUGHPUT_EXCELLENT = 10.0
        const val THROUGHPUT_GOOD = 5.0
        const val THROUGHPUT_FAIR = 2.0
        const val THROUGHPUT_POOR = 1.0
        
        // Adjustment increments
        const val QUALITY_STEP = 5
        const val FRAME_RATE_STEP_MS = 20L
        const val RESOLUTION_STEP = 0.1
        
        // Smoothing
        const val HISTORY_SIZE = 5
        const val ADJUSTMENT_INTERVAL_MS = 2000L // Adjust every 2 seconds
    }
    
    private var lastAdjustmentTime = System.currentTimeMillis()
    
    /**
     * Client-specific quality settings.
     */
    data class ClientQualitySettings(
        var jpegQuality: Int = DEFAULT_JPEG_QUALITY,
        var frameDelayMs: Long = DEFAULT_FRAME_DELAY_MS,
        var resolutionScale: Double = 1.0,
        var lastAdjustmentTime: Long = System.currentTimeMillis(),
        var adaptiveMode: Boolean = true // Can be disabled per client
    )
    
    /**
     * Update quality settings for a specific client based on current conditions.
     * Should be called periodically (e.g., every 2 seconds).
     */
    fun updateClientQuality(clientId: Long): ClientQualitySettings {
        synchronized(settingsLock) {
            val settings = clientQualitySettings.getOrPut(clientId) {
                ClientQualitySettings()
            }
            
            // Check if enough time has passed since last adjustment
            val now = System.currentTimeMillis()
            if (now - settings.lastAdjustmentTime < ADJUSTMENT_INTERVAL_MS) {
                return settings
            }
            
            if (!settings.adaptiveMode) {
                return settings // Adaptive mode disabled for this client
            }
            
            // Gather metrics
            val throughputMbps = bandwidthMonitor.getAverageThroughputMbps(clientId)
            val isCongested = bandwidthMonitor.isClientCongested(clientId)
            val pressure = performanceMetrics.isUnderPressure()
            
            // Calculate target quality based on conditions
            val targetQuality = calculateTargetQuality(throughputMbps, pressure)
            val targetFrameDelay = calculateTargetFrameDelay(throughputMbps, pressure)
            val targetResolutionScale = calculateTargetResolutionScale(throughputMbps, pressure)
            
            // Smooth transitions - don't change too abruptly
            settings.jpegQuality = smoothTransition(
                settings.jpegQuality,
                targetQuality,
                QUALITY_STEP
            )
            settings.frameDelayMs = smoothTransition(
                settings.frameDelayMs,
                targetFrameDelay,
                FRAME_RATE_STEP_MS
            )
            settings.resolutionScale = smoothTransition(
                settings.resolutionScale,
                targetResolutionScale,
                RESOLUTION_STEP
            )
            
            settings.lastAdjustmentTime = now
            
            Log.d(TAG, "Client $clientId adjusted: quality=${settings.jpegQuality}, " +
                    "frameDelay=${settings.frameDelayMs}ms, " +
                    "scale=${String.format("%.2f", settings.resolutionScale)}, " +
                    "throughput=${String.format("%.2f", throughputMbps)}Mbps, " +
                    "pressure=${pressure.overall}")
            
            return settings
        }
    }
    
    /**
     * Calculate target JPEG quality based on conditions.
     */
    private fun calculateTargetQuality(throughputMbps: Double, pressure: PerformancePressure): Int {
        // Start with base quality from throughput
        val qualityFromThroughput = when {
            throughputMbps >= THROUGHPUT_EXCELLENT -> MAX_JPEG_QUALITY
            throughputMbps >= THROUGHPUT_GOOD -> 80
            throughputMbps >= THROUGHPUT_FAIR -> 70
            throughputMbps >= THROUGHPUT_POOR -> 60
            else -> MIN_JPEG_QUALITY
        }
        
        // Apply pressure adjustments
        val pressureAdjustment = when (pressure.overall) {
            PressureLevel.CRITICAL -> -15
            PressureLevel.HIGH -> -10
            PressureLevel.NORMAL -> 0
        }
        
        // Additional CPU-specific adjustment
        val cpuAdjustment = when (pressure.cpuPressure) {
            PressureLevel.CRITICAL -> -5
            PressureLevel.HIGH -> -3
            PressureLevel.NORMAL -> 0
        }
        
        val targetQuality = qualityFromThroughput + pressureAdjustment + cpuAdjustment
        return targetQuality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
    }
    
    /**
     * Calculate target frame delay (inverse of frame rate) based on conditions.
     */
    private fun calculateTargetFrameDelay(throughputMbps: Double, pressure: PerformancePressure): Long {
        // Start with base frame delay from throughput
        val delayFromThroughput = when {
            throughputMbps >= THROUGHPUT_EXCELLENT -> MIN_FRAME_DELAY_MS
            throughputMbps >= THROUGHPUT_GOOD -> 50L  // ~20 fps
            throughputMbps >= THROUGHPUT_FAIR -> 75L  // ~13 fps
            throughputMbps >= THROUGHPUT_POOR -> 100L  // ~10 fps
            else -> MAX_FRAME_DELAY_MS
        }
        
        // Apply pressure adjustments (increase delay = reduce frame rate)
        val pressureAdjustment = when (pressure.overall) {
            PressureLevel.CRITICAL -> 60L
            PressureLevel.HIGH -> 30L
            PressureLevel.NORMAL -> 0L
        }
        
        val targetDelay = delayFromThroughput + pressureAdjustment
        return targetDelay.coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS)
    }
    
    /**
     * Calculate target resolution scale based on conditions.
     */
    private fun calculateTargetResolutionScale(throughputMbps: Double, pressure: PerformancePressure): Double {
        // Start with base scale from throughput
        val scaleFromThroughput = when {
            throughputMbps >= THROUGHPUT_GOOD -> MAX_RESOLUTION_SCALE
            throughputMbps >= THROUGHPUT_FAIR -> 0.85
            throughputMbps >= THROUGHPUT_POOR -> 0.75
            else -> 0.6
        }
        
        // Apply pressure adjustments
        val pressureAdjustment = when (pressure.overall) {
            PressureLevel.CRITICAL -> -0.2
            PressureLevel.HIGH -> -0.1
            PressureLevel.NORMAL -> 0.0
        }
        
        val targetScale = scaleFromThroughput + pressureAdjustment
        return targetScale.coerceIn(MIN_RESOLUTION_SCALE, MAX_RESOLUTION_SCALE)
    }
    
    /**
     * Smooth transition helper to avoid abrupt changes.
     */
    private fun smoothTransition(current: Int, target: Int, step: Int): Int {
        return when {
            target > current -> min(current + step, target)
            target < current -> max(current - step, target)
            else -> current
        }
    }
    
    private fun smoothTransition(current: Long, target: Long, step: Long): Long {
        return when {
            target > current -> min(current + step, target)
            target < current -> max(current - step, target)
            else -> current
        }
    }
    
    private fun smoothTransition(current: Double, target: Double, step: Double): Double {
        return when {
            target > current -> min(current + step, target)
            target < current -> max(current - step, target)
            else -> current
        }
    }
    
    /**
     * Get current quality settings for a client.
     */
    fun getClientSettings(clientId: Long): ClientQualitySettings {
        synchronized(settingsLock) {
            return clientQualitySettings.getOrPut(clientId) {
                ClientQualitySettings()
            }
        }
    }
    
    /**
     * Enable or disable adaptive mode for a specific client.
     */
    fun setAdaptiveMode(clientId: Long, enabled: Boolean) {
        synchronized(settingsLock) {
            val settings = clientQualitySettings.getOrPut(clientId) {
                ClientQualitySettings()
            }
            settings.adaptiveMode = enabled
            Log.d(TAG, "Client $clientId adaptive mode: $enabled")
        }
    }
    
    /**
     * Set fixed quality for a client (disables adaptive mode).
     */
    fun setFixedQuality(clientId: Long, quality: Int, frameDelayMs: Long) {
        synchronized(settingsLock) {
            val settings = clientQualitySettings.getOrPut(clientId) {
                ClientQualitySettings()
            }
            settings.jpegQuality = quality.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
            settings.frameDelayMs = frameDelayMs.coerceIn(MIN_FRAME_DELAY_MS, MAX_FRAME_DELAY_MS)
            settings.adaptiveMode = false
            Log.d(TAG, "Client $clientId fixed quality: $quality, frameDelay: $frameDelayMs")
        }
    }
    
    /**
     * Remove a client when they disconnect.
     */
    fun removeClient(clientId: Long) {
        synchronized(settingsLock) {
            clientQualitySettings.remove(clientId)
            Log.d(TAG, "Removed client $clientId from adaptive quality manager")
        }
    }
    
    /**
     * Get recommended resolution based on scale.
     */
    fun getScaledResolution(originalSize: Size, scale: Double): Size {
        val scaledWidth = (originalSize.width * scale).toInt()
        val scaledHeight = (originalSize.height * scale).toInt()
        
        // Ensure dimensions are even (required for some encoders)
        val width = (scaledWidth / 2) * 2
        val height = (scaledHeight / 2) * 2
        
        return Size(max(width, 16), max(height, 16)) // Minimum 16x16
    }
    
    /**
     * Get comprehensive statistics for debugging.
     */
    fun getDetailedStats(): String {
        synchronized(settingsLock) {
            val sb = StringBuilder()
            sb.append("=== Adaptive Quality Manager ===\n")
            sb.append("Active clients: ${clientQualitySettings.size}\n")
            
            clientQualitySettings.forEach { (clientId, settings) ->
                val throughput = bandwidthMonitor.getAverageThroughputMbps(clientId)
                sb.append("\nClient $clientId:\n")
                sb.append("  Quality: ${settings.jpegQuality}%\n")
                sb.append("  Frame delay: ${settings.frameDelayMs}ms (~${1000.0 / settings.frameDelayMs} fps)\n")
                sb.append("  Resolution scale: ${String.format("%.2f", settings.resolutionScale)}\n")
                sb.append("  Throughput: ${String.format("%.2f", throughput)} Mbps\n")
                sb.append("  Adaptive: ${settings.adaptiveMode}\n")
            }
            
            return sb.toString()
        }
    }
    
    /**
     * Reset all settings to defaults.
     */
    fun reset() {
        synchronized(settingsLock) {
            clientQualitySettings.clear()
            qualityHistory.clear()
            frameRateHistory.clear()
            Log.d(TAG, "Adaptive quality manager reset")
        }
    }
}
