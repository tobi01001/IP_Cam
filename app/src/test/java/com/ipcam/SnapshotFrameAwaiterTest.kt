package com.ipcam

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFrameAwaiterTest {
    @Test
    fun returnsExistingFrameImmediately() = runBlocking {
        val jpegBytes = byteArrayOf(1, 2, 3)

        val result = awaitSnapshotFrame(
            timeoutMs = 500,
            frameProvider = { jpegBytes },
            stateProvider = { "ACTIVE" },
            sleep = { error("sleep should not be called when frame is already available") }
        )

        assertTrue(result is SnapshotFrameResult.Available)
        assertArrayEquals(jpegBytes, (result as SnapshotFrameResult.Available).jpegBytes)
    }

    @Test
    fun waitsForFirstFrameDuringColdStart() = runBlocking {
        var nowMs = 0L
        val jpegBytes = byteArrayOf(9, 8, 7)

        val result = awaitSnapshotFrame(
            timeoutMs = 500,
            pollIntervalMs = 100,
            frameProvider = { if (nowMs >= 300L) jpegBytes else null },
            stateProvider = { if (nowMs < 200L) "INITIALIZING" else "ACTIVE" },
            sleep = { nowMs += it },
            currentTimeMs = { nowMs }
        )

        assertTrue(result is SnapshotFrameResult.Available)
        assertArrayEquals(jpegBytes, (result as SnapshotFrameResult.Available).jpegBytes)
    }

    @Test
    fun repeatedCallsCanSucceedAfterInitialTimeout() = runBlocking {
        var nowMs = 0L
        val jpegBytes = byteArrayOf(4, 5, 6)

        val firstResult = awaitSnapshotFrame(
            timeoutMs = 150,
            pollIntervalMs = 50,
            frameProvider = { if (nowMs >= 300L) jpegBytes else null },
            stateProvider = { "INITIALIZING" },
            sleep = { nowMs += it },
            currentTimeMs = { nowMs }
        )

        assertEquals(
            SnapshotFrameResult.Unavailable("INITIALIZING", SnapshotUnavailableReason.TIMEOUT),
            firstResult
        )

        val secondResult = awaitSnapshotFrame(
            timeoutMs = 200,
            pollIntervalMs = 50,
            frameProvider = { if (nowMs >= 300L) jpegBytes else null },
            stateProvider = { if (nowMs < 300L) "INITIALIZING" else "ACTIVE" },
            sleep = { nowMs += it },
            currentTimeMs = { nowMs }
        )

        assertTrue(secondResult is SnapshotFrameResult.Available)
        assertArrayEquals(jpegBytes, (secondResult as SnapshotFrameResult.Available).jpegBytes)
    }

    @Test
    fun returnsCameraErrorWithoutWaitingForTimeout() = runBlocking {
        var nowMs = 0L

        val result = awaitSnapshotFrame(
            timeoutMs = 500,
            pollIntervalMs = 100,
            frameProvider = { null },
            stateProvider = {
                if (nowMs == 0L) {
                    "INITIALIZING"
                } else {
                    "ERROR"
                }
            },
            sleep = { nowMs += it },
            currentTimeMs = { nowMs }
        )

        assertEquals(
            SnapshotFrameResult.Unavailable("ERROR", SnapshotUnavailableReason.CAMERA_ERROR),
            result
        )
    }
}
