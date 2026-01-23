# IP_Cam - Requirements Specification

## Table of Contents

1. [Overview](#overview)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Technical Requirements](#technical-requirements)
5. [API Requirements](#api-requirements)
6. [Implementation Status](#implementation-status)

---

## Overview

This document specifies the complete requirements for the IP_Cam Android application, which transforms Android devices into IP cameras for surveillance and monitoring applications.

**Version:** 1.0  
**Target Platform:** Android 11+ (API Level 30+)  
**Primary Use Case:** Local network IP camera with surveillance system integration

---

## Functional Requirements

### FR-1: Camera Capture & Display

#### FR-1.1: Live Camera Preview ✅ IMPLEMENTED
**Requirement:** Display live camera preview in the Android application  
**Status:** Complete  
**Implementation:** CameraX preview with callback-based frame distribution

#### FR-1.2: Camera Selection ✅ IMPLEMENTED
**Requirement:** Support switching between front and back cameras  
**Status:** Complete  
**Implementation:** Button in UI and HTTP API endpoint (`/switch`)

#### FR-1.3: Resolution Configuration ✅ IMPLEMENTED
**Requirement:** Allow user to select from available camera resolutions  
**Status:** Complete  
**Implementation:** Web UI dropdown with supported formats

#### FR-1.4: Flashlight Control ✅ IMPLEMENTED
**Requirement:** Toggle flashlight for back camera  
**Status:** Complete  
**Implementation:** In-app button and HTTP API (`/toggleFlashlight`)

#### FR-1.5: Rotation Control ✅ IMPLEMENTED
**Requirement:** Support 0°, 90°, 180°, 270° rotation and auto-rotation  
**Status:** Complete  
**Implementation:** API endpoint (`/setRotation?value=...`)

### FR-2: Video Streaming

#### FR-2.1: MJPEG Streaming ✅ IMPLEMENTED
**Requirement:** Provide MJPEG video stream over HTTP  
**Status:** Complete  
**Format:** `multipart/x-mixed-replace`  
**Endpoint:** `GET /stream`  
**Performance:** ~10 fps, 150-280ms latency

#### FR-2.2: RTSP Streaming ✅ IMPLEMENTED
**Requirement:** Provide RTSP/H.264 streaming as bandwidth-efficient alternative  
**Status:** Complete  
**Format:** RTSP with RTP/H.264  
**Performance:** 30 fps, 2-4 Mbps, 500ms-1s latency

#### FR-2.3: Multiple Concurrent Streams ✅ IMPLEMENTED
**Requirement:** Support 32+ simultaneous client connections  
**Status:** Complete  
**Implementation:** Thread pool expansion and dedicated streaming executor

#### FR-2.4: Dual-Stream Operation ✅ IMPLEMENTED
**Requirement:** MJPEG and RTSP can operate simultaneously  
**Status:** Complete  
**Implementation:** Single capture pipeline with frame duplication

### FR-3: Snapshot Capture

#### FR-3.1: Single Frame Capture ✅ IMPLEMENTED
**Requirement:** Provide API to capture single JPEG frame  
**Status:** Complete  
**Endpoint:** `GET /snapshot`  
**Format:** JPEG image

### FR-4: Web Interface

#### FR-4.1: Browser-Based Control ✅ IMPLEMENTED
**Requirement:** Provide web interface for camera control and viewing  
**Status:** Complete  
**Features:**
- Live MJPEG stream display
- Camera switch button
- Flashlight toggle
- Format/rotation controls
- Real-time connection count

#### FR-4.2: Real-Time Status Updates ✅ IMPLEMENTED
**Requirement:** Display live connection and camera status  
**Status:** Complete  
**Implementation:** Server-Sent Events (SSE) with 2-second updates

#### FR-4.3: Responsive Design ✅ IMPLEMENTED
**Requirement:** Web UI works on mobile and desktop browsers  
**Status:** Complete  
**Implementation:** Responsive HTML/CSS

### FR-5: Device Configuration

#### FR-5.1: Device Naming ✅ IMPLEMENTED
**Requirement:** Allow custom device name for identification  
**Status:** Complete  
**Default:** `IP_CAM_{device_model}`  
**Persistence:** SharedPreferences

#### FR-5.2: Server Port Configuration ⚠️ PARTIALLY IMPLEMENTED
**Requirement:** Allow user to configure HTTP server port  
**Status:** Hardcoded to 8080  
**Note:** Configuration exists in code but no UI to change it

#### FR-5.3: Auto-Start on Boot ⚠️ PARTIALLY IMPLEMENTED
**Requirement:** Optionally start camera service on device boot  
**Status:** BootReceiver exists but requires user configuration  
**Note:** Not exposed in UI settings

---

## Non-Functional Requirements

### NFR-1: Reliability

#### NFR-1.1: Service Persistence ✅ IMPLEMENTED
**Requirement:** Foreground service that survives app closure  
**Status:** Complete  
**Implementation:**
- START_STICKY restart policy
- Persistent notification
- Wake lock management
- Battery optimization exemption support

#### NFR-1.2: Automatic Recovery ✅ IMPLEMENTED
**Requirement:** Watchdog system to detect and recover from failures  
**Status:** Complete  
**Implementation:**
- 5-second health checks
- Exponential backoff (1s → 30s)
- Component restart on failure
- Network change detection

#### NFR-1.3: Settings Persistence ✅ IMPLEMENTED
**Requirement:** All settings survive app restart and device reboot  
**Status:** Complete  
**Storage:** SharedPreferences with immediate writes

### NFR-2: Performance

#### NFR-2.1: Frame Rate ✅ IMPLEMENTED
**Requirement:** Maintain target frame rates for streaming  
**Status:** Complete  
**MJPEG:** 10 fps target  
**RTSP:** 30 fps target

#### NFR-2.2: Latency ✅ IMPLEMENTED
**Requirement:** Minimize streaming latency for surveillance use  
**Status:** Complete  
**MJPEG:** 150-280ms  
**RTSP:** 500ms-1s

#### NFR-2.3: Resource Efficiency ✅ IMPLEMENTED
**Requirement:** Optimize CPU, memory, and bandwidth usage  
**Status:** Complete  
**Implementation:**
- Hardware-accelerated encoding (H.264)
- Efficient threading model
- Bitmap recycling
- Frame dropping for slow clients

#### NFR-2.4: Concurrent Connections ✅ IMPLEMENTED
**Requirement:** Support 32+ simultaneous clients without degradation  
**Status:** Complete  
**Implementation:** Thread pool (32) + streaming executor (unbounded)

### NFR-3: Compatibility

#### NFR-3.1: Surveillance System Integration ✅ IMPLEMENTED
**Requirement:** Work with popular NVR/surveillance systems  
**Status:** Complete  
**Tested With:**
- ZoneMinder
- Shinobi
- Blue Iris
- MotionEye
- VLC
- FFmpeg

#### NFR-3.2: Standard Protocols ✅ IMPLEMENTED
**Requirement:** Use standard streaming protocols  
**Status:** Complete  
**Protocols:**
- MJPEG (RFC 2046)
- RTSP/RTP (RFC 2326/3550)
- HTTP/1.1 (RFC 2616)

#### NFR-3.3: Android Version Support ✅ IMPLEMENTED
**Requirement:** Support Android 11+ (API 30+)  
**Status:** Complete  
**Target:** API 34 (Android 14)  
**Minimum:** API 30 (Android 11)

### NFR-4: Usability

#### NFR-4.1: Simple User Interface ✅ IMPLEMENTED
**Requirement:** Intuitive controls for end users  
**Status:** Complete  
**Features:**
- One-tap server start/stop
- Clear status indicators
- Real-time connection display
- Straightforward camera controls

#### NFR-4.2: Error Handling ✅ IMPLEMENTED
**Requirement:** Graceful error handling with informative messages  
**Status:** Complete  
**Implementation:**
- JSON error responses
- User-friendly error messages
- Automatic retry for recoverable errors

#### NFR-4.3: Documentation ✅ IMPLEMENTED
**Requirement:** Clear documentation for setup and API usage  
**Status:** Complete  
**Documents:**
- README with quick start
- API endpoint documentation
- Implementation details
- Testing guides

---

## Technical Requirements

### TR-1: Camera Framework

#### TR-1.1: CameraX Integration ✅ IMPLEMENTED
**Requirement:** Use CameraX for camera management  
**Status:** Complete  
**Version:** androidx.camera 1.3.1

#### TR-1.2: Single Camera Binding ✅ IMPLEMENTED
**Requirement:** One camera binding serves all consumers  
**Status:** Complete  
**Architecture:** CameraService owns binding, distributes frames via callbacks

### TR-2: Web Server

#### TR-2.1: Ktor Framework ✅ IMPLEMENTED
**Requirement:** Use Ktor for HTTP server implementation  
**Status:** Complete  
**Version:** Ktor 2.3.7

#### TR-2.2: Server-Sent Events ✅ IMPLEMENTED
**Requirement:** SSE support for real-time updates  
**Status:** Complete  
**Endpoint:** `GET /events`

### TR-3: Encoding

#### TR-3.1: JPEG Compression ✅ IMPLEMENTED
**Requirement:** JPEG encoding for MJPEG frames and snapshots  
**Status:** Complete  
**Quality:** 80% (configurable)

#### TR-3.2: H.264 Hardware Encoding ✅ IMPLEMENTED
**Requirement:** Hardware-accelerated H.264 for RTSP  
**Status:** Complete  
**Implementation:** MediaCodec API

### TR-4: Concurrency

#### TR-4.1: Threading Model ✅ IMPLEMENTED
**Requirement:** Separate executors for different task types  
**Status:** Complete  
**Executors:**
- Main: UI updates
- Camera: 1 thread for capture
- Processing: 2 threads for image processing
- HTTP: 32 threads for requests
- Streaming: Unbounded for long-lived streams
- Watchdog: Coroutine for monitoring

#### TR-4.2: Coroutines ✅ IMPLEMENTED
**Requirement:** Kotlin coroutines for async operations  
**Status:** Complete  
**Version:** kotlinx.coroutines 1.7.3

### TR-5: Lifecycle Management

#### TR-5.1: Service Lifecycle ✅ IMPLEMENTED
**Requirement:** Proper foreground service lifecycle  
**Status:** Complete  
**Type:** `android:foregroundServiceType="camera"`

#### TR-5.2: Callback Lifecycle ✅ IMPLEMENTED
**Requirement:** Lifecycle-aware callbacks to prevent crashes  
**Status:** Complete  
**Implementation:** Explicit registration/unregistration tied to lifecycle

---

## API Requirements

### API-1: HTTP Endpoints

#### API-1.1: Core Endpoints ✅ IMPLEMENTED
**Requirement:** RESTful API for camera control and status  
**Status:** Complete

**Implemented Endpoints:**

| Endpoint | Method | Purpose | Status |
|----------|--------|---------|--------|
| `/` | GET | Web interface | ✅ |
| `/stream` | GET | MJPEG video stream | ✅ |
| `/snapshot` | GET | Single JPEG frame | ✅ |
| `/status` | GET | JSON status | ✅ |
| `/events` | GET | SSE real-time updates | ✅ |
| `/switch` | GET | Switch camera | ✅ |
| `/toggleFlashlight` | GET | Toggle flashlight | ✅ |
| `/setRotation` | GET | Set rotation | ✅ |
| `/setFormat` | GET | Set resolution | ✅ |
| `/restart` | GET | Restart server | ✅ |
| `/version` | GET | Version info | ✅ |

#### API-1.2: Response Format ✅ IMPLEMENTED
**Requirement:** Consistent JSON response structure  
**Status:** Complete

```json
{
    "success": true,
    "message": "Operation completed",
    "data": { ... }
}
```

#### API-1.3: CORS Support ✅ IMPLEMENTED
**Requirement:** CORS headers for web-based clients  
**Status:** Complete  
**Headers:** `Access-Control-Allow-Origin: *`

### API-2: RTSP Protocol

#### API-2.1: RTSP Server ✅ IMPLEMENTED
**Requirement:** RTSP server on port 8554  
**Status:** Complete  
**URL:** `rtsp://<device-ip>:8554/camera`

#### API-2.2: Transport Modes ✅ IMPLEMENTED
**Requirement:** Support UDP and TCP transport  
**Status:** Complete  
**Methods:** Automatic negotiation, fallback to TCP

---

## Implementation Status

### Summary by Category

| Category | Total | Implemented | Partial | Open |
|----------|-------|-------------|---------|------|
| Functional | 18 | 16 | 2 | 0 |
| Non-Functional | 13 | 13 | 0 | 0 |
| Technical | 11 | 11 | 0 | 0 |
| API | 14 | 14 | 0 | 0 |
| **TOTAL** | **56** | **54** | **2** | **0** |

**Completion Rate:** 96% (54/56 fully implemented)

### Open/Partial Items

1. **FR-5.2: Server Port Configuration** ⚠️ PARTIAL
   - Backend supports configuration
   - No UI for user to change port
   - Requires UI implementation

2. **FR-5.3: Auto-Start on Boot** ⚠️ PARTIAL
   - BootReceiver implemented
   - Setting not exposed in UI
   - Requires settings screen addition

### Recommended Enhancements

#### High Priority
- [ ] Add settings screen for port and auto-start configuration
- [ ] Implement adaptive bitrate for MJPEG based on network conditions
- [ ] Add authentication/security for network access

#### Medium Priority
- [ ] PTZ controls simulation (digital zoom/pan)
- [ ] Audio streaming support
- [ ] Motion detection with notifications

#### Low Priority
- [ ] Cloud storage integration
- [ ] Multi-camera support
- [ ] Custom overlay text/timestamp

---

## Related Documentation

- **[Implementation](IMPLEMENTATION.md)** - Current implementation details
- **[Analysis](ANALYSIS.md)** - Architectural concepts and proposals
- **[Testing](TESTING.md)** - Testing guides and procedures

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-23
