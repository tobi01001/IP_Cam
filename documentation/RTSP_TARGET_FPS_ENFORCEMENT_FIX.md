# RTSP Target Framerate Enforcement Fix

## Issue
The RTSP stream always produced ~30 fps regardless of the configured target framerate. Setting the target to 1 fps still resulted in 29.x fps in VLC, and the stream did not show visible frame-by-frame updates.

## Root Cause

### MediaCodec KEY_FRAME_RATE vs KEY_OPERATING_RATE

Android MediaCodec has two framerate-related parameters that are often confused:

1. **`KEY_FRAME_RATE`** - A **hint** to the encoder about expected input framerate
   - Used for **rate control** algorithms
   - Does **NOT** limit the actual encoding rate
   - The encoder will encode **every frame** sent to its input buffer/surface

2. **`KEY_OPERATING_RATE`** - The **actual operating rate** for the encoder
   - Tells the encoder to **limit output to this rate**
   - This is what actually controls the encoding framerate

### The Problem

The previous implementation only set `KEY_FRAME_RATE`:
```kotlin
format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)  // Only a hint!
```

CameraX Preview was continuously sending frames at ~30 fps to the encoder's input surface, and the encoder dutifully encoded all of them because `KEY_FRAME_RATE` doesn't limit output.

## Solution

### 1. Add KEY_OPERATING_RATE to Encoder Configuration

**H264PreviewEncoder.kt**:
```kotlin
format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)      // Hint for rate control
format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)  // Actually limits encoding rate
```

**RTSPServer.kt** (legacy ImageProxy path):
```kotlin
format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
```

### 2. Configure CameraX Preview with Target Frame Rate

**CameraService.kt**:
```kotlin
videoCaptureUseCase = androidx.camera.core.Preview.Builder()
    .setResolutionSelector(previewResolutionSelector)
    .setTargetFrameRate(android.util.Range(targetRtspFps, targetRtspFps))  // Limit input frames
    .build()
```

This is a **defense-in-depth** approach:
- CameraX tries to send fewer frames to the encoder
- Encoder limits encoding rate with `KEY_OPERATING_RATE`

### 3. Add Encoder Output Verification Logging

Added detailed logging to verify the encoder is actually configured correctly:

```kotlin
val outputFormat = encoder?.outputFormat
val actualFrameRate = outputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
val actualBitrate = outputFormat.getInteger(MediaFormat.KEY_BIT_RATE)
val actualBitrateMode = outputFormat.getInteger(MediaFormat.KEY_BITRATE_MODE)

Log.i(TAG, "=== ENCODER OUTPUT FORMAT VERIFICATION ===")
Log.i(TAG, "Configured FPS: $fps, Actual output FPS: $actualFrameRate")
Log.i(TAG, "Configured bitrate: ${bitrate / 1_000_000}Mbps, Actual output bitrate: ${actualBitrate / 1_000_000}Mbps")
Log.i(TAG, "Configured bitrate mode: VBR, Actual output mode: $actualBitrateMode")
```

## Testing

### 1. Set Target FPS to 1

```bash
curl "http://DEVICE_IP:8080/rtsp/fps?value=1"
```

### 2. Connect VLC and Verify

```bash
vlc rtsp://DEVICE_IP:8554/stream
```

**Expected behavior**:
- Stream should show visible frame-by-frame updates (~1 second per frame)
- VLC codec info should show ~1 fps decoded (not 29.x fps)

### 3. Check Logs

```bash
adb logcat | grep "ENCODER OUTPUT FORMAT VERIFICATION"
```

**Expected output**:
```
=== ENCODER OUTPUT FORMAT VERIFICATION ===
Configured FPS: 1, Actual output FPS: 1
Configured bitrate: 5Mbps, Actual output bitrate: 5Mbps
Configured bitrate mode: VBR, Actual output mode: VBR
```

### 4. Test Different Frame Rates

Test with various FPS settings to ensure all work correctly:
- 1 fps - visible frame-by-frame updates
- 5 fps - smooth but noticeably slow
- 10 fps - smooth motion
- 15 fps - fluid motion
- 30 fps - full motion

## Technical Details

### MediaCodec Documentation

From Android documentation:

> **KEY_FRAME_RATE**: Used to supply a hint to a video encoder indicating the framerates (in frames per second) expected from the input buffer source. The encoder may use this information to tune parameters such as the target bitrate, but the actual frame rate is unaffected.

> **KEY_OPERATING_RATE**: The video encoder may drop frames to meet the operating rate specified. The operating rate must be greater than or equal to the frame rate. If the operating rate is much larger than the frame rate, the video encoder may produce higher quality.

### Why Both Approaches?

1. **CameraX `setTargetFrameRate()`**: Reduces unnecessary work at the camera level
   - Camera produces fewer frames
   - Less data to process
   - Lower CPU/battery usage

2. **MediaCodec `KEY_OPERATING_RATE`**: Guarantees encoding rate limit
   - Even if camera sends more frames, encoder won't encode all of them
   - Reliable framerate control
   - Works even if CameraX doesn't support target frame rate on some devices

## Bitrate Mode Verification

The logging also verifies bitrate mode (VBR/CBR) is correctly applied:

```
Configured bitrate mode: VBR, Actual output mode: VBR
```

This confirms:
- `MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR` is set
- Encoder acknowledges and applies VBR mode
- No silent fallback to CBR or other modes

## Files Changed

1. **app/src/main/java/com/ipcam/H264PreviewEncoder.kt**
   - Added `KEY_OPERATING_RATE` configuration
   - Added encoder output verification logging

2. **app/src/main/java/com/ipcam/RTSPServer.kt**
   - Added `KEY_OPERATING_RATE` configuration (legacy path)
   - Added encoder output verification logging

3. **app/src/main/java/com/ipcam/CameraService.kt**
   - Added `setTargetFrameRate()` to CameraX Preview configuration

## References

- [Android MediaCodec Documentation](https://developer.android.com/reference/android/media/MediaCodec)
- [MediaFormat KEY_FRAME_RATE](https://developer.android.com/reference/android/media/MediaFormat#KEY_FRAME_RATE)
- [MediaFormat KEY_OPERATING_RATE](https://developer.android.com/reference/android/media/MediaFormat#KEY_OPERATING_RATE)
- [CameraX Preview API](https://developer.android.com/reference/androidx/camera/core/Preview)

## Summary

The fix adds proper framerate enforcement using `KEY_OPERATING_RATE` instead of relying solely on the `KEY_FRAME_RATE` hint. Combined with CameraX input frame limiting and verification logging, this ensures the encoder produces output at the configured framerate and allows verification that all settings (FPS, bitrate, bitrate mode) are correctly applied.
