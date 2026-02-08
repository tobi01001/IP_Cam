# IP_Cam
Turn your Android device into an IP camera

## Overview
IP_Cam transforms your Android phone or tablet into a fully-featured IP camera accessible over your local WiFi network. With support for both MJPEG and RTSP streaming, it's compatible with popular surveillance systems like ZoneMinder, Shinobi, Blue Iris, and MotionEye.

## Key Features

- **Dual Streaming:** MJPEG (low latency) and RTSP/H.264 (bandwidth efficient)
- **Web Interface:** Browser-based control and live viewing
- **REST API:** Simple HTTP endpoints for automation
- **24/7 Operation:** Foreground service with auto-restart and wake locks
- **Multiple Cameras:** Switch between front and back cameras
- **Real-time Updates:** Server-Sent Events (SSE) for live status
- **Universal Compatibility:** Works with all major surveillance systems

## Quick Start

### Installation

1. Download the latest APK from [Releases](https://github.com/tobi01001/IP_Cam/releases)
2. Install on your Android device (Android 11+ required)
3. Grant camera permissions when prompted

### Usage

1. **Start Server:** Tap "Start Server" in the app
2. **Access Stream:** Open the displayed URL in a browser (e.g., `http://192.168.1.100:8080`)
3. **View Live Stream:** The web interface shows the live camera feed
4. **Control Camera:** Use web UI buttons or API endpoints to control the camera

### Basic API Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /` | Web interface with live stream |
| `GET /stream` | MJPEG video stream |
| `GET /snapshot` | Single JPEG image |
| `GET /status` | JSON status information |
| `GET /switch` | Switch camera (front/back) |
| `GET /resetCamera` | Reset camera service (recovery from frozen states) |
| `rtsp://<ip>:8554/camera` | RTSP/H.264 stream |

**Example:**
```bash
# Capture a snapshot
curl http://192.168.1.100:8080/snapshot -o photo.jpg

# View stream in VLC
vlc http://192.168.1.100:8080/stream
vlc rtsp://192.168.1.100:8554/camera
```

## Documentation

Comprehensive documentation is available in the [`/documentation`](documentation/) directory:

- **[Implementation Guide](documentation/IMPLEMENTATION.md)** - Architecture and implementation details
- **[Requirements Specification](documentation/REQUIREMENTS.md)** - Complete requirements with status
- **[Analysis & Concepts](documentation/ANALYSIS.md)** - Architectural analysis and proposals
- **[Testing Guide](documentation/TESTING.md)** - Testing procedures and troubleshooting

## Building from Source

### Prerequisites
- Android Studio (Arctic Fox or later)
- Android SDK (API 34)
- JDK 8+

### Build Steps
```bash
git clone https://github.com/tobi01001/IP_Cam.git
cd IP_Cam
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Requirements

- **OS:** Android 11+ (API level 30+)
- **Permissions:** Camera, Internet
- **Network:** WiFi connection

## License

MIT License - see [LICENSE](LICENSE) file for details

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

**Tested on:** Samsung Galaxy S10+  
**Compatible with:** Any Android device with camera (Android 11+)
