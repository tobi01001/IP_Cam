# MP4 Streaming Testing Guide

## Prerequisites

1. Android device or emulator with Android 7.0+ (API 24+)
2. Camera permission granted
3. Device connected to same network as test machine
4. VLC media player or browser with MP4 support

## Test Plan

### Test 1: Basic Mode Switching

**Objective**: Verify streaming mode can be changed via HTTP API

**Steps**:
1. Start the IP_Cam application
2. Note the device IP address (e.g., 192.168.1.100)
3. Check current mode:
   ```bash
   curl http://192.168.1.100:8080/streamingMode
   ```
   Expected: `{"status":"ok","streamingMode":"mjpeg"}`

4. Switch to MP4 mode:
   ```bash
   curl http://192.168.1.100:8080/setStreamingMode?value=mp4
   ```
   Expected: `{"status":"ok","message":"Streaming mode set to mp4...","streamingMode":"mp4"}`

5. Verify mode changed:
   ```bash
   curl http://192.168.1.100:8080/streamingMode
   ```
   Expected: `{"status":"ok","streamingMode":"mp4"}`

6. Check status endpoint includes mode:
   ```bash
   curl http://192.168.1.100:8080/status | jq '.streamingMode'
   ```
   Expected: `"mp4"`

**Success Criteria**:
- ✅ Mode switches from MJPEG to MP4
- ✅ Status endpoint reflects current mode
- ✅ No crashes or errors in logcat

### Test 2: MP4 Stream Playback in VLC

**Objective**: Verify MP4 stream can be played in VLC

**Steps**:
1. Set streaming mode to MP4 (see Test 1)
2. Open VLC media player
3. Go to Media > Open Network Stream
4. Enter URL: `http://192.168.1.100:8080/stream.mp4`
5. Click Play

**Success Criteria**:
- ✅ Video stream appears in VLC
- ✅ Video plays smoothly (30 fps target)
- ✅ Latency is 1-2 seconds (acceptable for MP4)
- ✅ No buffer underruns or freezing

**Troubleshooting**:
- Check logcat for encoder errors
- Verify device has H.264 hardware encoder support
- Try lower resolution if high resolution fails

### Test 3: MP4 Stream in Browser

**Objective**: Verify MP4 stream works in modern browsers

**Steps**:
1. Set streaming mode to MP4
2. Open Chrome or Firefox
3. Create HTML file with video tag:
   ```html
   <!DOCTYPE html>
   <html>
   <body>
     <h1>MP4 Stream Test</h1>
     <video src="http://192.168.1.100:8080/stream.mp4" 
            controls autoplay muted width="640" height="480">
     </video>
   </body>
   </html>
   ```
4. Open the HTML file in browser
5. Observe video playback

**Success Criteria**:
- ✅ Video loads and plays automatically
- ✅ Controls work (play/pause/volume)
- ✅ Acceptable latency (1-2 seconds)

**Note**: Browser playback may have higher latency than VLC due to buffering

### Test 4: Multiple Concurrent Clients

**Objective**: Verify multiple clients can stream simultaneously

**Steps**:
1. Set streaming mode to MP4
2. Open 3 VLC instances simultaneously
3. Connect all to: `http://192.168.1.100:8080/stream.mp4`
4. Monitor logcat for connection messages

**Success Criteria**:
- ✅ All 3 clients receive video stream
- ✅ No frame drops or quality degradation
- ✅ CPU usage remains reasonable (<50%)
- ✅ Logcat shows "Active streams: 3"

### Test 5: Mode Switch During Streaming

**Objective**: Verify clean transition between MJPEG and MP4

**Steps**:
1. Start in MJPEG mode
2. Open MJPEG stream: `http://192.168.1.100:8080/stream`
3. Switch to MP4 mode via API
4. Close MJPEG stream, open MP4 stream
5. Switch back to MJPEG
6. Verify MJPEG stream works again

**Success Criteria**:
- ✅ Camera rebinds on mode switch (check logcat)
- ✅ Old encoder stops before new one starts
- ✅ No resource leaks or crashes
- ✅ Both modes work after switching

### Test 6: Settings Persistence

**Objective**: Verify streaming mode persists across app restarts

**Steps**:
1. Set streaming mode to MP4
2. Force stop the app
3. Restart the app
4. Check streaming mode:
   ```bash
   curl http://192.168.1.100:8080/streamingMode
   ```

**Success Criteria**:
- ✅ Mode is still MP4 after restart
- ✅ Stream endpoint works immediately
- ✅ No need to reconfigure

### Test 7: Bandwidth Comparison

**Objective**: Compare bandwidth usage between MJPEG and MP4

**Steps**:
1. Set to MJPEG mode
2. Stream for 60 seconds
3. Note bandwidth from `/stats` endpoint
4. Set to MP4 mode
5. Stream for 60 seconds
6. Compare bandwidth usage

**Expected Results**:
- MP4 should use 30-50% less bandwidth than MJPEG
- MJPEG: ~2-4 Mbps for 1080p
- MP4: ~1-2 Mbps for 1080p

### Test 8: Error Handling

**Objective**: Verify graceful error handling

**Steps**:
1. Try to access MP4 stream while in MJPEG mode:
   ```bash
   curl http://192.168.1.100:8080/stream.mp4
   ```
   Expected: Error message explaining MP4 not active

2. Try invalid streaming mode:
   ```bash
   curl http://192.168.1.100:8080/setStreamingMode?value=invalid
   ```
   Expected: Error message with valid options

3. Simulate encoder failure (test on device without H.264 support)
   Expected: Fallback to MJPEG mode

**Success Criteria**:
- ✅ Clear error messages
- ✅ No crashes
- ✅ Graceful fallback when needed

### Test 9: Single Source of Truth

**Objective**: Verify only one encoder is active at a time

**Steps**:
1. Enable verbose logging in logcat
2. Switch to MP4 mode
3. Check logs for:
   - "Stopping MP4 encoder..." when switching away
   - "Initializing MP4 encoder..." when switching to MP4
   - No simultaneous MJPEG and MP4 processing

**Success Criteria**:
- ✅ Only one encoder active at any time
- ✅ Old encoder stops before new one starts
- ✅ Camera rebinds on mode switch

### Test 10: Resource Usage

**Objective**: Monitor CPU, memory, and battery usage

**Steps**:
1. Use Android Profiler or `adb shell top`
2. Stream MP4 for 5 minutes
3. Monitor:
   - CPU usage
   - Memory usage
   - Device temperature
   - Battery drain rate

**Expected Results**:
- CPU: 20-40% (hardware encoding)
- Memory: Stable, no leaks
- Temperature: Warm but not hot
- Battery: ~10-15% per hour

**Success Criteria**:
- ✅ Resource usage is reasonable
- ✅ No memory leaks
- ✅ No thermal throttling
- ✅ Hardware acceleration is working

## Logcat Filters

Useful logcat filters for debugging:

```bash
# General MP4 logs
adb logcat -s Mp4StreamWriter:D CameraService:D HttpServer:D

# Encoder-specific logs
adb logcat -s Mp4StreamWriter:D MediaCodec:D

# Network and streaming
adb logcat -s HttpServer:D

# Complete MP4 implementation logs
adb logcat | grep -E "Mp4|MP4|streaming|encoder|codec"
```

## Known Limitations

1. **Higher Latency**: MP4 has 1-2 seconds latency vs 100ms for MJPEG
2. **Hardware Requirement**: Requires H.264 hardware encoder
3. **Browser Compatibility**: Some older browsers may not support fMP4
4. **No Audio**: Current implementation is video-only

## Troubleshooting

### MP4 Stream Won't Play

1. Check logcat for encoder initialization errors
2. Verify device has H.264 hardware encoder:
   ```bash
   adb shell dumpsys media.player | grep codec
   ```
3. Try lower resolution if 1080p fails
4. Test with VLC first (better codec support than browsers)

### High Latency

1. MP4 inherently has higher latency than MJPEG
2. Reduce I-frame interval (edit Mp4StreamWriter.kt)
3. Consider using MJPEG for low-latency needs

### Encoder Crashes

1. Check device has sufficient memory
2. Verify no other apps are using camera
3. Try lower bitrate/resolution
4. Check for proper lifecycle management in logs

### Stream Stops After Some Time

1. Check device battery optimization settings
2. Grant battery exemption to IP_Cam app
3. Verify wake locks are held
4. Check for thermal throttling

## Next Steps

After validating basic functionality:

1. Test with NVR systems (ZoneMinder, Shinobi, Blue Iris)
2. Measure exact bandwidth savings
3. Test on various Android devices and versions
4. Optimize encoder settings for quality vs bandwidth
5. Consider adding UI controls for mode selection
6. Add audio track support (future enhancement)

## Reporting Issues

When reporting issues, include:
- Device model and Android version
- Streaming mode (MJPEG/MP4)
- Resolution and bitrate
- Logcat output (filtered for MP4 tags)
- CPU and memory usage
- Steps to reproduce
