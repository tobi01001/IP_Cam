# MP4 Streaming Debugging Guide for Android Studio

This guide explains how to test and debug the MP4 streaming feature using Android Studio.

## Prerequisites

1. Android Studio installed
2. Android device with USB debugging enabled OR Android Emulator
3. VLC media player (for testing MP4 streams)

## Common Issues and Solutions

### Issue 1: App Freezes When Switching to MP4 Mode

**Cause:** The app preview was not being updated in MP4 mode because only Preview (for encoder) was bound, not ImageAnalysis (for app UI).

**Solution (FIXED in latest commit):** 
- MP4 mode now binds BOTH Preview (for MediaCodec encoder) AND ImageAnalysis (for app preview)
- This maintains the app preview while encoding happens in the background
- Check logcat for: "Setting up ImageAnalysis for app preview alongside MP4 encoding"

### Issue 2: Web UI Shows MJPEG After Switching to MP4 in App

**Cause:** Web UI was not dynamically checking the current streaming mode from the server.

**Solution (FIXED in latest commit):**
- Web UI now loads current streaming mode on page load
- `currentStreamingMode` variable tracks the active mode
- Stream URL changes dynamically based on mode (MJPEG: `/stream`, MP4: `/stream.mp4`)
- Mode dropdown reflects actual server state

### Issue 3: VLC Cannot Play /stream.mp4

**Possible Causes:**
1. Encoder not producing frames
2. Network connectivity issues
3. Incomplete MP4 initialization segment
4. Device doesn't support H.264 hardware encoding

**Debug Steps:**

1. **Check if encoder is running:**
   ```
   Logcat filter: Mp4StreamWriter
   Look for: "MP4 encoder started"
   ```

2. **Verify frames are being encoded:**
   ```
   Logcat filter: Mp4StreamWriter
   Look for: "Sent keyframe" messages (should appear every ~2 seconds)
   ```

3. **Check HTTP connection:**
   ```
   Logcat filter: HttpServer
   Look for: "MP4 stream connection opened"
   Look for: "MP4 init segment sent to client"
   ```

4. **Test with curl first:**
   ```bash
   # This helps isolate if issue is in VLC or the stream
   curl http://DEVICE_IP:8080/stream.mp4 --output test.mp4 &
   sleep 10
   killall curl
   vlc test.mp4
   ```

5. **Check encoder initialization:**
   ```
   Logcat filter: CameraService
   Look for: "Initializing MP4 encoder"
   Look for: "MediaCodec initialized successfully" (from Mp4StreamWriter)
   
   If you see "Failed to initialize H.264 encoder", the device may not support it.
   ```

### Issue 4: App Shows Black Preview in MP4 Mode

**Cause:** ImageAnalysis not properly bound or frames not being processed.

**Debug Steps:**
1. Check logcat for: "Setting up ImageAnalysis for app preview alongside MP4 encoding"
2. Check for: "Camera bound successfully to back camera in MP4 mode"
3. Verify `processImage()` is being called (should see frame processing logs)

## Setup

### 1. Connect Device/Emulator

**Physical Device:**
```bash
# Enable USB debugging on your device
# Settings > Developer Options > USB Debugging

# Verify device is connected
adb devices
```

**Emulator:**
- Open Android Studio > Device Manager
- Create/start an emulator with API 24+ and camera support

### 2. Open Project in Android Studio

1. Open Android Studio
2. File > Open > Select `/home/runner/work/IP_Cam/IP_Cam`
3. Wait for Gradle sync to complete

### 3. Run the App

1. Select your device/emulator in the toolbar
2. Click Run (green play button) or press Shift+F10
3. Grant camera permissions when prompted

## Testing MP4 Streaming

### Step 1: Start the Server

1. In the app, tap "Start Server"
2. Note the IP address displayed (e.g., `http://192.168.1.100:8080`)

### Step 2: Change Streaming Mode

**Option A: Using the App UI**
1. Scroll down to "Streaming Mode" spinner
2. Select "MP4/H.264 (Better Bandwidth)"
3. Camera will rebind (preview should continue to work - not freeze)
4. Toast notification confirms mode change

**Option B: Using Web UI**
1. Navigate to `http://DEVICE_IP:8080`
2. Find "Streaming Mode" dropdown
3. Select "MP4/H.264"
4. Click "Apply Streaming Mode"
5. Stream will stop, wait 2 seconds, then restart automatically in MP4 mode

**Option C: Using HTTP API**
```bash
# From your computer on the same network
curl http://DEVICE_IP:8080/setStreamingMode?value=mp4

# Verify mode changed
curl http://DEVICE_IP:8080/streamingMode
```

### Step 3: Test MP4 Stream

**Test with VLC:**
1. Open VLC media player
2. Media > Open Network Stream
3. Enter: `http://DEVICE_IP:8080/stream.mp4`
4. Click Play
5. Video should start playing (may take 1-2 seconds to buffer)

**Test with Browser:**
1. Open Chrome/Firefox
2. Navigate to: `http://DEVICE_IP:8080`
3. Click "Start Stream" button
4. Stream should display (automatically uses MP4 if mode is set to MP4)

## Debugging in Android Studio

### Enable Logcat Filtering

1. Open Logcat tab (bottom of Android Studio)
2. Click filter dropdown > Edit Filter Configuration
3. Create filter:
   - Name: "MP4 Streaming"
   - Log Tag: `Mp4StreamWriter|CameraService|HttpServer`
   - Log Level: Debug

### Key Log Messages to Look For

**Successful MP4 Mode Switch:**
```
CameraService: Changing streaming mode from MJPEG to MP4
CameraService: Stopping camera before rebinding...
CameraService: Camera stopped successfully
CameraService: Binding camera for MP4 streaming mode
Mp4StreamWriter: Initializing MP4 encoder: 1920x1080 @ 30fps, 2000000bps
Mp4StreamWriter: MediaCodec initialized successfully
Mp4StreamWriter: MP4 encoder started
CameraService: Setting up ImageAnalysis for app preview alongside MP4 encoding
CameraService: Binding camera to lifecycle with Preview for MP4 encoding and ImageAnalysis for app preview...
CameraService: Camera bound successfully to back camera in MP4 mode
CameraService: Started MP4 encoder processing coroutine
```

**MP4 Stream Connection:**
```
HttpServer: MP4 stream connection opened. Client 1. Active streams: 1
HttpServer: MP4 init segment sent to client 1 (1920x1080)
Mp4StreamWriter: (periodic frame encoding messages)
HttpServer: Sent keyframe to MP4 client 1, seq=2
```

**App Preview Updates (Should Continue in MP4 Mode):**
```
CameraService: processImage() called (should see these regularly)
```

### Breakpoint Debugging

**Key places to set breakpoints:**

1. **MP4 Mode Camera Binding:**
   - File: `CameraService.kt`
   - Method: `bindCameraForMp4()` line 624
   - Check: Both Preview and ImageAnalysis are created and bound

2. **Encoder Initialization:**
   - File: `Mp4StreamWriter.kt`
   - Method: `initialize()` line 63
   - Check: MediaCodec is created without errors

3. **MP4 Stream Request:**
   - File: `HttpServer.kt`
   - Method: `serveMp4Stream()` line 1450
   - Check: Mode is MP4 and frames are being retrieved

4. **Web UI Mode Loading:**
   - File: `HttpServer.kt`
   - Search for: `loadStreamingMode()` function
   - Check: `currentStreamingMode` variable is updated correctly

### Performance Monitoring

**CPU Usage:**
```bash
adb shell top | grep com.ipcam
```

**Memory Usage:**
```bash
adb shell dumpsys meminfo com.ipcam
```

**Check Encoder is Running:**
```bash
adb logcat -s Mp4StreamWriter:D | grep "encoder"
```

### Network Testing

**Test Mode Switching:**
```bash
#!/bin/bash
DEVICE_IP="192.168.1.100"

echo "Current mode:"
curl -s http://$DEVICE_IP:8080/streamingMode | jq

echo "Switching to MP4..."
curl -s http://$DEVICE_IP:8080/setStreamingMode?value=mp4 | jq

sleep 3

echo "Verifying mode:"
curl -s http://$DEVICE_IP:8080/streamingMode | jq

echo "Testing MP4 stream (10 seconds)..."
timeout 10 curl http://$DEVICE_IP:8080/stream.mp4 > /tmp/test.mp4
ls -lh /tmp/test.mp4

echo "Testing with VLC..."
vlc /tmp/test.mp4
```

## Advanced Debugging

### Check H.264 Encoder Support

```bash
adb shell dumpsys media.player | grep -A 10 "video/avc"
```

### Monitor Encoder Queue

Add logging to `startMp4EncoderProcessing()` in CameraService.kt:
```kotlin
while (isActive && mp4StreamWriter?.isRunning() == true) {
    mp4StreamWriter?.processEncoderOutput()
    val hasFrames = mp4StreamWriter?.hasEncodedFrames() ?: false
    if (hasFrames) {
        Log.v(TAG, "MP4 encoder has frames available")
    }
    delay(MP4_ENCODER_PROCESSING_INTERVAL_MS)
}
```

### Trace Frame Flow

1. **Camera → Preview Surface:**
   - Check: `request.provideSurface()` is called
   - Encoder should start receiving frames automatically

2. **Camera → ImageAnalysis → App:**
   - Check: `processImage()` is called regularly
   - Check: `onFrameAvailableCallback?.invoke()` updates MainActivity

3. **Encoder → Queue → HTTP:**
   - Check: `processEncoderOutput()` runs every 10ms
   - Check: `getNextEncodedFrame()` returns frames
   - Check: HTTP clients receive moof+mdat boxes

## Known Limitations

1. **MP4 Latency**: 1-2 seconds (vs 100ms for MJPEG) due to buffering
2. **Hardware Required**: H.264 encoder must be supported by device
3. **Browser Compatibility**: Some older browsers may not support fMP4
4. **No Audio**: Current implementation is video-only

## Useful ADB Commands

```bash
# View real-time logs with all MP4-related tags
adb logcat -s Mp4StreamWriter:D CameraService:D HttpServer:D

# Clear logs before test
adb logcat -c

# Save logs to file
adb logcat -d > ipcam_mp4_logs.txt

# Check if app is running
adb shell ps | grep com.ipcam

# Force stop app
adb shell am force-stop com.ipcam

# Restart app
adb shell am start -n com.ipcam/.MainActivity

# Check camera usage
adb shell dumpsys media.camera | grep com.ipcam

# Monitor network activity
adb shell netstat | grep 8080
```

## Troubleshooting Checklist

Before reporting issues, verify:

- [ ] App preview continues to work in MP4 mode (no freeze)
- [ ] Logcat shows "Setting up ImageAnalysis for app preview"
- [ ] Logcat shows "MP4 encoder started"
- [ ] Logcat shows "MP4 init segment sent to client"
- [ ] Web UI dropdown shows correct mode after page reload
- [ ] Web UI automatically uses correct stream URL (/stream vs /stream.mp4)
- [ ] VLC can connect to the stream endpoint
- [ ] Device has H.264 hardware encoder support

## Getting Help

If issues persist after following this guide:

1. **Collect Full Logs:**
   ```bash
   adb logcat -d > full_logs.txt
   ```

2. **Check Device Info:**
   ```bash
   adb shell getprop ro.product.model > device_info.txt
   adb shell getprop ro.build.version.release >> device_info.txt
   adb shell dumpsys media.player | grep codec >> device_info.txt
   ```

3. **Test Basic Functionality:**
   ```bash
   # Does MJPEG work?
   curl http://DEVICE_IP:8080/setStreamingMode?value=mjpeg
   vlc http://DEVICE_IP:8080/stream
   
   # Can mode be changed?
   curl http://DEVICE_IP:8080/streamingMode
   ```

4. **Include in Bug Report:**
   - Device model and Android version
   - Streaming mode being tested
   - Exact steps to reproduce
   - Logcat output (filtered for MP4 tags)
   - Screenshot of app (showing freeze if applicable)
   - VLC error messages (if any)

