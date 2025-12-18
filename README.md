# IP_Cam
Android IP-Cam - turning an old phone into a camera

## Overview
IP_Cam is a simple Android application that turns your Android phone into an IP camera accessible over your local WiFi network. It provides a web server with a REST API and MJPEG streaming capabilities, making it compatible with popular surveillance systems like ZoneMinder and Shinobi.

## Features
- **Live Camera Preview**: View what the camera sees directly in the app
- **HTTP Web Server**: Access the camera through any web browser
- **MJPEG Streaming**: Real-time video streaming compatible with surveillance systems
- **Camera Selection**: Switch between front and back cameras
- **REST API**: Simple API for integration with other systems
- **Low Latency**: Optimized for fast streaming (~10 fps)

## Target Device
Developed and tested for Samsung Galaxy S10+ (but should work on any Android device with camera and Android 7.0+)

## Requirements
- Android 7.0 (API level 24) or higher
- Camera permission
- WiFi connection

## Building the App

### Prerequisites
- Android Studio Arctic Fox or later
- Android SDK with API level 34
- JDK 8 or higher

### Build Instructions
1. Clone the repository:
   ```bash
   git clone https://github.com/tobi01001/IP_Cam.git
   cd IP_Cam
   ```

2. Open the project in Android Studio

3. Sync Gradle files

4. Build and run the app:
   ```bash
   ./gradlew assembleDebug
   ```

5. Install on your device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

## Usage

### Starting the Server
1. Open the IP_Cam app on your Android device
2. Grant camera permissions when prompted
3. Tap "Start Server" button
4. The app will display the server URL (e.g., `http://192.168.1.100:8080`)
5. Keep the app running in the foreground or background (notification will show)

### Accessing the Camera

#### Web Browser
Simply open the displayed URL in any web browser on the same network to access the web interface.

#### API Endpoints

- **`GET /`** - Web interface with live stream
- **`GET /snapshot`** - Capture and return a single JPEG image
- **`GET /stream`** - MJPEG video stream
- **`GET /switch`** - Switch between front and back camera (returns JSON)
- **`GET /status`** - Get server status and camera information (returns JSON)

#### Example Usage

**Capture a snapshot:**
```bash
curl http://192.168.1.100:8080/snapshot -o snapshot.jpg
```

**View stream in browser:**
```html
<img src="http://192.168.1.100:8080/stream" />
```

**Switch camera:**
```bash
curl http://192.168.1.100:8080/switch
```

**Get status:**
```bash
curl http://192.168.1.100:8080/status
```

### Integration with Surveillance Systems

#### ZoneMinder
1. Add a new monitor
2. Source Type: `Ffmpeg`
3. Source Path: `http://YOUR_PHONE_IP:8080/stream`
4. Configure other settings as needed

#### Shinobi
1. Add a new monitor
2. Input Type: `MJPEG`
3. Input URL: `http://YOUR_PHONE_IP:8080/stream`
4. Configure other settings as needed

## Permissions
The app requires the following permissions:
- **CAMERA**: To access device cameras
- **INTERNET**: To run the web server
- **ACCESS_NETWORK_STATE**: To detect network connectivity
- **ACCESS_WIFI_STATE**: To get WiFi IP address
- **WAKE_LOCK**: To keep the device awake while streaming
- **FOREGROUND_SERVICE**: To run the camera service in the background

## Technical Details
- **HTTP Server**: NanoHTTPD
- **Camera API**: AndroidX CameraX
- **Streaming Format**: MJPEG (Motion JPEG)
- **Image Format**: JPEG
- **Default Port**: 8080
- **Frame Rate**: ~10 fps

## License
MIT License - see LICENSE file for details

## Contributing
Contributions are welcome! Please feel free to submit a Pull Request.

## Troubleshooting

### Server not accessible
- Ensure your phone and client device are on the same WiFi network
- Check that no firewall is blocking port 8080
- Verify the IP address displayed in the app

### Poor streaming performance
- Ensure strong WiFi signal
- Reduce the number of concurrent stream viewers
- Close other apps to free up resources

### Camera not working
- Grant camera permissions in Android settings
- Restart the app
- Check if another app is using the camera

