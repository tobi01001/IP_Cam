# MP4 Streaming Implementation Guide

## Overview

This document describes the MP4/H.264 streaming feature implementation for IP_Cam. The feature adds an alternative streaming mode to the existing MJPEG streaming, allowing users to choose between:

- **MJPEG**: Lower latency (~100ms), higher bandwidth, better compatibility with older systems
- **MP4/H.264**: Higher latency (~1-2s), better bandwidth efficiency, hardware-accelerated encoding

## Implementation Status

### ✅ Completed

1. **Core Infrastructure**
   - Created `StreamingMode.kt` enum with MJPEG and MP4 options
   - Created `Mp4StreamWriter.kt` class for MediaCodec-based H.264 encoding
   - Added streaming mode state management to CameraService
   - Implemented settings persistence (SharedPreferences)
   - Updated CameraServiceInterface with streaming mode methods

2. **HTTP API Endpoints**
   - `GET /streamingMode` - Get current streaming mode
   - `GET /setStreamingMode?value=mjpeg|mp4` - Switch streaming modes
   - `GET /stream.mp4` - MP4 stream endpoint (skeleton)
   - Updated `/status` endpoint to include streaming mode

3. **Architecture**
   - Single source of truth: Only one encoder (MJPEG or MP4) active at a time
   - Mode switching triggers camera rebinding to switch encoders
   - Settings persist across app restarts

### 🚧 In Progress / TODO

1. **Camera Pipeline Integration**
   - Integrate Mp4StreamWriter with CameraService's camera binding
   - Set up MediaCodec with input surface from CameraX
   - Handle encoder lifecycle (start/stop) during mode switches
   - Process encoded frames and make them available for streaming

2. **MP4 Stream Delivery**
   - Implement fMP4 (fragmented MP4) streaming in HttpServer
   - Generate proper MP4 initialization segment (moov box)
   - Stream media segments (moof + mdat boxes)
   - Handle multiple concurrent MP4 clients

3. **UI Updates**
   - Add streaming mode selector to MainActivity
   - Update web UI with mode selection dropdown
   - Show current mode in UI status displays

4. **Testing & Validation**
   - Test MP4 playback in VLC, browsers
   - Test mode switching reliability
   - Verify single source (no dual encoding)
   - Test with NVR systems (ZoneMinder, Shinobi, Blue Iris)
   - Compare bandwidth usage between MJPEG and MP4

5. **Documentation**
   - Update README with MP4 streaming usage
   - Document API endpoints
   - Add troubleshooting section for MP4 issues
   - Update surveillance system integration guide

## Technical Details

### MediaCodec Integration

The `Mp4StreamWriter` class uses Android's MediaCodec API for hardware-accelerated H.264 encoding:

```kotlin
// Encoder configuration
val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30) // 30 fps
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // I-frame every 2 seconds

// Create encoder and get input surface
mediaCodec = MediaCodec.createEncoderByType("video/avc")
mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
inputSurface = mediaCodec.createInputSurface()
```

### Camera Pipeline Changes Needed

The current implementation uses `ImageAnalysis` use case which provides `ImageProxy` frames. For MP4 encoding, we need to:

1. **Add a Preview use case** with the MP4 encoder's input surface:
   ```kotlin
   if (streamingMode == StreamingMode.MP4) {
       // Initialize MP4 encoder
       mp4StreamWriter = Mp4StreamWriter(resolution, bitrate, frameRate)
       mp4StreamWriter.initialize()
       mp4StreamWriter.start()
       
       // Create Preview use case with encoder's input surface
       val preview = Preview.Builder().build()
       preview.setSurfaceProvider { request ->
           val surface = mp4StreamWriter.getInputSurface()
           request.provideSurface(surface, executor) { }
       }
       
       // Bind to lifecycle
       camera = cameraProvider.bindToLifecycle(this, currentCamera, preview)
   } else {
       // Existing MJPEG flow with ImageAnalysis
       // ...
   }
   ```

2. **Process encoder output** periodically:
   ```kotlin
   // In a background thread/coroutine
   while (mp4StreamWriter.isRunning()) {
       mp4StreamWriter.processEncoderOutput()
       
       while (mp4StreamWriter.hasEncodedFrames()) {
           val frame = mp4StreamWriter.getNextEncodedFrame()
           // Make frame available to HTTP clients
       }
       
       delay(10) // Process every 10ms
   }
   ```

### Fragmented MP4 (fMP4) Streaming

For low-latency streaming, use fragmented MP4 format:

1. **Initialization Segment** (sent once at stream start):
   - `ftyp` box: File type declaration
   - `moov` box: Movie metadata (track info, codec config, SPS/PPS)

2. **Media Segments** (sent continuously):
   - `moof` box: Movie fragment (timing info, sample sizes)
   - `mdat` box: Media data (H.264 NAL units)

### HTTP Streaming Implementation

```kotlin
private suspend fun PipelineContext<Unit, ApplicationCall>.serveMp4Stream() {
    call.respondBytesWriter(ContentType.parse("video/mp4")) {
        // Send initialization segment once
        val initSegment = generateInitSegment()
        writeFully(initSegment, 0, initSegment.size)
        flush()
        
        // Stream media segments
        while (isActive) {
            // Get encoded frames from encoder
            val frames = getEncodedFramesBatch()
            
            // Package into fMP4 segment
            val segment = createMediaSegment(frames)
            
            // Send to client
            writeFully(segment, 0, segment.size)
            flush()
        }
    }
}
```

## API Usage Examples

### Check Current Mode
```bash
curl http://192.168.1.100:8080/streamingMode
# Response: {"status":"ok","streamingMode":"mjpeg"}
```

### Switch to MP4 Mode
```bash
curl http://192.168.1.100:8080/setStreamingMode?value=mp4
# Response: {"status":"ok","message":"Streaming mode set to mp4...","streamingMode":"mp4"}
```

### Access MP4 Stream
```bash
# VLC
vlc http://192.168.1.100:8080/stream.mp4

# Browser (with HTML5 video tag)
<video src="http://192.168.1.100:8080/stream.mp4" controls autoplay></video>

# curl (save to file)
curl http://192.168.1.100:8080/stream.mp4 > output.mp4
```

### Check Status
```bash
curl http://192.168.1.100:8080/status
# Response includes: "streamingMode": "mp4"
```

## Configuration Options

### Encoder Settings

Modify `Mp4StreamWriter` constructor parameters:

```kotlin
Mp4StreamWriter(
    resolution: Size,           // e.g., Size(1920, 1080)
    bitrate: Int = 2_000_000,  // 2 Mbps (adjust based on quality needs)
    frameRate: Int = 30,        // 30 fps (higher = smoother, more bandwidth)
    iFrameInterval: Int = 2     // I-frame every 2 seconds
)
```

### Bitrate Recommendations

- **Low quality (720p)**: 1 Mbps
- **Medium quality (1080p)**: 2-3 Mbps
- **High quality (1080p)**: 4-6 Mbps
- **Very high quality (4K)**: 10-15 Mbps

## Troubleshooting

### MP4 Stream Not Playing

1. **Check streaming mode**: Ensure mode is set to MP4 via `/setStreamingMode?value=mp4`
2. **Verify encoder initialization**: Check logs for MediaCodec initialization errors
3. **Test with VLC**: VLC has better codec support than browsers
4. **Check codec support**: Some older devices may not support H.264 hardware encoding

### High Latency

MP4 streaming inherently has higher latency (1-2 seconds) due to:
- Encoder buffering
- I-frame interval (need to wait for keyframes)
- MP4 container overhead

To reduce latency:
- Decrease I-frame interval (e.g., 1 second)
- Use smaller encoder buffer sizes
- Consider using MJPEG for lowest latency

### Encoder Crashes

- Check that only one encoder is active at a time
- Verify resolution is supported by hardware encoder
- Ensure proper lifecycle management (stop before restart)
- Check device has sufficient resources

## Implementation Checklist

- [x] Create StreamingMode enum
- [x] Create Mp4StreamWriter class
- [x] Add streaming mode to CameraService state
- [x] Add HTTP API endpoints
- [x] Implement settings persistence
- [ ] Integrate MediaCodec with camera pipeline
- [ ] Implement fMP4 initialization segment generation
- [ ] Implement fMP4 media segment streaming
- [ ] Add UI controls for mode switching
- [ ] Update web UI with mode selector
- [ ] Test with VLC and browsers
- [ ] Test with NVR systems
- [ ] Measure and compare bandwidth usage
- [ ] Update README documentation
- [ ] Add troubleshooting guide

## References

- [MediaCodec Documentation](https://developer.android.com/reference/android/media/MediaCodec)
- [H.264 Encoding with MediaCodec](https://developer.android.com/guide/topics/media/mediacodec)
- [ISO Base Media File Format (MP4)](https://www.iso.org/standard/68960.html)
- [Fragmented MP4 (CMAF)](https://www.wowza.com/blog/what-is-cmaf)
- [CameraX Documentation](https://developer.android.com/training/camerax)

## Notes for Developers

### Why Not MediaRecorder?

`MediaRecorder` is simpler but less flexible:
- Can't easily access encoded frames for streaming
- Designed for file recording, not live streaming
- Less control over encoding parameters

### Why Fragmented MP4?

Regular MP4 files require complete file to be available before playback:
- Can't start playback until entire file is downloaded
- Not suitable for live streaming

Fragmented MP4 allows progressive playback:
- Can start playback after receiving initialization segment
- New segments can be appended continuously
- Used by DASH and HLS streaming protocols

### Single Source Architecture

The implementation maintains single source of truth by:
1. Only one encoder (MJPEG or MP4) is active at any time
2. Mode switching triggers camera rebinding
3. Previous encoder is stopped before new one starts
4. Both app preview and web stream use same encoder output

This prevents:
- Resource conflicts (multiple camera access)
- Excessive CPU/battery usage
- Memory pressure from dual encoding
- State synchronization issues

## Future Enhancements

1. **Adaptive Bitrate**: Dynamically adjust bitrate based on network conditions
2. **Multiple Bitrate Options**: Allow user to select quality level
3. **HLS/DASH Support**: Implement standard streaming protocols
4. **Audio Support**: Add audio track to MP4 stream
5. **Recording**: Save MP4 stream to file while streaming
6. **Low Latency Mode**: Optimize settings for minimal latency
7. **Metrics Dashboard**: Show real-time encoding stats (bitrate, framerate, dropped frames)

## Contributing

When working on this feature:

1. Test on multiple devices (different chipsets, Android versions)
2. Measure actual bandwidth usage and latency
3. Verify single source of truth is maintained
4. Ensure backward compatibility with MJPEG
5. Document any device-specific issues or workarounds
6. Update this document with learnings and improvements
