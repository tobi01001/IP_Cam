# HLS Streaming Troubleshooting Guide

## Overview

This document provides troubleshooting information for HLS (HTTP Live Streaming) in the IP_Cam application, including fixes for common issues and workarounds for device limitations.

## Recent Fixes (2025-12-30)

### Issue: Green Artifacts in Preview and Stream

**Problem:**
- App preview displayed green artifacts
- VLC and web browser playback failed
- Both HLS and MJPEG streams affected

**Root Cause:**
The HLS encoder's `fillInputBuffer()` method was modifying shared `ImageProxy` buffer positions by calling `rewind()` and `position()` directly on the camera's plane buffers. Since these buffers are shared between the HLS and MJPEG pipelines, the position changes corrupted both streams.

**Fix:**
```kotlin
// Before (BROKEN):
val yBuffer = yPlane.buffer  // Direct reference - shared!
yBuffer.rewind()  // Corrupts shared buffer!

// After (FIXED):
val yBuffer = yPlane.buffer.duplicate()  // Independent view
yBuffer.rewind()  // Safe - only affects our copy
```

The fix uses `ByteBuffer.duplicate()` to create independent views of the buffers, allowing the HLS encoder to manipulate positions without affecting the MJPEG pipeline or app preview.

**Status:** ✅ Fixed in commit ada018c

---

### Issue: MP4 Fallback Not Working

**Problem:**
- Devices without MPEG-TS support (API < 26 or certain hardware) fell back to MP4
- MP4 segments created with `.ts` extension caused player confusion
- Standard HLS players couldn't play MP4-based HLS streams

**Root Cause:**
1. File extension mismatch: Creating `.ts` files with MP4 data
2. M3U8 version tag incorrect for MP4 format
3. Missing proper Content-Type headers for MP4 segments

**Fix:**
1. **Dynamic File Extension:**
   - MPEG-TS segments: `segment{N}.ts`
   - MP4 segments: `segment{N}.m4s` (standard for fragmented MP4)

2. **Correct M3U8 Version:**
   - MPEG-TS: `#EXT-X-VERSION:3`
   - MP4: `#EXT-X-VERSION:7` (indicates ISO Base Media File Format)

3. **Proper Content-Type:**
   - `.ts` files: `video/mp2t`
   - `.m4s` files: `video/mp4`

4. **Enhanced Logging:**
   ```
   MPEG-TS format supported, using MPEG-TS for HLS segments
   -- or --
   Using standard MP4 format for HLS segments - playback may be limited
   Recommended: Use a device with API 26+ for proper MPEG-TS support
   ```

**Status:** ✅ Fixed in commit ada018c

---

## Device Compatibility

### MPEG-TS Support (Preferred)

**Requirements:**
- Android API 26+ (Android 8.0 Oreo)
- Device hardware must support MPEG-TS muxing

**Detection:**
The app automatically tests for MPEG-TS support at runtime:
```kotlin
// App tries to create a test MPEG-TS muxer
// Falls back to MP4 if it fails
```

**Players Supporting MPEG-TS:**
- ✅ VLC (all versions)
- ✅ Safari (native HLS support)
- ✅ Chrome/Firefox (with hls.js)
- ✅ FFmpeg/FFplay
- ✅ ZoneMinder, Shinobi, Blue Iris
- ✅ Most NVR software

### MP4 Fallback (Limited Compatibility)

**When Used:**
- Android API < 26
- Devices that reject MPEG-TS format
- Some custom ROMs (e.g., Lineage OS on certain devices)

**Players Supporting MP4-in-HLS:**
- ✅ VLC 3.0+ (excellent MP4 segment support)
- ✅ Modern browsers with Media Source Extensions (MSE)
- ⚠️ Safari (may work with fMP4 but not guaranteed)
- ❌ Some older HLS players (expect MPEG-TS only)
- ⚠️ NVR software (varies by implementation)

**File Format:**
- Segments: `.m4s` (fragmented MP4)
- M3U8 version: 7
- Note: Not true fMP4 (no init segment), but works with tolerant players

---

## Testing HLS Streaming

### 1. Enable HLS

**Via Web UI:**
```
http://DEVICE_IP:8080/
Click "Enable HLS" button
Check status to verify encoder started
```

**Via API:**
```bash
curl http://DEVICE_IP:8080/enableHLS
```

**Via App:**
Currently not exposed in MainActivity (web-only feature)

### 2. Check Encoder Status

**Via Web UI:**
Click "Check Status" button in HLS section

**Via API:**
```bash
curl http://DEVICE_IP:8080/status | jq .hls
```

**Expected Output:**
```json
{
  "hls": {
    "enabled": true,
    "encoderName": "OMX.Exynos.AVC.Encoder",
    "isHardware": true,
    "targetBitrate": 2000000,
    "targetFps": 30,
    "actualFps": 29.8,
    "framesEncoded": 1234,
    "avgEncodingTimeMs": 12.5,
    "activeSegments": 5,
    "lastError": null
  }
}
```

### 3. Verify Playlist

**Fetch M3U8:**
```bash
curl http://DEVICE_IP:8080/hls/stream.m3u8
```

**Expected Output (MPEG-TS):**
```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:2.0,
segment0.ts
#EXTINF:2.0,
segment1.ts
...
```

**Expected Output (MP4 Fallback):**
```
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:2.0,
segment0.m4s
#EXTINF:2.0,
segment1.m4s
...
```

### 4. Test Playback

**VLC (Recommended):**
```bash
vlc http://DEVICE_IP:8080/hls/stream.m3u8
```
VLC supports both MPEG-TS and MP4 segments.

**FFplay:**
```bash
ffplay -fflags nobuffer http://DEVICE_IP:8080/hls/stream.m3u8
```

**Web Browser:**
```html
<!-- Option 1: Native (Safari) -->
<video src="http://DEVICE_IP:8080/hls/stream.m3u8" controls></video>

<!-- Option 2: hls.js (Chrome/Firefox) -->
<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<video id="video" controls></video>
<script>
  var video = document.getElementById('video');
  var hls = new Hls();
  hls.loadSource('http://DEVICE_IP:8080/hls/stream.m3u8');
  hls.attachMedia(video);
</script>
```

**FFmpeg (Record):**
```bash
ffmpeg -i http://DEVICE_IP:8080/hls/stream.m3u8 -c copy output.mp4
```

---

## Common Issues and Solutions

### Issue: "Segment not found" errors

**Symptoms:**
- Playlist loads but segments fail to fetch
- 404 errors in browser console

**Causes:**
1. Encoder not creating segments (check encoder status)
2. Segments deleted before player fetches them
3. Incorrect segment naming

**Solutions:**
1. Check encoder is running: `curl http://DEVICE_IP:8080/status`
2. Verify segments exist: Check `/data/data/com.ipcam/cache/hls_segments/` on device
3. Increase segment duration if network is slow
4. Check device logs: `adb logcat | grep HLSEncoderManager`

---

### Issue: High latency (>12 seconds)

**Symptoms:**
- Stream is delayed significantly from real-time
- Takes long time to start playing

**Causes:**
- This is **normal for HLS** (6-12 seconds expected)
- Segment duration (2 seconds) × buffer count (3-5)

**Solutions:**
- **For low latency, use MJPEG instead:** `http://DEVICE_IP:8080/stream` (150-280ms)
- HLS is designed for bandwidth efficiency, not real-time
- If you need HLS with lower latency, consider:
  - Reducing segment duration (trade-off: more frequent requests)
  - Using LL-HLS (Low-Latency HLS) - not yet implemented

---

### Issue: Player shows "unsupported format"

**Symptoms:**
- Playlist loads but player refuses to play
- "Cannot play this video" or similar error

**Causes:**
1. MP4 fallback used, player expects MPEG-TS
2. Player doesn't support HLS at all
3. Codec incompatibility

**Solutions:**
1. **Use VLC:** Most reliable player for both formats
2. **Use device with API 26+:** Ensures MPEG-TS format
3. **Check codec support:** Ensure player supports H.264
4. **Try different player:** VLC, FFplay, or browser with hls.js

**Verification:**
```bash
# Check which format is being used
curl http://DEVICE_IP:8080/hls/stream.m3u8 | grep "EXT-X-VERSION"
# Version 3 = MPEG-TS (good)
# Version 7 = MP4 (limited compatibility)
```

---

### Issue: Green artifacts in preview/stream

**Symptoms:**
- Green or corrupted colors in app preview
- Distorted MJPEG stream when HLS is enabled

**Status:**
✅ **FIXED** in commit ada018c

If you're still experiencing this:
1. Update to latest version
2. Clean and rebuild: `./gradlew clean assembleDebug`
3. Uninstall old app from device before installing new version
4. Check logs for buffer-related errors

---

### Issue: Encoder won't start

**Symptoms:**
- "Failed to enable HLS streaming" message
- No segments created

**Possible Causes:**
1. No H.264 encoder available (very rare)
2. Camera resolution not supported by encoder
3. Insufficient device resources

**Solutions:**
1. **Check encoder availability:**
   ```bash
   adb shell
   dumpsys media.codec | grep -A 20 "OMX.*.AVC.Encoder"
   ```

2. **Try different resolution:**
   - Lower resolution (e.g., 640x480 instead of 1920x1080)
   - Use `setFormat` endpoint to change resolution

3. **Restart camera:**
   - Stop HLS: `curl http://DEVICE_IP:8080/disableHLS`
   - Switch camera: `curl http://DEVICE_IP:8080/switch`
   - Switch back and re-enable HLS

4. **Check logs:**
   ```bash
   adb logcat | grep -E "HLSEncoderManager|MediaCodec"
   ```

---

## Performance Tuning

### Bandwidth vs Quality Trade-offs

**Current Defaults:**
- Bitrate: 2 Mbps
- FPS: 30
- Resolution: matches camera setting

**Recommendations by Use Case:**

**Recording (bandwidth priority):**
```
Bitrate: 1 Mbps
FPS: 15-20
Resolution: 720p
Expected bandwidth: ~1 Mbps
```

**Monitoring (quality priority):**
```
Bitrate: 3-4 Mbps
FPS: 30
Resolution: 1080p
Expected bandwidth: ~3-4 Mbps
```

**Slow network (extreme bandwidth reduction):**
```
Bitrate: 500 kbps
FPS: 10
Resolution: 480p
Expected bandwidth: ~0.5 Mbps
```

**To modify (requires code change):**
Edit `CameraService.kt`:
```kotlin
hlsEncoder = HLSEncoderManager(
    cacheDir = cacheDir,
    width = image.width,
    height = image.height,
    fps = 15,  // Change from 30
    bitrate = 1_000_000  // Change from 2_000_000
)
```

### CPU Usage

**MPEG-TS (hardware encoder):**
- Expected: 10-15% CPU
- Encoder: Hardware (GPU/DSP)

**MP4 fallback (may use software):**
- Expected: 15-25% CPU
- Encoder: May be software-based

**If CPU is too high:**
1. Reduce FPS
2. Lower resolution
3. Lower bitrate
4. Disable HLS if not needed

---

## Debugging Commands

### Check HLS Status
```bash
curl -s http://DEVICE_IP:8080/status | jq .hls
```

### Enable HLS
```bash
curl http://DEVICE_IP:8080/enableHLS
```

### Disable HLS
```bash
curl http://DEVICE_IP:8080/disableHLS
```

### View Playlist
```bash
curl http://DEVICE_IP:8080/hls/stream.m3u8
```

### Test Segment Fetch
```bash
curl -I http://DEVICE_IP:8080/hls/segment0.ts
# or
curl -I http://DEVICE_IP:8080/hls/segment0.m4s
```

### Monitor Logs
```bash
# All HLS-related logs
adb logcat | grep HLSEncoderManager

# Encoder errors only
adb logcat | grep -E "HLSEncoderManager.*E "

# Segment creation
adb logcat | grep "segment"
```

### Check Encoder Capabilities
```bash
adb shell dumpsys media.codec | grep -A 30 "Encoder:"
```

---

## Recommended Workflows

### Workflow 1: Real-Time Monitoring
**Use Case:** Security monitoring, baby monitor, live viewing  
**Recommendation:** Use MJPEG, not HLS  
**Endpoint:** `http://DEVICE_IP:8080/stream`  
**Latency:** 150-280ms  
**Bandwidth:** ~8 Mbps @ 1080p

### Workflow 2: Recording/Archiving
**Use Case:** Continuous recording, event logging  
**Recommendation:** Use HLS with recording  
**Setup:**
```bash
# Enable HLS
curl http://DEVICE_IP:8080/enableHLS

# Start FFmpeg recording
ffmpeg -i http://DEVICE_IP:8080/hls/stream.m3u8 \
  -c copy -f segment -segment_time 600 \
  /path/to/recording_%03d.mp4
```
**Latency:** 6-12s (acceptable for recording)  
**Bandwidth:** ~2-4 Mbps @ 1080p

### Workflow 3: Bandwidth-Limited Viewing
**Use Case:** Remote viewing over slow connection  
**Recommendation:** Use HLS  
**Endpoint:** `http://DEVICE_IP:8080/hls/stream.m3u8`  
**Latency:** 6-12s  
**Bandwidth:** ~2-4 Mbps @ 1080p (vs 8 Mbps MJPEG)

### Workflow 4: Multiple Viewers
**Use Case:** Sharing stream with multiple people  
**Recommendation:** Use HLS (more efficient for multiple clients)  
**Why:** Each HLS client fetches segments independently; server serves static files  
**MJPEG:** Each client = separate JPEG encoding (N × bandwidth)  
**HLS:** Each client = same segments (1 × encoding, N × segment fetches)

---

## Summary

### What Was Fixed
1. ✅ Green artifacts in preview (buffer corruption)
2. ✅ VLC/web playback issues (MP4 format compatibility)
3. ✅ File extension mismatch (.ts vs .m4s)
4. ✅ Missing Content-Type headers
5. ✅ Incorrect M3U8 version tags

### What Works Now
- ✅ Preview shows correct colors
- ✅ MJPEG unaffected by HLS encoder
- ✅ VLC playback (both MPEG-TS and MP4)
- ✅ Web browser playback (with hls.js)
- ✅ Proper format detection and fallback
- ✅ Comprehensive error logging

### Known Limitations
- ⚠️ MP4 fallback has limited HLS player compatibility
- ⚠️ HLS inherently has 6-12 second latency (by design)
- ⚠️ No fMP4 initialization segment (standard MP4 segments)
- ⚠️ Some NVR software may not support MP4-in-HLS

### Recommendations
1. **For best results:** Use device with Android 8.0+ (API 26+)
2. **For real-time monitoring:** Use MJPEG (`/stream`)
3. **For recording/bandwidth:** Use HLS (`/hls/stream.m3u8`)
4. **For testing:** Use VLC (supports all formats)
5. **For web:** Use hls.js library (Chrome/Firefox)

---

## Additional Resources

- [HLS Specification (RFC 8216)](https://tools.ietf.org/html/rfc8216)
- [Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- [Android MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)
- [hls.js Library](https://github.com/video-dev/hls.js/)
- [VLC Media Player](https://www.videolan.org/vlc/)

---

## Support

If you encounter issues not covered in this guide:

1. **Check logs:** `adb logcat | grep HLSEncoderManager`
2. **Check status:** `curl http://DEVICE_IP:8080/status`
3. **Try VLC:** Most reliable test player
4. **Report issue:** Include device model, Android version, and log output

For issues, please open a GitHub issue with:
- Device model and Android version
- Logcat output (especially HLSEncoderManager and MediaCodec lines)
- M3U8 playlist content
- Steps to reproduce
