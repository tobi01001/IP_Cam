# IP_Cam
Android IP-Cam - turning an old phone into a camera

## Overview
IP_Cam is a simple Android application that turns your Android phone into an IP camera accessible over your local WiFi network. It provides a web server with a REST API and MJPEG streaming capabilities, making it compatible with popular surveillance systems like ZoneMinder and Shinobi.

## Features
- **Live Camera Preview**: View what the camera sees directly in the app
- **HTTP Web Server**: Access the camera through any web browser
- **MJPEG Streaming**: Real-time video streaming compatible with surveillance systems
- **Camera Selection**: Switch between front and back cameras
- **Configurable Formats**: Choose supported resolutions from the web UI
- **Orientation Control**: Auto-detect device orientation or manually set rotation (0°, 90°, 180°, 270°)
- **Overlay & Reliability**: Battery/time overlay with auto-reconnect stream handling
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
- **`GET /formats`** - List supported resolutions for the active camera
- **`GET /setFormat?value=WIDTHxHEIGHT`** - Apply a supported resolution (omit to return to auto)
- **`GET /setRotation?value=auto|0|90|180|270`** - Set camera rotation (auto follows device orientation)

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

**Set camera rotation to 90 degrees:**
```bash
curl http://192.168.1.100:8080/setRotation?value=90
```

**Set camera rotation to auto (follow device orientation):**
```bash
curl http://192.168.1.100:8080/setRotation?value=auto
```

### Camera Orientation Control

The IP_Cam app supports both automatic and manual camera orientation control:

#### Auto-Detection Mode
By default, the camera orientation follows your device's physical orientation. As you rotate your phone:
- **Portrait (0°)**: Normal upright orientation
- **Landscape Right (90°)**: Device rotated 90° clockwise
- **Upside Down (180°)**: Device rotated 180°
- **Landscape Left (270°)**: Device rotated 90° counter-clockwise

The video stream and app preview automatically rotate to match your device's current orientation.

#### Manual Override
You can manually set a specific orientation that overrides the auto-detection:

**In the App:**
1. Start the server
2. Use the "Rotation" dropdown to select your desired orientation
3. Choose from: Auto, 0°, 90°, 180°, or 270°

**Via Web Interface:**
1. Open the camera's web interface in a browser
2. Use the "Rotation" dropdown and click "Apply Rotation"

**Via API:**
```bash
# Set to specific angle
curl http://192.168.1.100:8080/setRotation?value=90

# Return to auto-detection
curl http://192.168.1.100:8080/setRotation?value=auto
```

#### Use Cases
- **Auto Mode**: Perfect for handheld use or when the phone position changes
- **Fixed Rotation**: Ideal for permanently mounted cameras where you want consistent orientation
- **Landscape Streaming**: Force 90° or 270° for wide-angle monitoring regardless of how the phone is mounted

### Integration with Surveillance Systems

IP_Cam is designed to work seamlessly with popular video surveillance and NVR (Network Video Recorder) systems. The MJPEG stream is compatible with most surveillance software.

#### ZoneMinder

ZoneMinder is a popular open-source video surveillance system. To add your Android IP camera:

1. **Add a New Monitor**
   - Navigate to ZoneMinder's web interface
   - Click on "Add New Monitor"

2. **General Tab**
   - Name: Give your camera a descriptive name (e.g., "Android Kitchen Camera")
   - Source Type: Select **"Ffmpeg"** or **"Remote"**
   - Function: Choose based on your needs (Monitor, Modect for motion detection, etc.)

3. **Source Tab**
   - **Remote Protocol**: If using Remote source type, select "HTTP"
   - **Remote Method**: Select "Simple"
   - **Remote Host Path**: Enter your camera's stream endpoint
     ```
     YOUR_PHONE_IP:8080/stream
     ```
     Example: `192.168.1.100:8080/stream`
   - **OR Source Path** (if using Ffmpeg): Enter the full URL
     ```
     http://192.168.1.100:8080/stream
     ```
   - **Target Colorspace**: Set to "24 bit color"
   - **Capture Width/Height**: Leave blank or set to your preferred resolution (the app will auto-adjust)

4. **Storage Tab**
   - Configure according to your storage preferences

5. **Buffers Tab** (optional for performance tuning)
   - Image Buffer Size: 10-20 frames is usually sufficient
   - Pre Event Image Count: 5-10 for motion detection
   - Post Event Image Count: 5-10 for motion detection

6. **Save the Monitor**
   - Click "Save" to add the monitor
   - The live feed should appear in your ZoneMinder console

**Tips for ZoneMinder:**
- If the stream appears to lag, try reducing the buffer sizes
- For motion detection, use the "Modect" function
- Ensure your phone's screen timeout is set to never while running the camera
- Use a power source to keep the phone charged during extended use
- Consider using a dedicated WiFi network for security cameras

#### Shinobi

Shinobi is a modern, lightweight NVR written in Node.js:

1. **Add a New Monitor**
   - Log into your Shinobi interface
   - Click "Add Monitor" or the "+" icon

2. **Monitor Configuration**
   - Name: Give your camera a name
   - Input Type: Select **"MJPEG"**
   - Input URL: Enter your camera's stream URL
     ```
     http://192.168.1.100:8080/stream
     ```
   - Port: Leave blank (already in URL)
   - Path: Leave blank (already in URL)

3. **Additional Settings**
   - Mode: Choose "Watch Only" or "Record"
   - Detector: Enable if you want motion detection
   - Recording Settings: Configure as needed

4. **Save and Start**
   - Click "Save" to add the monitor
   - Click the power icon to start monitoring

**Tips for Shinobi:**
- Enable GPU acceleration if available for better performance
- Use object detection plugins for advanced motion detection
- Configure retention policies to manage storage

#### Motion (MotionEye)

For Motion or MotionEye OS users:

1. **Add Camera**
   - In MotionEye, click "Add Camera"
   - Camera Type: Select **"Network Camera"**

2. **Network Camera Configuration**
   - URL: Enter the MJPEG stream URL
     ```
     http://192.168.1.100:8080/stream
     ```
   - Keep Authentication disabled unless you add custom auth

3. **Configure Motion Detection**
   - Adjust frame rate and threshold as needed
   - Test the stream to ensure it's working

#### Blue Iris

For Blue Iris users on Windows:

1. **Add New Camera**
   - Right-click in the camera list and select "Add new camera"

2. **Camera Configuration**
   - Camera name: Enter a descriptive name
   - Make: Select **"Generic/ONVIF"** or **"MJPEG/H.264 IP camera"**
   - Model: Generic

3. **Video Configuration**
   - Network IP: `192.168.1.100` (your phone's IP)
   - Port: `8080`
   - Path: `/stream`
   - **OR** use the full URL in the path field: `http://192.168.1.100:8080/stream`

4. **Recording Settings**
   - Configure according to your needs
   - Enable motion detection if desired

#### iSpy / Agent DVR

For iSpy or Agent DVR:

1. **Add Camera**
   - Click "Add" > "IP Camera"

2. **Camera Setup**
   - Choose **"MJPEG"** as the stream type
   - URL: `http://192.168.1.100:8080/stream`

3. **Configure Recording**
   - Set up motion detection and recording schedules as needed

#### Generic MJPEG Stream Integration

For any other surveillance software that supports MJPEG streams:

- **Stream URL**: `http://YOUR_PHONE_IP:8080/stream`
- **Snapshot URL**: `http://YOUR_PHONE_IP:8080/snapshot`
- **Protocol**: HTTP
- **Format**: MJPEG (Motion JPEG)
- **Authentication**: None (consider using on trusted networks only)

**Important Security Notes:**
- This app is designed for use on **trusted local networks only**
- No authentication is currently implemented
- Do not expose the camera directly to the internet without additional security measures (VPN, reverse proxy with auth, etc.)
- Consider network isolation or VLANs for IP cameras

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
- **Orientation Detection**: OrientationEventListener for auto-rotation
- **Rotation Support**: Auto-detection or manual override (0°, 90°, 180°, 270°)

### Architecture

IP_Cam uses a service-based architecture to ensure reliable camera streaming:

**CameraService (Background Service)**
- Acts as the **single source of truth** for all camera operations
- Manages camera lifecycle and switching independently of UI
- Provides MJPEG stream to web clients via HTTP server
- Delivers live frames to MainActivity via callbacks
- Continues running in background (with foreground notification) even when app is minimized

**MainActivity (UI)**
- Displays live camera preview from CameraService
- Provides controls for starting/stopping server, switching cameras, and setting rotation
- Handles device configuration changes (orientation changes)
- Receives frame updates via callback mechanism
- Does NOT manage camera directly (preventing conflicts)

**Key Benefits:**
- **Unified Camera Control**: Single camera instance prevents resource conflicts
- **Synchronized Switching**: Camera switches from web or app update both interfaces immediately
- **Background Operation**: Server and streaming work reliably even when app is in background or closed
- **Single Stream Source**: Web stream and app preview use the same camera frames
- **Flexible Orientation**: Auto-detection or manual override for any mounting scenario

**How Camera Switching Works:**
1. User triggers switch (via app button or `/switch` web endpoint)
2. CameraService switches the camera and rebinds
3. CameraService notifies MainActivity via callback
4. Both web stream and app preview update to show new camera
5. State remains synchronized across all interfaces

**How Orientation Control Works:**
1. OrientationEventListener continuously monitors device orientation
2. In auto mode, rotation is applied based on current device angle
3. In manual mode, user-specified rotation overrides auto-detection
4. Rotation is applied to each frame using a transformation matrix
5. Both video stream and app preview reflect the selected orientation
6. Orientation setting can be changed via app UI or `/setRotation` API endpoint

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

## Frequently Asked Questions (FAQ)

### Does the server work when the app is in the background or closed?
**Yes!** The CameraService runs as a foreground service, which means:
- The server continues running even when you close/minimize the app
- You'll see a notification indicating the server is running
- The camera stream remains accessible to surveillance systems
- The service only stops when you explicitly tap "Stop Server" or force-stop the app

### Does the app preview block the web stream?
**No!** The architecture has been designed so that:
- CameraService manages a single camera instance
- Both the app preview and web stream receive frames from the same source
- There's no conflict or blocking between local preview and remote streaming
- You can have both the app open AND multiple web clients viewing the stream simultaneously

### Can I use the camera without the local app preview?
**Absolutely!** You have several options:
1. Start the server, then minimize/close the app - the stream continues in the background
2. Leave the app open but don't look at it - both preview and stream work
3. Only access via web browser/surveillance system - the app doesn't need to be visible

### How do I switch cameras remotely?
You can switch cameras in two ways:
1. **From the app**: Tap the "Switch Camera" button
2. **From web/API**: Send a GET request to `http://YOUR_PHONE_IP:8080/switch`

Both methods synchronize immediately - switching from the web updates the app preview, and vice versa.

### Why use an old phone as an IP camera?
Old Android phones make excellent IP cameras because:
- Built-in battery backup (works during power outages)
- Built-in WiFi connectivity
- High-quality camera sensors
- Free/low-cost repurposing of existing hardware
- Can be mounted anywhere with simple phone holders

### How can I keep the phone running 24/7?
For continuous operation:
1. Keep the phone plugged into power (recommended: use original charger)
2. Disable battery optimization for IP_Cam in Settings > Battery > Battery optimization and allow background activity
3. Lock/pin the app in the recent-apps view so the system does not swipe it away
4. Set Wi-Fi to stay on during sleep and keep the device where signal is strong
5. Set screen timeout to a reasonable value (or use "Stay Awake" developer option when charging)
6. Ensure good ventilation to prevent overheating; remove the case if the phone gets too warm

### Is this secure to use?
**Security considerations:**
- The app is designed for **trusted local networks only**
- No authentication is currently implemented
- **Do not expose directly to the internet** without additional security:
  - Use a VPN to access your home network remotely
  - Or set up a reverse proxy with authentication (nginx, Caddy, etc.)
  - Or use your surveillance system's built-in authentication
- Consider using network isolation/VLANs for IoT devices
- The app uses CORS headers set to "*" which is acceptable for local network use

### What's the video quality and frame rate?
- **Format**: MJPEG (Motion JPEG)
- **Frame Rate**: ~10 fps (optimized for network performance)
- **Image Quality**: JPEG compression at 80% quality
- **Resolution**: Depends on your phone's camera (usually 1080p or higher)
- Quality is a balance between bandwidth and visual clarity
