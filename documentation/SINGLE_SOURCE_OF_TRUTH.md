# Single Source of Truth Architecture

## Overview

IP_Cam implements a **strict single source of truth pattern** where `CameraService` is the ONLY component that manages camera state, operations, and lifecycle. This document explains the architecture, benefits, and implementation details.

## Architecture Principle

### The Core Rule

> **CameraService is the sole authority for ALL camera operations. No other component may access the camera directly.**

This means:
- ✅ CameraService manages the single CameraX instance
- ✅ CameraService controls all camera settings (resolution, rotation, orientation, flashlight)
- ✅ CameraService distributes frames to both MainActivity (app preview) and web clients (HTTP streams)
- ✅ CameraService notifies all interested parties when state changes
- ❌ MainActivity NEVER accesses camera directly
- ❌ MainActivity NEVER queries Camera2 API directly
- ❌ HTTP endpoints NEVER modify state directly

## Component Responsibilities

### CameraService (Single Source of Truth)

**Responsibilities:**
- Camera lifecycle management (bind, unbind, restart)
- Camera configuration (resolution, orientation, rotation)
- Frame capture and processing
- Frame distribution to app and web clients
- State persistence (SharedPreferences)
- State change notifications (callbacks)
- HTTP server management
- Web streaming (MJPEG, SSE)

**Key Methods:**
```kotlin
// Camera control - all trigger callbacks
fun switchCamera(cameraSelector: CameraSelector)
fun setResolution(resolution: Size?)
fun setCameraOrientation(orientation: String)
fun setRotation(rot: Int)
fun toggleFlashlight(): Boolean
fun setMaxConnections(max: Int): Boolean
fun setShowResolutionOverlay(show: Boolean)

// Camera queries - read-only access
fun getCurrentCamera(): CameraSelector
fun getSupportedResolutions(): List<Size>
fun getSelectedResolution(): Size?
fun getCameraOrientation(): String
fun getRotation(): Int
fun isFlashlightAvailable(): Boolean
fun isFlashlightEnabled(): Boolean
fun getMaxConnections(): Int

// Callbacks - for state change notifications
fun setOnCameraStateChangedCallback(callback: (CameraSelector) -> Unit)
fun setOnFrameAvailableCallback(callback: (Bitmap) -> Unit)
fun setOnConnectionsChangedCallback(callback: () -> Unit)
```

### MainActivity (Reactive Observer)

**Responsibilities:**
- UI display and updates
- User input handling
- Service binding/unbinding
- Permissions management
- Forwarding user actions to CameraService

**What MainActivity Does:**
- ✅ Receives camera frames via `onFrameAvailableCallback`
- ✅ Receives state changes via `onCameraStateChangedCallback`
- ✅ Receives connection updates via `onConnectionsChangedCallback`
- ✅ Calls CameraService methods when user interacts with UI
- ✅ Queries CameraService for current state to update UI

**What MainActivity Doesn't Do:**
- ❌ Never creates or binds camera instances
- ❌ Never queries Camera2 API directly
- ❌ Never modifies CameraService internal state
- ❌ Never persists settings (CameraService does this)

### HTTP Endpoints (Remote Control Interface)

**Responsibilities:**
- Accept HTTP requests from web clients
- Forward requests to CameraService methods
- Return JSON/stream responses

**Implementation Pattern:**
```kotlin
// CORRECT: Uses service method, triggers callbacks
private fun serveSwitch(): Response {
    val newCamera = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }
    
    // Use service method to ensure callbacks are triggered
    switchCamera(newCamera)
    
    return newFixedLengthResponse(...)
}

// WRONG: Direct state modification (old pattern, now removed)
private fun serveSwitch(): Response {
    currentCamera = ... // DON'T DO THIS!
    requestBindCamera() // DON'T DO THIS!
    return ...
}
```

## Callback Flow

### State Change Propagation

When any camera state changes (via app OR web):

1. **Action Initiated**: User clicks button in MainActivity OR makes HTTP request
2. **Service Method Called**: Action flows through CameraService setter method
3. **State Updated**: CameraService updates internal state
4. **Settings Persisted**: CameraService saves to SharedPreferences
5. **Callback Triggered**: CameraService invokes `onCameraStateChangedCallback`
6. **UI Updated**: MainActivity receives callback and refreshes all UI elements
7. **Web Notified**: HTTP SSE clients receive real-time updates

```
┌─────────────────────────────────────────────────────────┐
│                    User Actions                          │
│                                                          │
│   MainActivity Button    OR    HTTP Request /switch     │
└──────────┬──────────────────────────────┬───────────────┘
           │                               │
           ▼                               ▼
    ┌─────────────────────────────────────────────┐
    │        CameraService.switchCamera()         │
    │  (Single Source of Truth - One Method)      │
    └──────────────────┬──────────────────────────┘
                       │
           ┌───────────┴───────────┐
           │                       │
           ▼                       ▼
    Update Internal         Save Settings
    State (camera,          (SharedPreferences)
    resolution, etc.)
           │                       │
           └───────────┬───────────┘
                       │
                       ▼
            Trigger Callbacks
       ┌────────────────┴────────────────┐
       │                                  │
       ▼                                  ▼
onCameraStateChangedCallback      broadcastToSSEClients
       │                                  │
       ▼                                  ▼
MainActivity.updateUI()            Web UI updates
(Resolution list,                  real-time
 rotation spinner,
 flashlight button, etc.)
```

## Benefits of Single Source of Truth

### 1. **Consistency**
- App and web UI always show identical state
- No race conditions between app and web updates
- Settings changes propagate everywhere instantly

### 2. **Reliability**
- Only one camera instance prevents resource conflicts
- State changes are atomic and synchronized
- No duplicate camera bindings or access violations

### 3. **Maintainability**
- Camera logic centralized in one place
- Changes to camera behavior require updates in only one location
- Easy to debug - single point of control

### 4. **Testability**
- Mock CameraService to test MainActivity
- Test camera logic independently of UI
- Callbacks make state changes observable

### 5. **Code Reduction**
- Eliminated 40+ lines of duplicate camera query code
- Removed complex synchronization logic
- Simplified MainActivity significantly

## State Synchronization Examples

### Example 1: Camera Switch

**Scenario**: User switches camera in web UI, app UI should update

```kotlin
// HTTP endpoint receives request
GET /switch

// HTTP handler calls service method
fun serveSwitch(): Response {
    switchCamera(newCamera)  // Triggers callback
    return json("ok")
}

// Service method updates state and notifies
fun switchCamera(cameraSelector: CameraSelector) {
    currentCamera = cameraSelector
    selectedResolution = null  // Reset for new camera
    saveSettings()
    requestBindCamera()
    onCameraStateChangedCallback?.invoke(currentCamera)  // ← Notify MainActivity
}

// MainActivity callback handler updates UI
cameraService?.setOnCameraStateChangedCallback { _ ->
    runOnUiThread {
        updateUI()                        // Update camera label
        loadResolutions()                 // Reload resolution list
        loadCameraOrientationOptions()    // Reload orientation
        loadRotationOptions()             // Reload rotation
    }
}
```

**Result**: Web switches camera → App UI instantly reflects new camera state

### Example 2: Resolution Change

**Scenario**: User changes resolution in app, web UI should show new resolution

```kotlin
// MainActivity spinner selection
fun applyResolution(selection: String) {
    cameraService?.setResolution(Size(width, height))  // Triggers callback
}

// Service method updates state and notifies
fun setResolution(resolution: Size?) {
    selectedResolution = resolution
    saveSettings()
    requestBindCamera()
    onCameraStateChangedCallback?.invoke(currentCamera)  // ← Notify MainActivity
}

// MainActivity callback handler updates UI
// (Already shown above - same callback handles all state changes)

// HTTP /status endpoint returns updated resolution
GET /status
{
    "resolution": "1920x1080",  // ← Now reflects new resolution
    ...
}
```

**Result**: App changes resolution → Web /status shows new resolution immediately

### Example 3: Flashlight Toggle

**Scenario**: User toggles flashlight via web, app button should update

```kotlin
// HTTP endpoint
GET /toggleFlashlight

// HTTP handler calls service method
fun serveToggleFlashlight(): Response {
    val newState = toggleFlashlight()  // Triggers callback
    return json("Flashlight ${if (newState) "on" else "off"}")
}

// Service method updates state and notifies
fun toggleFlashlight(): Boolean {
    isFlashlightOn = !isFlashlightOn
    enableTorch(isFlashlightOn)
    saveSettings()
    onCameraStateChangedCallback?.invoke(currentCamera)  // ← Notify MainActivity
    return isFlashlightOn
}

// MainActivity callback triggers UI update
fun updateUI() {
    updateFlashlightButton()  // ← Updates button text and color
}

fun updateFlashlightButton() {
    val isEnabled = cameraService?.isFlashlightEnabled() ?: false
    flashlightButton.text = if (isEnabled) "Flashlight: ON" else "Flashlight: OFF"
    flashlightButton.backgroundTintList = if (isEnabled) orange else green
}
```

**Result**: Web toggles flashlight → App button updates instantly

## Settings Persistence

### Persisted Settings

All camera settings are persisted in SharedPreferences by CameraService:

- `cameraType`: "front" or "back"
- `cameraOrientation`: "landscape" or "portrait"
- `rotation`: 0, 90, 180, or 270
- `resolutionWidth` / `resolutionHeight`: Selected resolution
- `maxConnections`: Max simultaneous connections
- `flashlightOn`: Flashlight state (back camera only)
- `showResolutionOverlay`: Debug overlay visibility

### Persistence Flow

```kotlin
// Every setter method persists settings
fun setRotation(rot: Int) {
    rotation = rot
    saveSettings()  // ← Immediate persistence
    onCameraStateChangedCallback?.invoke(currentCamera)
}

private fun saveSettings() {
    val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
    prefs.edit().apply {
        putInt("rotation", rotation)
        putString("cameraOrientation", cameraOrientation)
        putString("cameraType", if (currentCamera == FRONT) "front" else "back")
        // ... all other settings
        apply()
    }
}

// Settings loaded on service startup
override fun onCreate() {
    loadSettings()  // Restore all settings
    startCamera()   // Apply restored settings
    ...
}
```

## Code Comparison: Before vs After

### Before: Multiple Sources of Truth ❌

```kotlin
// MainActivity had duplicate camera query logic
private fun getCameraFormatsDirectly(): List<Size> {
    val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
    // 40+ lines of Camera2 API queries
    // Duplicated from CameraService.getSupportedResolutions()
    return sizes
}

// HTTP endpoint modified state directly
private fun serveSwitch(): Response {
    currentCamera = if (currentCamera == BACK) FRONT else BACK
    selectedResolution = null
    requestBindCamera()
    onCameraStateChangedCallback?.invoke(currentCamera)  // Manually added
    return json(...)
}

// Problems:
// - MainActivity could query camera directly without service
// - HTTP endpoints directly modified internal state
// - Callbacks might be forgotten when adding new endpoints
// - State could diverge between app and web
// - Code duplication made maintenance harder
```

### After: Single Source of Truth ✅

```kotlin
// MainActivity removed all direct camera access
// Now uses CameraService exclusively
private fun loadResolutions() {
    val resolutions = cameraService?.getSupportedResolutions() ?: emptyList()
    // No direct camera queries - single source of truth
}

// HTTP endpoint uses service method
private fun serveSwitch(): Response {
    val newCamera = if (currentCamera == BACK) FRONT else BACK
    switchCamera(newCamera)  // Service method handles everything
    return json(...)
}

// Service method ensures consistency
fun switchCamera(cameraSelector: CameraSelector) {
    currentCamera = cameraSelector
    selectedResolution = null  // Reset for new camera
    saveSettings()             // Persist immediately
    requestBindCamera()        // Rebind camera
    onCameraStateChangedCallback?.invoke(currentCamera)  // Always notifies
}

// Benefits:
// ✅ One method for all camera switches (app + web)
// ✅ Callbacks always triggered (can't forget)
// ✅ Settings always persisted
// ✅ State stays synchronized everywhere
// ✅ No code duplication
```

## Testing Single Source of Truth

### Unit Test Scenarios

1. **Camera Switch Callback Test**
```kotlin
@Test
fun testCameraSwitchTriggersCallback() {
    var callbackInvoked = false
    cameraService.setOnCameraStateChangedCallback { 
        callbackInvoked = true 
    }
    
    cameraService.switchCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
    
    assertTrue(callbackInvoked)
    assertEquals(CameraSelector.DEFAULT_FRONT_CAMERA, cameraService.getCurrentCamera())
}
```

2. **Settings Persistence Test**
```kotlin
@Test
fun testRotationPersists() {
    cameraService.setRotation(90)
    
    // Simulate service restart
    val newService = createCameraService()
    
    assertEquals(90, newService.getRotation())
}
```

3. **HTTP Endpoint Consistency Test**
```kotlin
@Test
fun testHTTPAndAppUseSameMethod() {
    // Verify both paths use identical service method
    val httpResult = httpServer.serveSwitch()
    val appResult = mainActivity.switchCamera()
    
    // Both should result in same final state
    assertEquals(httpResult.camera, appResult.camera)
}
```

### Manual Testing Checklist

- [ ] **App → Web Sync**
  - [ ] Switch camera in app → Verify web /status reflects change
  - [ ] Change resolution in app → Verify web /status shows new resolution
  - [ ] Toggle flashlight in app → Verify web /status shows flashlight state
  - [ ] Change rotation in app → Verify video rotation on web

- [ ] **Web → App Sync**
  - [ ] Switch camera via /switch → Verify app UI updates immediately
  - [ ] Set resolution via /setFormat → Verify app spinner updates
  - [ ] Toggle flashlight via /toggleFlashlight → Verify app button updates
  - [ ] Set rotation via /setRotation → Verify app spinner updates

- [ ] **Persistence**
  - [ ] Change settings in app → Kill and restart app → Verify settings restored
  - [ ] Change settings via web → Restart app → Verify settings restored
  - [ ] Restart device → Verify all settings maintained

- [ ] **Race Conditions**
  - [ ] Rapidly toggle flashlight from app and web simultaneously → No crashes
  - [ ] Switch camera while changing resolution → No camera binding errors
  - [ ] Multiple web clients change settings simultaneously → Consistent final state

## Common Pitfalls to Avoid

### ❌ DON'T: Query Camera Directly

```kotlin
// BAD: MainActivity queries Camera2 API directly
private fun getFormats(): List<Size> {
    val manager = getSystemService(CAMERA_SERVICE) as CameraManager
    return manager.getCameraCharacteristics(...)
}
```

### ✅ DO: Query Through CameraService

```kotlin
// GOOD: MainActivity asks CameraService
private fun loadResolutions() {
    val resolutions = cameraService?.getSupportedResolutions() ?: emptyList()
}
```

### ❌ DON'T: Modify State Directly in HTTP Endpoints

```kotlin
// BAD: HTTP endpoint modifies internal state
private fun serveSetRotation(value: Int): Response {
    rotation = value  // Direct modification
    return json("ok")
}
```

### ✅ DO: Call Service Methods

```kotlin
// GOOD: HTTP endpoint uses service method
private fun serveSetRotation(value: Int): Response {
    setRotation(value)  // Service method handles state + callbacks
    return json("ok")
}
```

### ❌ DON'T: Forget Callbacks

```kotlin
// BAD: State changes without notification
fun setRotation(rot: Int) {
    rotation = rot
    saveSettings()
    // Oops! Forgot to notify MainActivity
}
```

### ✅ DO: Always Trigger Callbacks

```kotlin
// GOOD: State change notifies all observers
fun setRotation(rot: Int) {
    rotation = rot
    saveSettings()
    onCameraStateChangedCallback?.invoke(currentCamera)  // Always notify
}
```

## Summary

The single source of truth architecture ensures:

1. **CameraService** is the ONLY component that manages camera
2. **MainActivity** is purely reactive, receiving updates via callbacks
3. **HTTP endpoints** forward requests to service methods, never modify state directly
4. **All state changes** trigger callbacks that update both app and web UI
5. **Settings persist** immediately via SharedPreferences
6. **No code duplication** - camera logic exists in one place only

This architecture provides consistency, reliability, and maintainability while eliminating race conditions and state synchronization issues.
