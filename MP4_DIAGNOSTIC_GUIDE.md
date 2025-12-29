# MP4 Streaming Debug - Diagnostic Implementation

## Problem Summary (Updated Based on User Feedback)

The initial problem statement was about ANR/freezes during mode switching, but user feedback revealed:

**Actual Issues:**
1. MP4 streaming **never worked at all** (not just freezing during switch)
2. Preview stopped at last MJPEG frame because no MP4 stream was produced
3. UI became unresponsive when MP4 mode was selected
4. Preview/stream during switching is not needed - only when switch completes

## Implementation Approach

### What Was Changed

#### 1. Async Encoder Initialization (Prevents ANR)
```kotlin
// OLD - blocks main thread
synchronized(mp4StreamLock) {
    mp4StreamWriter = Mp4StreamWriter(...)
    mp4StreamWriter?.initialize()  // BLOCKS 100-500ms
    mp4StreamWriter?.start()
}

// NEW - non-blocking
serviceScope.launch(Dispatchers.IO) {
    val encoder = Mp4StreamWriter(...)
    encoder.initialize()  // Background thread
    encoder.start()
    
    withContext(Dispatchers.Main) {
        // Bind camera after encoder ready
        camera = cameraProvider?.bindToLifecycle(...)
    }
}
```

#### 2. Comprehensive Diagnostic Logging
Added `[MP4]` prefix to all MP4-related logs to trace execution:

**Encoder Initialization:**
```
[MP4] Starting MP4 camera binding process for resolution 1920x1080
[MP4] Initializing MP4 encoder on background thread...
[MP4] Calling encoder.initialize()...
[MP4] Calling encoder.start()...
[MP4] Encoder initialized and started successfully. Surface available: true
```

**Camera Binding:**
```
[MP4] Creating Preview use case...
[MP4] Providing encoder input surface to camera preview
[MP4] Surface provision result received
[MP4] Creating ImageAnalysis for app preview...
[MP4] App preview resolution: 640x480
[MP4] Binding camera to lifecycle...
[MP4] Camera bound successfully!
[MP4] MP4 mode initialization complete
```

**Frame Production:**
```
[MP4] Started MP4 encoder processing coroutine
[MP4] Encoder producing frames: 30 frames total, queue has frames: true
[MP4] Processing preview frame: 640x480, format=35
```

### What Was NOT Changed

- Simplified approach: removed complex pre-initialization logic
- Kept original MainActivity (no UI changes needed)
- Focused on diagnostics rather than premature optimization

## Diagnostic Strategy

The logging will reveal WHERE MP4 fails in the pipeline:

### Encoder Initialization Check
**If you see:**
```
[MP4] Initializing MP4 encoder on background thread...
[MP4] FATAL: Failed to initialize encoder
```
**Then:** Device doesn't support H.264 hardware encoding OR encoder configuration is wrong

### Surface Availability Check
**If you see:**
```
[MP4] Encoder initialized and started successfully. Surface available: false
```
**Then:** MediaCodec failed to create input surface (serious issue)

### Camera Binding Check
**If you see:**
```
[MP4] Binding camera to lifecycle...
[MP4] FATAL: Camera binding returned null!
```
**Then:** CameraX binding failed (permission issue, resource conflict, or use case incompatibility)

### Frame Production Check
**If you see encoder starts but:**
```
[MP4] Started MP4 encoder processing coroutine
[MP4] Encoder processing loop ended. isActive=true, encoder running=true
[MP4] MP4 encoder processing coroutine ended. Total frames processed: 0
```
**Then:** Encoder is running but not producing frames (camera → encoder connection broken)

### Preview Check
**If you see camera bound but:**
```
(No "[MP4] Processing preview frame" logs)
```
**Then:** ImageAnalysis not receiving frames (camera → ImageAnalysis connection broken)

## Testing Instructions

### 1. Enable ADB Logging
```bash
adb logcat -c  # Clear logs
adb logcat *:S CameraService:D Mp4StreamWriter:D | grep '\[MP4\]'
```

### 2. Test MP4 Mode Switch
1. Start app in MJPEG mode
2. Switch to MP4 mode (via app or web interface)
3. Observe logcat output

### 3. Expected Log Sequence
```
[MP4] Starting MP4 camera binding process for resolution 1920x1080
[MP4] Initializing MP4 encoder on background thread...
[MP4] Calling encoder.initialize()...
[MP4] Calling encoder.start()...
[MP4] Encoder initialized and started successfully. Surface available: true
[MP4] Creating Preview use case...
[MP4] Providing encoder input surface to camera preview
[MP4] Surface provision result received
[MP4] Creating ImageAnalysis for app preview...
[MP4] App preview resolution: 640x480
[MP4] Binding camera to lifecycle...
[MP4] Camera bound successfully!
[MP4] MP4 mode initialization complete
[MP4] Started MP4 encoder processing coroutine
[MP4] Processing preview frame: 640x480, format=35
[MP4] Encoder producing frames: 30 frames total, queue has frames: true
```

### 4. Test MP4 Stream
```bash
# Try to access stream
curl http://DEVICE_IP:8080/stream.mp4 --output test.mp4 &
sleep 5
killall curl
ls -lh test.mp4  # Should have some data if encoder is working
```

## Known Possible Issues

### Issue 1: MediaCodec Not Supported
**Symptom:** `[MP4] FATAL: Failed to initialize encoder`
**Cause:** Device doesn't have H.264 hardware encoder
**Solution:** Check codec support:
```bash
adb shell dumpsys media.player | grep -A 10 "video/avc"
```

### Issue 2: Surface Creation Failure
**Symptom:** `[MP4] Encoder initialized... Surface available: false`
**Cause:** MediaCodec.createInputSurface() failed
**Solution:** This is a serious MediaCodec issue, may need different encoder configuration

### Issue 3: Use Case Binding Conflict
**Symptom:** `[MP4] FATAL: Camera binding returned null!`
**Cause:** Can't bind both Preview and ImageAnalysis simultaneously
**Solution:** May need to bind only Preview OR use single ImageAnalysis with JPEG compression

### Issue 4: Encoder Not Receiving Frames
**Symptom:** Encoder running but 0 frames produced
**Cause:** Camera not writing to encoder surface
**Solution:** Surface connection issue, may need to check CameraX configuration

## Next Steps

1. **Test on actual device** and collect `[MP4]` logs
2. **Identify failure point** from log sequence
3. **Report which check fails** (see "Diagnostic Strategy" above)
4. **Share full logcat** if failure point is unclear

The comprehensive logging will pinpoint exactly where MP4 fails, allowing for targeted fixes.

## Commit Hash

All changes are in commit: `efa018e`
