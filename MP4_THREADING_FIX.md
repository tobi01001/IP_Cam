# MP4 Threading Issues - Fix Summary

## Problems Identified from Device Testing

Based on logcat analysis from actual device testing, three critical issues were identified:

### 1. MediaCodec Threading Conflict (IllegalStateException)

**Error:**
```
java.lang.IllegalStateException: Invalid to call while another dequeue output request is pending
    at android.media.MediaCodec.native_dequeueOutputBuffer(Native Method)
    at com.ipcam.Mp4StreamWriter.drainEncoder(Mp4StreamWriter.kt:164)
```

**Root Cause:**
- Encoder processing coroutine calls `processEncoderOutput()` → `drainEncoder()` continuously
- `stop()` method also calls `drainEncoder(true)` during shutdown
- MediaCodec doesn't support concurrent `dequeueOutputBuffer()` calls
- When mode switches, both try to dequeue simultaneously → exception

**Fix:**
```kotlin
// Before
fun stop() {
    isRunning.set(false)
    if (isCodecStarted) {
        drainEncoder(true)  // ❌ Conflicts with processing coroutine
    }
}

// After
fun stop() {
    isRunning.set(false)  // Signal processing coroutine to stop
    // Don't call drainEncoder - let processing coroutine exit cleanly
}

fun release() {
    stop()
    Thread.sleep(50)  // Wait for processing coroutine to exit
    mediaCodec?.stop()
    mediaCodec?.release()
}
```

### 2. Spinner Not Updating to MP4

**Symptom:**
- User selects MP4 from spinner
- Spinner immediately reverts to MJPEG
- No log message "User selected streaming mode: MP4"

**Root Cause:**
- `updateStreamingModeSelection()` was managing `isUpdatingSpinners` flag internally
- But callback in `onServiceConnected()` also manages the flag with deferred reset using `post()`
- Race condition: `updateStreamingModeSelection()` resets flag before callback's `post()` executes
- When callback's `post()` finally resets flag, spinner's `onItemSelected()` fires again
- This triggers another mode change, creating a loop that prevents MP4 from sticking

**Timeline:**
```
1. Web interface calls setStreamingMode(MP4)
2. Service callback fires: onCameraStateChangedCallback?.invoke()
3. MainActivity callback sets isUpdatingSpinners = true
4. updateStreamingModeSelection() called
   - Sets isUpdatingSpinners = true (again)
   - Calls setSelection(1) for MP4
   - Resets isUpdatingSpinners = false  ← TOO EARLY
5. Callback's post() block executes
   - Resets isUpdatingSpinners = false again
6. setSelection(1) actually executes (posted to UI thread)
7. onItemSelected() fires with isUpdatingSpinners = false
8. Calls setStreamingMode(MP4) again
9. Service says "already set to mp4" and returns
10. But spinner was never actually updated
```

**Fix:**
```kotlin
// Before
private fun updateStreamingModeSelection() {
    isUpdatingSpinners = true
    try {
        streamingModeSpinner.setSelection(position)
    } finally {
        isUpdatingSpinners = false  // ❌ Conflicts with callback's post()
    }
}

// After
private fun updateStreamingModeSelection() {
    // Let caller (callback) manage isUpdatingSpinners
    // Callback already uses post() for proper timing
    streamingModeSpinner.setSelection(position)
}
```

### 3. UI Freeze During Mode Switch

**Symptom:**
- UI becomes unresponsive when switching modes from web interface
- Encoder stop takes time (50-100ms) blocking main thread

**Root Cause:**
- `stopCamera()` called encoder stop/release on main thread
- `release()` calls `mediaCodec?.stop()` which can block for 10-50ms
- This happens during camera rebinding which runs on main thread

**Fix:**
```kotlin
// Before
private fun stopCamera() {
    synchronized(mp4StreamLock) {
        mp4StreamWriter?.stop()     // ❌ Blocks main thread
        mp4StreamWriter?.release()  // ❌ Blocks main thread
    }
}

// After
private fun stopCamera() {
    val encoderToStop = synchronized(mp4StreamLock) { mp4StreamWriter }
    if (encoderToStop != null) {
        serviceScope.launch(Dispatchers.IO) {
            encoderToStop.stop()
            delay(100)  // Let processing coroutine exit
            encoderToStop.release()
        }
        synchronized(mp4StreamLock) {
            mp4StreamWriter = null  // Clear reference immediately
        }
    }
}
```

## Additional Improvements

### Debug Logging
Added logging to track spinner behavior:
```kotlin
override fun onItemSelected(...) {
    if (isUpdatingSpinners) {
        Log.d("MainActivity", "Streaming mode spinner changed (programmatic), skipping")
        return
    }
    Log.d("MainActivity", "User selected streaming mode: $newMode")
    cameraService?.setStreamingMode(newMode)
}
```

This helps debug whether spinner changes are user-initiated or programmatic.

## Testing Verification

### Expected Behavior After Fix:

1. **Switching to MP4 via App Spinner:**
   - Spinner stays on MP4
   - Log shows: "User selected streaming mode: MP4"
   - No IllegalStateException in logcat
   - UI remains responsive

2. **Switching to MP4 via Web Interface:**
   - App spinner updates to MP4
   - Log shows: "Streaming mode spinner changed (programmatic), skipping"
   - No double-call to setStreamingMode
   - No IllegalStateException in logcat
   - UI remains responsive

3. **Mode Switch from MP4 to MJPEG:**
   - Clean encoder shutdown
   - No errors in logcat
   - UI remains responsive

### Logcat Verification:

**Good sequence (no errors):**
```
[MP4] Starting MP4 camera binding process
[MP4] Initializing encoder on background thread...
[MP4] Encoder initialized and started successfully
[MP4] Camera bound successfully!
[MP4] Started MP4 encoder processing coroutine
[MP4] Processing preview frame: 1280x720
[MP4] Encoder producing frames: 89 frames total

(When switching modes)
Stopping MP4 encoder (async)...
[MP4] Encoder processing loop ended. isActive=true, encoder running=false
[MP4] MP4 encoder processing coroutine ended. Total frames processed: 180
MP4 encoder stopped and released
```

**Bad sequence (before fix):**
```
Stopping MP4 encoder...
Mp4StreamWriter: Error stopping encoder
java.lang.IllegalStateException: Invalid to call while another dequeue output request is pending
[MP4] FATAL: Error in MP4 encoder processing
java.lang.IllegalStateException: Pending dequeue output buffer request cancelled
```

## Related Files Changed

1. **Mp4StreamWriter.kt**
   - Removed `drainEncoder()` call from `stop()`
   - Added delay in `release()` for clean coroutine exit

2. **CameraService.kt**
   - Made encoder stop/release async on Dispatchers.IO
   - Added proper cleanup sequencing

3. **MainActivity.kt**
   - Fixed `updateStreamingModeSelection()` flag management
   - Added debug logging for spinner changes

## Remaining Issues

The logcat also showed:
```
Mp4StreamWriter: Encoded frame queue full, maintaining rolling buffer (dropped 91 frames total)
```

This indicates frames are being encoded but not consumed by any client. This is expected if:
- No HTTP client is connected to `/stream.mp4`
- The queue (size 100) fills up from continuous encoding
- Frames are dropped to maintain rolling buffer

This is **not a bug** - it's the designed behavior when encoding without active streaming clients. The encoder continues to work so clients can connect anytime.

## Commit

All fixes are in commit: `f1d26e5`
