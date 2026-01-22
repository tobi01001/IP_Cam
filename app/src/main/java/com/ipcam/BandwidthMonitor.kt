package com.ipcam

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Monitors bandwidth usage and calculates throughput statistics for adaptive streaming.
 * 
 * Key Features:
 * - Tracks bytes sent per client
 * - Calculates moving average throughput
 * - Detects network congestion
 * - Thread-safe for concurrent access
 * 
 * Usage:
 * ```
 * val monitor = BandwidthMonitor()
 * monitor.recordBytesSent(clientId, jpegBytes.size.toLong())
 * val throughput = monitor.getCurrentThroughput(clientId)
 * ```
 */
class BandwidthMonitor {
    
    private data class ClientStats(
        val bytesSent: AtomicLong = AtomicLong(0),
        val framesSent: AtomicLong = AtomicLong(0),
        var lastUpdateTime: Long = System.currentTimeMillis(),
        var currentThroughputBps: Long = 0, // Bits per second
        val throughputHistory: MutableList<Long> = mutableListOf() // Last N measurements
    )
    
    private val clientStats = mutableMapOf<Long, ClientStats>()
    private val statsLock = ReentrantReadWriteLock()
    
    // Global stats across all clients
    private val globalBytesSent = AtomicLong(0)
    private val globalFramesSent = AtomicLong(0)
    private var globalStartTime = System.currentTimeMillis()
    
    companion object {
        private const val TAG = "BandwidthMonitor"
        private const val THROUGHPUT_WINDOW_MS = 1000L // Calculate throughput over 1 second
        private const val HISTORY_SIZE = 10 // Keep last 10 throughput measurements
        private const val CONGESTION_THRESHOLD_MBPS = 1.0 // Below 1 Mbps considered congested
    }
    
    /**
     * Record bytes sent to a specific client.
     * This should be called after each frame is successfully sent.
     */
    fun recordBytesSent(clientId: Long, bytes: Long) {
        statsLock.write {
            val stats = clientStats.getOrPut(clientId) { ClientStats() }
            stats.bytesSent.addAndGet(bytes)
            stats.framesSent.incrementAndGet()
            
            // Update global stats
            globalBytesSent.addAndGet(bytes)
            globalFramesSent.incrementAndGet()
            
            // Calculate throughput if enough time has passed
            val now = System.currentTimeMillis()
            val elapsed = now - stats.lastUpdateTime
            
            if (elapsed >= THROUGHPUT_WINDOW_MS) {
                // Get current counter value and reset it atomically
                val bytesSinceLastUpdate = stats.bytesSent.getAndSet(0)
                val throughputBps = (bytesSinceLastUpdate * 8 * 1000) / elapsed // bits per second
                
                stats.currentThroughputBps = throughputBps
                stats.lastUpdateTime = now
                
                // Add to history and maintain size
                stats.throughputHistory.add(throughputBps)
                if (stats.throughputHistory.size > HISTORY_SIZE) {
                    stats.throughputHistory.removeAt(0)
                }
                
                Log.d(TAG, "Client $clientId: ${throughputBps / 1_000_000.0} Mbps")
            }
        }
    }
    
    /**
     * Get current throughput for a specific client in bits per second.
     */
    fun getCurrentThroughput(clientId: Long): Long {
        return statsLock.read {
            clientStats[clientId]?.currentThroughputBps ?: 0L
        }
    }
    
    /**
     * Get current throughput in Mbps for easier reading.
     */
    fun getCurrentThroughputMbps(clientId: Long): Double {
        return getCurrentThroughput(clientId) / 1_000_000.0
    }
    
    /**
     * Get average throughput over recent history.
     */
    fun getAverageThroughput(clientId: Long): Long {
        return statsLock.read {
            val history = clientStats[clientId]?.throughputHistory
            if (history.isNullOrEmpty()) {
                0L
            } else {
                history.average().toLong()
            }
        }
    }
    
    /**
     * Get average throughput in Mbps.
     */
    fun getAverageThroughputMbps(clientId: Long): Double {
        return getAverageThroughput(clientId) / 1_000_000.0
    }
    
    /**
     * Check if a client is experiencing network congestion.
     */
    fun isClientCongested(clientId: Long): Boolean {
        val throughputMbps = getAverageThroughputMbps(clientId)
        return throughputMbps > 0 && throughputMbps < CONGESTION_THRESHOLD_MBPS
    }
    
    /**
     * Get frames per second for a client.
     */
    fun getCurrentFps(clientId: Long): Double {
        return statsLock.read {
            val stats = clientStats[clientId] ?: return@read 0.0
            val now = System.currentTimeMillis()
            val elapsed = now - stats.lastUpdateTime
            
            if (elapsed > 0) {
                (stats.framesSent.get() * 1000.0) / elapsed
            } else {
                0.0
            }
        }
    }
    
    /**
     * Get total bytes sent to a client since last throughput calculation.
     * Note: This resets periodically during throughput calculations.
     */
    fun getCurrentBytesSent(clientId: Long): Long {
        return statsLock.read {
            clientStats[clientId]?.bytesSent?.get() ?: 0L
        }
    }
    
    /**
     * Get total bytes sent to a client (cumulative, not reset).
     */
    fun getTotalBytesSent(clientId: Long): Long {
        // Calculate from history and current
        return statsLock.read {
            val stats = clientStats[clientId] ?: return@read 0L
            val currentBytes = stats.bytesSent.get()
            // Estimate total from throughput history (rough approximation)
            val historicalBytes = stats.throughputHistory.sumOf { it / 8 } // Convert bits to bytes
            currentBytes + historicalBytes
        }
    }
    
    /**
     * Get total frames sent to a client.
     */
    fun getTotalFramesSent(clientId: Long): Long {
        return statsLock.read {
            clientStats[clientId]?.framesSent?.get() ?: 0L
        }
    }
    
    /**
     * Remove a client from tracking when they disconnect.
     */
    fun removeClient(clientId: Long) {
        statsLock.write {
            clientStats.remove(clientId)
            Log.d(TAG, "Removed client $clientId from bandwidth monitoring")
        }
    }
    
    /**
     * Get global bandwidth statistics.
     */
    fun getGlobalStats(): GlobalBandwidthStats {
        val now = System.currentTimeMillis()
        val elapsedSeconds = (now - globalStartTime) / 1000.0
        
        return GlobalBandwidthStats(
            totalBytesSent = globalBytesSent.get(),
            totalFramesSent = globalFramesSent.get(),
            averageThroughputMbps = if (elapsedSeconds > 0) {
                (globalBytesSent.get() * 8) / (elapsedSeconds * 1_000_000)
            } else {
                0.0
            },
            activeClients = statsLock.read { clientStats.size },
            uptime = elapsedSeconds
        )
    }
    
    /**
     * Get current bandwidth from all active clients (more responsive than lifetime average).
     * Returns the sum of current throughput from all clients in bits per second.
     */
    fun getCurrentBandwidthBps(): Long {
        return statsLock.read {
            clientStats.values.sumOf { it.currentThroughputBps }
        }
    }
    
    /**
     * Reset all statistics.
     */
    fun reset() {
        statsLock.write {
            clientStats.clear()
            globalBytesSent.set(0)
            globalFramesSent.set(0)
            globalStartTime = System.currentTimeMillis()
            Log.d(TAG, "Bandwidth monitor reset")
        }
    }
    
    /**
     * Get comprehensive statistics for debugging.
     */
    fun getDetailedStats(): String {
        return statsLock.read {
            val sb = StringBuilder()
            sb.append("=== Bandwidth Monitor Stats ===\n")
            
            val global = getGlobalStats()
            sb.append("Global: ${global.totalBytesSent / 1_000_000} MB sent, ")
            sb.append("${global.totalFramesSent} frames, ")
            sb.append("${String.format("%.2f", global.averageThroughputMbps)} Mbps avg, ")
            sb.append("${global.activeClients} clients, ")
            sb.append("${String.format("%.1f", global.uptime)}s uptime\n")
            
            clientStats.forEach { (clientId, stats) ->
                sb.append("\nClient $clientId:\n")
                sb.append("  Current: ${String.format("%.2f", stats.currentThroughputBps / 1_000_000.0)} Mbps\n")
                sb.append("  Average: ${String.format("%.2f", getAverageThroughputMbps(clientId))} Mbps\n")
                sb.append("  Frames: ${stats.framesSent.get()}\n")
                sb.append("  Bytes (current window): ${stats.bytesSent.get() / 1024} KB\n")
                sb.append("  Congested: ${isClientCongested(clientId)}\n")
            }
            
            sb.toString()
        }
    }
}

/**
 * Global bandwidth statistics.
 */
data class GlobalBandwidthStats(
    val totalBytesSent: Long,
    val totalFramesSent: Long,
    val averageThroughputMbps: Double,
    val activeClients: Int,
    val uptime: Double
)
