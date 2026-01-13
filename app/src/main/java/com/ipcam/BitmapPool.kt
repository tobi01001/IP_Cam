package com.ipcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe bitmap pool for reusing bitmap allocations.
 * Reduces memory pressure and OutOfMemoryError risk by reusing existing bitmaps
 * instead of creating new ones for each frame.
 * 
 * Minimum API level: 30 (Android 11+)
 */
class BitmapPool(
    private val maxPoolSizeBytes: Long = DEFAULT_MAX_POOL_SIZE_BYTES
) {
    // Size-based buckets: Map of (width, height, config) -> Queue of available bitmaps
    private val pools = ConcurrentHashMap<BitmapKey, ConcurrentLinkedQueue<Bitmap>>()
    
    // Track current pool size in bytes
    @Volatile private var currentPoolSizeBytes: Long = 0
    
    // Statistics for monitoring
    @Volatile private var hits: Long = 0
    @Volatile private var misses: Long = 0
    @Volatile private var evictions: Long = 0
    
    companion object {
        private const val TAG = "BitmapPool"
        
        // Default max pool size: 64 MB (enough for ~8-10 Full HD frames at ARGB_8888)
        private const val DEFAULT_MAX_POOL_SIZE_BYTES = 64L * 1024 * 1024
        
        // Max bitmaps per bucket to prevent unbounded growth
        private const val MAX_BITMAPS_PER_BUCKET = 5
    }
    
    /**
     * Key for identifying compatible bitmaps in the pool
     */
    private data class BitmapKey(
        val width: Int,
        val height: Int,
        val config: Bitmap.Config
    )
    
    /**
     * Get a bitmap from the pool or create a new one.
     * Catches Throwable to handle OutOfMemoryError gracefully.
     * 
     * @param width Desired width
     * @param height Desired height
     * @param config Desired bitmap configuration
     * @return Bitmap from pool or newly created, or null if OOME occurs
     */
    fun get(width: Int, height: Int, config: Bitmap.Config): Bitmap? {
        val key = BitmapKey(width, height, config)
        val queue = pools[key]
        
        // Try to get from pool
        val bitmap = queue?.poll()
        if (bitmap != null && !bitmap.isRecycled) {
            hits++
            Log.v(TAG, "Bitmap pool HIT: ${width}x${height} $config (hits: $hits, misses: $misses)")
            // Ensure bitmap is cleared for reuse
            bitmap.eraseColor(0)
            return bitmap
        }
        
        // Create new bitmap with OOME protection
        misses++
        return try {
            val newBitmap = Bitmap.createBitmap(width, height, config)
            Log.v(TAG, "Bitmap pool MISS: ${width}x${height} $config (hits: $hits, misses: $misses)")
            newBitmap
        } catch (t: Throwable) {
            // Handle OutOfMemoryError and other allocation failures
            Log.e(TAG, "Failed to allocate bitmap ${width}x${height} $config", t)
            
            // Try to free memory by clearing pool
            if (t is OutOfMemoryError) {
                Log.w(TAG, "OutOfMemoryError detected, clearing bitmap pool")
                clear()
                
                // Try one more time after clearing pool
                try {
                    Bitmap.createBitmap(width, height, config)
                } catch (t2: Throwable) {
                    Log.e(TAG, "Failed to allocate bitmap even after clearing pool", t2)
                    null
                }
            } else {
                null
            }
        }
    }
    
    /**
     * Return a bitmap to the pool for reuse.
     * 
     * @param bitmap Bitmap to return (must not be recycled)
     * @return true if bitmap was added to pool, false otherwise
     */
    fun returnBitmap(bitmap: Bitmap?): Boolean {
        if (bitmap == null || bitmap.isRecycled) {
            return false
        }
        
        val key = BitmapKey(bitmap.width, bitmap.height, bitmap.config)
        val queue = pools.getOrPut(key) { ConcurrentLinkedQueue() }
        
        // Check if bucket is full
        if (queue.size >= MAX_BITMAPS_PER_BUCKET) {
            Log.v(TAG, "Bucket full for ${bitmap.width}x${bitmap.height}, recycling bitmap")
            bitmap.recycle()
            return false
        }
        
        // Check if adding this bitmap would exceed pool size limit
        val bitmapSize = getBitmapSize(bitmap)
        if (currentPoolSizeBytes + bitmapSize > maxPoolSizeBytes) {
            // Evict oldest bitmaps until we have space
            evictToMakeSpace(bitmapSize)
        }
        
        // Add to pool
        if (queue.offer(bitmap)) {
            currentPoolSizeBytes += bitmapSize
            Log.v(TAG, "Returned bitmap to pool: ${bitmap.width}x${bitmap.height} (pool size: ${currentPoolSizeBytes / 1024 / 1024} MB)")
            return true
        } else {
            bitmap.recycle()
            return false
        }
    }
    
    /**
     * Copy a bitmap using pool for memory efficiency.
     * Catches Throwable to handle OutOfMemoryError.
     * 
     * Note: The mutable parameter is effectively ignored for pooled bitmaps since
     * Canvas operations require mutable bitmaps anyway. Pooled bitmaps from get()
     * are always mutable. The parameter is preserved for API consistency and used
     * in fallback path.
     * 
     * @param source Source bitmap to copy
     * @param config Config for the copy
     * @param mutable Whether the copy should be mutable (used in fallback only)
     * @return Copied bitmap or null if failed
     */
    fun copy(source: Bitmap, config: Bitmap.Config, mutable: Boolean): Bitmap? {
        if (source.isRecycled) {
            Log.w(TAG, "Attempted to copy recycled bitmap")
            return null
        }
        
        return try {
            // Get bitmap from pool or create new
            // Note: Pooled bitmaps are always mutable (required for Canvas operations)
            val copy = get(source.width, source.height, config)
            if (copy == null) {
                Log.e(TAG, "Failed to get bitmap from pool for copy")
                // Fallback to direct copy
                return source.copy(config, mutable)
            }
            
            // Copy pixels using Canvas
            val canvas = Canvas(copy)
            canvas.drawBitmap(source, 0f, 0f, null)
            
            copy
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy bitmap: ${source.width}x${source.height}", t)
            
            // Try direct copy as fallback
            try {
                source.copy(config, mutable)
            } catch (t2: Throwable) {
                Log.e(TAG, "Fallback bitmap copy also failed", t2)
                null
            }
        }
    }
    
    /**
     * Evict bitmaps to make space for a new bitmap.
     * Uses FIFO eviction strategy.
     */
    private fun evictToMakeSpace(requiredBytes: Long) {
        Log.d(TAG, "Evicting bitmaps to make space for $requiredBytes bytes")
        
        // Iterate through all buckets and remove oldest bitmaps
        for ((key, queue) in pools) {
            while (currentPoolSizeBytes + requiredBytes > maxPoolSizeBytes && queue.isNotEmpty()) {
                val evicted = queue.poll()
                if (evicted != null && !evicted.isRecycled) {
                    val size = getBitmapSize(evicted)
                    evicted.recycle()
                    currentPoolSizeBytes -= size
                    evictions++
                    Log.v(TAG, "Evicted bitmap ${key.width}x${key.height} (${size / 1024} KB)")
                }
            }
            
            // If queue is now empty, remove it
            if (queue.isEmpty()) {
                pools.remove(key)
            }
            
            // Check if we have enough space now
            if (currentPoolSizeBytes + requiredBytes <= maxPoolSizeBytes) {
                break
            }
        }
    }
    
    /**
     * Calculate bitmap size in bytes
     */
    private fun getBitmapSize(bitmap: Bitmap): Long {
        return bitmap.allocationByteCount.toLong()
    }
    
    /**
     * Clear all bitmaps from the pool
     */
    fun clear() {
        Log.d(TAG, "Clearing bitmap pool (current size: ${currentPoolSizeBytes / 1024 / 1024} MB)")
        
        for ((_, queue) in pools) {
            while (queue.isNotEmpty()) {
                val bitmap = queue.poll()
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        
        pools.clear()
        currentPoolSizeBytes = 0
        Log.d(TAG, "Bitmap pool cleared")
    }
    
    /**
     * Get pool statistics
     */
    fun getStats(): PoolStats {
        return PoolStats(
            currentSizeBytes = currentPoolSizeBytes,
            maxSizeBytes = maxPoolSizeBytes,
            hits = hits,
            misses = misses,
            evictions = evictions,
            bucketCount = pools.size,
            totalBitmaps = pools.values.sumOf { it.size }
        )
    }
    
    /**
     * Statistics data class
     */
    data class PoolStats(
        val currentSizeBytes: Long,
        val maxSizeBytes: Long,
        val hits: Long,
        val misses: Long,
        val evictions: Long,
        val bucketCount: Int,
        val totalBitmaps: Int
    ) {
        val hitRate: Float
            get() = if (hits + misses > 0) hits.toFloat() / (hits + misses) else 0f
        
        val currentSizeMB: Float
            get() = currentSizeBytes / 1024f / 1024f
        
        val maxSizeMB: Float
            get() = maxSizeBytes / 1024f / 1024f
    }
}
