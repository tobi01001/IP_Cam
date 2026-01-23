# IP_Cam - Testing Documentation

## Table of Contents

1. [Overview](#overview)
2. [Testing Strategy](#testing-strategy)
3. [Manual Testing Procedures](#manual-testing-procedures)
4. [Automated Testing](#automated-testing)
5. [Performance Testing](#performance-testing)
6. [Compatibility Testing](#compatibility-testing)
7. [Troubleshooting Guide](#troubleshooting-guide)

---

## Overview

This document provides comprehensive testing procedures and guidelines for the IP_Cam application. It covers manual testing, automated scripts, performance validation, and compatibility verification.

**Testing Principles:**
1. Test core functionality after every significant change
2. Validate performance metrics (FPS, latency, bandwidth)
3. Ensure compatibility with surveillance systems
4. Verify reliability (24/7 operation, recovery from failures)

---

## Testing Strategy

### Test Pyramid

```
        /\
       /UI\
      /----\
     / API  \
    /--------\
   /  System  \
  /------------\
 /   Component  \
/________________\
```

**Component Tests:** Camera, encoding, streaming (minimal)  
**System Tests:** Service lifecycle, watchdog, persistence  
**API Tests:** HTTP endpoints, RTSP protocol  
**UI Tests:** Manual verification of Android UI  

### Test Environments

1. **Development Device:** Samsung Galaxy S10+ (primary)
2. **Network:** Local WiFi (192.168.x.x)
3. **Clients:** VLC, web browser, surveillance systems
4. **OS:** Android 11+ (API 30+)

---

## Manual Testing Procedures

### Basic Functionality Tests

#### Test 1: Server Start/Stop

**Procedure:**
1. Open IP_Cam app
2. Tap "Start Server" button
3. Verify notification appears with server URL
4. Open browser to displayed URL
5. Verify web interface loads with live stream
6. Tap "Stop Server" button
7. Verify notification disappears
8. Verify browser connection closes

**Expected Results:**
- ✅ Server starts within 2 seconds
- ✅ URL displayed correctly (e.g., http://192.168.1.100:8080)
- ✅ Web interface loads and displays stream
- ✅ Server stops cleanly
- ✅ No crash or error messages

#### Test 2: Camera Switching

**Procedure:**
1. Start server
2. Open web interface
3. Click "Switch Camera" button
4. Observe stream switches from back to front (or vice versa)
5. Verify app preview also switches
6. Click button again to switch back

**Expected Results:**
- ✅ Camera switches within 1 second
- ✅ Stream continues without interruption
- ✅ Both web interface and app preview synchronized
- ✅ No black frames or crashes

#### Test 3: Flashlight Control

**Procedure:**
1. Ensure back camera is active
2. Click "Toggle Flashlight" in web UI
3. Verify flashlight turns on
4. Click again to turn off
5. Switch to front camera
6. Verify flashlight button is disabled or shows error

**Expected Results:**
- ✅ Flashlight toggles on/off for back camera
- ✅ Visual confirmation (flashlight LED on device)
- ✅ Graceful handling for front camera (no flashlight)
- ✅ State persists across camera switches (back → front → back)

#### Test 4: Snapshot Capture

**Procedure:**
1. Start server
2. Open terminal: `curl http://<device-ip>:8080/snapshot -o test.jpg`
3. Verify test.jpg created
4. Open test.jpg and verify it shows current camera view
5. Repeat 5-10 times rapidly

**Expected Results:**
- ✅ JPEG file created successfully
- ✅ Image matches current camera view
- ✅ Reasonable file size (50-200 KB depending on resolution)
- ✅ Multiple rapid requests handled without errors

#### Test 5: Resolution Changes

**Procedure:**
1. Start server and open web interface
2. Note current resolution from `/status` endpoint
3. Select different resolution from dropdown
4. Click "Apply"
5. Verify stream resolution changes
6. Check `/status` again to confirm new resolution

**Expected Results:**
- ✅ Resolution changes within 2 seconds
- ✅ Stream continues without long interruption
- ✅ New resolution applied correctly
- ✅ Status endpoint reflects new setting

### Streaming Tests

#### Test 6: MJPEG Stream Stability

**Procedure:**
1. Start server
2. Open 3 browser windows to `http://<device-ip>:8080/`
3. Let streams run for 30 minutes
4. Monitor for interruptions, freezes, or quality degradation
5. Check `/status` endpoint for connection count

**Expected Results:**
- ✅ All 3 streams run continuously
- ✅ No freezing or black frames
- ✅ Frame rate stable (~10 fps)
- ✅ Connection count shows 3 active streams
- ✅ No memory leaks (check via Android Studio Profiler)

#### Test 7: RTSP Stream Playback

**Procedure:**
1. Start server
2. Open VLC: `vlc rtsp://<device-ip>:8554/camera`
3. Verify stream plays smoothly
4. Check latency (hold watch in front of camera, compare VLC to app preview)
5. Monitor VLC stats (Tools → Codec Information → Statistics)
6. Let run for 15 minutes

**Expected Results:**
- ✅ Stream starts within 2-3 seconds
- ✅ Smooth playback at 30 fps
- ✅ Latency: 500ms-1s
- ✅ Bandwidth: 2-4 Mbps
- ✅ No buffering or stuttering

#### Test 8: Concurrent Connections

**Automated Script:** `test_concurrent_connections.sh`

```bash
#!/bin/bash
DEVICE_IP="192.168.1.100"

# Test increasing load
for i in {1..10}; do
    curl "http://${DEVICE_IP}:8080/stream" > /dev/null &
    sleep 1
done

# Check status
curl "http://${DEVICE_IP}:8080/status"

# Cleanup
pkill -f "curl.*stream"
```

**Expected Results:**
- ✅ Server accepts 10+ concurrent MJPEG streams
- ✅ Status endpoint reports correct connection count
- ✅ No degradation in frame rate or quality
- ✅ CPU usage remains reasonable (<60%)

### Reliability Tests

#### Test 9: Service Persistence

**Procedure:**
1. Start server
2. Press Home button (app goes to background)
3. Wait 5 minutes
4. Verify stream still accessible from browser
5. Open Recent Apps and swipe away IP_Cam
6. Wait 1 minute
7. Verify stream still accessible

**Expected Results:**
- ✅ Foreground notification persists
- ✅ Service continues running in background
- ✅ Stream remains accessible after app dismissed
- ✅ No interruption in service

#### Test 10: Network Change Recovery

**Procedure:**
1. Start server, note IP address (e.g., 192.168.1.100)
2. Open browser to stream
3. Turn OFF WiFi on Android device
4. Wait 5 seconds
5. Turn ON WiFi
6. Wait for device to reconnect
7. Check new IP address (may have changed)
8. Verify stream accessible at new IP

**Expected Results:**
- ✅ Service detects network loss
- ✅ Server restarts after network reconnection
- ✅ New IP address displayed in notification
- ✅ Stream accessible within 10 seconds of reconnection

#### Test 11: Watchdog Recovery

**Procedure:**
1. Start server
2. Use ADB to simulate failure: `adb shell am crash com.example.ipcam`
3. Wait 5-10 seconds
4. Check if app/service restarts automatically
5. Verify stream becomes accessible again

**Expected Results:**
- ✅ Service restarts automatically (START_STICKY)
- ✅ Exponential backoff applied (check logs)
- ✅ Stream available within 30 seconds
- ✅ Settings preserved (camera, resolution, etc.)

#### Test 12: Settings Persistence

**Procedure:**
1. Start server
2. Switch to front camera
3. Change resolution
4. Set rotation to 90°
5. Enable flashlight (if back camera)
6. Force stop app: `adb shell am force-stop com.example.ipcam`
7. Reopen app
8. Start server again

**Expected Results:**
- ✅ All settings preserved:
  - Camera selection (front)
  - Resolution
  - Rotation (90°)
  - Flashlight state
- ✅ Stream resumes with saved configuration

### Performance Tests

#### Test 13: Frame Rate Validation

**Procedure:**
1. Start server with MJPEG stream
2. Use VLC to view stream
3. Check VLC statistics (Tools → Codec Information → Statistics)
4. Note "Displayed frames" and "Lost frames"
5. Calculate actual FPS over 60 seconds
6. Repeat for RTSP stream

**Expected Results:**
- ✅ MJPEG: ~10 fps (9-11 fps acceptable)
- ✅ RTSP: ~30 fps (28-32 fps acceptable)
- ✅ Lost frames: <5% of displayed frames
- ✅ Consistent frame rate (no wild fluctuations)

#### Test 14: Latency Measurement

**Procedure:**
1. Start server
2. Display device with visible seconds counter on camera view
3. Open VLC/browser showing stream
4. Record video of both device screen and computer screen side-by-side
5. Analyze video frame-by-frame to measure delay

**Expected Results:**
- ✅ MJPEG latency: 150-280ms
- ✅ RTSP latency: 500ms-1s
- ✅ Consistent latency (no jumps)

#### Test 15: Bandwidth Measurement

**Procedure:**
1. Start server
2. Use `iftop` or Wireshark to monitor network traffic
3. Open single MJPEG stream
4. Record average bandwidth over 60 seconds
5. Repeat for RTSP stream

**Expected Results:**
- ✅ MJPEG: ~8 Mbps (6-10 Mbps acceptable)
- ✅ RTSP: 2-4 Mbps
- ✅ RTSP bandwidth 50-75% less than MJPEG
- ✅ Stable bandwidth (no spikes)

#### Test 16: CPU & Memory Usage

**Procedure:**
1. Start server with 1 MJPEG stream
2. Open Android Studio Profiler
3. Monitor CPU, Memory, and Network for 5 minutes
4. Add 2nd and 3rd stream
5. Monitor for another 5 minutes

**Expected Results:**
- ✅ CPU: <30% for single stream, <60% for 3 streams
- ✅ Memory: Stable (no continuous growth)
- ✅ No memory leaks (heap size stabilizes)
- ✅ GC pauses: Minimal and brief

---

## Automated Testing

### Script 1: Concurrent Connection Test

**File:** `test_concurrent_connections.sh`

```bash
#!/bin/bash

DEVICE_IP="192.168.1.100"
PORT="8080"
MAX_CONNECTIONS=15

echo "Testing concurrent connections to IP Camera..."

# Start multiple stream connections
for i in $(seq 1 $MAX_CONNECTIONS); do
    echo "Starting connection $i..."
    curl -s "http://${DEVICE_IP}:${PORT}/stream" > /dev/null &
    sleep 0.5
done

# Wait a bit for connections to establish
sleep 5

# Check status
echo -e "\nChecking server status..."
curl -s "http://${DEVICE_IP}:${PORT}/status" | jq .

# Monitor for 30 seconds
echo -e "\nMonitoring for 30 seconds..."
sleep 30

# Check status again
echo -e "\nFinal status check..."
curl -s "http://${DEVICE_IP}:${PORT}/status" | jq .

# Cleanup
echo -e "\nCleaning up connections..."
pkill -f "curl.*${DEVICE_IP}:${PORT}/stream"

echo "Test complete!"
```

**Usage:**
```bash
chmod +x test_concurrent_connections.sh
./test_concurrent_connections.sh
```

### Script 2: RTSP Reliability Test

**File:** `test_rtsp_reliability.sh`

```bash
#!/bin/bash

DEVICE_IP="192.168.1.100"
RTSP_PORT="8554"
DURATION=300  # 5 minutes

echo "Testing RTSP stream reliability..."

# Start recording with ffmpeg
ffmpeg -rtsp_transport tcp \
       -i "rtsp://${DEVICE_IP}:${RTSP_PORT}/camera" \
       -t $DURATION \
       -c copy \
       -f null - \
       2>&1 | tee rtsp_test.log

# Analyze log for errors
echo -e "\nAnalyzing results..."
FRAMES=$(grep "frame=" rtsp_test.log | tail -1 | awk '{print $2}')
ERRORS=$(grep -i "error\|failed" rtsp_test.log | wc -l)

echo "Frames received: $FRAMES"
echo "Errors encountered: $ERRORS"

if [ $ERRORS -eq 0 ]; then
    echo "✅ RTSP stream stable!"
else
    echo "❌ Issues detected. Check rtsp_test.log"
fi
```

**Usage:**
```bash
chmod +x test_rtsp_reliability.sh
./test_rtsp_reliability.sh
```

### Script 3: API Endpoint Test

**File:** `test_api_endpoints.sh`

```bash
#!/bin/bash

DEVICE_IP="192.168.1.100"
PORT="8080"
BASE_URL="http://${DEVICE_IP}:${PORT}"

echo "Testing API endpoints..."

# Test status endpoint
echo -e "\n1. Testing /status..."
curl -s "${BASE_URL}/status" | jq .

# Test snapshot
echo -e "\n2. Testing /snapshot..."
curl -s "${BASE_URL}/snapshot" -o test_snapshot.jpg
if [ -f test_snapshot.jpg ]; then
    SIZE=$(wc -c < test_snapshot.jpg)
    echo "✅ Snapshot saved (${SIZE} bytes)"
    rm test_snapshot.jpg
else
    echo "❌ Snapshot failed"
fi

# Test camera switch
echo -e "\n3. Testing /switch..."
curl -s "${BASE_URL}/switch" | jq .

# Test flashlight toggle
echo -e "\n4. Testing /toggleFlashlight..."
curl -s "${BASE_URL}/toggleFlashlight" | jq .

# Test rotation
echo -e "\n5. Testing /setRotation..."
curl -s "${BASE_URL}/setRotation?value=90" | jq .

# Test version
echo -e "\n6. Testing /version..."
curl -s "${BASE_URL}/version" | jq .

echo -e "\nAPI tests complete!"
```

**Usage:**
```bash
chmod +x test_api_endpoints.sh
./test_api_endpoints.sh
```

---

## Performance Testing

### Benchmark 1: Frame Processing Latency

**Objective:** Measure time from camera capture to network transmission

**Procedure:**
1. Add timing logs in CameraService
2. Log timestamp at camera capture
3. Log timestamp at JPEG encoding complete
4. Log timestamp at network send
5. Calculate deltas
6. Run for 1000 frames and calculate average

**Expected Results:**
- Capture → Encode: <30ms
- Encode → Send: <10ms
- Total pipeline: <50ms

### Benchmark 2: Encoding Performance

**Objective:** Compare JPEG vs H.264 encoding speed and quality

**Procedure:**
1. Capture same frame from camera
2. Encode to JPEG (80% quality)
3. Encode to H.264 (hardware accelerated)
4. Measure time and output size for each
5. Repeat 100 times

**Expected Results:**
- JPEG: ~10-20ms per frame, 50-150 KB
- H.264: ~5-10ms per frame, 10-30 KB (I-frame)
- H.264 I-frame: Similar to JPEG
- H.264 P-frame: 2-5 KB (90% smaller)

### Benchmark 3: Threading Efficiency

**Objective:** Validate thread pool sizing is optimal

**Procedure:**
1. Monitor thread pool usage under load
2. Start with 10 concurrent streams
3. Gradually increase to 30 streams
4. Monitor active threads, queue size, rejections
5. Measure request latency (non-streaming endpoints)

**Expected Results:**
- No rejected connections up to 32 streams
- Non-streaming endpoints respond in <100ms
- Thread pool not exhausted
- CPU usage scales linearly with stream count

---

## Compatibility Testing

### Surveillance Systems

#### ZoneMinder

**Setup:**
1. Add new monitor in ZoneMinder
2. Source Type: Remote
3. Protocol: HTTP
4. Method: MJPEG
5. Host: `<device-ip>:8080`
6. Path: `/stream`

**Expected Results:**
- ✅ Stream appears in ZoneMinder interface
- ✅ Recording works
- ✅ Motion detection functional

#### Shinobi

**Setup:**
1. Add new monitor
2. Type: MJPEG
3. URL: `http://<device-ip>:8080/stream`

**Expected Results:**
- ✅ Live view functional
- ✅ Recording to disk works
- ✅ Alerts trigger correctly

#### Blue Iris

**Setup:**
1. Add new camera
2. Type: HTTP
3. Path: `/stream`
4. Host: `<device-ip>:8080`

**Expected Results:**
- ✅ Camera shows in camera list
- ✅ Recording schedules work
- ✅ Motion zones configurable

### Media Players

#### VLC

**MJPEG:**
```bash
vlc http://<device-ip>:8080/stream
```

**RTSP:**
```bash
vlc rtsp://<device-ip>:8554/camera
```

**Expected Results:**
- ✅ Both streams play without errors
- ✅ Controls (pause/resume) work
- ✅ Fullscreen mode functional

#### FFmpeg

**MJPEG:**
```bash
ffplay http://<device-ip>:8080/stream
```

**RTSP (TCP):**
```bash
ffplay -rtsp_transport tcp rtsp://<device-ip>:8554/camera
```

**RTSP (UDP):**
```bash
ffplay -rtsp_transport udp rtsp://<device-ip>:8554/camera
```

**Expected Results:**
- ✅ All streams play correctly
- ✅ TCP transport works reliably
- ✅ UDP transport works on local network

### Web Browsers

**Browsers to Test:**
- Chrome (Android & Desktop)
- Firefox (Android & Desktop)
- Safari (iOS & macOS)
- Edge (Desktop)

**Test Procedure:**
1. Open `http://<device-ip>:8080/` in each browser
2. Verify stream displays
3. Test control buttons
4. Verify SSE updates work
5. Test responsive layout on mobile

**Expected Results:**
- ✅ Stream visible in all browsers
- ✅ Controls functional
- ✅ Layout responsive
- ✅ Auto-reconnect works

---

## Troubleshooting Guide

### Issue: Stream Not Loading

**Symptoms:** Browser shows loading but no video

**Diagnosis:**
1. Check if server is running: `curl http://<device-ip>:8080/status`
2. Verify camera permission granted
3. Check Android logs: `adb logcat | grep IPCam`

**Solutions:**
- Ensure server started successfully
- Grant camera permission in Android settings
- Restart server
- Check firewall rules

### Issue: High Latency

**Symptoms:** 2+ second delay between camera and stream

**Diagnosis:**
1. Check network bandwidth: `iftop`
2. Monitor CPU usage on device
3. Verify no packet loss: `ping <device-ip>`

**Solutions:**
- Use wired connection or 5GHz WiFi
- Reduce resolution or frame rate
- Close other apps on device
- Switch from MJPEG to RTSP for lower bandwidth

### Issue: Connection Refused

**Symptoms:** "Connection refused" or "Cannot connect"

**Diagnosis:**
1. Verify device IP: Check in app or `adb shell ip addr`
2. Check port: Default is 8080
3. Verify same network: `ping <device-ip>` from computer

**Solutions:**
- Ensure device and computer on same WiFi network
- Check IP address hasn't changed (DHCP)
- Restart server
- Check firewall on computer

### Issue: Service Stops Unexpectedly

**Symptoms:** Stream works then stops after minutes/hours

**Diagnosis:**
1. Check battery optimization: Settings → Apps → IP_Cam → Battery
2. Review Android logs for crashes
3. Monitor watchdog behavior

**Solutions:**
- Disable battery optimization for IP_Cam
- Keep device plugged in
- Check for thermal throttling (device overheating)
- Verify enough storage space

### Issue: Poor Frame Rate

**Symptoms:** Choppy video, FPS below expected

**Diagnosis:**
1. Check CPU usage: Android Studio Profiler
2. Monitor memory: Look for memory leaks
3. Verify network bandwidth adequate

**Solutions:**
- Close other apps
- Reduce resolution
- Lower JPEG quality
- Use RTSP instead of MJPEG
- Check for thermal throttling

---

## Related Documentation

- **[Implementation](IMPLEMENTATION.md)** - Current implementation details
- **[Requirements](REQUIREMENTS.md)** - Requirements with status
- **[Analysis](ANALYSIS.md)** - Architectural concepts and proposals

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-23
