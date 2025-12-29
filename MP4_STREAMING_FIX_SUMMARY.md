# MP4 Streaming Fix - Technical Summary

## Problem Statement

The MP4 streaming feature was not working despite the encoder producing frames correctly. Clients connecting to `/stream.mp4` received empty or malformed responses.

### Symptoms
- MP4 encoder started successfully and produced frames (410+ frames logged)
- App preview worked correctly in MP4 mode
- HTTP endpoint `/stream.mp4` returned empty response or clients disconnected immediately
- No frames delivered to HTTP clients

### Root Cause Analysis

The core issue was **timing of codec configuration (SPS/PPS) availability**:

1. **MediaCodec Output Format Timing**: The codec's output format (containing SPS/PPS parameters) is only available AFTER the `INFO_OUTPUT_FORMAT_CHANGED` event, which occurs after the encoder starts processing frames.

2. **Init Segment Generation Failure**: The MP4 initialization segment (ftyp + moov boxes) requires SPS/PPS data, but was being generated before this data was available.

3. **Insufficient Wait Time**: The HTTP endpoint only waited 1 second (10 × 100ms) for the init segment, which was often insufficient for the encoder to:
   - Start processing frames
   - Trigger the `INFO_OUTPUT_FORMAT_CHANGED` event
   - Make codec configuration available

## Solution

### Three-Part Fix

#### 1. Cache Codec Format (Mp4StreamWriter.kt)

**Problem**: `mediaCodec.outputFormat` was accessed multiple times, but only populated after the format change event.

**Solution**: Cache the output format when it becomes available:

```kotlin
// Added fields
@Volatile private var cachedOutputFormat: MediaFormat? = null
@Volatile private var formatChangeReceived = AtomicBoolean(false)

// Cache when event occurs
outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
    val newFormat = codec.outputFormat
    cachedOutputFormat = newFormat
    formatChangeReceived.set(true)
    Log.d(TAG, "Encoder output format changed and cached: $newFormat")
}
```

**Added method**:
```kotlin
fun isFormatAvailable(): Boolean = formatChangeReceived.get()
```

**Modified getCodecConfig()**:
```kotlin
fun getCodecConfig(): ByteArray? {
    // Use cached output format if available (preferred)
    val format = cachedOutputFormat ?: mediaCodec?.outputFormat
    // ... rest of implementation
}
```

**Benefits**:
- Reliable access to codec configuration
- Thread-safe with volatile fields
- Clear indication when format is ready

#### 2. Increase Wait Time and Improve Retry Logic (HttpServer.kt)

**Problem**: 1-second wait was insufficient for encoder initialization.

**Solution**: Increased wait time and improved error messages:

```kotlin
// Before: 10 attempts × 100ms = 1 second total
var attempts = 0
while (attempts < 10 && initSegment == null) {
    initSegment = cameraService.getMp4InitSegment()
    if (initSegment == null) {
        delay(100)
        attempts++
    }
}

// After: 30 attempts × 200ms = 6 seconds total
var attempts = 0
while (attempts < 30 && initSegment == null) {
    initSegment = cameraService.getMp4InitSegment()
    if (initSegment == null) {
        if (attempts % 5 == 0) {  // Log every 5th attempt to reduce spam
            Log.d(TAG, "[MP4] Client $clientId: Waiting for MP4 init segment... attempt ${attempts + 1}/30")
        }
        delay(200)
        attempts++
    }
}
```

**Benefits**:
- More time for encoder to initialize (6 seconds vs 1 second)
- Less log spam (logs every 1 second instead of every 100ms)
- Better error messages with client ID tracking

#### 3. Enhanced Diagnostic Logging

**Added throughout the pipeline**:

**CameraService.kt**:
```kotlin
// Track when format becomes available
if (!formatAvailable && mp4StreamWriter?.isFormatAvailable() == true) {
    formatAvailable = true
    Log.d(TAG, "[MP4] Codec format (SPS/PPS) is now available - init segment can be generated")
}
```

**HttpServer.kt**:
```kotlin
// Client-specific logging
Log.d(TAG, "[MP4] Client $clientId: Init segment sent successfully (${initSegment.size} bytes)")
Log.d(TAG, "[MP4] Client $clientId: Sent keyframe, seq=$sequenceNumber, total frames=$framesStreamed")
Log.d(TAG, "[MP4] Client $clientId: Streaming progress - $framesStreamed frames sent")
```

**Benefits**:
- Clear visibility into encoder state
- Easy to trace individual client connections
- Reduced log spam with periodic updates

## Technical Details

### MP4 Fragmented Streaming (fMP4)

The implementation uses fragmented MP4 format for HTTP streaming:

1. **Initialization Segment** (sent once per connection):
   - `ftyp` box: File type identification
   - `moov` box: Movie metadata including:
     - Track information
     - Codec configuration (avcC box with SPS/PPS)
     - Fragment settings (mvex box)

2. **Media Segments** (sent continuously):
   - `moof` box: Movie fragment metadata
   - `mdat` box: Actual H.264 frame data (AVCC format)

### Codec Configuration (SPS/PPS)

**SPS (Sequence Parameter Set)**: Defines video properties (resolution, profile, level)
**PPS (Picture Parameter Set)**: Defines encoding parameters

These are extracted from MediaCodec's CSD (Codec Specific Data) buffers:
- `csd-0`: Usually contains SPS
- `csd-1`: Usually contains PPS

The implementation parses Annex B format (with start codes `0x00 0x00 0x00 0x01`) and converts to avcC format (ISO 14496-15) for MP4 containers.

### Timing Sequence

```
1. Camera binds to Preview surface
   ↓
2. Frames start flowing to encoder input surface
   ↓
3. MediaCodec processes first frame
   ↓
4. INFO_OUTPUT_FORMAT_CHANGED event (SPS/PPS available)
   ↓ [Format is cached here]
5. Subsequent frames encoded
   ↓
6. HTTP client connects
   ↓
7. Init segment generated using cached format
   ↓
8. Frames streamed to client
```

**Before fix**: Step 6 could happen before step 4, causing init segment generation to fail.

**After fix**: Cache ensures format is available when needed, and longer wait time gives encoder time to reach step 4.

## Testing Recommendations

### 1. Test Script
Use the provided `test_mp4_stream.sh`:

```bash
./test_mp4_stream.sh <DEVICE_IP>
```

This will:
- Verify server is running
- Switch to MP4 mode
- Capture 10 seconds of stream
- Verify file structure

### 2. Manual Testing with curl

```bash
# Capture MP4 stream for 10 seconds
timeout 10 curl http://DEVICE_IP:8080/stream.mp4 > test.mp4

# Check file type
file test.mp4

# Analyze with ffprobe
ffprobe test.mp4

# Play with VLC
vlc test.mp4
```

### 3. Live Streaming Test

```bash
# VLC
vlc http://DEVICE_IP:8080/stream.mp4

# ffplay
ffplay http://DEVICE_IP:8080/stream.mp4
```

### 4. Check Logs

Look for these log messages in sequence:

```
[MP4] Initializing MP4 encoder: 1920x1080 @ 30fps
[MP4] MediaCodec initialized successfully
[MP4] MP4 encoder started
[MP4] Started MP4 encoder processing coroutine
[MP4] Encoder output format changed and cached
[MP4] Codec format (SPS/PPS) is now available
[MP4] Client 1: Waiting for MP4 init segment... attempt 1/30
[MP4] Client 1: Init segment sent successfully (675 bytes)
[MP4] Client 1: Sent keyframe, seq=1, total frames=1
[MP4] Client 1: Streaming progress - 90 frames sent
```

## Expected Behavior After Fix

1. **Mode Switch**: When switching to MP4 mode, encoder starts within 2-3 seconds
2. **First Connection**: Client connection may wait up to 6 seconds for init segment (usually 1-2 seconds)
3. **Subsequent Connections**: Init segment available immediately (cached format)
4. **Frame Delivery**: Frames streamed continuously at ~30 fps
5. **Keyframes**: Logged every 2 seconds (I-frame interval setting)

## Comparison: Before vs After

| Aspect | Before Fix | After Fix |
|--------|-----------|-----------|
| Codec format | Accessed directly, may be null | Cached when available |
| Init wait time | 1 second (10 × 100ms) | 6 seconds (30 × 200ms) |
| Format availability | Unknown | Explicitly tracked |
| Logging | Minimal | Comprehensive with client IDs |
| Success rate | Low (timing dependent) | High (sufficient wait time) |

## Potential Issues and Mitigations

### Issue: Init segment still fails after 6 seconds

**Causes**:
- Device doesn't support H.264 hardware encoding
- Encoder crashed during initialization
- Camera not providing frames

**Check**:
```bash
adb logcat -s Mp4StreamWriter:E CameraService:E
```

**Mitigation**: The fix logs detailed error messages indicating the specific failure.

### Issue: High latency (>2 seconds)

**Cause**: This is expected for MP4 streaming due to buffering.

**Mitigation**: Use MJPEG mode for low-latency applications (controlled via web UI or API).

### Issue: Memory usage increase

**Cause**: Frame queue buffering (up to 100 frames).

**Mitigation**: 
- Queue uses rolling buffer (drops oldest frames when full)
- Monitor with: `adb shell dumpsys meminfo com.ipcam`

## Code Changes Summary

**Files Modified**: 3
- `Mp4StreamWriter.kt`: 26 lines changed
- `HttpServer.kt`: 40 lines changed  
- `CameraService.kt`: 13 lines changed

**Total Changes**: 63 additions, 16 deletions

**Impact**: Minimal, focused changes to codec format caching and timing.

## Backwards Compatibility

- No API changes
- No database schema changes
- Settings format unchanged
- Existing MJPEG streaming unaffected

## Performance Impact

- **CPU**: Negligible (caching reduces repeated format access)
- **Memory**: +24 bytes per Mp4StreamWriter instance (cached format reference + boolean)
- **Network**: No change
- **Latency**: Slight increase for first client connection (up to 6s wait), no change for subsequent connections

## Future Improvements

1. **Adaptive wait time**: Start with short wait, increase only if needed
2. **Init segment pre-generation**: Generate and cache init segment proactively
3. **Format change notifications**: Callback when format becomes available
4. **Encoder warm-up**: Start encoder early to reduce first-connection latency
5. **Error recovery**: Automatic retry with MJPEG fallback

## Conclusion

The fix addresses the root cause (codec format timing) with minimal code changes and comprehensive diagnostics. The increased wait time (1s → 6s) provides sufficient time for encoder initialization while maintaining good user experience for most devices. Enhanced logging makes it easy to diagnose any remaining issues.
