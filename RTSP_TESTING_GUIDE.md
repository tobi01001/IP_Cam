# Testing Guide - RTSP Stream Fixes

## Overview

This guide helps verify the RTSP stream fixes for monochrome output, color format issues, and performance problems.

## Prerequisites

- Android device with IP_Cam installed
- Device and test computer on same network
- VLC Media Player (https://www.videolan.org/)
- FFmpeg/FFplay (optional, for advanced testing)

## Quick Test Steps

### 1. Enable RTSP Streaming

**Via Web UI:**
1. Open browser to `http://DEVICE_IP:8080`
2. Scroll to "RTSP Streaming" section
3. Click "Enable RTSP" button
4. Verify status shows:
   - ✓ RTSP Active (green)
   - Encoder name and "Hardware: true"
   - Color Format (e.g., "COLOR_FormatYUV420SemiPlanar (NV12)")
   - Frame Rate close to target (e.g., "29.5 fps (target: 30 fps)")
   - RTSP URL displayed

**Via HTTP API:**
```bash
curl http://DEVICE_IP:8080/enableRTSP
```

Expected response:
```json
{
  "status": "ok",
  "message": "RTSP streaming enabled",
  "rtspEnabled": true,
  "encoder": "OMX.qcom.video.encoder.avc",
  "isHardware": true,
  "colorFormat": "COLOR_FormatYUV420SemiPlanar (NV12)",
  "colorFormatHex": "0x15",
  "url": "rtsp://192.168.1.100:8554/stream",
  "port": 8554
}
```

### 2. Test with VLC

**Open Stream:**
```bash
vlc rtsp://DEVICE_IP:8554/stream
```

Or in VLC:
1. Media → Open Network Stream
2. Enter: `rtsp://DEVICE_IP:8554/stream`
3. Click Play

**Verify:**
- [ ] Video displays in **full color** (not monochrome/grey)
- [ ] Video is smooth and not stuttering
- [ ] Frame rate is consistent (check VLC stats: Tools → Media Information)

**Check Codec Information:**
1. Tools → Codec Information (or Ctrl+J)
2. Verify:
   - Codec: **H264 - MPEG-4 AVC (part 10) (h264)**
   - Type: Video
   - Resolution: Matches camera (e.g., 1920x1080)
   - Frame rate: ~30 fps
   - Decoded format: **Planar 4:2:0 YUV** (or similar)

**Screenshot:**

Before fix:
- Codec info missing or incomplete
- Decoded format field showing incorrect or unclear value
- Monochrome/grey video

After fix:
- Codec: H264 clearly shown
- Decoded format: Shows YUV color space
- Full color video

### 3. Check Web UI Status

Click "Check Status" button in web UI, verify:
- Frame Rate: Should be close to 30 fps (e.g., 28-30 fps)
- Frames encoded: Increasing steadily
- Dropped frames: Some drops are normal for rate limiting
- Color Format: Shows specific format (NV12, I420, etc.)

Example:
```
✓ RTSP Active
Encoder: OMX.qcom.video.encoder.avc (Hardware: true)
Color Format: COLOR_FormatYUV420SemiPlanar (NV12) (0x15)
Frame Rate: 29.5 fps (target: 30 fps)
Frames: 5420 encoded, 180 dropped
Active Sessions: 1 | Playing: 1
URL: rtsp://192.168.1.100:8554/stream
```

### 4. Advanced Testing with FFmpeg

**Test Stream Quality:**
```bash
ffplay -rtsp_transport tcp -stats -loglevel warning rtsp://DEVICE_IP:8554/stream
```

Watch for:
- Smooth playback without stuttering
- Full color video
- Stats show ~30 fps

**Record and Analyze:**
```bash
# Record 10 seconds
ffmpeg -rtsp_transport tcp -i rtsp://DEVICE_IP:8554/stream -t 10 -c copy test.mp4

# Analyze recording
ffprobe test.mp4
```

Expected output:
```
Stream #0:0: Video: h264 (Baseline), yuv420p, 1920x1080, 30 fps, 30 tbr, 90k tbn, 60 tbc
```

Verify:
- Codec: **h264 (Baseline)**
- Pixel format: **yuv420p** (YUV 4:2:0 planar)
- Resolution: Matches camera
- Frame rate: ~30 fps

## LogCat Diagnostics

**View logs on Android device:**
```bash
adb logcat -s RTSPServer:* | grep -E "format|fps|frame|SPS|PPS"
```

**Expected logs on RTSP enable:**

```
RTSPServer: Available color formats for OMX.qcom.video.encoder.avc:
RTSPServer:   - COLOR_FormatYUV420SemiPlanar (NV12) (0x15)
RTSPServer:   - COLOR_FormatYUV420Planar (I420) (0x13)
RTSPServer: Selected preferred format: COLOR_FormatYUV420SemiPlanar (NV12)
RTSPServer: Using color format: COLOR_FormatYUV420SemiPlanar (NV12) (0x15)
RTSPServer: Encoder configuration: 1920x1080 @ 30fps, bitrate=2000000, format=COLOR_FormatYUV420SemiPlanar (NV12)
RTSPServer: Encoder started successfully: OMX.qcom.video.encoder.avc (hardware: true)
```

**Expected logs on first frame:**

```
RTSPServer: Encoding first frame: 1920x1080 @ 30 fps
RTSPServer: YUV_420_888 format details:
RTSPServer:   Y: 1920x1080, rowStride=1920, pixelStride=1
RTSPServer:   U: 960x540, rowStride=960, pixelStride=2
RTSPServer:   V: 960x540, rowStride=960, pixelStride=2
RTSPServer:   Encoder expects: COLOR_FormatYUV420SemiPlanar (NV12)
RTSPServer: First frame queued with size: 3110400 bytes
RTSPServer: Filled input buffer: 3110400 bytes
RTSPServer: Encoder output format changed: MediaFormat details...
RTSPServer: Extracted SPS: 27 bytes
RTSPServer: Extracted PPS: 4 bytes
```

**Frame rate limiting (normal):**

```
RTSPServer: Frame rate limiting: dropped 150 total frames to maintain 30 fps target
```

**Encoder queue full (normal under load):**

```
RTSPServer: Encoder input buffer unavailable (queue full), 180 total frames dropped
```

## Common Issues and Solutions

### Issue: Still seeing monochrome video

**Possible causes:**
1. Old APK version installed
2. RTSP server needs restart

**Solutions:**
```bash
# Disable and re-enable RTSP
curl http://DEVICE_IP:8080/disableRTSP
sleep 2
curl http://DEVICE_IP:8080/enableRTSP

# Or reinstall app with new APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Issue: Stuttering/choppy video

**Possible causes:**
1. Poor WiFi signal
2. High network latency
3. Device thermal throttling

**Solutions:**
```bash
# Try TCP instead of UDP
vlc --rtsp-tcp rtsp://DEVICE_IP:8554/stream

# Check dropped frames
curl http://DEVICE_IP:8080/rtspStatus | jq .droppedFrames

# Monitor device temperature
adb shell dumpsys battery | grep temperature
```

### Issue: VLC shows "Can't get codec description"

**Possible causes:**
1. SPS/PPS not available yet
2. Stream starting too quickly

**Solutions:**
1. Wait 2-3 seconds after enabling RTSP before connecting
2. Check logs for "Extracted SPS" and "Extracted PPS"
3. Try reconnecting in VLC

### Issue: "Connection timeout" in VLC

**Possible causes:**
1. Firewall blocking port 8554
2. Wrong IP address
3. RTSP not enabled

**Solutions:**
```bash
# Check RTSP is enabled
curl http://DEVICE_IP:8080/rtspStatus

# Test connectivity
telnet DEVICE_IP 8554

# Try TCP transport
vlc --rtsp-tcp rtsp://DEVICE_IP:8554/stream
```

## Performance Benchmarks

### Expected Performance Metrics

**Frame Rate:**
- Target: 30 fps
- Actual: 28-30 fps (97-100% of target)
- Dropped frames: 3-7% (normal for rate limiting)

**Encoding:**
- Frame encoding time: < 10ms per frame (on modern devices)
- CPU usage: 5-15% with hardware encoding
- Memory usage: ~50-100 MB for encoder buffers

**Network:**
- Bitrate: ~2 Mbps (as configured)
- Latency: 500ms-1s (UDP), 700ms-1.2s (TCP)
- Bandwidth: ~75% less than MJPEG (2 Mbps vs 8 Mbps)

**Quality:**
- Color reproduction: Full YUV 4:2:0
- Resolution: Matches camera (e.g., 1920x1080)
- Compression: H.264 Baseline Profile

### Compare MJPEG vs RTSP

**MJPEG Stream:**
```bash
# View in browser
open http://DEVICE_IP:8080/stream

# Characteristics:
# - Latency: 150-280ms (lower)
# - Bandwidth: ~8 Mbps (higher)
# - Quality: Good (JPEG per frame)
# - Compatibility: Universal
```

**RTSP Stream:**
```bash
# View in VLC
vlc rtsp://DEVICE_IP:8554/stream

# Characteristics:
# - Latency: 500-1000ms (moderate)
# - Bandwidth: ~2 Mbps (lower)
# - Quality: Excellent (H.264)
# - Compatibility: Modern systems
```

## Integration Testing

### Test with Surveillance Systems

**ZoneMinder:**
1. Add Monitor → Source Type: **Ffmpeg**
2. Source Path: `rtsp://DEVICE_IP:8554/stream`
3. Target Colorspace: **24 bit color**
4. Verify: Live view shows color, not monochrome

**Shinobi:**
1. Add Monitor → Input Type: **H264**
2. Input URL: `rtsp://DEVICE_IP:8554/stream`
3. Mode: **Watch Only** or **Record**
4. Verify: Monitor shows color video

**Blue Iris:**
1. Add Camera → Make: **Generic/ONVIF**
2. Network IP: `DEVICE_IP`
3. Port: `8554`
4. Path: `/stream`
5. Verify: Camera feed is in color

**Home Assistant:**
```yaml
camera:
  - platform: ffmpeg
    name: IP_Cam
    input: rtsp://DEVICE_IP:8554/stream
```

Verify in Home Assistant dashboard.

## Success Criteria

✅ **All tests pass when:**

1. **Color Output:**
   - Video displays in full color (RGB colors visible)
   - Not monochrome, grey, or desaturated
   - Proper color saturation

2. **Codec Information:**
   - VLC shows "H264 - MPEG-4 AVC"
   - Decoded format shows YUV color space
   - Profile: Baseline
   - Level: 3.1 or higher

3. **Performance:**
   - Actual FPS ≥ 27 fps (90% of 30 fps target)
   - Smooth playback without stuttering
   - Frame drops < 10% of total

4. **Web UI:**
   - Color format clearly displayed
   - Performance metrics accurate
   - Frame rate calculation correct

5. **Compatibility:**
   - Works with VLC, FFmpeg, FFplay
   - Works with surveillance software
   - Both UDP and TCP transports work

## Troubleshooting Commands

**Check all RTSP metrics:**
```bash
curl -s http://DEVICE_IP:8080/rtspStatus | jq .
```

**Monitor real-time logs:**
```bash
adb logcat -s RTSPServer:* CameraService:*
```

**Test network connectivity:**
```bash
# Check RTSP port open
nmap -p 8554 DEVICE_IP

# Test stream with ffprobe
ffprobe -rtsp_transport tcp rtsp://DEVICE_IP:8554/stream
```

**Benchmark encoding performance:**
```bash
# Monitor while streaming
adb shell top -m 5 -s 1 | grep ipcam

# Check thermal state
adb shell dumpsys thermalservice | grep Status
```

## Reporting Issues

If tests fail, collect this information:

1. **Device Info:**
   - Android version
   - Device model
   - Encoder name (from web UI)
   - Color format reported

2. **Symptoms:**
   - Still monochrome? (screenshot)
   - Stuttering? (describe)
   - VLC codec info? (screenshot)

3. **Logs:**
   ```bash
   adb logcat -s RTSPServer:* > rtsp_logs.txt
   ```

4. **Network Test:**
   ```bash
   ffprobe -rtsp_transport tcp rtsp://DEVICE_IP:8554/stream 2>&1 > ffprobe_output.txt
   ```

5. **Web UI Status:**
   ```bash
   curl http://DEVICE_IP:8080/rtspStatus > rtsp_status.json
   ```

## Additional Resources

- **RTSP_FIXES.md**: Detailed technical explanation of fixes
- **RTSP_IMPLEMENTATION.md**: Complete RTSP architecture documentation
- **VLC Network Stream**: https://wiki.videolan.org/Documentation:Streaming_HowTo/
- **FFmpeg RTSP**: https://ffmpeg.org/ffmpeg-protocols.html#rtsp
- **H.264 Profiles**: https://en.wikipedia.org/wiki/Advanced_Video_Coding#Profiles

---

**Last Updated:** 2025-12-31  
**Version:** 1.0 (RTSP Stream Fixes)
