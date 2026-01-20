# Android 14+ Boot Auto-Start Fix

## Overview

This document describes the solution implemented to fix the boot auto-start issue on Android 14+, where the system silently blocks activity launches from boot broadcasts.

## Problem

### Symptoms
- BootReceiver receives `BOOT_COMPLETED` broadcast successfully
- BootReceiver attempts to launch MainActivity
- `startActivity()` call completes without throwing exceptions
- **However**: MainActivity never actually starts
- Result: Camera service doesn't initialize, stream not available

### Root Cause
Android 14 introduced strict background activity launch restrictions as a privacy/security measure. Activities cannot be launched from background contexts (like boot broadcasts) without explicit user interaction. The system silently blocks these launches without logging errors or throwing exceptions.

Reference: [Android Background Activity Launch Restrictions](https://developer.android.com/guide/components/activities/background-starts)

## Solution Architecture

### Hybrid Approach: Service-First with Remote Activation

Instead of trying to launch MainActivity (which Android blocks), we:

1. **Start CameraService directly at boot** - HTTP server becomes accessible
2. **Defer camera initialization** - Camera doesn't start until explicitly triggered
3. **Provide remote activation** - User can activate camera via web interface
4. **Auto-activate on first request** - Streaming auto-triggers camera initialization

This approach:
- ✅ Complies with Android 14+ restrictions
- ✅ Service auto-starts reliably at boot
- ✅ Enables remote activation without physical device access
- ✅ Maintains backward compatibility with Android 11-13
- ✅ Preserves single source of truth architecture

## Implementation Details

### 1. BootReceiver Changes

**Before (Android 14+):**
```kotlin
// Attempted to launch MainActivity - BLOCKED by Android 14+
val activityIntent = Intent(context, MainActivity::class.java)
context.startActivity(activityIntent) // Silently fails!
```

**After (Android 14+):**
```kotlin
// Start service directly with deferred camera initialization
val serviceIntent = Intent(context, CameraService::class.java).apply {
    putExtra(CameraService.EXTRA_START_SERVER, true)
    putExtra(CameraService.EXTRA_DEFER_CAMERA_INIT, Build.VERSION.SDK_INT >= 34)
}
ContextCompat.startForegroundService(context, serviceIntent)
```

**Android 11-13:** No changes - service starts with camera immediately as before.

### 2. CameraService Changes

**New State Tracking:**
```kotlin
@Volatile private var cameraInitDeferred = false  // True when init deferred
@Volatile private var cameraActivated = false     // True once activated
```

**New Methods:**
```kotlin
override fun activateCamera(): Boolean {
    // Manually activate camera when deferred
    // Returns true if activation triggered
}

override fun isCameraActive(): Boolean {
    // Check if camera is currently running
}

override fun isCameraDeferred(): Boolean {
    // Check if camera init was deferred
}
```

**Modified Initialization:**
```kotlin
override fun onCreate() {
    // ... setup code ...
    
    serviceScope.launch {
        delay(1500)
        if (!cameraInitDeferred) {
            startCamera()  // Only start if NOT deferred
        } else {
            Log.i(TAG, "Camera initialization deferred - waiting for activation")
        }
    }
}
```

### 3. HttpServer Changes

**New Endpoint:**
```kotlin
get("/activateCamera") { serveActivateCamera() }

private suspend fun serveActivateCamera() {
    val activated = cameraService.activateCamera()
    if (activated) {
        call.respondText(
            """{"status":"ok","message":"Camera activation initiated...","cameraActive":true}""",
            ContentType.Application.Json
        )
    }
}
```

**Auto-Activation on First Request:**
```kotlin
private suspend fun serveStream() {
    // Auto-activate camera if deferred
    if (cameraService.isCameraDeferred()) {
        Log.i(TAG, "First stream request - auto-activating camera")
        cameraService.activateCamera()
        delay(CAMERA_ACTIVATION_DELAY_MS)  // 2 seconds
    }
    // ... continue with streaming ...
}

private suspend fun serveSnapshot() {
    // Same auto-activation logic
    if (cameraService.isCameraDeferred()) {
        cameraService.activateCamera()
        delay(CAMERA_ACTIVATION_DELAY_MS)
    }
    // ... return snapshot ...
}
```

**Enhanced Status Endpoint:**
```json
{
  "status": "running",
  "cameraActive": true,
  "cameraDeferred": false,
  "endpoints": [..., "/activateCamera"]
}
```

### 4. Web UI Enhancements

**Camera Status Banner** (visible only when deferred):
```html
<div id="cameraStatusDisplay" class="status-info" style="display: none;">
    <strong>⚠️ Camera Not Active:</strong> 
    <span id="cameraStatusText">Camera initialization deferred</span>
    <button id="activateCameraBtn" onclick="activateCamera()">
        🎥 Activate Camera
    </button>
    <p>Camera will auto-activate when you start streaming, 
       or click the button to activate manually.</p>
</div>
```

**JavaScript Functions:**
```javascript
// Manual activation
function activateCamera() {
    fetch('/activateCamera')
        .then(response => response.json())
        .then(data => {
            // Show success, hide banner after 3 seconds
        });
}

// Check camera status periodically
function checkCameraStatus() {
    fetch('/status')
        .then(response => response.json())
        .then(data => {
            if (data.cameraDeferred) {
                // Show activation UI
            } else {
                // Hide activation UI
            }
        });
}

// Check on load and every 10 seconds
checkCameraStatus();
setInterval(checkCameraStatus, 10000);
```

## User Experience

### Android 11-13 (Existing Behavior - Unchanged)
1. Device boots
2. BootReceiver starts CameraService with camera
3. Camera initializes automatically after 1.5 seconds
4. HTTP stream available immediately
5. **No user action required**

### Android 14+ (New Behavior)
1. Device boots
2. BootReceiver starts CameraService **without** camera
3. HTTP server starts, web interface accessible at `http://DEVICE_IP:8080`
4. User visits web interface
5. **If camera deferred:** Yellow banner shows "⚠️ Camera Not Active"
6. User has two options:
   - **Option A:** Click blue "🎥 Activate Camera" button
   - **Option B:** Start streaming directly (camera auto-activates)
7. Camera activates in 2-3 seconds
8. Banner automatically hides
9. Stream works normally
10. **Camera persists** - once activated, stays active until service stops

## API Documentation

### New Endpoint: `/activateCamera`

**Purpose:** Manually activate camera when initialization was deferred.

**Method:** `GET`

**Response:**
```json
// Success
{
  "status": "ok",
  "message": "Camera activation initiated successfully. Camera should be ready in 2-3 seconds.",
  "cameraActive": true,
  "activated": true
}

// Already active
{
  "status": "ok",
  "message": "Camera already active",
  "cameraActive": true,
  "wasDeferred": false
}

// Error
{
  "status": "error",
  "message": "Failed to activate camera...",
  "cameraActive": false,
  "activated": false
}
```

### Enhanced Endpoint: `/status`

**New Fields:**
- `cameraActive` (boolean): True if camera is currently running
- `cameraDeferred` (boolean): True if camera initialization was deferred
- `endpoints` (array): Now includes `/activateCamera`

**Example:**
```json
{
  "status": "running",
  "server": "Ktor",
  "deviceName": "IP_CAM_Pixel",
  "camera": "back",
  "cameraActive": false,
  "cameraDeferred": true,
  "url": "http://192.168.1.100:8080",
  "endpoints": [..., "/activateCamera"]
}
```

## Testing Guide

### Test on Android 14 (API 34)

**Scenario 1: Manual Activation**
1. Enable auto-start in app
2. Reboot device: `adb shell reboot`
3. Wait for boot (device can remain locked)
4. Check service status: `adb logcat -s BootReceiver:* CameraService:* | grep -E "Boot|Camera"`
   - Expected: "Service started", "Camera initialization deferred"
5. Open browser on another device: `http://DEVICE_IP:8080`
6. Verify yellow banner shows "⚠️ Camera Not Active"
7. Click "🎥 Activate Camera" button
8. Wait 3 seconds
9. Verify banner disappears
10. Click "Start Stream"
11. Verify stream works

**Scenario 2: Auto-Activation**
1. Reboot device with auto-start enabled
2. Open browser: `http://DEVICE_IP:8080`
3. Immediately click "Start Stream" (don't wait)
4. Verify banner shows briefly
5. Verify stream starts after 2-3 seconds
6. Verify banner disappears

**Scenario 3: Status API**
```bash
# After boot, before activation
curl http://DEVICE_IP:8080/status | jq '.cameraActive, .cameraDeferred'
# Expected: false, true

# Activate camera
curl http://DEVICE_IP:8080/activateCamera
# Expected: {"status":"ok",...}

# Check status again
curl http://DEVICE_IP:8080/status | jq '.cameraActive, .cameraDeferred'
# Expected: true, false
```

**Scenario 4: Persistence**
1. Activate camera via web UI
2. Stop and restart service: 
   - `adb shell am force-stop com.ipcam`
   - Service should auto-restart (START_STICKY)
3. Check status: `curl http://DEVICE_IP:8080/status`
4. Verify `cameraActive: true` (camera stays active)

### Test on Android 11-13 (API 30-33)

**Backward Compatibility:**
1. Enable auto-start
2. Reboot device
3. Check logs: `adb logcat -s BootReceiver:* CameraService:*`
   - Expected: "Starting service directly", "Camera provider initialized"
4. Open browser: `http://DEVICE_IP:8080`
5. Verify NO yellow banner visible
6. Click "Start Stream"
7. Verify stream works immediately
8. Verify behavior unchanged from before this fix

### Test Edge Cases

**Multiple Activation Attempts:**
```bash
curl http://DEVICE_IP:8080/activateCamera  # First call
curl http://DEVICE_IP:8080/activateCamera  # Second call
# Second call should return: "Camera already active"
```

**Snapshot Before Activation:**
```bash
# After boot, camera deferred
curl http://DEVICE_IP:8080/snapshot -o test.jpg
# Should auto-activate camera and return snapshot after 2 seconds
```

**Service Restart:**
```bash
# Activate camera
curl http://DEVICE_IP:8080/activateCamera

# Kill service
adb shell am force-stop com.ipcam

# Wait for auto-restart (START_STICKY)
sleep 5

# Check if camera still active
curl http://DEVICE_IP:8080/status | jq '.cameraActive'
# Expected: true (camera persists)
```

## Troubleshooting

### Camera doesn't activate after clicking button

**Check logs:**
```bash
adb logcat -s HttpServer:* CameraService:* | grep -E "activate|Camera"
```

**Common causes:**
- Camera permission not granted
- Service crashed/stopped
- Network connectivity issue

### Banner doesn't hide after activation

**Check status API:**
```bash
curl http://DEVICE_IP:8080/status
```

**If `cameraActive: false` after activation:**
- Camera may have failed to initialize
- Check logcat for camera errors
- Try manual activation again

### Stream doesn't work after activation

**Wait 3-5 seconds** - Camera initialization takes time.

**Check camera state:**
```bash
adb logcat -s CameraService:* | grep "Camera provider"
```

**Expected:** "Camera provider initialized successfully"

## Constants and Configuration

```kotlin
// CameraService.kt
const val EXTRA_DEFER_CAMERA_INIT = "defer_camera_init"

// HttpServer.kt
const val CAMERA_ACTIVATION_DELAY_MS = 2000L

// JavaScript (index page)
const CAMERA_STATUS_POLL_INTERVAL_MS = 10000
```

## Future Improvements

### Option 1: Notification Action
Add "Activate Camera" action to foreground service notification:
```kotlin
val activateIntent = PendingIntent.getService(
    context,
    0,
    Intent(context, CameraService::class.java).apply {
        action = "ACTIVATE_CAMERA"
    },
    PendingIntent.FLAG_IMMUTABLE
)

notification.addAction(
    R.drawable.ic_camera,
    "Activate Camera",
    activateIntent
)
```

### Option 2: Quick Settings Tile
Implement a Quick Settings tile for one-tap camera activation.

### Option 3: SSE for Camera Status
Use Server-Sent Events instead of polling:
```javascript
eventSource.addEventListener('cameraState', function(event) {
    const data = JSON.parse(event.data);
    updateCameraStatusUI(data.cameraActive, data.cameraDeferred);
});
```

## Related Documentation

- [Android Background Activity Launch Restrictions](https://developer.android.com/guide/components/activities/background-starts)
- [Foreground Service Restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Android 14 Behavior Changes](https://developer.android.com/about/versions/14/behavior-changes-14)
- [BOOT_RECEIVER_FIX.md](BOOT_RECEIVER_FIX.md) - Direct Boot implementation
- [API35_BOOT_FIX.md](API35_BOOT_FIX.md) - Android 15 restrictions

## Summary

This fix implements a **hybrid service-first approach** that:

1. ✅ **Complies** with Android 14+ restrictions (no activity launch from boot)
2. ✅ **Starts reliably** at boot on all Android versions (11-15+)
3. ✅ **Enables remote activation** without physical device access
4. ✅ **Auto-activates** on first stream/snapshot for convenience
5. ✅ **Maintains architecture** - single source of truth preserved
6. ✅ **Backward compatible** - Android 11-13 behavior unchanged

**Result:** IP camera functionality fully restored on Android 14+ with improved usability.
