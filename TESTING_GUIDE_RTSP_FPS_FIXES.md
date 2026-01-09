# Testing Guide: RTSP Stream and FPS Fixes

## Overview
This guide provides comprehensive testing procedures for the RTSP stream fixes, FPS application corrections, and camera activation improvements implemented in this PR.

## Issues Fixed

### 1. RTSP Stream Not Working
- **Problem**: RTSP server started but H.264 encoder was never created
- **Fix**: Enabling/disabling RTSP now triggers camera rebind to create/remove encoder
- **Fix**: RTSP auto-starts on service init if enabled in settings

### 2. Target FPS Not Applied Correctly
- **MJPEG Problem**: No frame throttling, all frames processed regardless of target FPS
- **MJPEG Fix**: Added explicit frame skipping based on `targetMjpegFps`
- **RTSP Problem**: FPS changes didn't recreate encoder
- **RTSP Fix**: FPS changes trigger camera rebind to recreate encoder with new FPS

### 3. Camera Not Auto-Starting
- **Problem**: If permission not granted when service starts, camera never initializes
- **Fix**: Watchdog monitors permission state and starts camera when granted
- **Fix**: Enhanced `onStartCommand()` to handle late permission grants

## Testing Procedures

### Prerequisites
- Android device with camera (API 24+)
- VLC or ffplay installed on testing computer
- Both devices on same WiFi network
- IP_Cam app installed on Android device

### Test 1: RTSP Stream Initialization

**Steps:**
1. Install and launch IP_Cam app
2. Grant camera permission when prompted
3. Wait for camera preview to appear
4. Enable RTSP via HTTP API:
   ```bash
   curl http://DEVICE_IP:8080/rtsp/enable
   ```
5. Check response JSON for `"enabled": true`
6. On computer, open VLC or ffplay:
   ```bash
   vlc rtsp://DEVICE_IP:8554/stream
   # OR
   ffplay rtsp://DEVICE_IP:8554/stream
   ```

**Expected Result:**
- RTSP stream should display video with color (not monochrome)
- Video should be smooth without excessive stuttering
- Check logs for: `"H.264 encoder created and connected"`

**Verification:**
- VLC should show codec info: H.264, resolution, bitrate
- Stream should maintain stable playback for 30+ seconds

### Test 2: RTSP State Persistence

**Steps:**
1. Enable RTSP as in Test 1
2. Force-stop the app (Settings → Apps → IP_Cam → Force Stop)
3. Wait 5 seconds
4. Reopen the app
5. Try connecting to RTSP stream immediately

**Expected Result:**
- RTSP server should auto-start on app launch
- Stream should be available within 2-3 seconds
- Check logs for: `"RTSP was enabled in settings, starting RTSP server..."`

### Test 3: RTSP FPS Changes

**Steps:**
1. Enable RTSP and connect VLC
2. Note current FPS in stream (default 30 fps)
3. Change RTSP FPS via API:
   ```bash
   curl "http://DEVICE_IP:8080/rtsp/fps?value=15"
   ```
4. Observe stream in VLC
5. Change again to 60 fps:
   ```bash
   curl "http://DEVICE_IP:8080/rtsp/fps?value=60"
   ```

**Expected Result:**
- Stream should restart (brief interruption)
- New FPS should take effect
- Check logs for: `"RTSP FPS changed from X to Y, rebinding camera"`
- VLC info should show new FPS

**Verification:**
- Use VLC: Tools → Codec Information → Statistics tab
- Check "Displayed frames" counter over time to verify FPS

### Test 4: MJPEG FPS Throttling

**Steps:**
1. Start HTTP server (if not already running)
2. Set MJPEG FPS to 5:
   ```bash
   curl "http://DEVICE_IP:8080/settings/mjpeg_fps?value=5"
   ```
3. Monitor MJPEG stream in browser or curl:
   ```bash
   curl http://DEVICE_IP:8080/stream
   ```
4. Check device logs:
   ```bash
   adb logcat -s CameraService:D | grep "MJPEG\|fps"
   ```
5. Change to 30 fps and observe again:
   ```bash
   curl "http://DEVICE_IP:8080/settings/mjpeg_fps?value=30"
   ```

**Expected Result:**
- At 5 fps: ~200ms between frames
- At 30 fps: ~33ms between frames
- Check logs for FPS calculations
- No resource leaks (check memory usage over time)

**Verification:**
- Calculate actual FPS: count frames received over 10 seconds, divide by 10
- Should be close to target FPS (±10%)

### Test 5: Camera Auto-Start After Permission Grant

**Steps:**
1. Install fresh app (or clear data: Settings → Apps → IP_Cam → Clear Data)
2. Launch app but DON'T grant camera permission yet
3. Check logs:
   ```bash
   adb logcat -s CameraService:* | grep "Camera\|permission"
   ```
4. Wait 10 seconds
5. Grant camera permission through notification or app
6. Observe logs and app preview

**Expected Result:**
- Before permission: Logs show "waiting for permission"
- After permission: Within 5-10 seconds, logs show "Camera provider initialized"
- Preview appears automatically without manual intervention
- Check logs for watchdog detecting permission and starting camera

### Test 6: Camera Switch with RTSP Enabled

**Steps:**
1. Enable RTSP and connect VLC
2. Switch camera via app button or API:
   ```bash
   curl http://DEVICE_IP:8080/switch
   ```
3. Observe both stream and logs

**Expected Result:**
- Stream restarts (brief interruption)
- New camera appears in stream
- No crashes or errors
- Check logs for: `"Rebinding camera..."` and `"H.264 encoder created"`

### Test 7: Simultaneous MJPEG and RTSP

**Steps:**
1. Start HTTP server
2. Enable RTSP
3. Open MJPEG stream in browser: `http://DEVICE_IP:8080/stream`
4. Open RTSP stream in VLC: `rtsp://DEVICE_IP:8554/stream`
5. Monitor both streams for 60 seconds

**Expected Result:**
- Both streams work simultaneously
- Different FPS targets respected (MJPEG: 10 fps, RTSP: 30 fps)
- No performance degradation
- Check logs for separate FPS tracking: `currentMjpegFps` and `currentRtspFps`

### Test 8: RTSP Disable/Re-enable

**Steps:**
1. Enable RTSP and connect VLC
2. Disable RTSP:
   ```bash
   curl http://DEVICE_IP:8080/rtsp/disable
   ```
3. Verify stream stops in VLC
4. Re-enable RTSP:
   ```bash
   curl http://DEVICE_IP:8080/rtsp/enable
   ```
5. Reconnect VLC

**Expected Result:**
- Disable: Stream stops, encoder destroyed
- Enable: Stream restarts, new encoder created
- Check logs for: `"Rebinding camera to remove/create H.264 encoder pipeline"`

## Key Log Messages to Monitor

### RTSP Initialization
```
RTSP streaming enabled on port 8554 (fps=30, bitrate=5000000, mode=VBR)
Rebinding camera to create H.264 encoder pipeline
H.264 encoder created and connected
```

### RTSP Auto-Start
```
RTSP was enabled in settings, starting RTSP server...
RTSP streaming enabled on port 8554
```

### FPS Changes
```
RTSP FPS changed from 30 to 15, rebinding camera to apply change
Target MJPEG FPS set to 10 (throttling applied in frame processing)
```

### Camera Activation
```
startCamera() - initializing camera provider...
Camera provider initialized successfully
Camera bound successfully with 2 use case(s):
  1. ImageAnalysis (MJPEG + MainActivity preview): ~10 fps target
  2. Preview → H.264 Encoder (RTSP): 30 fps target
```

### Watchdog Recovery
```
Watchdog: Camera provider not initialized but permission granted, starting camera...
Watchdog: Camera provider exists but camera not bound, binding camera...
```

## Common Issues and Solutions

### Issue: RTSP stream shows "no data" or doesn't connect
**Solution**: 
- Check if RTSP is enabled: `curl http://DEVICE_IP:8080/rtsp/status`
- Verify port 8554 is not blocked by firewall
- Check logs for encoder errors

### Issue: Stream is monochrome/grey
**Solution**: 
- This should be fixed in current implementation
- Check logs for color format: should see "NV12" or "I420"
- If still grey, report encoder model and Android version

### Issue: FPS changes don't take effect
**Solution**:
- Verify camera rebind occurs (check logs)
- For RTSP: Stream should restart (brief interruption)
- For MJPEG: Should see immediate throttling in logs

### Issue: Camera doesn't start on app launch
**Solution**:
- Check camera permission granted
- Check logs for watchdog messages
- Wait 10-15 seconds for watchdog to detect and recover
- Force-stop and restart app

## Performance Metrics

### Expected Values
- **MJPEG CPU usage**: 15-30% (depending on resolution and FPS)
- **RTSP CPU usage**: 10-20% (hardware encoding)
- **Combined CPU usage**: 25-45%
- **Memory usage**: 100-200 MB
- **Battery drain**: ~5-10% per hour (screen off, continuous streaming)

### Monitoring Commands
```bash
# Monitor logcat
adb logcat -s CameraService:D RTSPServer:D H264PreviewEncoder:D

# Check CPU usage
adb shell top -n 1 | grep com.ipcam

# Check memory
adb shell dumpsys meminfo com.ipcam

# Check battery
adb shell dumpsys battery
```

## Success Criteria

All tests must pass with the following criteria:
- ✅ RTSP stream displays color video (not monochrome)
- ✅ RTSP persists across app restarts
- ✅ RTSP FPS changes take effect within 2 seconds
- ✅ MJPEG FPS throttling maintains target FPS (±10%)
- ✅ Camera auto-starts after permission grant (within 10 seconds)
- ✅ Camera switch works with RTSP enabled
- ✅ Both MJPEG and RTSP work simultaneously
- ✅ RTSP disable/re-enable works without issues
- ✅ No resource leaks over 5+ minutes of continuous streaming
- ✅ CPU usage stays below 50% combined
- ✅ No crashes or ANRs

## Reporting Issues

When reporting issues, please include:
1. Android version and device model
2. Full logcat output (`adb logcat -s CameraService:* RTSPServer:* H264PreviewEncoder:*`)
3. Steps to reproduce
4. Expected vs actual behavior
5. Screenshots of VLC codec info (if RTSP related)
6. Network configuration (WiFi, mobile hotspot, etc.)

## Additional Testing Tools

### Test Multiple Clients
```bash
# Terminal 1
ffplay rtsp://DEVICE_IP:8554/stream

# Terminal 2
ffplay rtsp://DEVICE_IP:8554/stream

# Terminal 3
curl http://DEVICE_IP:8080/stream > /dev/null
```

### Stress Test
```bash
# Run for 30 minutes and monitor stability
while true; do
  curl -s http://DEVICE_IP:8080/status | jq '.currentMjpegFps, .currentRtspFps'
  sleep 5
done
```

### Network Quality Test
```bash
# Monitor network bandwidth
iftop -i wlan0 -f "host DEVICE_IP"

# Or use vnstat
vnstat -l -i wlan0
```

## Notes

- All fixes preserve backward compatibility
- Settings persist in SharedPreferences
- RTSP uses standard protocol (RFC 2326)
- H.264 uses Baseline profile for maximum compatibility
- Both pipelines run in parallel without interference
