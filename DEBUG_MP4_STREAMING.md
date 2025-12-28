# MP4 Streaming Debugging Guide for Android Studio

This guide explains how to test and debug the MP4 streaming feature using Android Studio.

## Prerequisites

1. Android Studio installed
2. Android device with USB debugging enabled OR Android Emulator
3. VLC media player (for testing MP4 streams)

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
3. Camera will rebind (preview may freeze briefly)

**Option B: Using HTTP API**
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

**Test with Browser:**
1. Open Chrome/Firefox
2. Navigate to: `http://DEVICE_IP:8080`
3. Scroll to "Streaming Mode" dropdown
4. Select "MP4/H.264" and click "Apply Streaming Mode"
5. Wait 2 seconds for camera to rebind
6. Stream should reload automatically

## Debugging in Android Studio

### Enable Logcat Filtering

1. Open Logcat tab (bottom of Android Studio)
2. Click filter dropdown > Edit Filter Configuration
3. Create filter:
   - Name: "MP4 Streaming"
   - Log Tag: `Mp4StreamWriter|CameraService|HttpServer`
   - Log Level: Debug

### Key Log Messages to Look For

**Successful MP4 Initialization:**
```
CameraService: Binding camera for MP4 streaming mode
Mp4StreamWriter: Initializing MP4 encoder: 1920x1080 @ 30fps, 2000000bps
Mp4StreamWriter: MediaCodec initialized successfully
Mp4StreamWriter: MP4 encoder started
CameraService: Started MP4 encoder processing coroutine
CameraService: Camera bound successfully to back camera in MP4 mode
```

**MP4 Stream Connection:**
```
HttpServer: MP4 stream connection opened. Client 1. Active streams: 1
HttpServer: MP4 init segment sent to client 1 (1920x1080)
HttpServer: Sent keyframe to MP4 client 1, seq=2
```

**Mode Switching:**
```
CameraService: Changing streaming mode from MJPEG to MP4
CameraService: Stopping camera before rebinding...
CameraService: Camera stopped successfully
CameraService: Unbinding all use cases before rebinding...
CameraService: Binding camera for MP4 streaming mode
```

### Common Issues and Solutions

#### Issue 1: "Failed to initialize H.264 encoder"

**Cause:** Device doesn't have H.264 hardware encoder or encoder is busy.

**Solution:**
1. Check logcat for detailed error message
2. Try a lower resolution:
   ```bash
   curl http://DEVICE_IP:8080/setFormat?value=1280x720
   curl http://DEVICE_IP:8080/setStreamingMode?value=mp4
   ```
3. If still fails, the device may not support H.264 encoding

**Check encoder support:**
```bash
adb shell dumpsys media.player | grep -i codec
```

#### Issue 2: Stream plays but video is black/frozen

**Cause:** Camera permission issue or encoder not receiving frames.

**Debug Steps:**
1. Check logcat for encoder output:
   ```
   Mp4StreamWriter: processEncoderOutput called
   ```
2. Verify camera is providing frames:
   ```
   CameraService: Providing encoder input surface to camera
   ```
3. Restart the app completely

#### Issue 3: VLC can't play stream

**Cause:** Incomplete MP4 headers or network issue.

**Debug Steps:**
1. Check if init segment was sent:
   ```
   HttpServer: MP4 init segment sent to client
   ```
2. Check for encoder output:
   ```
   Mp4StreamWriter: Found encoded frame
   ```
3. Try MJPEG to rule out network issues:
   ```bash
   curl http://DEVICE_IP:8080/setStreamingMode?value=mjpeg
   vlc http://DEVICE_IP:8080/stream
   ```

#### Issue 4: "MP4 streaming not active" error

**Cause:** Streaming mode is still MJPEG.

**Solution:**
1. Verify current mode:
   ```bash
   curl http://DEVICE_IP:8080/streamingMode
   ```
2. Set to MP4:
   ```bash
   curl http://DEVICE_IP:8080/setStreamingMode?value=mp4
   ```
3. Wait 2-3 seconds for camera to rebind
4. Try streaming again

#### Issue 5: High latency (> 5 seconds)

**Cause:** Network buffering or encoder settings.

**Debug Steps:**
1. Check encoder is producing frames regularly:
   ```
   Mp4StreamWriter: Sent keyframe (should appear every ~2 seconds)
   ```
2. Try on same WiFi network (not mobile data)
3. Reduce resolution to lower bandwidth needs

### Breakpoint Debugging

**Key places to set breakpoints:**

1. **MP4 Encoder Initialization:**
   - File: `Mp4StreamWriter.kt`
   - Method: `initialize()` line 63

2. **Camera Binding for MP4:**
   - File: `CameraService.kt`
   - Method: `bindCameraForMp4()` line 624

3. **MP4 Stream Request:**
   - File: `HttpServer.kt`
   - Method: `serveMp4Stream()` line 1450

4. **Encoder Output Processing:**
   - File: `CameraService.kt`
   - Method: `startMp4EncoderProcessing()` line 672

**How to Debug:**
1. Set breakpoint (click left gutter in code editor)
2. Run app in Debug mode (Shift+F9)
3. Trigger the action (e.g., change streaming mode)
4. Inspect variables when breakpoint hits
5. Step through code (F8 for step over, F7 for step into)

### Performance Monitoring

**CPU Usage:**
```bash
adb shell top | grep com.ipcam
```

**Memory Usage:**
```bash
adb shell dumpsys meminfo com.ipcam
```

**Battery Drain:**
```bash
adb shell dumpsys batterystats | grep com.ipcam
```

### Network Testing

**Capture MP4 stream to file:**
```bash
curl http://DEVICE_IP:8080/stream.mp4 > test.mp4
# Press Ctrl+C after 10 seconds
vlc test.mp4
```

**Monitor bandwidth:**
```bash
curl -w "@-" http://DEVICE_IP:8080/stats <<'EOF'
time_total: %{time_total}s
size_download: %{size_download} bytes
speed_download: %{speed_download} bytes/sec
EOF
```

### Comparing MJPEG vs MP4

**Test script:**
```bash
#!/bin/bash
DEVICE_IP="192.168.1.100"

# Test MJPEG
echo "Testing MJPEG..."
curl http://$DEVICE_IP:8080/setStreamingMode?value=mjpeg
sleep 2
curl http://$DEVICE_IP:8080/stream > /dev/null 2>&1 &
PID=$!
sleep 10
kill $PID
MJPEG_STATS=$(curl -s http://$DEVICE_IP:8080/stats)

# Test MP4
echo "Testing MP4..."
curl http://$DEVICE_IP:8080/setStreamingMode?value=mp4
sleep 2
curl http://$DEVICE_IP:8080/stream.mp4 > /dev/null 2>&1 &
PID=$!
sleep 10
kill $PID
MP4_STATS=$(curl -s http://$DEVICE_IP:8080/stats)

echo "MJPEG Stats: $MJPEG_STATS"
echo "MP4 Stats: $MP4_STATS"
```

## Advanced Debugging

### Enable Verbose Logging

Add to `CameraService.kt`:
```kotlin
companion object {
    private const val TAG = "CameraService"
    private const val VERBOSE_LOGGING = true // Set to true for detailed logs
}
```

### Trace Encoder Lifecycle

Add logs in `Mp4StreamWriter.kt`:
```kotlin
fun processEncoderOutput() {
    if (!isRunning.get() || !isCodecStarted) {
        Log.v(TAG, "Skipping encoder output: running=${isRunning.get()}, started=$isCodecStarted")
        return
    }
    
    Log.v(TAG, "Processing encoder output...")
    drainEncoder(false)
    Log.v(TAG, "Encoder output processed. Queue size: ${encodedDataQueue.size}")
}
```

### Monitor Frame Queue

Check if frames are being produced but not consumed:
```kotlin
// In CameraService.kt, startMp4EncoderProcessing()
while (isActive && mp4StreamWriter?.isRunning() == true) {
    mp4StreamWriter?.processEncoderOutput()
    val queueSize = mp4StreamWriter?.encodedDataQueue?.size ?: 0
    if (queueSize > 50) {
        Log.w(TAG, "MP4 frame queue is large: $queueSize frames")
    }
    delay(MP4_ENCODER_PROCESSING_INTERVAL_MS)
}
```

## Useful ADB Commands

```bash
# View real-time logs
adb logcat -s Mp4StreamWriter:D CameraService:D HttpServer:D

# Clear logs
adb logcat -c

# Save logs to file
adb logcat -d > ipcam_logs.txt

# Check app processes
adb shell ps | grep com.ipcam

# Force stop app
adb shell am force-stop com.ipcam

# Restart app
adb shell am start -n com.ipcam/.MainActivity

# Check camera usage
adb shell dumpsys media.camera | grep com.ipcam
```

## Emulator Limitations

**Note:** Android Emulator may have limited camera/encoder support:
- Camera may use simulated video feed
- H.264 encoder may be software-only (slower)
- For best testing, use a real Android device

## Getting Help

If issues persist:

1. **Collect Debug Info:**
   ```bash
   adb logcat -d > full_logs.txt
   adb shell dumpsys media.player > codec_info.txt
   adb shell getprop ro.build.version.release > android_version.txt
   ```

2. **Include in Bug Report:**
   - Device model and Android version
   - Streaming mode (MJPEG/MP4)
   - Steps to reproduce
   - Logcat output with filters
   - Screenshot of error (if visible in app/web)

3. **Test with curl:**
   ```bash
   # This helps isolate if issue is in encoder or network
   curl -v http://DEVICE_IP:8080/streamingMode
   curl -v http://DEVICE_IP:8080/setStreamingMode?value=mp4
   curl -v http://DEVICE_IP:8080/stream.mp4 --output test.mp4
   ```

## Summary

Key debugging workflow:
1. Enable Logcat filtering for MP4 tags
2. Change to MP4 mode via app UI or API
3. Monitor logs for initialization errors
4. Test stream with VLC
5. Check encoder output is being produced
6. Verify network connectivity
7. Compare with MJPEG mode if needed

The most common issue is device not supporting H.264 hardware encoding - in this case, the app will automatically fall back to MJPEG mode.
