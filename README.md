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
- **Auto Updates (OTA):** Automatic over-the-air updates via GitHub Releases

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
| `GET /checkUpdate` | Check for app updates |
| `GET /triggerUpdate` | Download and install update |
| `rtsp://<ip>:8554/camera` | RTSP/H.264 stream |

**Example:**
```bash
# Capture a snapshot
curl http://192.168.1.100:8080/snapshot -o photo.jpg

# View stream in VLC
vlc http://192.168.1.100:8080/stream
vlc rtsp://192.168.1.100:8554/camera

# Check for app updates
curl http://192.168.1.100:8080/checkUpdate

# Trigger update (if available)
curl http://192.168.1.100:8080/triggerUpdate
```

## Auto Updates (OTA)

IP_Cam supports automatic over-the-air updates using GitHub Releases. Updates can be triggered from:
- **Web Interface:** "Software Update" section in the Server Management tab
- **App UI:** "Check for Update" button in the app
- **HTTP API:** `/checkUpdate` and `/triggerUpdate` endpoints

### How It Works

1. **Check for Updates:** App queries GitHub API for the latest release
2. **Version Comparison:** Compares current BUILD_NUMBER with latest release tag
3. **Download:** If update available, downloads APK from GitHub
4. **Install:** User confirms installation via Android's package installer
5. **Restart:** App automatically restarts with the new version

### Using Auto Updates

#### From Web Interface

1. Navigate to `http://<device-ip>:8080` in your browser
2. Click the "Server Management" tab
3. Scroll to "Software Update" section
4. Click "Check for Update"
5. If update available, click "Install Update"
6. Confirm installation on the device when prompted

#### From App

1. Open IP_Cam app on your device
2. Scroll to "Software Update" section
3. Tap "Check for Update"
4. If update available, tap "Install Update" in the dialog
5. Confirm installation when prompted

#### From HTTP API

```bash
# Check if update is available
curl http://192.168.1.100:8080/checkUpdate

# Response:
# {
#   "status": "ok",
#   "updateAvailable": true,
#   "currentVersion": 20260123140000,
#   "latestVersion": 20260123150000,
#   "latestVersionName": "v1.2 (Build 20260123150000)",
#   "apkSize": 5242880,
#   "releaseNotes": "Automated build..."
# }

# Trigger update download and installation
curl http://192.168.1.100:8080/triggerUpdate

# Response:
# {
#   "status": "ok",
#   "message": "Update check initiated. If update is available, installation will be prompted."
# }
```

### Update Frequency

- **Automatic Builds:** Every commit to main branch triggers a new release
- **Version Format:** `v{BUILD_NUMBER}` (e.g., `v20260123140000`)
- **Update Checks:** Manual only (no automatic background checks)
- **User Confirmation:** Required by Android for security

### Security

- **Signed APKs:** All releases are signed with the same keystore
- **Signature Verification:** Android verifies APK signature before installation
- **HTTPS Only:** Downloads from GitHub's CDN over HTTPS
- **User Control:** User must explicitly confirm each installation

### For Developers

To set up auto-update releases for your fork:

1. Follow the [Signing Setup Guide](documentation/SIGNING_SETUP.md)
2. Generate a release keystore
3. Add GitHub secrets (SIGNING_KEY, KEY_STORE_PASSWORD, ALIAS, KEY_PASSWORD)
4. Merge PRs to main branch to trigger automatic releases

See [AUTO_UPDATE_IMPLEMENTATION.md](documentation/AUTO_UPDATE_IMPLEMENTATION.md) for complete details.

## Documentation

Comprehensive documentation is available in the [`/documentation`](documentation/) directory:

- **[Implementation Guide](documentation/IMPLEMENTATION.md)** - Architecture and implementation details
- **[Requirements Specification](documentation/REQUIREMENTS.md)** - Complete requirements with status
- **[Analysis & Concepts](documentation/ANALYSIS.md)** - Architectural analysis and proposals
- **[Testing Guide](documentation/TESTING.md)** - Testing procedures and troubleshooting
- **[Auto Update Implementation](documentation/AUTO_UPDATE_IMPLEMENTATION.md)** - OTA update system guide
- **[Signing Setup Guide](documentation/SIGNING_SETUP.md)** - APK signing for auto updates

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
