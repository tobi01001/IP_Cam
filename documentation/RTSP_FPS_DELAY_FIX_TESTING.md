# RTSP Framerate & Delay Fix - Testing Guide

## Overview
This document provides comprehensive testing procedures for the RTSP framerate mismatch and increasing delay fixes implemented in this PR.

## Issues Fixed

### 1. Framerate Mismatch
**Problem**: VLC always showed ~30 fps decoded framerate regardless of encoder target FPS setting (e.g., 15 fps)

**Root Cause**: RTP timestamps were calculated synthetically using frame counter instead of actual MediaCodec presentation times

**Fix**: Use actual presentation time from MediaCodec for RTP timestamp calculation

### 2. Increasing Stream Delay
**Problem**: Stream delay started at ~1-2 seconds but grew by ~10 seconds per minute

**Root Cause**: 
- Incorrect timestamps caused jitter buffer accumulation in clients
- Encoder buffering without low-latency settings added delay

**Fix**: 
- Correct RTP timestamps using actual presentation times
- Low-latency encoder settings (KEY_LATENCY=0, KEY_MAX_B_FRAMES=0, KEY_PRIORITY=0)

## Testing Prerequisites

### Hardware/Software Required
- Android device with camera (Android 11+ / API 30+)
- Computer with VLC Media Player installed
- Both devices on same WiFi network
- IP_Cam app installed on Android device

### Optional Tools
- `ffplay` (from FFmpeg suite) for alternative testing
- `adb logcat` for detailed logging
- Network monitoring tools (optional)

## Test Procedures

### Test 1: Framerate Match Verification

**Objective**: Verify decoded framerate matches encoder target FPS

**Steps**:
1. Start IP_Cam app on Android device
2. Grant camera permission when prompted
3. Note the device IP address (shown in app)
4. Enable RTSP via HTTP API:
   ```bash
   curl http://DEVICE_IP:8080/rtsp/enable
   ```
5. Set target FPS to 15 (or any non-30 value):
   ```bash
   curl "http://DEVICE_IP:8080/rtsp/fps?value=15"
   ```
6. Open VLC on computer and connect to stream:
   ```
   rtsp://DEVICE_IP:8554/stream
   ```
7. In VLC, open: Tools → Codec Information → Statistics tab
8. Observe "Decoded frame rate" value

**Expected Results**:
- ✅ Decoded framerate shows ~15 fps (not 30 fps)
- ✅ Framerate stays consistent over time (±2 fps variation acceptable)
- ✅ Video plays smoothly without stuttering

**How to Verify**:
- Watch "Decoded" counter in VLC Statistics for ~10 seconds
- Calculate: frames / seconds should be close to target FPS
- Example: 150 frames in 10 seconds = 15 fps ✅

### Test 2: Stream Delay Consistency

**Objective**: Verify stream delay remains constant and doesn't grow over time

**Steps**:
1. Connect RTSP stream as in Test 1
2. Create a visual timestamp reference:
   - Point camera at a clock with seconds display, OR
   - Point camera at computer screen showing stopwatch
3. Start streaming and note initial delay
4. Measure delay every 60 seconds for 5+ minutes
5. Record measurements

**Delay Measurement Method**:
- Compare time shown in camera view vs actual time
- Example: Camera shows 12:34:56, actual time is 12:34:58 → 2 second delay

**Expected Results**:
- ✅ Initial delay: ~1-2 seconds
- ✅ Delay after 5 minutes: ~1-2 seconds (same as initial)
- ✅ Delay growth: < 0.5 seconds over 5 minutes
- ❌ FAIL if delay grows to 6+ seconds after 5 minutes

**Sample Data Table**:
| Time Elapsed | Measured Delay | Status |
|--------------|----------------|--------|
| 0 min        | 1.5 sec       | ✅     |
| 1 min        | 1.6 sec       | ✅     |
| 2 min        | 1.7 sec       | ✅     |
| 3 min        | 1.8 sec       | ✅     |
| 5 min        | 2.0 sec       | ✅     |

### Test 3: Multiple FPS Settings

**Objective**: Verify fix works across different FPS settings

**Test Matrix**:
| Target FPS | Expected Decoded FPS | Pass/Fail |
|------------|---------------------|-----------|
| 10 fps     | ~10 fps (±1)       |           |
| 15 fps     | ~15 fps (±2)       |           |
| 20 fps     | ~20 fps (±2)       |           |
| 30 fps     | ~30 fps (±3)       |           |
| 60 fps     | ~60 fps (±5)       |           |

**Steps** (repeat for each FPS):
1. Set target FPS via API:
   ```bash
   curl "http://DEVICE_IP:8080/rtsp/fps?value=<FPS>"
   ```
2. Wait 2-3 seconds for encoder to restart
3. Reconnect VLC
4. Check decoded framerate in VLC Statistics
5. Record result in table

**Expected Results**:
- ✅ All FPS settings show matching decoded framerate
- ✅ Higher FPS settings may vary more due to hardware limits
- ✅ All settings maintain constant delay

### Test 4: Low-Latency Settings Verification

**Objective**: Confirm low-latency encoder settings are applied

**Steps**:
1. Connect Android device to computer via USB
2. Enable USB debugging
3. Start logcat filtering for encoder logs:
   ```bash
   adb logcat -s H264PreviewEncoder:* RTSPServer:* | grep -i latency
   ```
4. Enable RTSP streaming
5. Check log output

**Expected Log Messages**:
```
H264PreviewEncoder: Low-latency encoder settings applied
RTSPServer: Low-latency encoder settings applied
```

**Notes**:
- If logs show "Some low-latency settings not supported", this is OK
- Older Android versions (< API 29) may not support all settings
- The fix still works even if some settings are unsupported

### Test 5: Multi-Client Stress Test

**Objective**: Verify timestamps work correctly with multiple simultaneous clients

**Steps**:
1. Open VLC instance #1 and connect to RTSP stream
2. Open VLC instance #2 and connect to same stream
3. (Optional) Open VLC instance #3
4. Let all streams run for 2+ minutes
5. Check framerate and delay consistency on all clients

**Expected Results**:
- ✅ All clients show same decoded framerate
- ✅ All clients show similar delay (~1-2 seconds)
- ✅ No client accumulates delay over time
- ✅ No crashes or stream interruptions

### Test 6: Timestamp Continuity Check

**Objective**: Verify no timestamp jumps or discontinuities

**Steps**:
1. Connect VLC to RTSP stream
2. In VLC, open: Tools → Messages (Verbosity: Debug)
3. Filter for "discontinuity" or "timestamp"
4. Stream for 3+ minutes
5. Check for warning/error messages

**Expected Results**:
- ✅ No "timestamp discontinuity" warnings
- ✅ No "PTS is out of range" errors
- ✅ Smooth playback without freezes or jumps

**Common Issues**:
- ❌ "DTS out of range" → Indicates timestamp problems
- ❌ Frequent discontinuity warnings → Timestamp not monotonic

## Troubleshooting

### Issue: VLC shows 0 fps or "N/A"
**Solution**: 
- Wait 5-10 seconds for statistics to populate
- Ensure stream is actually playing (not frozen)
- Try closing and reopening VLC

### Issue: Can't connect to RTSP stream
**Solution**:
- Verify RTSP is enabled: `curl http://DEVICE_IP:8080/rtsp/status`
- Check port 8554 is not blocked by firewall
- Try TCP transport: `ffplay -rtsp_transport tcp rtsp://DEVICE_IP:8554/stream`

### Issue: Stream freezes or stutters
**Solution**:
- Check WiFi signal strength
- Reduce FPS if device can't keep up
- Verify encoder logs for errors

### Issue: Delay measurement inconsistent
**Solution**:
- Use clock/stopwatch with clear seconds display
- Ensure good lighting for camera to capture display
- Take multiple measurements and average them

## Alternative Testing with FFplay

FFplay provides detailed statistics for advanced testing:

```bash
# Connect and show stats
ffplay -rtsp_transport tcp -stats rtsp://DEVICE_IP:8554/stream

# Record to file for analysis
ffmpeg -rtsp_transport tcp -i rtsp://DEVICE_IP:8554/stream -c copy -t 60 output.mp4

# Analyze recorded stream
ffprobe -show_frames output.mp4 | grep pkt_pts_time
```

## Performance Benchmarks

### Expected Performance Metrics
- **CPU Usage**: 15-25% (hardware encoding)
- **Memory Usage**: 150-250 MB
- **Network Bandwidth**: 2-5 Mbps (depending on resolution/FPS)
- **Latency**: 500-2000ms (stable, no growth)

### Monitoring Commands
```bash
# CPU usage
adb shell top -n 1 | grep com.ipcam

# Memory usage
adb shell dumpsys meminfo com.ipcam | grep TOTAL

# Network stats
adb shell dumpsys netstats | grep com.ipcam
```

## Success Criteria

All tests must pass with the following criteria:

| Test | Success Criteria | Status |
|------|-----------------|--------|
| Framerate Match | Decoded FPS matches target (±10%) | [ ] |
| Delay Consistency | Delay stays < 2.5s over 5 minutes | [ ] |
| Multiple FPS | All FPS settings work correctly | [ ] |
| Low-Latency Logs | Settings applied or graceful fallback | [ ] |
| Multi-Client | All clients work simultaneously | [ ] |
| Timestamp Continuity | No discontinuity warnings | [ ] |

## Reporting Results

When reporting test results, please include:

1. **Device Info**:
   - Android device model
   - Android version / API level
   - Chipset (if known)

2. **Test Results**:
   - Which tests passed/failed
   - Actual vs expected measurements
   - Screenshots of VLC Codec Info

3. **Logs** (if issues found):
   ```bash
   adb logcat -s H264PreviewEncoder:* RTSPServer:* > logs.txt
   ```

4. **Network Info**:
   - WiFi or mobile hotspot
   - Signal strength
   - Any network issues observed

## Known Limitations

1. **First-Frame Delay**: Initial connection may take 2-3 seconds to establish
2. **FPS Accuracy**: Actual encoded FPS may vary ±10% from target due to hardware
3. **Android Version**: Low-latency settings require Android 10+ (API 29+)
4. **Hardware Encoding**: Some devices may have firmware-specific encoder behavior

## Conclusion

This fix addresses the root causes of both framerate mismatch and increasing delay by:

1. ✅ Using actual MediaCodec presentation times for RTP timestamps
2. ✅ Applying low-latency encoder settings to minimize buffering
3. ✅ High-precision monotonic timing using System.nanoTime()

The expected result is that RTSP streams now:
- Show correct decoded framerate in clients (matching encoder target)
- Maintain constant stream delay over extended streaming sessions
- Provide better synchronization and jitter buffer behavior

If any tests fail, please report with full details as described above.
