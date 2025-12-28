package com.ipcam

/**
 * Enum representing the streaming mode for the camera.
 * Only one mode can be active at a time to maintain single source of truth.
 */
enum class StreamingMode {
    /**
     * MJPEG (Motion JPEG) streaming mode.
     * - Lower latency (~100ms)
     * - Higher bandwidth usage
     * - Better compatibility with older systems
     * - Frame-by-frame JPEG compression
     */
    MJPEG,
    
    /**
     * MP4/H.264 streaming mode using fragmented MP4 (fMP4).
     * - Higher latency (~1-2 seconds for buffering)
     * - Better bandwidth efficiency
     * - Hardware-accelerated H.264 encoding
     * - Modern streaming format
     */
    MP4;
    
    companion object {
        fun fromString(value: String?): StreamingMode {
            return when (value?.uppercase()) {
                "MP4", "H264", "H.264" -> MP4
                else -> MJPEG // Default to MJPEG for backward compatibility
            }
        }
    }
    
    override fun toString(): String {
        return name.lowercase()
    }
}
