package com.ipcam

import java.util.concurrent.atomic.AtomicLong

enum class StreamTransport {
    MJPEG,
    RTSP
}

data class RuntimeTelemetrySnapshot(
    val timestampMs: Long,
    val cpuUsagePercent: Float,
    val bandwidthBps: Long,
    val mjpegBandwidthBps: Long,
    val rtspBandwidthBps: Long,
    val currentCameraFps: Float,
    val currentMjpegFps: Float,
    val currentRtspFps: Float,
    val activeHttpStreams: Int,
    val activeSseClients: Int,
    val rtspPlayingSessions: Int,
    val totalCameraClients: Int,
    val batteryLevel: Int,
    val isCharging: Boolean
) {
    fun toJson(): String {
        return """{"timestampMs":$timestampMs,"cpuUsagePercent":$cpuUsagePercent,"bandwidthBps":$bandwidthBps,"mjpegBandwidthBps":$mjpegBandwidthBps,"rtspBandwidthBps":$rtspBandwidthBps,"currentCameraFps":$currentCameraFps,"currentMjpegFps":$currentMjpegFps,"currentRtspFps":$currentRtspFps,"activeHttpStreams":$activeHttpStreams,"activeSseClients":$activeSseClients,"rtspPlayingSessions":$rtspPlayingSessions,"totalCameraClients":$totalCameraClients,"batteryLevel":$batteryLevel,"isCharging":$isCharging}"""
    }

    fun toDebugString(): String {
        return """
            === Runtime Telemetry ===
            Timestamp: $timestampMs
            CPU: ${String.format("%.1f", cpuUsagePercent)}%
            Bandwidth: $bandwidthBps bps total
            MJPEG Bandwidth: $mjpegBandwidthBps bps
            RTSP Bandwidth: $rtspBandwidthBps bps
            Camera FPS: ${String.format("%.1f", currentCameraFps)}
            MJPEG FPS: ${String.format("%.1f", currentMjpegFps)}
            RTSP FPS: ${String.format("%.1f", currentRtspFps)}
            HTTP Streams: $activeHttpStreams
            SSE Clients: $activeSseClients
            RTSP Playing Sessions: $rtspPlayingSessions
            Camera Clients: $totalCameraClients
            Battery: $batteryLevel%${if (isCharging) " charging" else ""}
        """.trimIndent()
    }

    companion object {
        fun empty(timestampMs: Long = System.currentTimeMillis()): RuntimeTelemetrySnapshot {
            return RuntimeTelemetrySnapshot(
                timestampMs = timestampMs,
                cpuUsagePercent = 0f,
                bandwidthBps = 0L,
                mjpegBandwidthBps = 0L,
                rtspBandwidthBps = 0L,
                currentCameraFps = 0f,
                currentMjpegFps = 0f,
                currentRtspFps = 0f,
                activeHttpStreams = 0,
                activeSseClients = 0,
                rtspPlayingSessions = 0,
                totalCameraClients = 0,
                batteryLevel = 0,
                isCharging = false
            )
        }
    }
}

class RuntimeTelemetrySampler {
    private val mjpegBytesTotal = AtomicLong(0)
    private val rtspBytesTotal = AtomicLong(0)

    private var lastSampleTimeMs = System.currentTimeMillis()
    private var lastMjpegBytesTotal = 0L
    private var lastRtspBytesTotal = 0L

    fun recordBytes(transport: StreamTransport, bytes: Long) {
        if (bytes <= 0) return

        when (transport) {
            StreamTransport.MJPEG -> mjpegBytesTotal.addAndGet(bytes)
            StreamTransport.RTSP -> rtspBytesTotal.addAndGet(bytes)
        }
    }

    @Synchronized
    fun sample(
        cpuUsagePercent: Float,
        currentCameraFps: Float,
        currentMjpegFps: Float,
        currentRtspFps: Float,
        activeHttpStreams: Int,
        activeSseClients: Int,
        rtspPlayingSessions: Int,
        totalCameraClients: Int,
        batteryLevel: Int,
        isCharging: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): RuntimeTelemetrySnapshot {
        val elapsedMs = (nowMs - lastSampleTimeMs).coerceAtLeast(1L)
        val currentMjpegTotal = mjpegBytesTotal.get()
        val currentRtspTotal = rtspBytesTotal.get()

        val mjpegDelta = (currentMjpegTotal - lastMjpegBytesTotal).coerceAtLeast(0L)
        val rtspDelta = (currentRtspTotal - lastRtspBytesTotal).coerceAtLeast(0L)

        val mjpegBandwidthBps = (mjpegDelta * 8 * 1000) / elapsedMs
        val rtspBandwidthBps = (rtspDelta * 8 * 1000) / elapsedMs

        lastSampleTimeMs = nowMs
        lastMjpegBytesTotal = currentMjpegTotal
        lastRtspBytesTotal = currentRtspTotal

        return RuntimeTelemetrySnapshot(
            timestampMs = nowMs,
            cpuUsagePercent = cpuUsagePercent,
            bandwidthBps = mjpegBandwidthBps + rtspBandwidthBps,
            mjpegBandwidthBps = mjpegBandwidthBps,
            rtspBandwidthBps = rtspBandwidthBps,
            currentCameraFps = currentCameraFps,
            currentMjpegFps = currentMjpegFps,
            currentRtspFps = currentRtspFps,
            activeHttpStreams = activeHttpStreams,
            activeSseClients = activeSseClients,
            rtspPlayingSessions = rtspPlayingSessions,
            totalCameraClients = totalCameraClients,
            batteryLevel = batteryLevel,
            isCharging = isCharging
        )
    }

    @Synchronized
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        mjpegBytesTotal.set(0)
        rtspBytesTotal.set(0)
        lastSampleTimeMs = nowMs
        lastMjpegBytesTotal = 0L
        lastRtspBytesTotal = 0L
    }
}
