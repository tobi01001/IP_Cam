# MP4 Streaming Fix - User Guide

## What Was Fixed

The MP4 streaming feature was not working because the HTTP endpoint couldn't generate the required initialization segment. This has been fixed by:

1. **Caching codec configuration** - The encoder's format information (SPS/PPS) is now cached when it becomes available
2. **Increased wait time** - Clients now wait up to 6 seconds for the encoder to initialize (previously 1 second)
3. **Better diagnostics** - Enhanced logging helps identify any remaining issues

## How to Test

### Quick Test (Recommended)

Run the automated test script:

```bash
./test_mp4_stream.sh YOUR_DEVICE_IP
```

Example:
```bash
./test_mp4_stream.sh 192.168.1.100
```

The script will:
- ✓ Check server status
- ✓ Switch to MP4 mode
- ✓ Capture 10 seconds of stream
- ✓ Verify file structure
- ✓ Show file size and type

### Manual Test

#### 1. Switch to MP4 Mode

**Via Web UI:**
1. Open `http://YOUR_DEVICE_IP:8080` in your browser
2. Find "Streaming Mode" dropdown
3. Select "MP4/H.264 (Better Bandwidth, ~1-2s latency)"
4. Click "Apply Streaming Mode"
5. Wait 5 seconds for camera to rebind

**Via API:**
```bash
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mp4
```

#### 2. Test Stream with curl

```bash
# Capture 10 seconds of stream
timeout 10 curl http://YOUR_DEVICE_IP:8080/stream.mp4 -o test.mp4

# Check file type
file test.mp4

# Should show something like:
# test.mp4: ISO Media, MP4 Base Media v1 [IS0 14496-12:2003]
```

#### 3. Play with VLC

**Saved file:**
```bash
vlc test.mp4
```

**Live stream:**
```bash
vlc http://YOUR_DEVICE_IP:8080/stream.mp4
```

Or in VLC:
- Media → Open Network Stream
- Enter: `http://YOUR_DEVICE_IP:8080/stream.mp4`
- Click Play

#### 4. Test with Browser

**Modern browsers (Chrome, Firefox, Safari):**
1. Open `http://YOUR_DEVICE_IP:8080`
2. Click "Start Stream" button
3. Video should display automatically in MP4 mode

**Note:** First connection may take 1-6 seconds to initialize. Subsequent connections are faster.

### Verify Logs (Android Studio/adb)

```bash
# Filter for MP4-related logs
adb logcat -s Mp4StreamWriter:D CameraService:D HttpServer:D | grep "\[MP4\]"
```

Look for this sequence:
```
[MP4] MediaCodec initialized successfully
[MP4] MP4 encoder started
[MP4] Started MP4 encoder processing coroutine
[MP4] Encoder output format changed and cached: ...
[MP4] Codec format (SPS/PPS) is now available - init segment can be generated
[MP4] Encoder producing frames: 90 frames total, queue has frames: true
[MP4] Client 1: Waiting for MP4 init segment... attempt 1/30
[MP4] Client 1: Init segment sent successfully (675 bytes)
[MP4] Client 1: Sent keyframe, seq=1, total frames=1
[MP4] Client 1: Streaming progress - 90 frames sent
```

## Expected Behavior

### First Connection
- **Wait time:** 1-6 seconds (usually 2-3 seconds)
- **Reason:** Encoder needs to process frames before codec config is available
- **What you'll see:** Client waits, then stream starts

### Subsequent Connections
- **Wait time:** < 1 second
- **Reason:** Codec config is already cached
- **What you'll see:** Stream starts almost immediately

### During Streaming
- **Frame rate:** ~30 fps (configurable)
- **Keyframes:** Every 2 seconds
- **Latency:** 1-2 seconds (normal for MP4 streaming)
- **Connection limit:** 32 simultaneous clients (configurable)

## Troubleshooting

### Problem: Stream still doesn't work after 6 seconds

**Check logs for:**
```
[MP4] Failed to get MP4 init segment after 30 attempts
```

**Possible causes:**
1. Device doesn't support H.264 hardware encoding
2. Camera not providing frames
3. Encoder crashed during initialization

**Solution:**
```bash
# Check encoder support
adb shell dumpsys media.player | grep -A 10 "video/avc"

# Try MJPEG mode instead
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mjpeg
```

### Problem: File downloads but won't play

**Check file size:**
```bash
ls -lh test.mp4
```

**If file is very small (<1KB):**
- Check logs for error messages
- Verify encoder is producing frames

**If file is reasonable size but won't play:**
```bash
# Analyze file structure
ffprobe test.mp4

# Check for MP4 boxes
xxd test.mp4 | head -20
# Should see: ftyp, moov, moof, mdat
```

### Problem: High memory usage

**Normal behavior:**
- Encoder buffers up to 100 frames
- Old frames are dropped when buffer is full

**Check memory:**
```bash
adb shell dumpsys meminfo com.ipcam
```

**If excessive:**
- Reduce resolution
- Reduce frame rate
- Use MJPEG mode

### Problem: Playback is choppy

**Possible causes:**
1. Network congestion
2. Client device too slow
3. Bitrate too high

**Solutions:**
```bash
# Test network speed
ping YOUR_DEVICE_IP

# Try local playback first
# (eliminates network issues)
timeout 10 curl http://YOUR_DEVICE_IP:8080/stream.mp4 -o test.mp4
vlc test.mp4
```

## Comparison: MJPEG vs MP4

| Feature | MJPEG | MP4/H.264 |
|---------|-------|-----------|
| Latency | ~100ms | 1-2 seconds |
| Bandwidth | Higher | 30-50% lower |
| CPU Usage | Lower | Moderate |
| Compatibility | Universal | Modern devices |
| Quality | Good | Better |
| Best for | Low latency | Bandwidth savings |

**Switch modes:**
```bash
# MJPEG (low latency)
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mjpeg

# MP4 (low bandwidth)
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mp4
```

## Integration with NVR Systems

### ZoneMinder / Blue Iris / Shinobi

**For MP4 streaming:**
1. Add camera with source type: "HTTP"
2. Path: `http://YOUR_DEVICE_IP:8080/stream.mp4`
3. Format: MP4 or H.264

**Note:** Some older NVR systems may not support fragmented MP4. Use MJPEG mode for maximum compatibility:
- Path: `http://YOUR_DEVICE_IP:8080/stream`
- Format: MJPEG

### VLC Command Line

```bash
# Play MP4 stream
vlc http://YOUR_DEVICE_IP:8080/stream.mp4

# Play MJPEG stream
vlc http://YOUR_DEVICE_IP:8080/stream

# Record to file
vlc http://YOUR_DEVICE_IP:8080/stream.mp4 --sout=file/ps:output.mp4
```

### ffmpeg

```bash
# Convert MP4 stream to file
ffmpeg -i http://YOUR_DEVICE_IP:8080/stream.mp4 -c copy output.mp4

# Re-stream to RTSP
ffmpeg -i http://YOUR_DEVICE_IP:8080/stream.mp4 -c copy -f rtsp rtsp://localhost:8554/stream
```

## Performance Tips

### For Best Quality
- Use highest resolution supported by camera
- MP4 mode for better compression
- Ensure good WiFi signal
- Plug device into power

### For Best Performance
- Use lower resolution (e.g., 1280×720)
- MJPEG mode for lower CPU usage
- Limit simultaneous connections
- Disable adaptive quality if network is stable

### For Best Battery Life
- MJPEG mode (less CPU intensive)
- Lower resolution
- Lower frame rate
- Request battery optimization exemption in Android settings

## API Reference

### Check Streaming Mode
```bash
curl http://YOUR_DEVICE_IP:8080/streamingMode
```

Response:
```json
{"status":"ok","streamingMode":"mp4"}
```

### Set Streaming Mode
```bash
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mp4
curl http://YOUR_DEVICE_IP:8080/setStreamingMode?value=mjpeg
```

### Get Server Status
```bash
curl http://YOUR_DEVICE_IP:8080/status
```

Response includes:
- Current mode
- Active connections
- Camera state
- Resolution

## Additional Resources

- **Technical Details:** See `MP4_STREAMING_FIX_SUMMARY.md`
- **Implementation:** See commit history
- **Debug Guide:** See `DEBUG_MP4_STREAMING.md`

## Support

If you encounter issues:

1. **Check logs** with the filter above
2. **Run test script** to verify basic functionality
3. **Try MJPEG mode** to isolate MP4-specific issues
4. **Report issue** with logs and device information

Include in bug report:
- Device model and Android version
- Streaming mode (MP4 or MJPEG)
- Error logs from logcat
- Output from test script
- Steps to reproduce
