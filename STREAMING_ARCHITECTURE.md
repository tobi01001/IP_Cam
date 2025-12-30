# IP_Cam Streaming Architecture & Performance Analysis

## Executive Summary

This document provides a comprehensive technical analysis of the IP_Cam application's video streaming implementation, including the current architecture, frame handling mechanisms, performance characteristics, and recommendations for optimization.

**Current Implementation:** MJPEG (Motion JPEG) streaming with individual frame delivery  
**Target Use Case:** Local network IP camera for surveillance integration  
**Performance:** ~10 fps, optimized for reliability over maximum throughput

---

## Table of Contents

1. [MJPEG: Current Streaming Architecture (Preserved)](#mjpeg-current-streaming-architecture-preserved)
2. [Current Streaming Implementation](#current-streaming-implementation)
3. [Frame Processing Pipeline](#frame-processing-pipeline)
4. [Performance Characteristics](#performance-characteristics)
5. [Optimization Opportunities](#optimization-opportunities)
6. [Requirements for Hardware-Encoded Modern Streaming](#requirements-for-hardware-encoded-modern-streaming)
7. [MP4 Segment Streaming Analysis](#mp4-segment-streaming-analysis)
8. [Deep Dive: Hardware Encoding & Implementation Details](#deep-dive-hardware-encoding--implementation-details)
9. [Recommendations](#recommendations)

---

## MJPEG: Current Streaming Architecture (Preserved)

### Why MJPEG is Preserved and Remains the Foundation

**MJPEG (Motion JPEG) is the current and primary streaming architecture for IP_Cam, and it will be preserved as the foundation for basic camera functionality.** This decision is based on strong technical and practical rationale:

#### Key Advantages of MJPEG

**1. Low Latency (~150-280ms)**
- Immediate frame delivery with no buffering required
- Each frame is independently encoded and transmitted
- Perfect for live surveillance and monitoring applications
- No segment buffer delays like HLS (6-12 seconds)

**2. Universal Compatibility**
- Works with ALL surveillance systems out-of-the-box
- ZoneMinder, Shinobi, Blue Iris, MotionEye natively support MJPEG
- Simple `<img>` tag in web browsers - no JavaScript required
- VLC and all media players support MJPEG streams
- No codec negotiation or compatibility issues

**3. Simplicity and Reliability**
- Standard HTTP protocol with multipart/x-mixed-replace
- No complex container formats or muxing required
- Each frame is a complete image (no inter-frame dependencies)
- Stream recovery is instantaneous - just start reading the next frame
- Minimal server-side complexity

**4. Real-Time Operation**
- Frame-by-frame transmission allows instant adaptation
- No pre-buffering delays
- Perfect for motion detection and real-time alerts
- Suitable for interactive monitoring

**5. Proven Track Record**
- Battle-tested protocol used by IP cameras for decades
- Known performance characteristics
- Predictable behavior across all platforms
- Minimal implementation bugs or edge cases

#### Use Cases Where MJPEG Excels

✅ **Primary surveillance monitoring** - Low latency is critical  
✅ **Motion detection integration** - Frame-by-frame analysis  
✅ **Legacy NVR/VMS compatibility** - Maximum compatibility  
✅ **Simple browser-based viewing** - No plugins required  
✅ **Quick deployment** - Works immediately with standard tools  
✅ **Local network streaming** - Bandwidth is generally sufficient  
✅ **Real-time monitoring** - Sub-300ms latency  

#### MJPEG Performance Profile

| Metric | Value | Notes |
|--------|-------|-------|
| **Latency** | 150-280ms | Excellent for surveillance |
| **Bandwidth** | ~8 Mbps @ 10fps 1080p | Acceptable on WiFi networks |
| **Compatibility** | 100% | Universal support |
| **Complexity** | Low | Simple implementation |
| **Quality** | Good | 75% JPEG quality |
| **Recovery** | Instant | No buffer flush needed |

### MJPEG Will Not Be Replaced

**Important: MJPEG will remain as the primary streaming method indefinitely.** Any future additions (such as HLS or RTSP) will be implemented **alongside** MJPEG, not as replacements. This ensures:

- Backward compatibility with existing integrations
- Continued support for low-latency use cases
- Simple deployment remains possible
- Universal surveillance system compatibility
- Reliable fallback option

### When MJPEG May Not Be Optimal

While MJPEG excels in many scenarios, there are specific use cases where alternative streaming methods may provide benefits:

⚠️ **High bandwidth cost** - Remote viewing over cellular/metered connections  
⚠️ **Many concurrent viewers** - 10+ simultaneous streams  
⚠️ **Recording to disk** - MP4 format more storage efficient  
⚠️ **Very low bandwidth** - < 2 Mbps available  

For these specialized cases, **hardware-encoded modern streaming (HLS/RTSP)** can be implemented as an **optional alternative**, as detailed in the [Requirements for Hardware-Encoded Modern Streaming](#requirements-for-hardware-encoded-modern-streaming) section below.

---

## Current Streaming Implementation

### Overview: MJPEG Streaming

The IP_Cam application uses **MJPEG (Motion JPEG)** streaming, which transmits video as a continuous stream of individual JPEG images over HTTP. This is confirmed in multiple places:

**From README.md:**
- Line 10: "MJPEG Streaming: Real-time video streaming compatible with surveillance systems"
- Line 405: "Streaming Format: MJPEG (Motion JPEG)"

**Technical Implementation:**
```
Protocol: HTTP multipart/x-mixed-replace
Boundary: --jpgboundary
Content-Type: image/jpeg per frame
Frame Rate: ~10 fps (100ms delay between frames)
```

### Detailed Architecture

#### 1. Camera Capture (CameraX)
**Source:** `CameraService.kt` lines 451-495

```kotlin
ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
```

- Uses **CameraX** library (androidx.camera:camera-core:1.3.1)
- **Backpressure Strategy:** `KEEP_ONLY_LATEST` - drops old frames if processing is slow
- Captures frames from camera in **YUV_420_888** format (ImageProxy)
- Runs on dedicated `cameraExecutor` (single-threaded)

#### 2. Frame Processing Pipeline
**Source:** `CameraService.kt` lines 503-542

**Step-by-step processing:**

1. **YUV to Bitmap Conversion** (lines 625-646)
   - Converts ImageProxy (YUV_420_888) to NV21 byte array
   - Compresses to JPEG using `YuvImage.compressToJpeg()`
   - **Quality:** 70% (JPEG_QUALITY_CAMERA) to reduce memory pressure
   - Decodes JPEG back to Bitmap using `BitmapFactory.decodeByteArray()`

2. **Rotation Application** (lines 544-573)
   - Applies camera orientation (landscape/portrait)
   - Applies user-specified rotation (0°, 90°, 180°, 270°)
   - Uses Android `Matrix.postRotate()` for efficient transformation

3. **Bitmap Annotation** (lines 905-990)
   - Adds timestamp overlay (top left)
   - Adds battery status overlay (top right)
   - Optional resolution overlay (bottom right)
   - Creates mutable copy for Canvas operations

4. **Pre-compression to JPEG** (lines 516-519)
   - **Critical optimization:** Compresses annotated bitmap to JPEG on camera thread
   - **Quality:** 75% (JPEG_QUALITY_STREAM)
   - Stores pre-compressed bytes in `lastFrameJpegBytes` volatile variable
   - **Purpose:** Avoids Bitmap operations on HTTP threads (prevents crashes)

5. **Thread-Safe Storage** (lines 522-532)
   - Stores both Bitmap (for MainActivity preview) and JPEG bytes (for HTTP)
   - Uses synchronized blocks with separate locks (`bitmapLock`, `jpegLock`)
   - Properly recycles old bitmaps to prevent memory leaks

#### 3. HTTP Streaming Delivery
**Source:** `CameraService.kt` lines 1425-1494

**MJPEG Stream Format:**
```http
HTTP/1.1 200 OK
Content-Type: multipart/x-mixed-replace; boundary=--jpgboundary

--jpgboundary
Content-Type: image/jpeg
Content-Length: [size]

[JPEG data]
--jpgboundary
Content-Type: image/jpeg
Content-Length: [size]

[JPEG data]
...
```

**Implementation Details:**

1. **Custom InputStream** (lines 1427-1491)
   - Streams pre-compressed JPEG bytes directly from memory
   - No additional compression on HTTP thread (performance optimization)
   - Frame delivery loop with 100ms delay (STREAM_FRAME_DELAY_MS)
   - Streams until client disconnects

2. **Frame Rate Control** (line 1457-1462)
   - Fixed delay: `Thread.sleep(STREAM_FRAME_DELAY_MS)` = 100ms
   - Target frame rate: ~10 fps
   - **Not adaptive** - fixed rate regardless of network conditions

3. **Chunked Transfer Encoding**
   - Uses `newChunkedResponse()` for streaming
   - HTTP/1.1 chunked transfer encoding
   - No predetermined content length

#### 4. Connection Management
**Source:** `CameraService.kt` lines 142-211

**Bounded Thread Pool:**
```kotlin
ThreadPoolExecutor(
    HTTP_CORE_POOL_SIZE = 2,        // Minimum threads
    HTTP_MAX_POOL_SIZE = 8,         // Maximum concurrent connections
    HTTP_KEEP_ALIVE_TIME = 60L,     // Thread timeout
    HTTP_QUEUE_CAPACITY = 50        // Queue size before rejection
)
```

**Key Features:**
- **Max 8 concurrent connections** (suitable for typical IP camera use)
- **Graceful degradation:** Uses CallerRunsPolicy for overload
- **Connection tracking:** Active connection counter via SSE (Server-Sent Events)
- **Resource protection:** Prevents unbounded thread creation

---

## Frame Processing Pipeline

### Visual Flow Diagram

```
┌─────────────────┐
│   Camera Sensor │ (YUV_420_888 format)
│   (CameraX)     │
└────────┬────────┘
         │
         │ ImageProxy (YUV)
         │
         ▼
┌─────────────────────────────┐
│  processImage()             │
│  (Camera Executor Thread)   │
├─────────────────────────────┤
│ 1. YUV → NV21 conversion    │
│ 2. JPEG compression (70%)   │
│ 3. JPEG → Bitmap decode     │
└────────┬────────────────────┘
         │
         │ Bitmap (unrotated)
         │
         ▼
┌─────────────────────────────┐
│  applyRotationCorrectly()   │
├─────────────────────────────┤
│ 1. Calculate total rotation │
│ 2. Matrix transformation    │
│ 3. Recycle old bitmap       │
└────────┬────────────────────┘
         │
         │ Bitmap (rotated)
         │
         ▼
┌─────────────────────────────┐
│  annotateBitmap()           │
├─────────────────────────────┤
│ 1. Copy bitmap (mutable)    │
│ 2. Draw timestamp overlay   │
│ 3. Draw battery overlay     │
│ 4. Draw resolution overlay  │
└────────┬────────────────────┘
         │
         │ Bitmap (annotated)
         │
         ▼
┌─────────────────────────────┐
│  Pre-compress to JPEG       │
├─────────────────────────────┤
│ 1. Compress at 75% quality  │
│ 2. Store in byte array      │
└────────┬────────────────────┘
         │
         ├──────────────────────┬─────────────────────┐
         │                      │                     │
         ▼                      ▼                     ▼
┌────────────────┐    ┌────────────────┐    ┌──────────────┐
│ lastFrameBitmap│    │lastFrameJpeg   │    │ MainActivity │
│ (synchronized) │    │Bytes           │    │ Preview      │
│                │    │(synchronized)  │    │              │
└────────────────┘    └───────┬────────┘    └──────────────┘
                              │
                              │ Multiple HTTP threads
                              │ read pre-compressed JPEG
                              │
                              ▼
                    ┌──────────────────┐
                    │  MJPEG Stream    │
                    │  (HTTP Response) │
                    ├──────────────────┤
                    │ Frame 1 (JPEG)   │
                    │ 100ms delay      │
                    │ Frame 2 (JPEG)   │
                    │ 100ms delay      │
                    │ Frame 3 (JPEG)   │
                    │ ...              │
                    └──────────────────┘
```

### Frame Handling Characteristics

#### 1. **Individual Frame Delivery**
- ✅ **Each frame is sent individually** as a complete JPEG image
- ✅ **Real-time delivery** with minimal buffering
- ✅ **No frame batching or coalescing** - frames are sent as soon as available

#### 2. **Frame Synchronization**
- Uses `volatile` variables for thread-safe access
- Latest frame always available via `lastFrameJpegBytes`
- Old frames automatically dropped (KEEP_ONLY_LATEST strategy)

#### 3. **Memory Management**
- Pre-compression prevents Bitmap operations on HTTP threads
- Synchronized access with dedicated locks
- Explicit bitmap recycling to prevent leaks
- Fixed memory footprint (one frame in memory)

---

## Performance Characteristics

### Current Performance Metrics

| Metric | Value | Location |
|--------|-------|----------|
| **Target Frame Rate** | ~10 fps | STREAM_FRAME_DELAY_MS = 100ms |
| **JPEG Quality (Camera)** | 70% | JPEG_QUALITY_CAMERA |
| **JPEG Quality (Stream)** | 75% | JPEG_QUALITY_STREAM |
| **JPEG Quality (Snapshot)** | 85% | JPEG_QUALITY_SNAPSHOT |
| **Max Concurrent Clients** | 8 | HTTP_MAX_POOL_SIZE |
| **Connection Queue** | 50 | HTTP_QUEUE_CAPACITY |

### Bandwidth Estimation

**Typical JPEG sizes (1080p at 75% quality):**
- Conservative estimate: 50-150 KB per frame
- Average: ~100 KB per frame

**Bandwidth per stream:**
```
10 fps × 100 KB = 1,000 KB/s = ~8 Mbps per client
```

**Maximum load (8 clients):**
```
8 clients × 8 Mbps = 64 Mbps total
```

**Network Requirements:**
- Single client: 8-12 Mbps
- Multiple clients: Scales linearly
- Suitable for: WiFi 802.11n/ac/ax networks
- Challenge: May saturate 100 Mbps Ethernet

### Latency Analysis

**End-to-End Latency Components:**

1. **Camera Capture:** ~33ms (at 30 fps camera sensor rate)
2. **YUV to JPEG:** ~10-20ms (hardware-accelerated)
3. **Rotation & Annotation:** ~5-10ms
4. **Pre-compression:** ~10-20ms
5. **Network Transmission:** Variable (depends on network)
6. **Fixed Frame Delay:** 100ms (intentional rate limiting)

**Total approximate latency:** 150-280ms

**Latency characteristics:**
- ✅ Low latency for local network use
- ✅ Suitable for surveillance (not real-time control)
- ⚠️ Fixed 100ms delay adds consistent latency
- ⚠️ No adaptive quality based on network conditions

### Resource Utilization

**CPU Usage:**
- **Camera thread:** Continuous (encoding, rotation, annotation)
- **HTTP threads:** Minimal (pre-compressed frames)
- **Estimated:** 20-30% CPU on modern devices

**Memory Usage:**
- **Per frame:** ~10-20 MB (Bitmap + JPEG buffers)
- **Thread pool:** 2-8 threads × ~1 MB stack = 2-8 MB
- **Total estimated:** 30-50 MB active memory

**Battery Impact:**
- Continuous camera usage
- High-performance WiFi lock (WIFI_MODE_FULL_HIGH_PERF)
- Partial wake lock (prevents CPU sleep)
- **Recommendation:** Keep device plugged in for 24/7 operation

---

## Optimization Opportunities

### Current Constraints & Bottlenecks

#### 1. **Fixed Frame Rate (Not Adaptive)**
**Current:** Fixed 100ms delay between frames (10 fps)
**Issue:** No adaptation to network conditions or client capabilities

**Potential improvements:**
- Measure network throughput
- Adjust frame rate based on connection quality
- Different rates for different clients

#### 2. **No Frame Skipping During Congestion**
**Current:** All clients receive all frames at same rate
**Issue:** Slow clients can't skip frames to catch up

**Potential improvements:**
- Implement frame dropping for slow clients
- Priority queuing (keyframes vs regular frames)
- Client-specific frame rate negotiation

#### 3. **Redundant Encoding (YUV → JPEG → Bitmap → JPEG)**
**Current:** Double JPEG encoding in pipeline
**Issue:** CPU overhead and potential quality loss

**Path:**
```
YUV → JPEG (70%) → Bitmap → Annotate → JPEG (75%)
      ^^^^^^^^^^^^^          ^^^^^^^^^^^^^^^^
      First encoding         Second encoding
```

**Potential improvements:**
- Annotate directly on YUV or NV21 data
- Single JPEG encode after annotation
- Use hardware overlay for metadata (if available)

#### 4. **Synchronous Annotation on Camera Thread**
**Current:** Annotation happens on camera executor thread
**Issue:** Could slow down frame capture

**Potential improvements:**
- Offload annotation to separate thread pool
- Use GPU rendering for overlays
- Make overlays optional for HTTP stream (keep only for preview)

#### 5. **MJPEG Bandwidth Overhead**
**Current:** Each frame has HTTP multipart headers
**Issue:** Extra bytes per frame

**Overhead per frame:**
```
--jpgboundary\r\n                    (15 bytes)
Content-Type: image/jpeg\r\n         (26 bytes)
Content-Length: [size]\r\n\r\n       (~25 bytes)
[JPEG data]
\r\n                                  (2 bytes)
                                     --------
Total overhead: ~68 bytes/frame
```

**At 10 fps:** 680 bytes/second = 5.4 Kbps (negligible)

### Low-Hanging Fruit Optimizations

#### 1. **Adaptive Frame Rate** (Medium Effort, High Impact)
```kotlin
// Monitor network send rate
private var lastFrameSentTime = 0L
private var adaptiveFrameDelay = STREAM_FRAME_DELAY_MS

fun calculateAdaptiveDelay(sendDuration: Long): Long {
    // If sending takes longer than target, increase delay
    return if (sendDuration > STREAM_FRAME_DELAY_MS) {
        (sendDuration * 1.2).toLong()
    } else {
        STREAM_FRAME_DELAY_MS
    }
}
```

#### 2. **Client-Specific Quality** (Low Effort, Medium Impact)
```kotlin
// Add quality parameter to stream endpoint
// GET /stream?quality=high|medium|low

fun getQualityLevel(quality: String): Int {
    return when (quality) {
        "high" -> 85
        "medium" -> 75
        "low" -> 60
        else -> 75
    }
}
```

#### 3. **Resolution Scaling** (Low Effort, High Impact)
```kotlin
// Add resolution parameter to stream endpoint
// GET /stream?resolution=1080p|720p|480p

fun scaleDownBitmap(source: Bitmap, targetWidth: Int): Bitmap {
    val scale = targetWidth.toFloat() / source.width
    val targetHeight = (source.height * scale).toInt()
    return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
}
```

#### 4. **Skip Annotation for HTTP** (Low Effort, Medium Impact)
```kotlin
// Separate paths for preview (with annotation) and HTTP (without)
// Saves CPU and memory

fun processImageForPreview(image: ImageProxy): Bitmap {
    val bitmap = imageProxyToBitmap(image)
    return annotateBitmap(bitmap)
}

fun processImageForStream(image: ImageProxy): ByteArray {
    val bitmap = imageProxyToBitmap(image)
    // Skip annotation - direct JPEG compression
    return bitmapToJpeg(bitmap, JPEG_QUALITY_STREAM)
}
```

---

## Requirements for Hardware-Encoded Modern Streaming

This section defines the requirements for implementing **optional** hardware-encoded video streaming with modern protocols (HLS/RTSP) alongside the existing MJPEG streaming. These requirements ensure proper implementation that leverages hardware capabilities while maintaining compatibility with the current architecture.

### Overview and Scope

**Purpose:** Provide an alternative streaming method that significantly reduces bandwidth consumption for use cases where higher latency (6-12 seconds) is acceptable.

**Implementation Approach:** Dual-stream architecture where both MJPEG and HW-encoded streaming coexist, allowing clients to choose based on their requirements.

**Priority:** OPTIONAL - To be implemented only after core MJPEG functionality is stable and battle-tested.

---

### REQ-HW-001: Protocol Selection

**Requirement:** The system SHALL implement HLS (HTTP Live Streaming) as the modern streaming protocol.

**Rationale:**
- HLS is HTTP-based and works through existing web server infrastructure
- Native support in Safari and iOS devices
- Works in all browsers via hls.js JavaScript library
- Segment-based approach allows caching and CDN distribution
- Widely supported by modern surveillance systems (Shinobi, Blue Iris)

**Alternative Considered:** RTSP (Real-Time Streaming Protocol)
- RTSP is UDP-based requiring separate server implementation
- Not natively supported in web browsers
- More complex firewall configuration
- Better for very low latency (<1 second) which is not our target

**Decision:** HLS for better compatibility and simpler integration.

---

### REQ-HW-002: Hardware Encoder Detection and Selection

**Requirement:** The system SHALL detect and prefer hardware-accelerated H.264 encoders via MediaCodec API.

**Implementation:**

```kotlin
fun selectBestEncoder(): MediaCodec {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    
    // Prefer hardware encoder
    for (codecInfo in codecList.codecInfos) {
        if (!codecInfo.isEncoder) continue
        if (!codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_AVC)) continue
        
        // Hardware encoders don't contain "OMX.google" or "c2.android"
        if (!codecInfo.name.contains("OMX.google", ignoreCase = true) &&
            !codecInfo.name.contains("c2.android", ignoreCase = true)) {
            Log.d(TAG, "Selected hardware encoder: ${codecInfo.name}")
            return MediaCodec.createByCodecName(codecInfo.name)
        }
    }
    
    // Fallback to any available H.264 encoder
    Log.w(TAG, "Hardware encoder not found, using software fallback")
    return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
}
```

**Verification:**
- Log the selected encoder name on startup
- Report encoder type in `/status` endpoint
- Measure encoding time to verify hardware acceleration (should be <20ms per frame for 1080p)

**Fallback:** If hardware encoder is not available, system SHALL fallback to software encoder with warning logged.

---

### REQ-HW-003: Encoder Configuration

**Requirement:** The H.264 encoder SHALL be configured for surveillance use with the following parameters:

**Video Format:**
- **Codec:** H.264 (MIMETYPE_VIDEO_AVC)
- **Profile:** Baseline or Main Profile (for broad compatibility)
- **Level:** Level 4.0 or higher (supports up to 1080p@30fps)

**Encoding Parameters:**
- **Resolution:** 1920x1080 (configurable, matches camera resolution)
- **Frame Rate:** 30 fps (target, may be lower based on camera capabilities)
- **Bitrate:** 2,000,000 bps (2 Mbps) for 1080p
  - Variable bitrate (VBR) preferred for quality
  - CBR acceptable for consistent network usage
- **Keyframe Interval:** 1-2 seconds (30-60 frames)
  - Shorter interval = better seek performance, larger file size
  - Longer interval = better compression, slower segment starts

**Color Format:**
- **Input:** YUV_420_888 (from CameraX ImageProxy)
- **Encoder Format:** COLOR_FormatYUV420Flexible or COLOR_FormatYUV420SemiPlanar (NV12)

**Configuration Example:**
```kotlin
val format = MediaFormat.createVideoFormat(
    MediaFormat.MIMETYPE_VIDEO_AVC,
    width,  // 1920
    height  // 1080
)

// Basic settings
format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

// Quality settings
format.setInteger(MediaFormat.KEY_BITRATE_MODE,
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
format.setInteger(MediaFormat.KEY_COMPLEXITY,
    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)

// Optional: Set profile/level explicitly
format.setInteger(MediaFormat.KEY_PROFILE,
    MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
format.setInteger(MediaFormat.KEY_LEVEL,
    MediaCodecInfo.CodecProfileLevel.AVCLevel4)

encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
encoder.start()
```

---

### REQ-HW-004: Segment Management

**Requirement:** The system SHALL generate and manage HLS segments with the following characteristics:

**Segment Properties:**
- **Duration:** 2 seconds per segment (configurable: 2-6 seconds)
- **Format:** MPEG-TS (Transport Stream) for HLS compatibility
- **Container:** Use MediaMuxer with `MUXER_OUTPUT_MPEG_2_TS`
- **Naming:** `segment{sequence}.ts` (e.g., segment0.ts, segment1.ts)

**Segment Management:**
- **Sliding Window:** Maintain last 10 segments (20 seconds of video)
- **Storage:** App cache directory (`context.cacheDir/hls_segments/`)
- **Cleanup:** Delete segments older than the sliding window
- **Disk Space:** Monitor and ensure at least 50MB free space

**Implementation Pattern:**
```kotlin
class HLSSegmentManager(
    private val cacheDir: File,
    private val segmentDurationSec: Int = 2,
    private val maxSegments: Int = 10
) {
    private val segmentFiles = mutableListOf<File>()
    private var segmentIndex = 0
    
    fun createNewSegment(): File {
        val segmentFile = File(cacheDir, "segment${segmentIndex}.ts")
        segmentFiles.add(segmentFile)
        
        // Cleanup old segments
        while (segmentFiles.size > maxSegments) {
            val old = segmentFiles.removeAt(0)
            old.delete()
        }
        
        segmentIndex++
        return segmentFile
    }
    
    fun generatePlaylist(): String {
        val playlist = StringBuilder()
        playlist.append("#EXTM3U\n")
        playlist.append("#EXT-X-VERSION:3\n")
        playlist.append("#EXT-X-TARGETDURATION:$segmentDurationSec\n")
        playlist.append("#EXT-X-MEDIA-SEQUENCE:${maxOf(0, segmentIndex - maxSegments)}\n")
        
        segmentFiles.forEach { file ->
            playlist.append("#EXTINF:$segmentDurationSec.0,\n")
            playlist.append("${file.name}\n")
        }
        
        return playlist.toString()
    }
}
```

---

### REQ-HW-005: HTTP Endpoints for HLS

**Requirement:** The system SHALL expose the following HTTP endpoints for HLS streaming:

**Primary Endpoints:**

1. **`GET /hls/stream.m3u8`** - Master playlist
   - **MIME Type:** `application/vnd.apple.mpegurl` or `application/x-mpegURL`
   - **Response:** M3U8 playlist with list of available segments
   - **Cache Headers:** `Cache-Control: no-cache` (playlist updates frequently)

2. **`GET /hls/segment{N}.ts`** - Video segment files
   - **MIME Type:** `video/mp2t` (MPEG Transport Stream)
   - **Response:** Binary TS segment data
   - **Cache Headers:** `Cache-Control: public, max-age=60` (segments are immutable)

**Example Playlist Response:**
```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:2
#EXT-X-MEDIA-SEQUENCE:50
#EXTINF:2.0,
segment50.ts
#EXTINF:2.0,
segment51.ts
#EXTINF:2.0,
segment52.ts
...
```

**Endpoint Implementation:**
```kotlin
when (uri) {
    "/hls/stream.m3u8" -> {
        val playlist = hlsEncoder?.generatePlaylist() ?: return@respond newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            MIME_PLAINTEXT,
            "HLS not enabled"
        )
        
        val response = newFixedLengthResponse(Response.Status.OK,
            "application/vnd.apple.mpegurl", playlist)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return@respond response
    }
    
    uri.startsWith("/hls/segment") && uri.endsWith(".ts") -> {
        val segmentName = uri.substringAfterLast("/")
        val segmentFile = File(File(cacheDir, "hls_segments"), segmentName)
        
        if (!segmentFile.exists()) {
            return@respond newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Segment not found"
            )
        }
        
        val response = newFixedLengthResponse(Response.Status.OK,
            "video/mp2t",
            segmentFile.inputStream(),
            segmentFile.length())
        response.addHeader("Cache-Control", "public, max-age=60")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return@respond response
    }
}
```

---

### REQ-HW-006: Latency Considerations

**Requirement:** The system SHALL accept and communicate the latency characteristics of HLS streaming.

**Expected Latency:**
- **Standard HLS:** 6-12 seconds (2-second segments × 3-6 segment buffer)
- **Acceptable for:** Recording, bandwidth-constrained viewing, non-real-time monitoring
- **NOT acceptable for:** Real-time monitoring, motion-triggered alerts, live interaction

**Latency Composition:**
1. Segment creation: 2 seconds (buffering frames)
2. Encoding: <0.5 seconds (hardware acceleration)
3. Network transmission: <0.5 seconds (local network)
4. Client buffering: 4-8 seconds (2-4 segments ahead)
5. **Total: 6-12 seconds**

**Communication:**
- Document latency clearly in README and API documentation
- Add latency warning in web UI when switching to HLS
- Include latency information in `/status` endpoint for HLS streams

---

### REQ-HW-007: Reliability and Error Handling

**Requirement:** The system SHALL implement robust error handling for HLS encoding and streaming.

**Error Scenarios and Handling:**

1. **Encoder Initialization Failure:**
   - Log error with encoder name and capabilities
   - Attempt software fallback encoder
   - If all encoders fail, disable HLS and log critical error
   - Continue MJPEG streaming normally

2. **Encoding Error During Operation:**
   - Catch `MediaCodec.CodecException`
   - Log frame number and error details
   - Attempt to recover by restarting encoder
   - Use exponential backoff (1s, 2s, 4s, 8s, max 30s)
   - After 5 failed attempts, disable HLS until restart

3. **Segment Write Failure:**
   - Catch IOException during MediaMuxer operations
   - Check disk space availability
   - Clean up old segments more aggressively
   - If space < 10MB, stop HLS and alert via logs
   - Continue MJPEG streaming

4. **Client Request for Missing Segment:**
   - Return HTTP 404 Not Found
   - Log segment request and available segments
   - Client should request updated playlist

**Watchdog Integration:**
```kotlin
private fun checkHLSHealth(): Boolean {
    // Check encoder is alive
    if (hlsEncoder?.isAlive() == false) {
        Log.w(TAG, "HLS encoder dead, restarting...")
        restartHLSEncoder()
        return false
    }
    
    // Check segment freshness (should have segment from last 5 seconds)
    val latestSegment = segmentManager.getLatestSegment()
    if (latestSegment != null && 
        System.currentTimeMillis() - latestSegment.lastModified() > 5000) {
        Log.w(TAG, "HLS segments stale, encoder may be stuck")
        return false
    }
    
    return true
}
```

---

### REQ-HW-008: Performance Monitoring

**Requirement:** The system SHALL monitor and report HLS encoder performance metrics.

**Metrics to Track:**
- Encoding time per frame (ms)
- Frames encoded per second
- Keyframe interval (actual vs target)
- Bitrate (actual vs target)
- Segment creation time
- Disk space used by segments
- Number of active HLS clients

**Status Endpoint Enhancement:**
```json
{
  "status": "running",
  "streaming": {
    "mjpeg": {
      "enabled": true,
      "clients": 2,
      "bandwidth": "8.5 Mbps"
    },
    "hls": {
      "enabled": true,
      "clients": 3,
      "encoderType": "OMX.qcom.video.encoder.avc",
      "resolution": "1920x1080",
      "bitrate": 2000000,
      "targetFps": 30,
      "actualFps": 28.5,
      "avgEncodingTimeMs": 18.3,
      "keyframeInterval": 30,
      "segmentDuration": 2,
      "activeSegments": 10,
      "diskSpaceUsedMB": 4.2,
      "latencyEstimateMs": 8000
    }
  }
}
```

---

### REQ-HW-009: Integration with Existing Architecture

**Requirement:** HLS streaming SHALL be implemented alongside MJPEG without disrupting existing functionality.

**Integration Points:**

1. **CameraService Modifications:**
   - Add `HLSEncoderManager` component (optional, lazy initialization)
   - Feed camera frames to both MJPEG and HLS pipelines
   - Both pipelines process independently from same ImageProxy source

2. **Frame Distribution:**
```kotlin
private fun processImage(image: ImageProxy) {
    try {
        // Existing MJPEG pipeline (always active)
        val bitmap = imageProxyToBitmap(image)
        val annotated = annotateBitmap(bitmap)
        val jpegBytes = compressToJpeg(annotated, currentQuality)
        synchronized(jpegLock) {
            lastFrameJpegBytes = jpegBytes
        }
        
        // Optional HLS pipeline (if enabled)
        if (hlsEnabled && hlsEncoder != null) {
            // Feed raw YUV to HLS encoder (no annotation for efficiency)
            hlsEncoder.encodeFrame(image)
        }
        
    } finally {
        image.close()
    }
}
```

3. **Settings Persistence:**
   - Add `hlsEnabled` to SharedPreferences
   - Add `hlsBitrate`, `hlsSegmentDuration` settings
   - Restore HLS state on service restart

4. **Control Endpoints:**
   - `/enableHLS` - Enable HLS streaming
   - `/disableHLS` - Disable HLS streaming
   - `/setHLSBitrate?value=2000000` - Adjust HLS bitrate
   - `/setHLSSegmentDuration?value=2` - Adjust segment duration

---

### REQ-HW-010: Compatibility Testing

**Requirement:** HLS implementation SHALL be tested with common clients and surveillance systems.

**Test Matrix:**

| Client/System | Test Type | Expected Result |
|---------------|-----------|-----------------|
| **Safari (macOS/iOS)** | Native playback | Works without hls.js |
| **Chrome/Firefox** | hls.js playback | Requires hls.js library |
| **VLC Media Player** | Network stream | Direct HLS playback |
| **Shinobi NVR** | HLS input | Recording and playback |
| **Blue Iris** | HLS camera | Integration and recording |
| **FFmpeg** | HLS download | `ffmpeg -i http://.../stream.m3u8 output.mp4` |

**Web UI Test:**
```html
<video id="hlsPlayer" controls style="width: 100%; max-width: 1280px;">
    <source src="/hls/stream.m3u8" type="application/x-mpegURL">
</video>

<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<script>
    if (Hls.isSupported()) {
        var video = document.getElementById('hlsPlayer');
        var hls = new Hls();
        hls.loadSource('/hls/stream.m3u8');
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, function() {
            video.play();
        });
    }
</script>
```

---

### REQ-HW-011: Documentation Requirements

**Requirement:** Complete documentation SHALL be provided for HLS streaming feature.

**Documentation Must Include:**

1. **User Guide:**
   - How to enable/disable HLS streaming
   - Comparison table: MJPEG vs HLS
   - When to use each streaming method
   - Latency expectations and use cases
   - Client configuration examples

2. **API Documentation:**
   - All HLS endpoints with request/response examples
   - Error codes and handling
   - Client integration examples (web, VLC, NVR)

3. **Technical Documentation:**
   - Architecture diagrams showing dual-stream design
   - Encoder selection and configuration details
   - Performance characteristics and benchmarks
   - Troubleshooting guide

4. **Integration Examples:**
   - Shinobi configuration
   - Blue Iris configuration
   - VLC playback commands
   - Web browser integration code

---

### Implementation Priority and Roadmap

**Phase 1: Foundation (Week 1-2)**
- Implement MediaCodec encoder detection and initialization
- Create basic H.264 encoding pipeline
- Test single segment creation and playback

**Phase 2: Segmentation (Week 2-3)**
- Implement HLSSegmentManager
- Add sliding window and cleanup logic
- Generate M3U8 playlists

**Phase 3: Integration (Week 3-4)**
- Integrate with CameraService
- Add HTTP endpoints for HLS
- Implement enable/disable controls

**Phase 4: Optimization (Week 4-5)**
- Performance tuning (bitrate, quality)
- Error handling and recovery
- Monitoring and metrics

**Phase 5: Testing (Week 5-6)**
- Client compatibility testing
- Stress testing with multiple clients
- Long-term reliability testing

**Phase 6: Documentation (Week 6)**
- Complete user and technical documentation
- Integration examples and guides
- Performance benchmarks

**Total Estimated Effort:** 6-8 weeks for complete implementation

---

### Success Criteria

**The HLS implementation is considered successful when:**

✅ Hardware encoder is detected and used on supported devices  
✅ H.264 encoding achieves 50-75% bandwidth reduction vs MJPEG  
✅ Segments are generated reliably with 2-second duration  
✅ Playlist is valid and playable in Safari, VLC, and modern browsers  
✅ Multiple simultaneous HLS clients are supported (10+ clients)  
✅ Disk space is managed automatically with segment cleanup  
✅ Error recovery is robust (survives encoder crashes)  
✅ MJPEG streaming continues to work perfectly alongside HLS  
✅ Performance overhead is acceptable (<10% CPU increase)  
✅ Latency is consistent at 6-12 seconds as expected  
✅ Documentation is complete and accurate  

---

### Summary: Why This Approach

This requirements section provides a complete blueprint for implementing hardware-encoded modern streaming while:

1. **Preserving MJPEG** as the primary, low-latency streaming method
2. **Adding HLS as optional** for bandwidth-constrained use cases
3. **Leveraging hardware encoding** for efficiency and quality
4. **Maintaining compatibility** with existing surveillance systems
5. **Ensuring reliability** through robust error handling
6. **Providing flexibility** for users to choose based on their needs

The dual-stream architecture ensures that users get the best of both worlds: low-latency MJPEG for real-time monitoring and bandwidth-efficient HLS for recording and remote viewing.

---

## MP4 Segment Streaming Analysis

### Feasibility: Caching & Re-encoding to MP4

**Question:** Would it be feasible to cache several seconds of stream, re-encode as MP4, and stream segments?

**Answer:** Yes, it's feasible with **HLS (HTTP Live Streaming)** or **DASH**, but it comes with significant tradeoffs.

### HLS (HTTP Live Streaming) Architecture

#### How HLS Works

1. **Segment Creation:**
   - Cache 2-10 seconds of video
   - Encode to H.264 MP4 segments
   - Create M3U8 playlist file

2. **Segment Delivery:**
   - Client requests playlist (.m3u8)
   - Client downloads segments sequentially
   - Client plays segments in order

3. **Example Structure:**
```
stream.m3u8:
#EXTM3U
#EXT-X-TARGETDURATION:2
#EXT-X-VERSION:3
#EXTINF:2.0,
segment0.ts
#EXTINF:2.0,
segment1.ts
#EXTINF:2.0,
segment2.ts
```

### Technical Requirements for MP4/HLS Streaming

#### 1. **Video Encoder (H.264/H.265)**
**Required:** Android `MediaCodec` for hardware-accelerated encoding

```kotlin
val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
val format = MediaFormat.createVideoFormat(
    MediaFormat.MIMETYPE_VIDEO_AVC,
    width,
    height
)
format.setInteger(MediaFormat.KEY_COLOR_FORMAT, 
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000) // 2 Mbps
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second
```

#### 2. **Container Muxer**
**Required:** Android `MediaMuxer` to create MP4/TS segments

```kotlin
val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
val trackIndex = muxer.addTrack(format)
muxer.start()
// Write encoded frames
muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
muxer.stop()
muxer.release()
```

#### 3. **Segment Management**
**Required:** Logic to create, serve, and cleanup segments

```kotlin
class HLSSegmentManager {
    private val segments = mutableListOf<Segment>()
    private val maxSegments = 10 // Keep last 10 segments
    
    fun createSegment(duration: Long): Segment {
        val segment = Segment(index++, duration)
        segments.add(segment)
        
        // Cleanup old segments
        while (segments.size > maxSegments) {
            val old = segments.removeAt(0)
            deleteFile(old.path)
        }
        
        return segment
    }
    
    fun generatePlaylist(): String {
        // Generate M3U8 playlist
    }
}
```

### Performance Impact: MJPEG vs HLS

| Aspect | MJPEG (Current) | HLS (MP4 Segments) |
|--------|-----------------|-------------------|
| **Bandwidth** | ~8 Mbps @ 10fps | ~2-4 Mbps @ 30fps |
| **Latency** | ~150-280ms | ~6-12 seconds |
| **CPU Usage** | Medium | High (encoding) |
| **Compatibility** | Universal | Modern browsers |
| **Complexity** | Low | High |
| **Quality** | Good | Excellent |
| **Adaptive Bitrate** | No | Yes |

### Bandwidth Reduction Analysis

**MJPEG (Current):**
```
10 fps × 100 KB/frame = 1,000 KB/s = 8 Mbps
```

**HLS with H.264:**
```
30 fps @ 2 Mbps = 2 Mbps total
Reduction: 75% less bandwidth
```

**Why H.264 is more efficient:**
1. **Inter-frame compression:** Only encodes differences between frames
2. **Motion compensation:** Reuses similar blocks from previous frames
3. **Better compression:** DCT + quantization + entropy coding
4. **Keyframe strategy:** Full frames only every 1-2 seconds

**MJPEG disadvantage:**
- Every frame is a complete JPEG (no inter-frame compression)
- Redundant data in every frame
- No motion compensation

### Latency Impact

**MJPEG Latency:** ~150-280ms
- Immediate frame delivery
- No buffering required

**HLS Latency:** ~6-12 seconds
- Must buffer full segments (2-6 seconds each)
- Client needs 2-3 segments buffered
- Not suitable for real-time use

**HLS Low-Latency Variants:**
- **LL-HLS (Apple):** Can achieve ~2-3 seconds
- **CMAF + Chunked Transfer:** Can achieve ~1-2 seconds
- **Requires:** More complex implementation

### Server-Side Changes Required

#### 1. **Replace Camera Processing Pipeline**
**Current:** Direct JPEG compression
**New:** Feed frames to MediaCodec

```kotlin
// New encoder pipeline
private lateinit var videoEncoder: MediaCodec
private lateinit var segmentMuxer: MediaMuxer
private var currentSegmentStart: Long = 0
private val SEGMENT_DURATION_MS = 2000L // 2 seconds

fun initEncoder() {
    videoEncoder = MediaCodec.createEncoderByType("video/avc")
    // Configure encoder
    videoEncoder.start()
}

fun processFrameForHLS(image: ImageProxy) {
    // Convert YUV to encoder input format
    val inputBuffer = videoEncoder.getInputBuffer(inputBufferIndex)
    // Fill buffer with YUV data
    videoEncoder.queueInputBuffer(...)
    
    // Check if segment is complete
    if (System.currentTimeMillis() - currentSegmentStart > SEGMENT_DURATION_MS) {
        finalizeSegment()
        startNewSegment()
    }
}
```

#### 2. **New HTTP Endpoints**
```
GET /hls/stream.m3u8      - Master playlist
GET /hls/segment{N}.ts    - Video segments
GET /hls/segment{N}.m4s   - CMAF segments (optional)
```

#### 3. **Segment Storage & Cleanup**
```kotlin
class SegmentStorage {
    private val segmentDir = File(context.cacheDir, "hls_segments")
    private val maxSegments = 10
    
    fun cleanup() {
        val segments = segmentDir.listFiles() ?: return
        val sorted = segments.sortedBy { it.lastModified() }
        
        if (sorted.size > maxSegments) {
            sorted.take(sorted.size - maxSegments).forEach { it.delete() }
        }
    }
}
```

#### 4. **Concurrent Support for MJPEG + HLS**
```kotlin
// Support both streaming methods simultaneously
when (uri) {
    "/stream" -> serveMJPEG()      // Legacy/compatibility
    "/hls/stream.m3u8" -> serveHLSPlaylist()  // New method
    "/hls/segment*.ts" -> serveHLSSegment()
}
```

### Client-Side Changes Required

**MJPEG (Current):** Simple `<img>` tag
```html
<img src="http://192.168.1.100:8080/stream" />
```

**HLS:** Requires video player with HLS support
```html
<video id="player" controls>
    <source src="http://192.168.1.100:8080/hls/stream.m3u8" type="application/x-mpegURL">
</video>

<script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
<script>
    if (Hls.isSupported()) {
        var video = document.getElementById('player');
        var hls = new Hls();
        hls.loadSource('http://192.168.1.100:8080/hls/stream.m3u8');
        hls.attachMedia(video);
    }
</script>
```

### Storage Requirements

**MJPEG (Current):**
- No storage needed (live stream only)
- Memory: One frame (~100 KB)

**HLS:**
- Storage: 10 segments × 2 seconds × 2 Mbps = ~5 MB
- Continuous write operations to storage
- Need cleanup logic to prevent filling storage

### Compatibility Concerns

| Client Type | MJPEG Support | HLS Support |
|-------------|---------------|-------------|
| **Web Browsers** | ✅ Universal | ✅ Modern (Safari native, others via hls.js) |
| **VLC Media Player** | ✅ Yes | ✅ Yes |
| **ZoneMinder** | ✅ Yes (preferred) | ⚠️ Possible but not typical |
| **Shinobi** | ✅ Yes | ✅ Yes |
| **Blue Iris** | ✅ Yes | ✅ Yes |
| **IP Camera Viewers** | ✅ Universal | ⚠️ Mixed support |
| **FFmpeg/curl** | ✅ Yes | ✅ Yes |

**Verdict:** MJPEG has better universal compatibility for surveillance systems

---

## Deep Dive: Hardware Encoding & Implementation Details

This section provides detailed technical information about hardware-accelerated H.264 encoding on Android and potential implementation approaches for HLS/MP4 streaming.

### Hardware Encoding Availability on Android

#### MediaCodec Hardware Acceleration

**Yes, hardware encoding is widely available on Android devices** through the `MediaCodec` API, which has been part of Android since API level 16 (Android 4.1). Modern devices (especially since Android 7.0+) have reliable hardware encoder support.

**Checking Hardware Encoder Availability:**

```kotlin
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat

fun checkHardwareEncoderSupport(): EncoderCapabilities {
    val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    
    for (codecInfo in codecList.codecInfos) {
        if (!codecInfo.isEncoder) continue
        
        for (type in codecInfo.supportedTypes) {
            if (type.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)) {
                // Found H.264 encoder
                val isHardware = !codecInfo.name.contains("OMX.google", ignoreCase = true) &&
                                !codecInfo.name.contains("c2.android", ignoreCase = true)
                
                val capabilities = codecInfo.getCapabilitiesForType(type)
                val colorFormats = capabilities.colorFormats
                val videoCapabilities = capabilities.videoCapabilities
                
                return EncoderCapabilities(
                    codecName = codecInfo.name,
                    isHardware = isHardware,
                    maxWidth = videoCapabilities.supportedWidths.upper,
                    maxHeight = videoCapabilities.supportedHeights.upper,
                    maxFrameRate = videoCapabilities.supportedFrameRates.upper.toInt(),
                    supportedColorFormats = colorFormats.toList(),
                    bitrateRange = videoCapabilities.bitrateRange
                )
            }
        }
    }
    
    return EncoderCapabilities(codecName = "none", isHardware = false)
}

data class EncoderCapabilities(
    val codecName: String,
    val isHardware: Boolean,
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Int = 0,
    val supportedColorFormats: List<Int> = emptyList(),
    val bitrateRange: android.util.Range<Int>? = null
)
```

**Typical Hardware Encoders by Chipset:**

| Chipset Vendor | Encoder Name | Typical Capabilities |
|----------------|--------------|---------------------|
| **Qualcomm** | OMX.qcom.video.encoder.avc | H.264 up to 4K@60fps |
| **Samsung Exynos** | OMX.Exynos.AVC.Encoder | H.264 up to 4K@30fps |
| **MediaTek** | OMX.MTK.VIDEO.ENCODER.AVC | H.264 up to 1080p@60fps |
| **HiSilicon (Huawei)** | OMX.hisi.video.encoder.avc | H.264 up to 4K@30fps |
| **NVIDIA Tegra** | OMX.Nvidia.h264.encoder | H.264 up to 4K@60fps |

**Software Fallback:**
- Google's software encoder: `OMX.google.h264.encoder` or `c2.android.avc.encoder`
- Always available but much slower (5-10x CPU usage)
- Usually limited to 1080p@30fps

#### Performance Characteristics

**Hardware Encoder Benefits:**
- ✅ **Low CPU usage:** 5-15% for 1080p@30fps (vs 60-80% for software)
- ✅ **Low power consumption:** Dedicated silicon, minimal battery impact
- ✅ **Real-time encoding:** Can sustain 30-60 fps without dropping frames
- ✅ **High quality:** Modern encoders support high profiles, good rate control

**Hardware Encoder Limitations:**
- ⚠️ **Limited control:** Less flexibility than software encoders
- ⚠️ **Vendor differences:** Quality and capabilities vary by manufacturer
- ⚠️ **Format restrictions:** May require specific YUV formats (NV12, NV21)
- ⚠️ **Latency:** Typically 1-3 frame latency for encoding pipeline

### Complete HLS Implementation with Hardware Encoding

#### Architecture Overview

```
┌─────────────────────┐
│   CameraX Frames    │ (YUV_420_888)
│   ImageAnalysis     │
└──────────┬──────────┘
           │
           │ 30 fps
           ▼
┌─────────────────────────────────┐
│  MediaCodec Hardware Encoder    │
│  (H.264 encoding)                │
├─────────────────────────────────┤
│ Input: YUV buffer                │
│ Output: H.264 NAL units          │
│ Config: 1080p@30fps, 2Mbps      │
└──────────┬──────────────────────┘
           │
           │ Encoded frames
           ▼
┌─────────────────────────────────┐
│  MediaMuxer                      │
│  (Segment Creation)              │
├─────────────────────────────────┤
│ Buffer frames for 2-6 seconds    │
│ Mux to MP4/TS segments           │
│ Write to cache directory         │
└──────────┬──────────────────────┘
           │
           │ Segment files
           ▼
┌─────────────────────────────────┐
│  Segment Manager                 │
│  (Playlist & Cleanup)            │
├─────────────────────────────────┤
│ Generate M3U8 playlist           │
│ Manage sliding window (10 segs) │
│ Delete old segments              │
└──────────┬──────────────────────┘
           │
           │ HTTP requests
           ▼
┌─────────────────────────────────┐
│  NanoHTTPD Server                │
│  (HLS Endpoints)                 │
├─────────────────────────────────┤
│ /hls/stream.m3u8 → playlist      │
│ /hls/segment{N}.ts → video       │
└─────────────────────────────────┘
```

#### Detailed Implementation Code

**1. HLS Encoder Manager**

```kotlin
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer

class HLSEncoderManager(
    private val cacheDir: File,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val bitrate: Int = 2_000_000, // 2 Mbps
    private val segmentDurationSec: Int = 2
) {
    private lateinit var encoder: MediaCodec
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var segmentIndex: Int = 0
    private var segmentStartTime: Long = 0
    private val segmentFiles = mutableListOf<File>()
    private val maxSegments = 10
    
    private var isEncoding = false
    private var frameCount = 0
    
    fun start() {
        // Create output format for H.264
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )
        
        // Configure encoder settings
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second
        
        // Advanced encoder settings for better quality
        format.setInteger(MediaFormat.KEY_BITRATE_MODE, 
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) // Variable bitrate
        format.setInteger(MediaFormat.KEY_COMPLEXITY,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        
        // Try to create hardware encoder first
        encoder = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create hardware encoder, using software fallback", e)
            MediaCodec.createByCodecName("OMX.google.h264.encoder")
        }
        
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        
        isEncoding = true
        segmentStartTime = System.currentTimeMillis()
        startNewSegment()
        
        Log.d(TAG, "HLS encoder started with codec: ${encoder.name}")
    }
    
    fun encodeFrame(image: ImageProxy) {
        if (!isEncoding) return
        
        try {
            // Get input buffer from encoder
            val inputBufferIndex = encoder.dequeueInputBuffer(10_000) // 10ms timeout
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                
                // Convert ImageProxy YUV to encoder format
                inputBuffer?.let {
                    fillInputBuffer(it, image)
                    
                    val presentationTimeUs = frameCount * 1_000_000L / fps
                    encoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        it.limit(),
                        presentationTimeUs,
                        0
                    )
                    frameCount++
                }
            }
            
            // Retrieve encoded output
            drainEncoder(false)
            
            // Check if segment is complete
            val segmentDuration = System.currentTimeMillis() - segmentStartTime
            if (segmentDuration >= segmentDurationSec * 1000L) {
                finalizeSegment()
                startNewSegment()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding frame", e)
        }
    }
    
    private fun fillInputBuffer(buffer: ByteBuffer, image: ImageProxy) {
        // Convert YUV_420_888 to NV12 (or NV21) format expected by encoder
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        
        // Copy Y plane
        buffer.put(yPlane.buffer)
        
        // Interleave U and V planes for NV12 format
        val uvBuffer = ByteBuffer.allocate(uPlane.buffer.remaining() + vPlane.buffer.remaining())
        
        // NV12: UVUV... interleaved
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        while (uBuffer.hasRemaining() && vBuffer.hasRemaining()) {
            uvBuffer.put(uBuffer.get())
            uvBuffer.put(vBuffer.get())
        }
        
        uvBuffer.flip()
        buffer.put(uvBuffer)
        buffer.flip()
    }
    
    private fun startNewSegment() {
        val segmentFile = File(cacheDir, "segment${segmentIndex}.ts")
        
        muxer = MediaMuxer(
            segmentFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_2_TS // MPEG-TS for HLS
        )
        
        // Add video track (format already configured)
        val format = encoder.outputFormat
        videoTrackIndex = muxer!!.addTrack(format)
        muxer!!.start()
        
        segmentFiles.add(segmentFile)
        segmentStartTime = System.currentTimeMillis()
        
        Log.d(TAG, "Started new segment: ${segmentFile.name}")
    }
    
    private fun finalizeSegment() {
        drainEncoder(true)
        
        muxer?.stop()
        muxer?.release()
        muxer = null
        
        // Cleanup old segments (keep only last maxSegments)
        while (segmentFiles.size > maxSegments) {
            val oldFile = segmentFiles.removeAt(0)
            oldFile.delete()
            Log.d(TAG, "Deleted old segment: ${oldFile.name}")
        }
        
        segmentIndex++
    }
    
    private fun drainEncoder(endOfStream: Boolean) {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Format changed, update muxer if needed
                    val newFormat = encoder.outputFormat
                    Log.d(TAG, "Encoder output format changed: $newFormat")
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                    
                    if (encodedData != null && bufferInfo.size > 0) {
                        // Write to muxer
                        muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    
                    encoder.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }
    
    fun stop() {
        isEncoding = false
        finalizeSegment()
        encoder.stop()
        encoder.release()
    }
    
    fun generatePlaylist(): String {
        val playlist = StringBuilder()
        playlist.append("#EXTM3U\n")
        playlist.append("#EXT-X-VERSION:3\n")
        playlist.append("#EXT-X-TARGETDURATION:$segmentDurationSec\n")
        playlist.append("#EXT-X-MEDIA-SEQUENCE:${maxOf(0, segmentIndex - maxSegments)}\n")
        
        segmentFiles.forEach { file ->
            playlist.append("#EXTINF:$segmentDurationSec.0,\n")
            playlist.append("${file.name}\n")
        }
        
        return playlist.toString()
    }
    
    companion object {
        private const val TAG = "HLSEncoderManager"
    }
}
```

**2. Integration with CameraService**

```kotlin
// Add to CameraService.kt

private var hlsEncoder: HLSEncoderManager? = null
private var enableHLS = false // Toggle via settings or API

private fun processImage(image: ImageProxy) {
    try {
        // Existing MJPEG pipeline
        val bitmap = imageProxyToBitmap(image)
        // ... existing code ...
        
        // Also feed to HLS encoder if enabled
        if (enableHLS) {
            hlsEncoder?.encodeFrame(image)
        }
        
    } catch (e: Exception) {
        Log.e(TAG, "Error processing image", e)
    } finally {
        image.close()
    }
}

fun enableHLSStreaming() {
    val cacheDir = File(cacheDir, "hls_segments")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    
    hlsEncoder = HLSEncoderManager(cacheDir, width = 1920, height = 1080)
    hlsEncoder?.start()
    enableHLS = true
}

fun disableHLSStreaming() {
    enableHLS = false
    hlsEncoder?.stop()
    hlsEncoder = null
}

// Add HLS endpoints to CameraHttpServer
private fun serveHLSPlaylist(): Response {
    val playlist = hlsEncoder?.generatePlaylist() ?: return newFixedLengthResponse(
        Response.Status.SERVICE_UNAVAILABLE,
        MIME_PLAINTEXT,
        "HLS not enabled"
    )
    
    return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", playlist)
}

private fun serveHLSSegment(segmentName: String): Response {
    val segmentFile = File(File(cacheDir, "hls_segments"), segmentName)
    
    if (!segmentFile.exists()) {
        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            MIME_PLAINTEXT,
            "Segment not found"
        )
    }
    
    return newFixedLengthResponse(
        Response.Status.OK,
        "video/mp2t", // MPEG-TS MIME type
        segmentFile.inputStream(),
        segmentFile.length()
    )
}
```

### Pass-Through Recording Approach

The user asked: **"Would Android actually directly deliver a kind of MP4 stream if using e.g. onboard recording functionality so one could also use a pass-through approach?"**

**Answer:** Yes, this is possible with MediaRecorder, but with important caveats.

#### Using MediaRecorder for Pass-Through

Android's `MediaRecorder` can encode and write video directly to a file or socket, which could theoretically be used for streaming:

```kotlin
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.net.Socket

class MediaRecorderStreamingApproach(
    private val surface: Surface
) {
    private var mediaRecorder: MediaRecorder? = null
    
    fun startRecordingToSocket(socket: Socket) {
        mediaRecorder = MediaRecorder().apply {
            // Set video source
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            
            // Set output format
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            // Set encoder
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            
            // Set encoding parameters
            setVideoSize(1920, 1080)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(2_000_000)
            
            // Output to socket
            val pfd = ParcelFileDescriptor.fromSocket(socket)
            setOutputFile(pfd.fileDescriptor)
            
            prepare()
            start()
        }
    }
    
    fun stop() {
        mediaRecorder?.stop()
        mediaRecorder?.release()
        mediaRecorder = null
    }
}
```

#### Limitations of MediaRecorder Pass-Through

**Why this approach is problematic for live streaming:**

1. **Container Format Issues:**
   - MediaRecorder writes MP4 with `moov` atom at the end of file
   - This means the file isn't playable until recording stops
   - Not suitable for live streaming (client can't play until complete)

2. **No Segmentation:**
   - MediaRecorder creates a single continuous file
   - No automatic segment creation for HLS
   - Can't implement sliding window of segments

3. **Limited Control:**
   - Can't access individual frames or NAL units
   - No ability to insert timestamps or metadata
   - Can't adjust quality dynamically

4. **Fragmented MP4 Workaround:**
   ```kotlin
   // Using fragmented MP4 (fMP4) for streaming
   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
       mediaRecorder.setOutputFormat(
           MediaRecorder.OutputFormat.MPEG_4
       )
       // This creates fragmented MP4 suitable for progressive download
       // But still not ideal for HLS/DASH
   }
   ```

#### Better Approach: MediaCodec + Manual Muxing

**Recommended:** Use MediaCodec directly (as shown earlier) instead of MediaRecorder because:

✅ **Frame-level access:** Can process each encoded frame  
✅ **Flexible muxing:** Control when and how segments are created  
✅ **Multiple outputs:** Can serve MJPEG and HLS simultaneously  
✅ **Adaptive quality:** Can adjust encoding parameters in real-time  
✅ **Metadata control:** Can add custom metadata, timestamps, etc.  

### Dual-Stream Architecture (Recommended)

For maximum flexibility, implement **both MJPEG and HLS simultaneously**:

```kotlin
class DualStreamCameraService : Service() {
    // MJPEG pipeline (existing)
    private var lastFrameJpegBytes: ByteArray? = null
    
    // HLS pipeline (new)
    private var hlsEncoder: HLSEncoderManager? = null
    
    private fun processImage(image: ImageProxy) {
        try {
            // Path 1: MJPEG (low latency, compatibility)
            val bitmap = imageProxyToBitmap(image)
            val annotated = annotateBitmap(bitmap)
            val jpegBytes = compressToJpeg(annotated, 75)
            synchronized(jpegLock) {
                lastFrameJpegBytes = jpegBytes
            }
            
            // Path 2: HLS (bandwidth efficient, recording)
            if (enableHLS) {
                // Send raw YUV to hardware encoder
                hlsEncoder?.encodeFrame(image)
            }
            
        } finally {
            image.close()
        }
    }
}
```

**Benefits of Dual-Stream:**
- ✅ Clients choose based on needs (latency vs bandwidth)
- ✅ Existing surveillance systems continue working (MJPEG)
- ✅ Modern clients can use HLS for efficiency
- ✅ Recording uses HLS segments (native MP4 format)
- ✅ No need to choose one approach

### Motion Detection with HLS Recording

Since latency isn't an issue for your use case, HLS is perfect for motion-triggered recording:

```kotlin
class MotionDetectionRecorder(
    private val hlsEncoder: HLSEncoderManager
) {
    private var isRecording = false
    private val recordingSegments = mutableListOf<File>()
    
    fun onMotionDetected() {
        if (!isRecording) {
            isRecording = true
            recordingSegments.clear()
            Log.d(TAG, "Motion detected - started recording")
        }
    }
    
    fun onMotionStopped() {
        if (isRecording) {
            isRecording = false
            
            // Merge recorded segments into single MP4 file
            mergeSegmentsToFile()
            
            Log.d(TAG, "Motion stopped - saved recording")
        }
    }
    
    private fun mergeSegmentsToFile() {
        val outputFile = File(recordingsDir, "motion_${System.currentTimeMillis()}.mp4")
        
        // Use MediaMuxer to combine segments
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        // Copy all segments into final file
        recordingSegments.forEach { segment ->
            // Remux from TS to MP4
            // (Implementation details omitted for brevity)
        }
        
        muxer.stop()
        muxer.release()
    }
}
```

### Performance Comparison: MediaCodec vs MediaRecorder

| Aspect | MediaCodec (Manual) | MediaRecorder (Pass-Through) |
|--------|-------------------|----------------------------|
| **Control** | ✅ Full frame access | ❌ Black box |
| **Segmentation** | ✅ Manual control | ❌ Single file only |
| **HLS Support** | ✅ Can implement | ❌ Not suitable |
| **Latency** | ✅ Low (1-3 frames) | ⚠️ Higher (buffering) |
| **Quality Control** | ✅ Per-frame adjustment | ⚠️ Fixed at start |
| **Multiple Outputs** | ✅ Easy | ❌ Difficult |
| **Complexity** | ⚠️ Higher | ✅ Simpler |
| **Flexibility** | ✅ Very high | ⚠️ Limited |

**Verdict for IP_Cam:** Use MediaCodec for maximum flexibility and HLS support.

### Resource Usage: MJPEG + HLS Dual Mode

**Expected resource usage when running both:**

| Resource | MJPEG Only | HLS Only | Both (Dual Mode) |
|----------|-----------|----------|------------------|
| **CPU Usage** | 20-30% | 10-15% | 25-35% |
| **Memory** | 30-50 MB | 40-60 MB | 60-90 MB |
| **Storage I/O** | None | 2-4 MB/s | 2-4 MB/s |
| **Network (per client)** | 8 Mbps | 2 Mbps | Varies |
| **Battery Impact** | Medium | Low | Medium |

**Notes:**
- HLS uses less CPU due to hardware encoding efficiency
- Storage I/O only active when HLS is enabled
- Memory overhead is manageable on modern devices
- Can disable MJPEG when only HLS clients connected (saves 10-15% CPU)

### Implementation Roadmap for HLS

**Phase 1: Proof of Concept (1-2 weeks)**
1. ✅ Check hardware encoder availability
2. ✅ Implement basic MediaCodec encoder
3. ✅ Create single segment and test playback
4. ✅ Verify hardware acceleration is working

**Phase 2: HLS Pipeline (2-3 weeks)**
1. ✅ Implement segment rotation (sliding window)
2. ✅ Generate M3U8 playlists
3. ✅ Add HTTP endpoints for HLS
4. ✅ Test with VLC and web browsers

**Phase 3: Integration (1-2 weeks)**
1. ✅ Integrate with existing CameraService
2. ✅ Add enable/disable controls
3. ✅ Implement dual-stream mode
4. ✅ Add UI toggle for HLS streaming

**Phase 4: Optimization (1-2 weeks)**
1. ✅ Tune encoder parameters for quality
2. ✅ Implement adaptive bitrate (if needed)
3. ✅ Optimize segment management
4. ✅ Add telemetry and monitoring

**Phase 5: Recording Features (1-2 weeks)**
1. ✅ Motion detection integration
2. ✅ Segment merging for recordings
3. ✅ Storage management and cleanup
4. ✅ Playback interface for recordings

**Total estimated effort:** 6-11 weeks for complete implementation

---

## Recommendations

### Short-Term Optimizations (Low Effort, High Value)

**Note:** Focus on MJPEG optimizations first, as it remains the primary streaming method.

#### 1. **Implement Adaptive Frame Rate** ⭐⭐⭐
**Effort:** Medium | **Impact:** High | **Risk:** Low

Add network-aware frame rate adjustment:
- Monitor send duration per frame
- Increase delay if network is slow
- Decrease delay if network is fast
- Per-client frame rate control

**Benefits:**
- Better experience on slow networks
- Reduced packet loss
- Lower CPU usage during congestion

#### 2. **Add Quality/Resolution Parameters** ⭐⭐⭐
**Effort:** Low | **Impact:** High | **Risk:** Low

Add URL parameters for client control:
```
/stream?quality=low     (50% JPEG quality)
/stream?quality=medium  (75% JPEG quality, default)
/stream?quality=high    (85% JPEG quality)

/stream?resolution=480p
/stream?resolution=720p
/stream?resolution=1080p (default)
```

**Benefits:**
- Bandwidth reduction for mobile clients
- Better support for multiple concurrent clients
- User control over quality vs bandwidth tradeoff

#### 3. **Remove Annotation from HTTP Stream** ⭐⭐
**Effort:** Low | **Impact:** Medium | **Risk:** Low

Keep annotation only for local preview:
- Saves CPU cycles on overlay rendering
- Reduces memory allocations
- Faster frame processing

**Benefits:**
- ~5-10% CPU reduction
- Faster frame rate possible
- Lower memory pressure

#### 4. **Implement Frame Skipping for Slow Clients** ⭐⭐
**Effort:** Medium | **Impact:** Medium | **Risk:** Medium

Track client read speed and skip frames if needed:
```kotlin
if (clientBufferFull) {
    skipFrames = true
    while (skipFrames && newFrameAvailable) {
        // Skip to latest frame
    }
}
```

**Benefits:**
- Prevents slow clients from blocking others
- Better overall system stability
- Maintains low latency for fast clients

### Medium-Term Enhancements (Medium Effort, High Value)

#### 5. **Add HLS Streaming Option** ⭐⭐⭐
**Effort:** High | **Impact:** Very High | **Risk:** Medium

Implement H.264/HLS streaming alongside MJPEG:
- Keep MJPEG for compatibility and low latency
- Add HLS for bandwidth efficiency
- Client can choose based on needs

**Complete implementation requirements are documented in the [Requirements for Hardware-Encoded Modern Streaming](#requirements-for-hardware-encoded-modern-streaming) section above.**

**Benefits:**
- 50-75% bandwidth reduction
- Better video quality
- Adaptive bitrate streaming
- Modern surveillance system support

**Drawbacks:**
- Higher implementation complexity
- Increased latency (6-12 seconds)
- More CPU usage for encoding
- Storage requirements for segments

**Recommended approach:**
- Implement as optional feature
- Keep MJPEG as default
- Add `/hls/stream.m3u8` endpoint
- Document use cases for each method

#### 6. **Optimize YUV → Bitmap Pipeline** ⭐⭐
**Effort:** High | **Impact:** Medium | **Risk:** High

Remove double JPEG encoding:
- Annotate directly on YUV/NV21 data
- Single JPEG encode after all processing
- Consider hardware overlay capabilities

**Benefits:**
- Reduced CPU usage
- Better image quality (single compression)
- Faster processing

**Risks:**
- Complex implementation (NV21 rendering)
- Potential compatibility issues
- Requires thorough testing

### Long-Term Improvements (High Effort)

#### 7. **Implement WebRTC** ⭐⭐⭐
**Effort:** Very High | **Impact:** Very High | **Risk:** High

Add WebRTC for ultra-low latency:
- Sub-200ms latency possible
- Adaptive bitrate built-in
- Modern web browser support
- P2P capable

**Benefits:**
- Ultra-low latency (<200ms)
- Excellent for real-time monitoring
- Modern standard
- Adaptive quality

**Drawbacks:**
- Complex implementation
- Signaling server required
- Limited surveillance system support
- Higher resource usage

#### 8. **Hardware-Accelerated Overlay** ⭐
**Effort:** High | **Impact:** Low | **Risk:** Medium

Use GPU for overlay rendering:
- OpenGL ES for overlays
- Hardware composition
- Parallel processing

**Benefits:**
- Offload CPU
- Faster rendering
- More complex overlays possible

### Decision Matrix

| Optimization | Effort | Impact | Bandwidth Savings | Latency Impact | Compatibility | Recommended |
|-------------|--------|--------|-------------------|----------------|---------------|-------------|
| **Adaptive Frame Rate** | Medium | High | 10-30% | None | ✅ 100% | ✅ Yes |
| **Quality/Resolution Params** | Low | High | 30-70% | None | ✅ 100% | ✅ Yes |
| **Remove HTTP Annotation** | Low | Medium | 0% | Positive | ✅ 100% | ✅ Yes |
| **Frame Skipping** | Medium | Medium | 0% | Positive | ✅ 100% | ✅ Yes |
| **HLS Implementation** | High | Very High | 50-75% | Negative | ⚠️ 90% | ⚠️ Optional |
| **Optimize YUV Pipeline** | High | Medium | 0% | Positive | ✅ 100% | ⚠️ Maybe |
| **WebRTC** | Very High | Very High | Variable | Very Positive | ⚠️ 70% | ❌ Future |
| **GPU Overlay** | High | Low | 0% | Positive | ⚠️ 95% | ❌ Not Priority |

---

## Conclusion

### Current Implementation Strengths

✅ **Simple and Reliable:** MJPEG is battle-tested and widely compatible  
✅ **Low Latency:** ~150-280ms suitable for surveillance  
✅ **Universal Support:** Works with all major surveillance systems  
✅ **Easy Client:** Simple `<img>` tag in browsers  
✅ **No Buffering:** Live stream with minimal delay  
✅ **Resource Efficient:** Pre-compression optimization prevents crashes  

### Areas for Improvement

⚠️ **Fixed Frame Rate:** No adaptation to network conditions  
⚠️ **Bandwidth Usage:** Higher than H.264 (8 Mbps vs 2 Mbps)  
⚠️ **No Scaling:** Same quality for all clients  
⚠️ **Double Encoding:** YUV → JPEG → Bitmap → JPEG pipeline  

### Recommended Roadmap

**Phase 1: Quick Wins (1-2 weeks)**
1. Add quality/resolution URL parameters
2. Remove annotation from HTTP stream (optional)
3. Implement basic frame skipping for slow clients

**Phase 2: Network Adaptation (2-4 weeks)**
1. Implement adaptive frame rate
2. Per-client quality negotiation
3. Network bandwidth monitoring

**Phase 3: Advanced Streaming (4-8 weeks)**
1. Implement optional HLS streaming endpoint (see [Requirements for Hardware-Encoded Modern Streaming](#requirements-for-hardware-encoded-modern-streaming))
2. Keep MJPEG as default and primary method
3. Document use cases for each method
4. Add client switching logic

**Phase 4: Pipeline Optimization (4-6 weeks)**
1. Optimize YUV to JPEG pipeline
2. Single compression pass
3. Performance benchmarking

**Final Verdict on MP4 Streaming**

**Is it feasible?** ✅ **Yes**, via HLS with H.264 encoding (see [Requirements for Hardware-Encoded Modern Streaming](#requirements-for-hardware-encoded-modern-streaming) for complete implementation details)

**Should you implement it?** ⚠️ **Depends on use case**

**Recommendation:**
- **Keep MJPEG as primary method** for compatibility, simplicity, and low latency
- **Add HLS as optional feature** for users who need bandwidth efficiency (complete requirements documented above)
- **Implement quality/resolution parameters for MJPEG first** - easier wins with less risk
- **Document tradeoffs** clearly for users to choose

**MP4/HLS is beneficial if:**
- Multiple concurrent streams needed (8+ clients)
- Bandwidth is limited (< 50 Mbps network)
- Recording to disk is needed (easier with MP4)
- Modern clients only (web browsers with HLS.js)

**MJPEG is better if:**
- Maximum compatibility is needed
- Low latency is critical (< 500ms)
- Surveillance system integration is primary use
- Simpler implementation preferred
- Resource usage must be minimal

---

## References

### Documentation
- CameraX: https://developer.android.com/training/camerax
- MediaCodec: https://developer.android.com/reference/android/media/MediaCodec
- MediaCodecList: https://developer.android.com/reference/android/media/MediaCodecList
- MediaMuxer: https://developer.android.com/reference/android/media/MediaMuxer
- MediaRecorder: https://developer.android.com/reference/android/media/MediaRecorder
- Hardware Codec Best Practices: https://developer.android.com/guide/topics/media/hardware-codec
- NanoHTTPD: https://github.com/NanoHttpd/nanohttpd
- HLS Spec (RFC 8216): https://datatracker.ietf.org/doc/html/rfc8216
- Low-Latency HLS: https://developer.apple.com/documentation/http_live_streaming/protocol_extension_for_low-latency_hls

### Project Files
- `CameraService.kt` - Main streaming implementation
- `MainActivity.kt` - UI and service integration
- `README.md` - User documentation
- `app/build.gradle` - Dependencies and configuration

### Key Constants
```kotlin
PORT = 8080
STREAM_FRAME_DELAY_MS = 100L // 10 fps
JPEG_QUALITY_STREAM = 75
HTTP_MAX_POOL_SIZE = 8
```

---

**Document Version:** 2.0  
**Last Updated:** 2025-12-20  
**Author:** StreamMaster Analysis Agent

**Revision History:**
- v1.0 (2025-12-20): Initial architecture analysis and MJPEG documentation
- v2.0 (2025-12-20): Added detailed hardware encoding section, MediaCodec implementation guide, pass-through recording analysis, and dual-stream architecture recommendations
