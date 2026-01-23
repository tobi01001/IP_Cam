# Web UI Testing Guide

This guide provides comprehensive testing instructions for the refactored web interface to ensure all functionality works correctly after separating HTML/CSS/JS into modular files.

## Pre-Testing Setup

### 1. Build and Install
```bash
# Build the APK
./gradlew assembleDebug

# Install on Android device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Or use Android Studio:
# Run > Run 'app' (Shift+F10)
```

### 2. Grant Permissions
- Camera permission
- Disable battery optimization (optional but recommended)

### 3. Start the Service
- Open the IP_Cam app
- Note the device IP address displayed
- Server should start automatically

## Test Categories

## 1. Basic Page Load Tests

### Test 1.1: Homepage Loads
**URL:** `http://<device-ip>:8080/`

**Expected Results:**
- ✅ Page loads without errors
- ✅ Template variables are replaced:
  - Title shows device name or "IP Camera Server"
  - Version info displays correctly (e.g., "1.2 | 20240123-070900")
  - Connection count shows (e.g., "0/32")
- ✅ CSS is loaded (gradient background, styled cards)
- ✅ JavaScript is loaded (tabs are interactive)

**Verification:**
1. Open browser DevTools (F12)
2. Check Console for errors (should be none)
3. Check Network tab:
   - `/` returns 200 OK with HTML
   - `/styles.css` returns 200 OK
   - `/script.js` returns 200 OK

### Test 1.2: Static Assets Load
**URLs to test:**
- `http://<device-ip>:8080/styles.css`
- `http://<device-ip>:8080/script.js`

**Expected Results:**
- ✅ CSS file loads with Content-Type: text/css
- ✅ JS file loads with Content-Type: text/javascript
- ✅ No 404 errors
- ✅ Files contain expected content

## 2. UI Component Tests

### Test 2.1: Navigation Tabs
**Steps:**
1. Click each tab: Live Stream, Camera Controls, Stream Settings, RTSP, Server Management, API Reference
2. Verify only one tab content is visible at a time
3. Verify active tab has different styling

**Expected Results:**
- ✅ Tab switching works smoothly
- ✅ Active tab is highlighted
- ✅ Content changes for each tab
- ✅ No JavaScript errors in console

### Test 2.2: Status Dashboard
**Location:** Always visible at top of page

**Expected Results:**
- ✅ Server Status shows "Running" (green badge)
- ✅ Connections shows "X/Y" format
- ✅ Battery Status loads and updates
- ✅ Streaming Status shows "Active"
- ✅ Camera FPS displays (updates in real-time)
- ✅ MJPEG FPS displays (updates in real-time)
- ✅ RTSP FPS displays (updates in real-time)
- ✅ CPU Usage displays (updates in real-time)

## 3. Live Stream Tab Tests

### Test 3.1: MJPEG Stream
**Steps:**
1. Go to "Live Stream" tab
2. Click "Start Stream" button
3. Verify video feed appears
4. Click "Fullscreen" button
5. Press ESC to exit fullscreen
6. Click "Refresh" to reload stream
7. Click "Stop Stream" to stop

**Expected Results:**
- ✅ Stream starts when button clicked
- ✅ Video displays in container
- ✅ Button changes to "Stop Stream"
- ✅ Fullscreen works (video fills screen)
- ✅ ESC exits fullscreen
- ✅ Refresh reloads stream without errors
- ✅ Stop removes video feed

### Test 3.2: Stream URL
**Direct URL:** `http://<device-ip>:8080/stream`

**Expected Results:**
- ✅ MJPEG stream opens directly
- ✅ Works in VLC player
- ✅ Works in browser
- ✅ Content-Type: multipart/x-mixed-replace

## 4. Camera Controls Tab Tests

### Test 4.1: Camera Switch
**Steps:**
1. Go to "Camera Controls" tab
2. Click "Switch Camera"
3. Observe camera switch (front ↔ back)

**Expected Results:**
- ✅ Camera switches successfully
- ✅ Success message displayed
- ✅ If stream active, it updates to new camera
- ✅ API call to `/switch` succeeds

### Test 4.2: Flashlight Toggle
**Steps:**
1. Click "Toggle Flashlight" button
2. Observe device flashlight
3. Click again to toggle off

**Expected Results:**
- ✅ Flashlight turns on/off
- ✅ Success message displayed
- ✅ Works only on back camera (expected behavior)

### Test 4.3: Resolution Change
**Steps:**
1. Select different resolution from dropdown
2. Click "Apply Format"
3. Verify resolution changes

**Expected Results:**
- ✅ Available resolutions populate dropdown
- ✅ Format changes successfully
- ✅ Status message shows new resolution
- ✅ Stream reflects new resolution

### Test 4.4: Camera Orientation
**Steps:**
1. Select "Portrait" or "Landscape"
2. Click "Apply Orientation"

**Expected Results:**
- ✅ Orientation changes
- ✅ Success message displayed
- ✅ Stream updates accordingly

### Test 4.5: Rotation
**Steps:**
1. Select rotation angle (0°, 90°, 180°, 270°)
2. Click "Apply Rotation"

**Expected Results:**
- ✅ Video rotates correctly
- ✅ Works in both MJPEG and RTSP streams

## 5. Stream Settings Tab Tests

### Test 5.1: OSD Overlays
**Steps:**
1. Go to "Stream Settings" tab
2. Toggle each overlay checkbox:
   - Date/Time (Top Left)
   - Battery (Top Right)
   - Resolution (Bottom Right)
   - FPS (Bottom Left)
3. Observe changes in stream

**Expected Results:**
- ✅ Each overlay can be toggled on/off
- ✅ Changes reflect immediately in stream
- ✅ Overlays appear in correct positions
- ✅ Settings persist across page reloads

### Test 5.2: MJPEG FPS Setting
**Steps:**
1. Select different FPS value (1, 5, 10, 15, 20, 24, 30, 60)
2. Click "Apply FPS"
3. Observe FPS counter in status dashboard

**Expected Results:**
- ✅ Target FPS can be set
- ✅ Actual FPS approaches target (may vary based on device)
- ✅ Success message displayed

### Test 5.3: RTSP FPS Setting
**Steps:**
1. Select RTSP FPS value
2. Click "Apply FPS"
3. Note message about RTSP restart requirement

**Expected Results:**
- ✅ FPS setting changes
- ✅ Warning displayed about restart requirement
- ✅ After RTSP restart, new FPS is active

## 6. RTSP Tab Tests

### Test 6.1: Enable RTSP
**Steps:**
1. Go to "RTSP" tab
2. Click "Enable RTSP"
3. Wait for confirmation
4. Click "Check Status"

**Expected Results:**
- ✅ RTSP server starts
- ✅ RTSP URL displayed (rtsp://<ip>:8554/camera)
- ✅ Status shows "Running"
- ✅ Port and codec info displayed

### Test 6.2: RTSP Stream Playback
**Tools:** VLC, FFmpeg, or any RTSP client

**URL:** `rtsp://<device-ip>:8554/camera`

**Steps:**
1. Open VLC: Media > Open Network Stream
2. Enter RTSP URL
3. Click Play

**Expected Results:**
- ✅ Stream opens in VLC
- ✅ Video plays smoothly
- ✅ Hardware-accelerated H.264 encoding
- ✅ ~500ms-1s latency

### Test 6.3: Bitrate Settings
**Steps:**
1. Enter bitrate value (0.5 - 20 Mbps)
2. Click "Set Bitrate"
3. Select bitrate mode (VBR, CBR, CQ)
4. Click "Set Mode"

**Expected Results:**
- ✅ Bitrate changes accepted
- ✅ Mode changes accepted
- ✅ Success messages displayed
- ✅ Settings take effect (may require RTSP restart)

### Test 6.4: Disable RTSP
**Steps:**
1. Click "Disable RTSP"
2. Wait for confirmation
3. Click "Check Status"

**Expected Results:**
- ✅ RTSP server stops
- ✅ Status shows "Stopped"
- ✅ VLC/clients disconnect gracefully

## 7. Server Management Tab Tests

### Test 7.1: View Active Connections
**Steps:**
1. Go to "Server Management" tab
2. View active connections list
3. Start stream or SSE connection
4. Click "Refresh Connections"

**Expected Results:**
- ✅ Connections list displays
- ✅ Shows type (MJPEG, SSE, etc.)
- ✅ Shows duration
- ✅ Shows client identifier
- ✅ Updates when refreshed

### Test 7.2: Max Connections Setting
**Steps:**
1. Select max connections value (4, 8, 16, 32, 64, 100)
2. Click "Apply"
3. Note restart requirement message

**Expected Results:**
- ✅ Setting changes
- ✅ Warning about restart displayed
- ✅ After restart, new limit is active

### Test 7.3: Server Restart
**Steps:**
1. Click "Restart Server" (red button)
2. Confirm action
3. Wait for restart
4. Reload page

**Expected Results:**
- ✅ Server restarts (brief interruption)
- ✅ Page reloads automatically or shows reconnect message
- ✅ All connections resume
- ✅ Settings preserved

## 8. API Reference Tab Tests

### Test 8.1: Clickable Endpoints
**Steps:**
1. Go to "API Reference" tab
2. Click various endpoint links
3. Verify they open in new tabs

**Expected Results:**
- ✅ Links are clickable
- ✅ Open in new tabs
- ✅ Return valid JSON or appropriate responses
- ✅ Status endpoint returns full JSON status

## 9. Real-Time Updates Tests

### Test 9.1: Server-Sent Events (SSE)
**Steps:**
1. Open browser DevTools > Network tab
2. Filter for "events"
3. Observe SSE connection
4. Trigger events (switch camera, change settings)

**Expected Results:**
- ✅ SSE connection established to `/events`
- ✅ Updates received in real-time
- ✅ Connection stays alive
- ✅ Stats update without page reload

### Test 9.2: Auto-Refresh Status
**Steps:**
1. Leave page open
2. Observe status dashboard values
3. They should update every ~2 seconds

**Expected Results:**
- ✅ FPS values update continuously
- ✅ CPU usage updates
- ✅ Connection count updates
- ✅ Battery status updates

## 10. Responsive Design Tests

### Test 10.1: Desktop Browser
**Screen Sizes:** 1920x1080, 1366x768

**Expected Results:**
- ✅ Layout fits screen nicely
- ✅ All tabs visible
- ✅ Stats grid shows 4 columns
- ✅ Stream container sized appropriately

### Test 10.2: Tablet
**Screen Sizes:** 768px width

**Expected Results:**
- ✅ Tabs wrap if needed
- ✅ Stats grid adjusts to 2 columns
- ✅ Controls remain accessible
- ✅ Stream scales appropriately

### Test 10.3: Mobile Phone
**Screen Sizes:** 375px width

**Expected Results:**
- ✅ Tabs stack vertically or wrap
- ✅ Stats become single column
- ✅ Buttons remain tappable
- ✅ Stream fills width
- ✅ Text remains readable

## 11. Error Handling Tests

### Test 11.1: Network Errors
**Steps:**
1. Disconnect device from Wi-Fi
2. Observe page behavior

**Expected Results:**
- ✅ Graceful error messages
- ✅ SSE reconnects when network returns
- ✅ No page crashes

### Test 11.2: Invalid Requests
**Steps:**
1. Visit non-existent endpoint: `/nonexistent`
2. Try invalid parameters: `/setFormat?value=invalid`

**Expected Results:**
- ✅ 404 error for missing endpoints
- ✅ Error messages for invalid parameters
- ✅ Page remains functional

## 12. Cross-Browser Tests

Test in multiple browsers:
- ✅ Chrome/Chromium
- ✅ Firefox
- ✅ Safari
- ✅ Edge
- ✅ Mobile browsers (Chrome, Safari)

**Expected Results:**
- ✅ All features work in all browsers
- ✅ CSS renders correctly
- ✅ JavaScript functions work
- ✅ MJPEG streams work (note: some mobile browsers may have issues)

## 13. Performance Tests

### Test 13.1: Page Load Time
**Steps:**
1. Clear browser cache
2. Open DevTools > Network
3. Load page
4. Check load time

**Expected Results:**
- ✅ Page loads in < 2 seconds
- ✅ Assets load efficiently
- ✅ No blocking resources

### Test 13.2: Memory Usage
**Steps:**
1. Open DevTools > Performance/Memory
2. Load page and use features
3. Monitor memory usage

**Expected Results:**
- ✅ No memory leaks
- ✅ Stable memory usage over time
- ✅ Efficient resource cleanup

## Test Checklist

Print or copy this checklist for manual testing:

### Basic Functionality
- [ ] Homepage loads correctly
- [ ] CSS loads and applies
- [ ] JavaScript loads and executes
- [ ] Template variables replaced correctly
- [ ] All 6 tabs switch correctly

### Camera Features
- [ ] MJPEG stream starts/stops
- [ ] Camera switch works
- [ ] Flashlight toggles
- [ ] Resolution changes apply
- [ ] Rotation changes apply

### Settings & Configuration
- [ ] OSD overlays toggle
- [ ] MJPEG FPS changes
- [ ] RTSP FPS changes
- [ ] Max connections setting works

### RTSP Features
- [ ] RTSP enables successfully
- [ ] RTSP URL accessible in VLC
- [ ] Bitrate changes apply
- [ ] Bitrate mode changes apply
- [ ] RTSP disables cleanly

### Real-Time Updates
- [ ] SSE connection established
- [ ] Status dashboard updates
- [ ] Connection list updates
- [ ] FPS counters update

### Responsive Design
- [ ] Works on desktop (1920x1080)
- [ ] Works on tablet (768px)
- [ ] Works on mobile (375px)

### Cross-Browser
- [ ] Chrome/Chromium
- [ ] Firefox
- [ ] Safari
- [ ] Edge
- [ ] Mobile browsers

### Error Handling
- [ ] Network errors handled gracefully
- [ ] Invalid requests show errors
- [ ] Server restart works correctly

## Reporting Issues

If you find any issues during testing, report them with:
1. **Browser & Version:** e.g., Chrome 120.0.6099.109
2. **Device & OS:** e.g., Android 13, Pixel 6
3. **Steps to Reproduce:** Detailed steps
4. **Expected Behavior:** What should happen
5. **Actual Behavior:** What actually happened
6. **Screenshots:** If applicable
7. **Console Errors:** From browser DevTools

## Success Criteria

All tests pass if:
- ✅ 100% of basic functionality tests pass
- ✅ 95%+ of feature tests pass
- ✅ No critical errors in browser console
- ✅ Responsive design works across all screen sizes
- ✅ Cross-browser compatibility confirmed
- ✅ Performance is acceptable (page loads < 2s)

## Conclusion

This comprehensive test plan ensures the refactored web UI maintains full functionality while providing a better development experience through separated, maintainable files.
