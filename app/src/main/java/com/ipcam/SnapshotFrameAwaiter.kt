package com.ipcam

import kotlinx.coroutines.delay

internal enum class SnapshotUnavailableReason {
    TIMEOUT,
    CAMERA_ERROR
}

internal sealed interface SnapshotFrameResult {
    data class Available(val jpegBytes: ByteArray) : SnapshotFrameResult

    data class Unavailable(
        val cameraState: String,
        val reason: SnapshotUnavailableReason
    ) : SnapshotFrameResult
}

internal suspend fun awaitSnapshotFrame(
    timeoutMs: Long,
    pollIntervalMs: Long = 100L,
    frameProvider: () -> ByteArray?,
    stateProvider: () -> String,
    sleep: suspend (Long) -> Unit = { delay(it) },
    currentTimeMs: () -> Long = { System.nanoTime() / 1_000_000L }
): SnapshotFrameResult {
    val deadlineMs = currentTimeMs() + timeoutMs

    while (true) {
        frameProvider()?.let { return SnapshotFrameResult.Available(it) }

        val cameraState = stateProvider()
        if (cameraState == "ERROR") {
            return SnapshotFrameResult.Unavailable(
                cameraState = cameraState,
                reason = SnapshotUnavailableReason.CAMERA_ERROR
            )
        }

        val remainingMs = deadlineMs - currentTimeMs()
        if (remainingMs <= 0L) {
            return SnapshotFrameResult.Unavailable(
                cameraState = cameraState,
                reason = SnapshotUnavailableReason.TIMEOUT
            )
        }

        sleep(minOf(pollIntervalMs, remainingMs))
    }
}
