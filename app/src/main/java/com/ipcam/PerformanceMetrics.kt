package com.ipcam

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Tracks performance metrics for adaptive streaming optimization.
 * 
 * Monitors:
 * - CPU usage (process and system)
 * - Memory usage (heap and native)
 * - Frame processing times
 * - Frame drops
 * - Encoding performance
 * 
 * Thread-safe for concurrent access from multiple threads.
 */
class PerformanceMetrics(private val context: Context) {
    
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // Frame processing metrics
    private val frameProcessingTimes = mutableListOf<Long>()
    private val frameEncodingTimes = mutableListOf<Long>()
    private val framesProcessed = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val framesSkipped = AtomicLong(0)
    
    // Memory metrics
    private var lastHeapSize: Long = 0
    private var lastHeapFree: Long = 0
    private var peakMemoryUsage: Long = 0
    
    // CPU metrics
    private var lastCpuTimeMs: Long = 0
    private var lastMeasurementTimeMs: Long = 0
    
    // Timing
    private val startTime = System.currentTimeMillis()
    
    // Configuration
    private val metricsLock = Any()
    
    companion object {
        private const val TAG = "PerformanceMetrics"
        private const val HISTORY_SIZE = 100 // Keep last 100 measurements
        
        // Performance thresholds for adaptive quality
        const val CPU_HIGH_THRESHOLD = 0.70 // 70% CPU usage
        const val CPU_CRITICAL_THRESHOLD = 0.85 // 85% CPU usage
        const val MEMORY_HIGH_THRESHOLD = 0.75 // 75% memory usage
        const val MEMORY_CRITICAL_THRESHOLD = 0.90 // 90% memory usage
        const val FRAME_DROP_RATE_THRESHOLD = 0.10 // 10% frame drop rate
    }
    
    /**
     * Record frame processing time in milliseconds.
     */
    fun recordFrameProcessingTime(timeMs: Long) {
        synchronized(metricsLock) {
            frameProcessingTimes.add(timeMs)
            if (frameProcessingTimes.size > HISTORY_SIZE) {
                frameProcessingTimes.removeAt(0)
            }
            framesProcessed.incrementAndGet()
        }
    }
    
    /**
     * Record frame encoding time in milliseconds.
     */
    fun recordFrameEncodingTime(timeMs: Long) {
        synchronized(metricsLock) {
            frameEncodingTimes.add(timeMs)
            if (frameEncodingTimes.size > HISTORY_SIZE) {
                frameEncodingTimes.removeAt(0)
            }
        }
    }
    
    /**
     * Record a dropped frame (couldn't process in time).
     */
    fun recordFrameDropped() {
        framesDropped.incrementAndGet()
        Log.d(TAG, "Frame dropped. Total drops: ${framesDropped.get()}")
    }
    
    /**
     * Record a skipped frame (intentionally skipped for adaptive quality).
     */
    fun recordFrameSkipped() {
        framesSkipped.incrementAndGet()
    }
    
    /**
     * Get average frame processing time in milliseconds.
     */
    fun getAverageProcessingTime(): Double {
        synchronized(metricsLock) {
            return if (frameProcessingTimes.isEmpty()) {
                0.0
            } else {
                frameProcessingTimes.average()
            }
        }
    }
    
    /**
     * Get average frame encoding time in milliseconds.
     */
    fun getAverageEncodingTime(): Double {
        synchronized(metricsLock) {
            return if (frameEncodingTimes.isEmpty()) {
                0.0
            } else {
                frameEncodingTimes.average()
            }
        }
    }
    
    /**
     * Get 95th percentile processing time (useful for identifying outliers).
     */
    fun get95thPercentileProcessingTime(): Long {
        synchronized(metricsLock) {
            if (frameProcessingTimes.isEmpty()) return 0
            
            val sorted = frameProcessingTimes.sorted()
            val index = (sorted.size * 0.95).toInt()
            return sorted.getOrNull(index) ?: sorted.last()
        }
    }
    
    /**
     * Get frame drop rate (0.0 to 1.0).
     */
    fun getFrameDropRate(): Double {
        val total = framesProcessed.get() + framesDropped.get()
        return if (total > 0) {
            framesDropped.get().toDouble() / total
        } else {
            0.0
        }
    }
    
    /**
     * Get current memory usage information.
     */
    fun getMemoryStats(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val heapSize = runtime.totalMemory()
        val heapFree = runtime.freeMemory()
        val heapUsed = heapSize - heapFree
        val heapMax = runtime.maxMemory()
        
        lastHeapSize = heapSize
        lastHeapFree = heapFree
        peakMemoryUsage = max(peakMemoryUsage, heapUsed)
        
        // Get native memory
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        
        return MemoryStats(
            heapUsedMB = heapUsed / (1024 * 1024),
            heapSizeMB = heapSize / (1024 * 1024),
            heapMaxMB = heapMax / (1024 * 1024),
            heapUsageRatio = heapUsed.toDouble() / heapMax,
            nativeHeapMB = nativeHeap / (1024 * 1024),
            systemAvailableMB = memInfo.availMem / (1024 * 1024),
            systemTotalMB = memInfo.totalMem / (1024 * 1024),
            systemLowMemory = memInfo.lowMemory,
            peakUsageMB = peakMemoryUsage / (1024 * 1024)
        )
    }
    
    /**
     * Get CPU usage estimate using Process.getElapsedCpuTime().
     * This uses the official Android API that doesn't require reading /proc/stat.
     * Returns the percentage of CPU time used by this process.
     */
    fun getCpuUsage(): CpuStats {
        val currentTimeMs = System.currentTimeMillis()
        
        // Get elapsed CPU time for this process (in milliseconds)
        // This is the total CPU time consumed by the process since it started
        val currentCpuTimeMs = Process.getElapsedCpuTime()
        
        // Calculate CPU usage percentage based on elapsed wall-clock time
        val processUsage = if (lastMeasurementTimeMs > 0 && lastCpuTimeMs > 0) {
            val cpuDeltaMs = currentCpuTimeMs - lastCpuTimeMs
            val wallClockDeltaMs = currentTimeMs - lastMeasurementTimeMs
            
            if (wallClockDeltaMs > 0) {
                // CPU usage = (CPU time delta / wall-clock time delta) * 100
                // This gives us the percentage of one core's time that we're using
                val cores = Runtime.getRuntime().availableProcessors()
                // Normalize to show usage across all cores (0-100% range)
                val usagePercent = (cpuDeltaMs.toDouble() / wallClockDeltaMs) * 100.0
                // Cap at 100% to handle any measurement anomalies
                usagePercent.coerceIn(0.0, 100.0)
            } else {
                0.0
            }
        } else {
            0.0
        }
        
        // Update last measurements for next calculation
        lastCpuTimeMs = currentCpuTimeMs
        lastMeasurementTimeMs = currentTimeMs
        
        // Get number of CPU cores
        val cores = Runtime.getRuntime().availableProcessors()
        
        return CpuStats(
            processUsagePercent = processUsage,
            perCoreUsagePercent = processUsage / cores,
            isHighUsage = processUsage > CPU_HIGH_THRESHOLD * 100
        )
    }
    
    /**
     * Check if system is under performance pressure.
     */
    fun isUnderPressure(): PerformancePressure {
        val memStats = getMemoryStats()
        val cpuStats = getCpuUsage()
        val frameDropRate = getFrameDropRate()
        
        val memoryPressure = when {
            memStats.heapUsageRatio > MEMORY_CRITICAL_THRESHOLD -> PressureLevel.CRITICAL
            memStats.heapUsageRatio > MEMORY_HIGH_THRESHOLD -> PressureLevel.HIGH
            memStats.systemLowMemory -> PressureLevel.HIGH
            else -> PressureLevel.NORMAL
        }
        
        val cpuPressure = when {
            cpuStats.processUsagePercent > CPU_CRITICAL_THRESHOLD * 100 -> PressureLevel.CRITICAL
            cpuStats.processUsagePercent > CPU_HIGH_THRESHOLD * 100 -> PressureLevel.HIGH
            else -> PressureLevel.NORMAL
        }
        
        val framePressure = when {
            frameDropRate > FRAME_DROP_RATE_THRESHOLD * 2 -> PressureLevel.CRITICAL
            frameDropRate > FRAME_DROP_RATE_THRESHOLD -> PressureLevel.HIGH
            else -> PressureLevel.NORMAL
        }
        
        val overall = listOf(memoryPressure, cpuPressure, framePressure).maxOrNull() 
            ?: PressureLevel.NORMAL
        
        return PerformancePressure(
            memoryPressure = memoryPressure,
            cpuPressure = cpuPressure,
            framePressure = framePressure,
            overall = overall
        )
    }
    
    /**
     * Get comprehensive statistics for debugging.
     */
    fun getDetailedStats(): String {
        val sb = StringBuilder()
        sb.append("=== Performance Metrics ===\n")
        
        // Frame stats
        val uptime = (System.currentTimeMillis() - startTime) / 1000.0
        sb.append("Uptime: ${String.format("%.1f", uptime)}s\n")
        sb.append("Frames: ${framesProcessed.get()} processed, ")
        sb.append("${framesDropped.get()} dropped, ")
        sb.append("${framesSkipped.get()} skipped\n")
        sb.append("Drop rate: ${String.format("%.1f", getFrameDropRate() * 100)}%\n")
        sb.append("Avg processing: ${String.format("%.1f", getAverageProcessingTime())}ms\n")
        sb.append("Avg encoding: ${String.format("%.1f", getAverageEncodingTime())}ms\n")
        sb.append("P95 processing: ${get95thPercentileProcessingTime()}ms\n")
        
        // Memory stats
        val mem = getMemoryStats()
        sb.append("\nMemory:\n")
        sb.append("  Heap: ${mem.heapUsedMB}/${mem.heapMaxMB} MB ")
        sb.append("(${String.format("%.1f", mem.heapUsageRatio * 100)}%)\n")
        sb.append("  Native: ${mem.nativeHeapMB} MB\n")
        sb.append("  Peak: ${mem.peakUsageMB} MB\n")
        sb.append("  System: ${mem.systemAvailableMB}/${mem.systemTotalMB} MB available\n")
        sb.append("  Low memory: ${mem.systemLowMemory}\n")
        
        // CPU stats
        val cpu = getCpuUsage()
        sb.append("\nCPU:\n")
        sb.append("  Process: ${String.format("%.1f", cpu.processUsagePercent)}%\n")
        sb.append("  Per core: ${String.format("%.1f", cpu.perCoreUsagePercent)}%\n")
        sb.append("  High usage: ${cpu.isHighUsage}\n")
        
        // Pressure
        val pressure = isUnderPressure()
        sb.append("\nPressure:\n")
        sb.append("  Memory: ${pressure.memoryPressure}\n")
        sb.append("  CPU: ${pressure.cpuPressure}\n")
        sb.append("  Frames: ${pressure.framePressure}\n")
        sb.append("  Overall: ${pressure.overall}\n")
        
        return sb.toString()
    }
    
    /**
     * Reset all metrics.
     */
    fun reset() {
        synchronized(metricsLock) {
            frameProcessingTimes.clear()
            frameEncodingTimes.clear()
            framesProcessed.set(0)
            framesDropped.set(0)
            framesSkipped.set(0)
            peakMemoryUsage = 0
            Log.d(TAG, "Performance metrics reset")
        }
    }
}

/**
 * Memory statistics.
 */
data class MemoryStats(
    val heapUsedMB: Long,
    val heapSizeMB: Long,
    val heapMaxMB: Long,
    val heapUsageRatio: Double,
    val nativeHeapMB: Long,
    val systemAvailableMB: Long,
    val systemTotalMB: Long,
    val systemLowMemory: Boolean,
    val peakUsageMB: Long
)

/**
 * CPU statistics.
 */
data class CpuStats(
    val processUsagePercent: Double,
    val perCoreUsagePercent: Double,
    val isHighUsage: Boolean
)

/**
 * Performance pressure levels.
 */
enum class PressureLevel {
    NORMAL,     // No pressure, can maintain or increase quality
    HIGH,       // Some pressure, should reduce quality
    CRITICAL    // Severe pressure, must reduce quality significantly
}

/**
 * Overall performance pressure assessment.
 */
data class PerformancePressure(
    val memoryPressure: PressureLevel,
    val cpuPressure: PressureLevel,
    val framePressure: PressureLevel,
    val overall: PressureLevel
)
