# FPS Calculation Logic

## Overview

The IP_Cam application tracks three distinct FPS metrics to provide accurate performance monitoring:

1. **Camera Capture FPS** (`currentCameraFps`) - Rate at which the camera hardware captures frames
2. **MJPEG Streaming FPS** (`currentMjpegFps`) - Average rate at which each MJPEG client receives frames
3. **RTSP Streaming FPS** (`currentRtspFps`) - Rate at which H.264 frames are encoded for RTSP streaming

## 1. Camera Capture FPS

**Location**: `CameraService.processMjpegFrame()`

**Purpose**: Measures the raw frame rate from the camera hardware (ImageAnalysis callback rate).

**Calculation**:
```kotlin
// Track every frame callback from camera
synchronized(fpsFrameTimes) {
    fpsFrameTimes.add(currentTime)
    // Keep only last 2 seconds of timestamps
    fpsFrameTimes.removeAll { it < (currentTime - 2000) }
    
    // Calculate FPS every 500ms
    if (fpsFrameTimes.size > 1) {
        val timeSpan = fpsFrameTimes.last() - fpsFrameTimes.first()
        currentCameraFps = (fpsFrameTimes.size - 1) * 1000f / timeSpan
    }
}
```

**Key Points**:
- Tracks ALL frames received from camera, including those that may be skipped/throttled
- Updated on every ImageAnalysis callback
- Independent of streaming clients
- Typically matches camera's configured frame rate (e.g., 30 FPS)

## 2. MJPEG Streaming FPS

**Location**: `CameraService.recordMjpegFrameServed()`, called from `HttpServer.serveStream()`

**Purpose**: Measures the **average streaming rate per MJPEG client**. This answers: "How many FPS is each client actually receiving?"

**Calculation**:
```kotlin
// Called once per frame delivery per client
synchronized(mjpegFpsLock) {
    mjpegFpsFrameTimes.add(currentTime)
    mjpegFpsFrameTimes.removeAll { it < (currentTime - 2000) }
    
    if (mjpegFpsFrameTimes.size > 1) {
        val timeSpan = mjpegFpsFrameTimes.last() - mjpegFpsFrameTimes.first()
        
        // Calculate total throughput (all deliveries to all clients)
        val totalFps = (mjpegFpsFrameTimes.size - 1) * 1000f / timeSpan
        
        // Divide by client count to get average FPS per client
        val clientCount = getMjpegClientCount()
        currentMjpegFps = if (clientCount > 0) {
            totalFps / clientCount
        } else {
            0f
        }
    }
}
```

**Example Scenarios**:

| Scenario | Frame Deliveries/sec | Clients | Calculated FPS | Meaning |
|----------|---------------------|---------|----------------|---------|
| 1 client | 10 deliveries | 1 | 10 / 1 = **10 FPS** | Single client gets 10 FPS |
| 2 clients | 20 deliveries | 2 | 20 / 2 = **10 FPS** | Each of 2 clients gets 10 FPS |
| 3 clients | 30 deliveries | 3 | 30 / 3 = **10 FPS** | Each of 3 clients gets 10 FPS |

**Key Points**:
- `recordMjpegFrameServed()` is called **once per client per frame** in HttpServer
- Total frame deliveries = targetMjpegFps × number_of_clients
- Dividing by client count gives the actual per-client streaming rate
- Shows what each individual client experiences, not aggregate throughput
- Typically matches `targetMjpegFps` (e.g., 10 FPS) regardless of client count

## 3. RTSP Streaming FPS

**Location**: `CameraService.recordRtspFrameEncoded()`, called from `RTSPServer.encodeFrame()`

**Purpose**: Measures the H.264 **encoding rate**, which is independent of how many clients consume the stream.

**Calculation**:
```kotlin
// Called once per frame encoding
synchronized(rtspFpsLock) {
    rtspFpsFrameTimes.add(currentTime)
    rtspFpsFrameTimes.removeAll { it < (currentTime - 2000) }
    
    if (rtspFpsFrameTimes.size > 1) {
        val timeSpan = rtspFpsFrameTimes.last() - rtspFpsFrameTimes.first()
        
        // RTSP encoding happens once, regardless of client count
        // All clients receive the same encoded stream
        val totalFps = (rtspFpsFrameTimes.size - 1) * 1000f / timeSpan
        currentRtspFps = totalFps  // No division by client count
    }
}
```

**Key Points**:
- `recordRtspFrameEncoded()` is called **once per frame** when queued to MediaCodec encoder
- H.264 encoding happens once; the same encoded frames are broadcast to all RTSP clients
- **NOT divided by client count** because encoding rate is independent of how many clients view it
- Represents the actual H.264 encoder throughput
- Typically matches `targetRtspFps` (e.g., 30 FPS)

**Why Different from MJPEG?**
- **MJPEG**: Each client receives an independent JPEG-compressed stream. Each frame must be encoded and sent separately to each client. Recording happens per-client-per-frame.
- **RTSP**: H.264 encoding happens once. The same encoded NAL units are broadcast to all clients. Recording happens per-frame (single time).

## Architecture Diagram

```
Camera Hardware (30 FPS)
    ↓
ImageAnalysis Callback (tracks currentCameraFps)
    ↓
    ├─→ MJPEG Pipeline (CPU-based)
    │   ├─→ Throttle to targetMjpegFps (e.g., 10 FPS)
    │   ├─→ JPEG Encoding
    │   └─→ HttpServer.serveStream()
    │       ├─→ Client 1: recordMjpegFrameServed() [10 times/sec]
    │       ├─→ Client 2: recordMjpegFrameServed() [10 times/sec]
    │       └─→ Client N: recordMjpegFrameServed() [10 times/sec]
    │           → Total: 10×N calls/sec
    │           → Divided by N clients = 10 FPS per client ✓
    │
    └─→ RTSP Pipeline (Hardware-accelerated)
        ├─→ MediaCodec H.264 Encoding (targetRtspFps, e.g., 30 FPS)
        │   └─→ recordRtspFrameEncoded() [30 times/sec]
        │       → NOT divided by clients (encoding happens once) ✓
        └─→ RTSPServer broadcast to all clients
            ├─→ Client 1: receives 30 FPS stream
            ├─→ Client 2: receives 30 FPS stream
            └─→ Client N: receives 30 FPS stream
```

## Display and Interpretation

### Web Interface & OSD
All three FPS metrics are displayed:

| Metric | Label | Typical Value | Meaning |
|--------|-------|---------------|---------|
| `currentCameraFps` | "Camera" | ~30 FPS | Camera hardware capture rate |
| `currentMjpegFps` | "MJPEG" | ~10 FPS | Per-client MJPEG streaming rate |
| `currentRtspFps` | "RTSP" | ~30 FPS | H.264 encoding rate |

### Expected Behavior

**Single MJPEG Client:**
- Camera FPS: 30 FPS (capturing continuously)
- MJPEG FPS: 10 FPS (throttled to target)
- RTSP FPS: 0 or 30 FPS (if enabled)

**Multiple MJPEG Clients (e.g., 3 clients):**
- Camera FPS: 30 FPS (unchanged)
- MJPEG FPS: 10 FPS (each client gets 10 FPS) ← **This is the key fix**
- RTSP FPS: 0 or 30 FPS (unchanged)

**Before the Fix:**
- With 3 MJPEG clients, `currentMjpegFps` would incorrectly show 30 FPS (3 × 10)
- This was misleading because it suggested each client was getting 30 FPS, when they were actually getting 10 FPS

**After the Fix:**
- With 3 MJPEG clients, `currentMjpegFps` correctly shows 10 FPS
- This accurately reflects what each individual client experiences

## Code Locations

### Camera FPS
- **Tracking**: `CameraService.processMjpegFrame()` line ~1316
- **Calculation**: Inline in `synchronized(fpsFrameTimes)` block

### MJPEG FPS  
- **Recording**: `HttpServer.serveStream()` line ~501, calls `cameraService.recordMjpegFrameServed()`
- **Calculation**: `CameraService.recordMjpegFrameServed()` line ~2036
- **Division by client count**: Line ~2055

### RTSP FPS
- **Recording**: `RTSPServer.encodeFrame()` line ~1163, calls `cameraService?.recordRtspFrameEncoded()`
- **Calculation**: `CameraService.recordRtspFrameEncoded()` line ~2110
- **No division**: Encoding happens once for all clients

## Testing Recommendations

To verify the FPS calculations work correctly:

1. **Test Camera FPS**:
   - Start camera service
   - Check that `currentCameraFps` matches camera's native frame rate (~30 FPS)
   - Should be independent of any streaming clients

2. **Test MJPEG FPS with 1 client**:
   - Connect 1 MJPEG client to `/stream`
   - Verify `currentMjpegFps` shows ~10 FPS (matches `targetMjpegFps`)

3. **Test MJPEG FPS with multiple clients**:
   - Connect 3 MJPEG clients to `/stream` simultaneously
   - Verify `currentMjpegFps` STILL shows ~10 FPS (not 30 FPS)
   - Each client should receive 10 FPS stream

4. **Test RTSP FPS**:
   - Connect 1 or more RTSP clients
   - Verify `currentRtspFps` shows ~30 FPS (matches `targetRtspFps`)
   - Should be same regardless of number of RTSP clients

5. **Test mixed scenario**:
   - Connect 2 MJPEG clients + 2 RTSP clients
   - Camera FPS: ~30 FPS
   - MJPEG FPS: ~10 FPS (per client)
   - RTSP FPS: ~30 FPS (encoding rate)

## Implementation Notes

### Thread Safety
All FPS calculations use synchronized blocks to ensure thread-safe access:
- `mjpegFpsLock` for MJPEG FPS calculation
- `rtspFpsLock` for RTSP FPS calculation
- `fpsFrameTimes` synchronized in Camera FPS calculation

### Performance Considerations
- FPS calculations happen at most every 500ms (controlled by `lastMjpegFpsCalculation` and `lastRtspFpsCalculation`)
- Only last 2 seconds of frame times are kept in memory
- Broadcast to UI only when FPS changes by more than 0.5 to reduce unnecessary updates

### Edge Cases
- **Zero clients**: FPS resets to 0 when no clients are connected (handled by `checkAndResetFpsCounters()`)
- **Client disconnect**: FPS automatically adjusts as `getMjpegClientCount()` changes
- **Rapid client changes**: Rolling 2-second window smooths out temporary fluctuations
