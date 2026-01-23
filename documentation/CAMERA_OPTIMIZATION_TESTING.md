# Camera Optimization Testing Guide

This document provides comprehensive testing instructions for the on-demand camera activation feature.

## Overview

The camera now operates in an **on-demand** mode:
- **Default State**: Camera is IDLE (not running) when no consumers need it
- **Activation**: Camera starts when first consumer connects
- **Deactivation**: Camera stops when last consumer disconnects
- **Consumers**: Preview (MainActivity), MJPEG streams, RTSP streaming

## Camera States

The camera can be in one of five states:

| State | Description |
|-------|-------------|
| `IDLE` | Camera not initialized, no consumers |
| `INITIALIZING` | Camera binding in progress |
| `ACTIVE` | Camera bound and providing frames |
| `STOPPING` | Camera unbinding in progress |
| `ERROR` | Camera failed to initialize |

## Testing Scenarios

### 1. Initial Startup Test

**Expected Behavior**: Camera stays IDLE on startup

```bash
# 1. Install and launch the app
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ipcam/.MainActivity

# 2. Wait 5 seconds, then check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"IDLE","mjpegStreams":0,"message":"Camera state retrieved"}
```

**Pass Criteria**: 
- ✅ Camera state is IDLE
- ✅ No error notifications
- ✅ App starts quickly (< 2 seconds)

### 2. Preview Activation Test

**Expected Behavior**: Camera activates when preview is expanded

```bash
# 1. Launch app (camera should be IDLE)
# 2. Tap "Camera Preview" section header to expand
# 3. Wait 2-3 seconds for camera to initialize
# 4. Check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"ACTIVE","mjpegStreams":0,"message":"Camera state retrieved"}
```

**Pass Criteria**:
- ✅ Camera state transitions: IDLE → INITIALIZING → ACTIVE
- ✅ Preview shows live camera feed
- ✅ Logs show: "Preview expanded, registering preview consumer..."

### 3. Preview Deactivation Test

**Expected Behavior**: Camera deactivates when preview is collapsed (if no other consumers)

```bash
# 1. With preview expanded and active
# 2. Tap "Camera Preview" section header to collapse
# 3. Wait 2 seconds
# 4. Check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"IDLE","mjpegStreams":0,"message":"Camera state retrieved"}
```

**Pass Criteria**:
- ✅ Camera state transitions: ACTIVE → STOPPING → IDLE
- ✅ Preview stops showing feed
- ✅ Logs show: "Preview collapsed, unregistering preview consumer..."
- ✅ Logs show: "Last consumer unregistered, deactivating camera..."

### 4. MJPEG Stream Activation Test

**Expected Behavior**: Camera activates when MJPEG client connects

```bash
# 1. Ensure preview is collapsed (camera IDLE)
curl http://DEVICE_IP:8080/cameraState

# 2. Start MJPEG stream
curl http://DEVICE_IP:8080/stream > /tmp/stream.mjpeg &
STREAM_PID=$!

# 3. Wait 3 seconds, then check camera state
sleep 3
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"ACTIVE","mjpegStreams":1,"message":"Camera state retrieved"}

# 4. Stop stream
kill $STREAM_PID
```

**Pass Criteria**:
- ✅ Camera activates when stream connects
- ✅ mjpegStreams count is 1
- ✅ Logs show: "First MJPEG stream connecting, registering consumer..."
- ✅ Stream delivers frames

### 5. MJPEG Stream Deactivation Test

**Expected Behavior**: Camera deactivates when last MJPEG client disconnects

```bash
# 1. Start and then stop MJPEG stream (as in test 4)
curl http://DEVICE_IP:8080/stream > /tmp/stream.mjpeg &
STREAM_PID=$!
sleep 3
kill $STREAM_PID

# 2. Wait 2 seconds for cleanup
sleep 2

# 3. Check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"IDLE","mjpegStreams":0,"message":"Camera state retrieved"}
```

**Pass Criteria**:
- ✅ Camera deactivates after stream disconnects
- ✅ mjpegStreams count returns to 0
- ✅ Logs show: "Last MJPEG stream disconnected, unregistering consumer..."

### 6. Multiple MJPEG Streams Test

**Expected Behavior**: Camera stays active while any stream is connected

```bash
# 1. Start 3 concurrent streams
curl http://DEVICE_IP:8080/stream > /tmp/stream1.mjpeg &
PID1=$!
curl http://DEVICE_IP:8080/stream > /tmp/stream2.mjpeg &
PID2=$!
curl http://DEVICE_IP:8080/stream > /tmp/stream3.mjpeg &
PID3=$!

# 2. Check state
sleep 3
curl http://DEVICE_IP:8080/cameraState
# Expected: {"status":"ok","cameraState":"ACTIVE","mjpegStreams":3,...}

# 3. Stop 2 streams
kill $PID1 $PID2
sleep 2

# 4. Check state (should still be ACTIVE with 1 stream)
curl http://DEVICE_IP:8080/cameraState
# Expected: {"status":"ok","cameraState":"ACTIVE","mjpegStreams":1,...}

# 5. Stop last stream
kill $PID3
sleep 2

# 6. Check state (should be IDLE)
curl http://DEVICE_IP:8080/cameraState
# Expected: {"status":"ok","cameraState":"IDLE","mjpegStreams":0,...}
```

**Pass Criteria**:
- ✅ Camera activates on first stream
- ✅ Camera stays active while any stream is active
- ✅ Camera deactivates only when last stream disconnects

### 7. Snapshot with Idle Camera Test

**Expected Behavior**: Snapshot temporarily activates camera if idle

```bash
# 1. Ensure camera is IDLE
curl http://DEVICE_IP:8080/cameraState

# 2. Take snapshot
curl http://DEVICE_IP:8080/snapshot -o /tmp/snapshot.jpg

# 3. Wait 1 second
sleep 1

# 4. Check camera state (may briefly be ACTIVE, then return to IDLE)
curl http://DEVICE_IP:8080/cameraState
```

**Pass Criteria**:
- ✅ Snapshot succeeds (valid JPEG image)
- ✅ Logs show: "Camera IDLE, temporarily activating for snapshot..."
- ✅ Camera returns to IDLE after snapshot (if no other consumers)

### 8. RTSP Stream Activation Test

**Expected Behavior**: Enabling RTSP registers consumer and activates camera

```bash
# 1. Ensure camera is IDLE
curl http://DEVICE_IP:8080/cameraState

# 2. Enable RTSP
curl http://DEVICE_IP:8080/enableRTSP

# 3. Wait 3 seconds for initialization
sleep 3

# 4. Check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"ACTIVE","mjpegStreams":0,...}

# 5. Test RTSP stream with VLC or ffmpeg
vlc rtsp://DEVICE_IP:8554/stream
# OR
ffprobe rtsp://DEVICE_IP:8554/stream
```

**Pass Criteria**:
- ✅ Camera activates when RTSP enabled
- ✅ RTSP stream works in VLC/ffmpeg
- ✅ Logs show: "RTSP was enabled in settings, registering RTSP consumer..."

### 9. RTSP Stream Deactivation Test

**Expected Behavior**: Disabling RTSP unregisters consumer

```bash
# 1. With RTSP enabled and camera ACTIVE
# 2. Disable RTSP
curl http://DEVICE_IP:8080/disableRTSP

# 3. Wait 2 seconds
sleep 2

# 4. Check camera state (should be IDLE if no other consumers)
curl http://DEVICE_IP:8080/cameraState
```

**Pass Criteria**:
- ✅ Camera deactivates after RTSP disabled (if no other consumers)
- ✅ Logs show: "RTSP streaming disabled"
- ✅ RTSP stream stops working

### 10. Mixed Consumer Test

**Expected Behavior**: Camera stays active with multiple consumer types

```bash
# 1. Start with IDLE camera
# 2. Expand preview in app
# 3. Start MJPEG stream
curl http://DEVICE_IP:8080/stream > /tmp/stream.mjpeg &
STREAM_PID=$!

# 4. Enable RTSP
curl http://DEVICE_IP:8080/enableRTSP

# 5. Check camera state
sleep 3
curl http://DEVICE_IP:8080/cameraState
# Expected: ACTIVE with multiple consumers

# 6. Collapse preview in app
# 7. Check camera state (should still be ACTIVE)
curl http://DEVICE_IP:8080/cameraState

# 8. Stop MJPEG stream
kill $STREAM_PID

# 9. Check camera state (should still be ACTIVE due to RTSP)
sleep 2
curl http://DEVICE_IP:8080/cameraState

# 10. Disable RTSP
curl http://DEVICE_IP:8080/disableRTSP

# 11. Check camera state (should be IDLE now)
sleep 2
curl http://DEVICE_IP:8080/cameraState
```

**Pass Criteria**:
- ✅ Camera stays active while ANY consumer is active
- ✅ Camera only deactivates when ALL consumers are gone

### 11. Manual Activation/Deactivation Test

**Expected Behavior**: Manual endpoints control camera state

```bash
# 1. Ensure camera is IDLE
curl http://DEVICE_IP:8080/cameraState

# 2. Manually activate
curl http://DEVICE_IP:8080/activateCamera

# 3. Check state
sleep 3
curl http://DEVICE_IP:8080/cameraState
# Expected: ACTIVE

# 4. Manually deactivate
curl http://DEVICE_IP:8080/deactivateCamera

# 5. Check state
sleep 2
curl http://DEVICE_IP:8080/cameraState
# Expected: IDLE
```

**Pass Criteria**:
- ✅ Manual activation works
- ✅ Manual deactivation works
- ✅ State transitions correctly

### 12. Service Restart Test

**Expected Behavior**: Camera stays IDLE after service restart

```bash
# 1. Stop service
adb shell am force-stop com.ipcam

# 2. Restart service
adb shell am start -n com.ipcam/.MainActivity

# 3. Wait 5 seconds
sleep 5

# 4. Check camera state
curl http://DEVICE_IP:8080/cameraState

# Expected Result:
# {"status":"ok","cameraState":"IDLE",...}
```

**Pass Criteria**:
- ✅ Camera starts in IDLE state
- ✅ Service starts quickly without camera initialization
- ✅ No errors in logs

### 13. Watchdog Behavior Test

**Expected Behavior**: Watchdog only monitors camera when consumers are active

```bash
# 1. Monitor logs while camera is IDLE
adb logcat -s CameraService:D | grep Watchdog

# Expected: Should see "Watchdog: No consumers, camera state: IDLE" every 10 seconds

# 2. Activate camera (expand preview or start stream)
# 3. Monitor logs while camera is ACTIVE
adb logcat -s CameraService:D | grep Watchdog

# Expected: Should see watchdog checking frame staleness, camera state, etc.

# 4. Deactivate camera
# 5. Monitor logs
# Expected: Should return to "no consumers" messages
```

**Pass Criteria**:
- ✅ Watchdog respects consumer state
- ✅ No unnecessary recovery attempts when IDLE
- ✅ Active monitoring when consumers are present

### 14. App Lifecycle Test

**Expected Behavior**: Preview consumer unregisters on MainActivity destroy

```bash
# 1. Launch app with preview expanded
# 2. Press Android back button to exit app
# 3. Check logs
adb logcat -s MainActivity:D CameraService:D | grep "consumer"

# Expected logs:
# MainActivity: Activity destroying with expanded preview, unregistering consumer...
# CameraService: Unregistered consumer: PREVIEW, remaining consumers: X
```

**Pass Criteria**:
- ✅ Preview consumer properly unregistered on exit
- ✅ Camera deactivates if no other consumers remain
- ✅ No memory leaks or crashes

## Status Endpoint Output

The `/status` endpoint now includes `cameraState`:

```json
{
  "status": "running",
  "server": "Ktor",
  "deviceName": "IP_CAM_Pixel_5",
  "camera": "back",
  "cameraState": "ACTIVE",
  "url": "http://192.168.1.100:8080",
  "resolution": "1920x1080",
  "flashlightAvailable": true,
  "flashlightOn": false,
  "activeConnections": 3,
  "maxConnections": 32,
  "connections": "3/32",
  "activeStreams": 2,
  "activeSSEClients": 1,
  "batteryMode": "NORMAL",
  "streamingAllowed": true,
  "endpoints": [...],
  "version": {...}
}
```

## Troubleshooting

### Camera Won't Activate

1. Check permissions: `adb shell pm list permissions -g | grep CAMERA`
2. Check logs: `adb logcat -s CameraService:E`
3. Try manual activation: `curl http://DEVICE_IP:8080/activateCamera`
4. Check camera state: `curl http://DEVICE_IP:8080/cameraState`

### Camera Stays Active

1. Check active consumers: `curl http://DEVICE_IP:8080/cameraState`
2. Check MJPEG streams: Look at `mjpegStreams` count
3. Check RTSP status: `curl http://DEVICE_IP:8080/rtspStatus`
4. Check preview: Is preview section expanded in MainActivity?

### Camera Won't Deactivate

1. Ensure all MJPEG streams are stopped: `kill` all curl processes
2. Disable RTSP: `curl http://DEVICE_IP:8080/disableRTSP`
3. Collapse preview in MainActivity
4. Check logs for any errors preventing cleanup

## Performance Expectations

| Metric | Expected Value |
|--------|---------------|
| Service startup (IDLE) | < 2 seconds |
| Camera activation time | 2-3 seconds |
| Camera deactivation time | < 1 second |
| CPU usage (IDLE) | < 1% |
| CPU usage (ACTIVE, 1 stream) | 10-20% |
| Memory usage (IDLE) | ~50 MB |
| Memory usage (ACTIVE) | ~100-150 MB |

## Log Patterns to Look For

### Successful Activation
```
CameraService: Registered consumer: MJPEG, total consumers: 1
CameraService: First consumer registered, activating camera...
CameraService: Camera IDLE → INITIALIZING (on-demand activation)
CameraService: startCamera() - initializing camera provider...
CameraService: Camera provider initialized successfully
CameraService: Camera binding successful → ACTIVE state
```

### Successful Deactivation
```
CameraService: Unregistered consumer: MJPEG, remaining consumers: 0
CameraService: Last consumer unregistered, deactivating camera...
CameraService: Camera ACTIVE → STOPPING (no consumers)
CameraService: Stopping camera...
CameraService: Camera stopped → IDLE state
```

### Watchdog with Consumers
```
CameraService: Watchdog: Camera provider exists but camera not bound (1 consumers), binding camera...
```

### Watchdog without Consumers
```
CameraService: Watchdog: No consumers, camera state: IDLE
```

## Summary

This testing guide covers all major scenarios for the on-demand camera activation feature. The camera should:

1. ✅ Start in IDLE state
2. ✅ Activate only when consumers need it
3. ✅ Deactivate when no consumers remain
4. ✅ Support multiple concurrent consumers
5. ✅ Properly clean up on app/service lifecycle events
6. ✅ Be monitored by watchdog only when active

If all tests pass, the feature is working correctly and provides significant resource savings compared to the previous always-on camera approach.
