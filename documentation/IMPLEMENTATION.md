# IP_Cam - Implementation Documentation

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Core Components](#core-components)
4. [Streaming Implementation](#streaming-implementation)
5. [Lifecycle Management](#lifecycle-management)
6. [Persistence & Reliability](#persistence--reliability)
7. [Camera Management](#camera-management)
8. [Web Server & UI](#web-server--ui)
9. [Performance Optimizations](#performance-optimizations)
10. [Version Management](#version-management)

---

## Overview

IP_Cam is an Android application that transforms Android devices into IP cameras with HTTP/RTSP streaming capabilities. The implementation emphasizes reliability, performance, and compatibility with surveillance systems.

**Key Technologies:**
- **Camera:** CameraX for camera management
- **Streaming:** MJPEG (HTTP) and RTSP (H.264) support
- **Web Server:** Ktor-based HTTP server with SSE support
- **Concurrency:** Kotlin Coroutines for async operations
- **Target:** Android 11+ (API level 30+)

---

## Architecture

### System Overview

```
MainActivity (UI Layer)
  └── Controls & Status Display
  
CameraService (Foreground Service)
  ├── Camera Management (CameraX)
  ├── HTTP Server (Ktor)
  │   ├── MJPEG Streaming
  │   ├── Snapshot API
  │   ├── Status/Control API
  │   └── SSE Events
  ├── RTSP Server (Optional)
  │   └── H.264 Hardware Encoding
  ├── Watchdog (Auto-restart)
  └── Network Monitor
```

### Connection Handling

The architecture supports 32+ concurrent connections through:

1. **Increased Thread Pool**: Expanded from 8 to 32 threads
2. **Streaming Executor**: Unbounded cached thread pool for long-lived streams
3. **Separation of Concerns**: Quick requests use main pool, streams use dedicated executor
4. **Non-blocking Operations**: Streaming operations don't block HTTP handler threads

**Key Achievement:** Eliminated thread starvation issue where long-lived `/stream` and `/events` connections would exhaust the thread pool.

---

## Core Components

### CameraService

The CameraService is a foreground service that manages:
- Camera lifecycle and frame capture
- HTTP/RTSP server instances
- Watchdog monitoring
- Network connectivity
- Settings persistence

**Service Type:** Foreground with `android:foregroundServiceType="camera"`

**Restart Policy:** `START_STICKY` for automatic restart after system kills

### MainActivity

Simple UI providing:
- Camera preview (via callback from CameraService)
- Server start/stop controls
- Camera switching
- Format selection
- Real-time status display
- Device name configuration

---

## Streaming Implementation

### MJPEG Streaming

**Endpoint:** `GET /stream`

**Format:** `multipart/x-mixed-replace` with JPEG frames

**Implementation:**
```kotlin
// Efficient frame distribution without blocking
streamingExecutor.execute {
    while (isStreaming) {
        val frame = captureFrame()
        sendMultipartFrame(frame)
    }
}
```

**Performance:**
- Target: 10 fps
- JPEG Quality: 80%
- Bandwidth: ~8 Mbps
- Latency: 150-280ms

**Compatibility:** Works with all surveillance systems (ZoneMinder, Shinobi, Blue Iris, MotionEye)

### RTSP Streaming (Optional)

**Protocol:** RTSP with RTP/H.264

**Implementation:**
- Hardware-accelerated H.264 encoding via MediaCodec
- Dual transport: UDP (low latency) and TCP (firewall-friendly)
- NAL unit streaming over RTP

**Performance:**
- Bandwidth: 2-4 Mbps (50-75% reduction vs MJPEG)
- Latency: 500ms-1s
- Frame Rate: 30 fps

**Usage:**
```bash
vlc rtsp://<device-ip>:8554/camera
ffplay rtsp://<device-ip>:8554/camera
```

### Dual-Stream Architecture

Both MJPEG and RTSP can run simultaneously:
- Single camera capture pipeline
- Frame duplication to multiple consumers
- Independent encoding paths
- No interference between streams

---

## Lifecycle Management

### Service Lifecycle

**Start:** 
- Explicit start via MainActivity
- Auto-start on boot (if configured)

**Stop:**
- User-initiated stop
- Service destruction (system kill with auto-restart)

**Recovery:**
- Watchdog monitors health every 5 seconds
- Exponential backoff for restarts (1s → 30s)
- Handles network changes automatically

### Callback Management

**Problem:** Callbacks to destroyed Activities cause crashes

**Solution:** Explicit lifecycle registration
```kotlin
// Activity registers callback on start
cameraService.registerFrameCallback(lifecycleOwner) { bitmap ->
    updatePreview(bitmap)
}

// Automatically unregistered on lifecycle destroy
```

**Benefits:**
- No memory leaks
- No crashes from dead context access
- Clean resource management

---

## Persistence & Reliability

### Foreground Service

**Features:**
- Persistent notification (cannot be dismissed)
- Survives app task removal
- Runs in background indefinitely
- Battery optimization exemption recommended

**Notification Content:**
- Title: Device name or "IP Camera Server"
- Status: Server URL or error state
- Tap action: Return to MainActivity

### Watchdog System

**Purpose:** Monitor and auto-restart failed components

**Monitored Components:**
1. HTTP Server health
2. Camera binding state
3. Network connectivity

**Recovery Process:**
```
Check Health → Detect Failure → Exponential Backoff → Restart Component → Verify → Success/Retry
```

**Backoff Strategy:**
- Initial: 1 second
- Max: 30 seconds
- Respects intentional stops

### Settings Persistence

**Storage:** SharedPreferences

**Persisted Settings:**
- Camera selection (front/back)
- Resolution format
- Rotation angle
- Flashlight state
- Device name
- Server port
- Auto-start preference

**Persistence Timing:** Immediate on change (not on service destroy)

### Network Monitoring

**Purpose:** Restart server on network changes (WiFi disconnect/reconnect)

**Implementation:**
```kotlin
networkMonitor.observe { networkState ->
    if (networkState.isConnected && serverWasRunning) {
        restartServer()
    }
}
```

---

## Camera Management

### CameraX Integration

**API:** CameraX (androidx.camera:camera-*)

**Configuration:**
```kotlin
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    preview,
    imageAnalysis
)
```

**Frame Capture:**
- Format: YUV_420_888
- Processing: CPU-based conversion to Bitmap/JPEG
- Distribution: Callbacks to multiple consumers

### Camera Switching

**Support:** Front and back cameras

**Implementation:**
- Unbind current camera
- Switch CameraSelector
- Rebind to new camera
- Update all consumers

**Synchronization:** All consumers (app preview, HTTP clients, RTSP clients) see the same camera

### Flashlight Control

**Availability:** Back camera only (hardware limitation)

**Control Methods:**
- In-app toggle button
- HTTP API: `GET /toggleFlashlight`
- Web UI button

---

## Web Server & UI

### HTTP Server (Ktor)

**Port:** 8080 (configurable)

**Core Endpoints:**
- `GET /` - Web interface with live stream
- `GET /stream` - MJPEG video stream
- `GET /snapshot` - Single JPEG frame
- `GET /status` - JSON status (camera, connections, settings)
- `GET /events` - Server-Sent Events (SSE) for real-time updates
- `GET /switch` - Switch camera
- `GET /toggleFlashlight` - Toggle flashlight
- `GET /setRotation?value=<0|90|180|270|auto>` - Set rotation
- `GET /setFormat?value=<WIDTHxHEIGHT>` - Set resolution
- `GET /resetCamera` - Reset camera service (recovery from frozen states)
- `GET /restart` - Restart server
- `GET /version` - API version info

**Response Format:** JSON with consistent structure
```json
{
    "success": true,
    "message": "Operation completed",
    "data": { ... }
}
```

**CORS:** Enabled for web-based clients

### Web Interface

**Features:**
- Live MJPEG stream with auto-reconnect
- Real-time connection count (SSE updates)
- Camera switch button
- Flashlight toggle
- Format/rotation controls
- Responsive design (mobile/desktop)
- Battery/time overlay

**Auto-reconnect Logic:**
```javascript
stream.onerror = () => {
    setTimeout(() => {
        stream.src = '/stream?' + Date.now();
    }, 1000);
};
```

**Status Updates:** 2-second polling via SSE

### Server-Sent Events (SSE)

**Endpoint:** `GET /events`

**Events:**
- `status` - Connection count, camera state
- `heartbeat` - Keep-alive signal

**Implementation:**
```kotlin
call.respondTextWriter(ContentType.Text.EventStream) {
    while (isActive) {
        write("data: $statusJson\n\n")
        flush()
        delay(2000)
    }
}
```

---

## Performance Optimizations

### Bandwidth Optimization

**Techniques:**
1. JPEG quality tuning (80% default)
2. Frame rate limiting (10 fps for MJPEG)
3. Hardware-accelerated H.264 (RTSP)
4. Efficient YUV-to-JPEG conversion
5. Frame dropping for slow clients

**Monitoring:**
- Real-time bandwidth calculation
- Per-client tracking
- Adaptive quality (planned)

### Image Processing

**Pipeline:**
```
Camera (YUV_420_888) → Rotation → YUV-to-Bitmap → JPEG Compression → Network
```

**Optimizations:**
- YUV-to-Bitmap: Native RenderScript/C++ implementations (device-dependent)
- Rotation: Matrix transformation on Bitmap
- JPEG: Hardware encoding where available
- Memory: Bitmap recycling to reduce GC pressure

**Decoupling Implementation:**
- Separate processing executor (2 threads)
- Camera executor (1 thread) for capture only
- No blocking operations in camera callback

### Memory Management

**Bitmap Handling:**
```kotlin
// Proper recycling pattern
val bitmap = processBitmap(frame)
try {
    distributeToConsumers(bitmap)
} finally {
    bitmap.recycle()
}
```

**Frame Dropping:**
- Monitor client backpressure
- Drop frames if client queue is full
- Prevent memory accumulation

### Threading Model

**Executors:**
1. **Main Thread:** UI updates only
2. **Camera Executor:** 1 thread for frame capture
3. **Processing Executor:** 2 threads for image processing
4. **HTTP Pool:** 32 threads for request handling
5. **Streaming Executor:** Unbounded cached pool for long-lived streams
6. **Watchdog:** Separate coroutine for monitoring

**Benefits:**
- No thread starvation
- Efficient resource utilization
- Responsive UI
- Scalable streaming

---

## Version Management

### Automated Version System

**Strategy:** Semantic versioning (MAJOR.MINOR.PATCH)

**Build Integration:**
```groovy
// version.properties
version.major=1
version.minor=2
version.patch=0
```

**Gradle Configuration:**
```kotlin
versionCode = major * 10000 + minor * 100 + patch
versionName = "$major.$minor.$patch"
```

**API Endpoint:** `GET /version`
```json
{
    "version": "1.2.0",
    "build": 10200,
    "api_level": 1
}
```

**Update Workflow:**
1. Increment version in `version.properties`
2. Gradle reads version on build
3. Embedded in APK manifest
4. Exposed via HTTP API

---

## Related Documentation

For more detailed information, see:
- **[Requirements](REQUIREMENTS.md)** - Complete requirements specification
- **[Analysis](ANALYSIS.md)** - Architectural concepts and proposals
- **[Testing](TESTING.md)** - Testing guides and procedures

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-23
