# IP_Cam Application - Complete Requirements Specification

**Version:** 1.0  
**Date:** 2025-12-21  
**Document Type:** Technical Requirements Specification  
**Target Platform:** Android 12+ (API Level 31+)

**Note:** Android 12 (API 31) is the minimum supported version due to significant platform changes in foreground service management, privacy indicators, and background execution restrictions that are critical for reliable 24/7 camera operation.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [System Overview](#system-overview)
3. [Core Design Principles](#core-design-principles)
4. [Functional Requirements](#functional-requirements)
5. [Non-Functional Requirements](#non-functional-requirements)
6. [Technical Architecture Requirements](#technical-architecture-requirements)
7. [User Interface Requirements](#user-interface-requirements)
8. [API & Integration Requirements](#api--integration-requirements)
9. [Security & Privacy Requirements](#security--privacy-requirements)
10. [Performance & Resource Requirements](#performance--resource-requirements)
11. [Deployment & Operational Requirements](#deployment--operational-requirements)
12. [Testing & Quality Assurance Requirements](#testing--quality-assurance-requirements)
13. [Documentation Requirements](#documentation-requirements)

---

## 1. Executive Summary

### 1.1 Purpose

IP_Cam is an Android application that transforms Android devices into fully-functional IP cameras with HTTP streaming capabilities. The application is designed for 24/7 surveillance operations, repurposing older Android devices into reliable, network-accessible camera systems compatible with professional surveillance software.

### 1.2 Target Users

- **Primary:** Home users repurposing old Android phones as security cameras
- **Secondary:** Small businesses needing low-cost surveillance solutions
- **Technical:** Integrators deploying multiple cameras with NVR/VMS systems

### 1.3 Key Value Propositions

1. **Zero-Cost Hardware Reuse:** Leverage existing Android devices
2. **Professional Compatibility:** Works with ZoneMinder, Shinobi, Blue Iris, MotionEye
3. **24/7 Reliability:** Designed for continuous operation with automatic recovery
4. **Network Streaming:** MJPEG streaming over HTTP for universal compatibility
5. **Simple Integration:** Standard endpoints, no authentication barriers for trusted networks

---

## 2. System Overview

### 2.1 Application Description

IP_Cam provides camera streaming functionality through:
- **Live camera preview** in the Android app
- **HTTP web server** for browser and API access
- **MJPEG video streaming** for real-time viewing
- **RESTful API** for camera control and configuration
- **Persistent background service** for unattended operation

### 2.2 Primary Use Cases

**UC-001: Home Security Monitoring**
- Mount old phone in strategic location
- Stream to local surveillance system
- 24/7 continuous operation

**UC-002: Baby/Pet Monitoring**
- View camera feed from web browser
- Low-latency live viewing
- Remote camera switching

**UC-003: Multi-Camera Surveillance Integration**
- Deploy multiple IP_Cam devices
- Integrate with NVR software (ZoneMinder, Blue Iris)
- Centralized recording and monitoring

**UC-004: Remote Monitoring**
- Access via VPN or secure tunnel
- View from anywhere
- Control camera settings remotely

---

## 3. Core Design Principles

The IP_Cam application is built around **five critical design principles** that guide all implementation decisions:

### 3.1 Bandwidth Usage & Performance

**Principle:** Minimize network bandwidth consumption while maintaining acceptable video quality for surveillance applications.

**Requirements:**
- REQ-BP-001: Target frame rate of ~10 fps for optimal bandwidth/quality balance
- REQ-BP-002: JPEG compression quality of 70-85% (configurable)
- REQ-BP-003: Pre-compress frames on camera thread to avoid HTTP thread blocking
- REQ-BP-004: Use hardware-accelerated encoding where available
- REQ-BP-005: Drop frames for slow clients (backpressure strategy)
- REQ-BP-006: Monitor network conditions and adapt quality accordingly
- REQ-BP-007: Target bandwidth: ~8 Mbps per client for 1080p @ 10fps

**Rationale:** Surveillance use cases prioritize reliability and continuous operation over maximum frame rate. 10fps provides sufficient temporal resolution for motion detection while minimizing network load.

### 3.2 Single Source of Truth Architecture

**Principle:** ONE camera instance managed by ONE service serves ALL consumers (app preview + web clients).

**Requirements:**
- REQ-SST-001: CameraService MUST be the sole manager of camera resources
- REQ-SST-002: MainActivity SHALL receive frames via callback only (no direct camera access)
- REQ-SST-003: Web clients SHALL access the same camera instance through HTTP
- REQ-SST-004: Camera state changes SHALL propagate to all consumers immediately
- REQ-SST-005: Camera switching SHALL update both app UI and web stream simultaneously
- REQ-SST-006: Settings changes SHALL be persisted and applied uniformly
- REQ-SST-007: NO resource conflicts SHALL occur between app preview and web streaming

**Rationale:** Prevents resource contention, eliminates state synchronization issues, and ensures consistent behavior across all interfaces.

### 3.3 Persistence of Background Processes

**Principle:** Service MUST continue operating reliably 24/7, surviving system kills, crashes, and device reboots.

**Requirements:**
- REQ-PER-001: Service SHALL run as foreground service with persistent notification
- REQ-PER-002: Service SHALL use START_STICKY for automatic restart after system kill
- REQ-PER-003: Service SHALL implement onTaskRemoved() to restart when app is swiped away
- REQ-PER-004: Service SHALL maintain CPU wake lock during streaming
- REQ-PER-005: Service SHALL maintain high-performance WiFi lock
- REQ-PER-006: Service SHALL implement watchdog for health monitoring (5-second intervals)
- REQ-PER-007: Service SHALL use exponential backoff for recovery (1s → 30s max)
- REQ-PER-008: Service SHALL persist all settings to SharedPreferences immediately
- REQ-PER-009: Service SHALL restore settings on startup
- REQ-PER-010: App SHALL request battery optimization exemption
- REQ-PER-011: Service SHALL monitor network state and restart server on WiFi reconnection

**Rationale:** Surveillance cameras must operate continuously without user intervention. System reliability is the highest priority.

### 3.4 Usability

**Principle:** Interface MUST be simple, intuitive, and provide real-time feedback for both end users and integrators.

**Requirements:**
- REQ-USE-001: One-tap controls for all common operations (start, stop, switch camera)
- REQ-USE-002: Real-time status display (connection count, camera state, server URL)
- REQ-USE-003: Auto-refresh status every 2 seconds via Server-Sent Events
- REQ-USE-004: Clear, actionable error messages with guidance
- REQ-USE-005: Responsive web UI for mobile and desktop browsers
- REQ-USE-006: RESTful API with consistent JSON response format
- REQ-USE-007: Live camera preview in app
- REQ-USE-008: Visual indicators for flashlight, camera selection, server status
- REQ-USE-009: Settings SHALL persist across app restarts
- REQ-USE-010: Web interface SHALL work without JavaScript (basic functionality)

**Rationale:** Target users range from technical novices to professional integrators. Interface must be self-explanatory while providing full control.

### 3.5 Standardized Interface for Surveillance Software

**Principle:** Full compatibility with popular NVR/VMS systems using industry-standard protocols.

**Requirements:**
- REQ-STD-001: Standard MJPEG stream endpoint at `/stream`
- REQ-STD-002: Proper MIME type: `multipart/x-mixed-replace; boundary=--jpgboundary`
- REQ-STD-003: Snapshot endpoint at `/snapshot` returning single JPEG image
- REQ-STD-004: Status endpoint at `/status` returning JSON system information
- REQ-STD-005: RESTful control endpoints for camera switching, settings
- REQ-STD-006: Support for 32+ simultaneous clients
- REQ-STD-007: CORS headers set to `*` for local network use
- REQ-STD-008: Proper HTTP status codes and error responses
- REQ-STD-009: Compatibility verified with: ZoneMinder, Shinobi, Blue Iris, MotionEye
- REQ-STD-010: Chunked transfer encoding for streaming responses

**Rationale:** Surveillance software expects standard MJPEG streams. Compatibility ensures wide adoption and ease of integration.

### 3.6 Optional/Future Enhancements

**Principle:** The application MAY be extended with advanced streaming options for specialized use cases requiring lower bandwidth at the cost of increased latency.

#### 3.6.1 HLS (HTTP Live Streaming) Support

**Purpose:** Provide an alternative streaming method that significantly reduces bandwidth consumption for scenarios where higher latency is acceptable.

**Optional Requirements:**
- REQ-OPT-001: System MAY support HLS streaming alongside MJPEG streaming
- REQ-OPT-002: HLS implementation SHALL use hardware-accelerated H.264 encoding via MediaCodec
- REQ-OPT-003: HLS SHALL generate 2-6 second segments in MPEG-TS or MP4 format
- REQ-OPT-004: HLS SHALL maintain a sliding window of 10 segments
- REQ-OPT-005: HLS playlist SHALL be served at `/hls/stream.m3u8` endpoint
- REQ-OPT-006: HLS segments SHALL be served at `/hls/segment{N}.ts` endpoints
- REQ-OPT-007: HLS implementation SHALL target 2-4 Mbps bitrate for 1080p @ 30fps
- REQ-OPT-008: HLS SHALL provide 50-75% bandwidth reduction compared to MJPEG
- REQ-OPT-009: HLS latency SHALL be 6-12 seconds (acceptable for non-real-time monitoring)
- REQ-OPT-010: Both MJPEG and HLS streams SHALL be available simultaneously
- REQ-OPT-011: HLS SHALL be configurable (enabled/disabled) via settings
- REQ-OPT-012: System SHALL cache segments in app cache directory with automatic cleanup

**Use Cases:**
- Multiple concurrent remote viewers over limited bandwidth connections
- Recording to disk (native MP4 format)
- Integration with modern web-based surveillance systems
- Bandwidth-constrained networks (cellular, limited WiFi)

**Tradeoffs:**
- **Benefit:** 50-75% bandwidth reduction (8 Mbps → 2-4 Mbps per client)
- **Benefit:** Better video quality through inter-frame compression
- **Benefit:** Native MP4/TS format for easy recording
- **Cost:** Increased latency (150ms → 6-12 seconds)
- **Cost:** Higher implementation complexity
- **Cost:** Additional storage for segment cache (~5 MB)
- **Cost:** Increased CPU usage for H.264 encoding

**Implementation Reference:** See STREAMING_ARCHITECTURE.md for detailed HLS implementation analysis, including MediaCodec integration, segment management, and performance comparisons.

**Priority:** LOW - Implement only after core MJPEG functionality is stable and tested. MJPEG remains the primary streaming method for compatibility and low latency.

### 3.7 UI Framework Recommendation

**Recommendation:** For new implementations starting from scratch, **Jetpack Compose** is the preferred modern UI toolkit for Android development.

**Rationale:**
- **Declarative UI:** Reduces boilerplate code and improves maintainability
- **State Management:** Better integration with Kotlin coroutines and state handling
- **Native Kotlin:** Fully Kotlin-native with no XML layout files
- **Modern Tooling:** Built-in preview support and easier testing
- **Material Design 3:** Native support for latest Material Design guidelines
- **Performance:** Optimized rendering with smart recomposition
- **Future-Proof:** Google's recommended approach for new Android UI development

**Important Constraint:** If Jetpack Compose is chosen for the implementation, the entire application UI MUST be fully Compose-compliant. Mixing Compose with traditional View-based UI (XML layouts, View Binding) should be avoided to maintain consistency, simplify maintenance, and leverage Compose's full benefits.

**Alternative:** Implementations may choose traditional View-based UI (XML layouts with View Binding or Data Binding) if:
- Team has existing expertise with View-based development
- Integration with legacy codebases is required
- Specific UI requirements are better suited to traditional views

**Compose Compatibility:** Jetpack Compose is fully compatible with Android 12+ (minimum supported API 21, well below our target of API 31+).

---

## Conclusion

This requirements specification provides a complete blueprint for implementing the IP_Cam application from scratch. It captures all functional and non-functional requirements, technical architecture details, and operational considerations necessary for building a professional-grade IP camera solution on Android.

The document is structured to guide developers through:
- **Core Design Principles**: The five foundational principles that drive all implementation decisions
- **Functional Requirements**: Detailed feature specifications
- **Technical Architecture**: Complete system design and implementation guidelines
- **Quality Assurance**: Testing and validation criteria
- **Deployment**: Installation and operational procedures

By following this specification, implementers can create an IP camera application that is reliable, performant, user-friendly, and compatible with industry-standard surveillance software.

---

**Document Status:** Complete and Ready for Implementation  
**Maintainer:** Development Team  
**Review Schedule:** Quarterly or after major feature additions
