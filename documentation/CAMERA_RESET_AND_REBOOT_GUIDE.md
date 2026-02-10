# Camera Reset and Device Reboot Guide
## Practical Guide for SM-A528B and Other Devices

**Last Updated:** 2026-02-10  
**Status:** Investigation Complete, Improvements Implemented

---

## Quick Answer

### Camera Reset (When Stream Stops Working)

**Does it require root?** ❌ **NO** - Works without root or Device Owner

**When to use:** Stream frozen, camera not responding, black screen

**How to use:**
1. **In App:** Press "Reset Camera" button
2. **Via Web:** Click "Reset Camera" button
3. **Via API:** `GET http://DEVICE_IP:8080/resetCamera`

**What it does:** Restarts the camera service within the app (unbind camera, clear provider, rebind)

**What it does NOT do:** Fix system-level camera failures (requires device reboot)

### Device Reboot (When Camera Reset Fails)

**Does it require root?** ❌ **NO** - Works with Device Owner mode (no root needed)

**Requirements:**
- ✅ App must be set as **Device Owner** (NOT just Device Admin)
- ✅ Device must be **unlocked** (lock screen blocks reboot on most devices)

**How to use:**
1. **Check capability first:** Click "Reboot Diagnostics" button
2. **If Device Owner + Unlocked:** Press "Reboot Device" button
3. **Via API:** `GET http://DEVICE_IP:8080/reboot`

**What happens:** App tries 3 different reboot methods automatically

---

## Understanding Device Owner vs Device Admin

### Device Admin (Basic)
- Can lock screen
- Can wipe data
- **Cannot reboot device** ❌
- Easier to set up

### Device Owner (Advanced)
- All Device Admin capabilities
- **Can reboot device** ✅
- Can install updates silently
- Requires factory reset device to set up
- Best for dedicated surveillance devices

### Check Your Status

**Via Web UI:**
Click "Reboot Diagnostics" button - shows:
```
Device Owner: YES ✓ or NO ✗
Device Admin: YES or NO
```

**Via ADB:**
```bash
adb shell dpm list-owners
```

**Expected Output (Device Owner):**
```
Device owner: ComponentInfo{com.ipcam/com.ipcam.DeviceAdminReceiver}
```

**Expected Output (Device Admin only):**
```
Device admin: ComponentInfo{com.ipcam/com.ipcam.DeviceAdminReceiver}
```
OR
```
No device owner set
```

---

## Troubleshooting Guide

### Problem 1: Camera Reset Doesn't Restore Stream

**Symptoms:**
- Reset completes successfully
- Stream still doesn't work
- App says camera is "BOUND" but no video

**Diagnosis:**
1. Click "Camera Diagnostics" button
2. Check these values:
   - `permissionGranted`: Must be `true`
   - `cameraState`: Should be "BOUND"
   - `lastFrameSizeBytes`: Should be > 0

**Solution:**

If `permissionGranted` is `false`:
```
Settings → Apps → IP_Cam → Permissions → Camera → Allow
```

If `permissionGranted` is `true` but still no stream:
- System camera service is likely dead
- **Solution:** Reboot the device (see Problem 2)

### Problem 2: Device Reboot Button Does Nothing

**Step 1: Check Device Owner Status**

Click "Reboot Diagnostics" button and check output:

```
Device Owner: NO ✗
Device Admin: YES
```

**Diagnosis:** App is Device Admin, not Device Owner. Device Admin cannot reboot.

**Solution:** Set app as Device Owner (see "Setting Up Device Owner" below)

---

**Step 2: Check Device Lock Status**

Click "Reboot Diagnostics" button and check output:

```
Device Owner: YES ✓
Device Locked: YES (unlock required)
```

**Diagnosis:** Android security policy prevents reboot when device is locked

**Solution:** 
1. Unlock the device (swipe screen, enter PIN/pattern)
2. Try reboot again
3. OR disable lock screen via Device Owner:
   ```kotlin
   Settings → Security → Screen lock → None
   ```

---

**Step 3: Samsung Knox Restrictions**

Click "Reboot Diagnostics" button and check output:

```
Device Owner: YES ✓
Device Locked: NO ✓
Manufacturer: samsung
Knox: 3.7 (or any version)
```

**Diagnosis:** Samsung Knox may be blocking reboot even with Device Owner

**What the app does:** Automatically tries 3 different reboot methods:
1. DevicePolicyManager.reboot() - Official API
2. PowerManager.reboot() - Alternative method
3. Shell exec "reboot" - Last resort

**If all methods fail:**
- Check logcat for detailed error messages
- Knox may have additional restrictions
- Consider manual reboot: Hold Power button → Restart

---

### Problem 3: Camera Diagnostics Shows Issues

**Example Output:**
```
Camera Diagnostics:

State: ERROR
Consumers: 3
Has Provider: false
Permission: DENIED
MJPEG Clients: 2
```

**Issue 1: Permission DENIED**
```
Settings → Apps → IP_Cam → Permissions → Camera → Allow
```

**Issue 2: State = ERROR**
- Camera failed to initialize
- Try "Reset Camera"
- If still fails, reboot device

**Issue 3: Consumers > 0 but Has Provider = false**
- Camera provider lost
- Try "Reset Camera"
- Camera may be in use by another app

---

## Setting Up Device Owner

### Prerequisites
- ⚠️ **Factory reset device** (or use fresh device without accounts)
- USB debugging enabled
- ADB installed on computer
- No Google accounts on device
- No other device admins active

### Step-by-Step Setup

**1. Factory Reset Device**
```
Settings → System → Reset options → Erase all data (factory reset)
```

**2. Skip All Setup Steps**
- Don't add Google account
- Don't set up WiFi (or use temporary network)
- Don't add lock screen (or set to "None")

**3. Enable USB Debugging**
```
Settings → About phone → Tap "Build number" 7 times
Settings → System → Developer options → USB debugging → ON
```

**4. Install IP_Cam APK**
```bash
adb install IP_Cam.apk
```

**5. Grant Camera Permission**
Open app, allow camera permission when prompted

**6. Set as Device Owner**
```bash
adb shell dpm set-device-owner com.ipcam/.DeviceAdminReceiver
```

**Expected Output:**
```
Success: Device owner set to package com.ipcam
```

**7. Verify Device Owner**
```bash
adb shell dpm list-owners
```

**Expected Output:**
```
Device owner: ComponentInfo{com.ipcam/com.ipcam.DeviceAdminReceiver}
```

**8. Test Reboot Capability**
- Open web UI: `http://DEVICE_IP:8080`
- Click "Reboot Diagnostics"
- Should show: `Device Owner: YES ✓`
- Should show: `Reboot Possible: YES ✓`

---

## Using the New Diagnostic Features

### Camera Diagnostics

**Access:**
- Web UI: Click "Camera Diagnostics" button
- API: `GET http://DEVICE_IP:8080/diagnostics/camera`

**Sample Output:**
```json
{
  "status": "ok",
  "cameraState": "BOUND",
  "consumerCount": 3,
  "hasLastFrame": true,
  "lastFrameSizeBytes": 45678,
  "currentFps": 10.2,
  "permissionGranted": true,
  "mjpegClients": 2,
  "rtspClients": 1,
  "rtspEnabled": true,
  "serverUrl": "http://192.168.1.100:8080",
  "deviceName": "IP_Cam_Kitchen"
}
```

**What to check:**
- ✅ `cameraState`: "BOUND" = good, "ERROR" = problem
- ✅ `permissionGranted`: Must be `true`
- ✅ `lastFrameSizeBytes`: > 0 = camera working
- ✅ `currentFps`: > 0 = frames flowing

### Reboot Diagnostics

**Access:**
- Web UI: Click "Reboot Diagnostics" button  
- API: `GET http://DEVICE_IP:8080/diagnostics/reboot`

**Sample Output (Reboot Capable):**
```json
{
  "status": "ok",
  "diagnostics": {
    "isDeviceOwner": true,
    "isDeviceAdmin": true,
    "isDeviceLocked": false,
    "deviceManufacturer": "samsung",
    "deviceModel": "SM-A528B",
    "androidVersion": 13,
    "androidVersionName": "13",
    "selinuxStatus": "Enforcing",
    "knoxVersion": "3.9",
    "rebootPossible": true,
    "blockingReason": null
  }
}
```

**Sample Output (Cannot Reboot):**
```json
{
  "status": "ok",
  "diagnostics": {
    "isDeviceOwner": false,
    "isDeviceAdmin": true,
    "isDeviceLocked": false,
    "rebootPossible": false,
    "blockingReason": "Not Device Owner (only Device Admin)"
  }
}
```

---

## API Endpoints

### Camera Reset
```bash
curl http://DEVICE_IP:8080/resetCamera
```

**Success Response:**
```json
{
  "status": "ok",
  "cameraState": "BOUND",
  "message": "Camera reset successfully initiated"
}
```

**Failure Response:**
```json
{
  "status": "error",
  "cameraState": "ERROR",
  "message": "Camera reset failed"
}
```

### Device Reboot
```bash
curl http://DEVICE_IP:8080/reboot
```

**Success Response:**
```json
{
  "status": "ok",
  "message": "Device rebooting..."
}
```

**Failure Response (Not Device Owner):**
```json
{
  "status": "error",
  "error": "not_device_owner",
  "message": "Reboot requires Device Owner mode. App is only Device Admin."
}
```

**Failure Response (Device Locked):**
```json
{
  "status": "error",
  "error": "device_locked",
  "message": "Device must be unlocked to reboot"
}
```

### Camera Diagnostics
```bash
curl http://DEVICE_IP:8080/diagnostics/camera
```

### Reboot Diagnostics
```bash
curl http://DEVICE_IP:8080/diagnostics/reboot
```

---

## Samsung SM-A528B Specific Notes

### Device Information
- **Model:** Samsung Galaxy A52s 5G
- **Knox:** Likely version 3.7 or 3.9
- **Known Issues:** 
  - Knox may restrict some Device Owner operations
  - Camera HAL can enter hung state under heavy load
  - SELinux policies stricter than stock Android

### Recommended Approach for SM-A528B

**1. For Camera Issues:**
   - Try "Camera Reset" first
   - If no improvement after 10 seconds → reboot needed

**2. For Reboot Issues:**
   - Ensure device is unlocked
   - Device Owner properly set
   - Check Knox restrictions via diagnostics

**3. Preventive Maintenance:**
   - Schedule automatic reboot (3 AM daily) to prevent accumulation of issues
   - Monitor camera diagnostics periodically
   - Keep device unlocked or disable lock screen for unattended operation

### Samsung Knox Workarounds

If reboot fails with Device Owner:

**Option 1:** Disable device lock
```kotlin
Device Owner can disable keyguard:
setKeyguardDisabled(adminComponent, true)
```

**Option 2:** Manual reboot
```
Hold Power button → Restart
```

**Option 3:** Scheduled reboot
```
Use AlarmManager + Device Owner to reboot at low-usage time (e.g., 3 AM)
```

---

## When Root is Actually Required

### Operations That Need Root

❌ **Restart system camera service**
```bash
# Requires root
adb shell su -c "stop cameraserver"
adb shell su -c "start cameraserver"
```

❌ **Override SELinux policies**
```bash
# Requires root
adb shell su -c "setenforce 0"
```

❌ **Force reboot when Device Owner methods fail**
```bash
# Requires root
adb shell su -c "reboot"
```

### Operations That DON'T Need Root

✅ **Camera reset** - Works without root or Device Owner

✅ **Device reboot** - Works with Device Owner (no root)

✅ **Silent app updates** - Works with Device Owner (no root)

✅ **Change device settings** - Works with Device Owner (no root)

---

## Comparison: Device Admin vs Device Owner vs Root

| Operation | Device Admin | Device Owner | Root |
|-----------|--------------|--------------|------|
| Camera Reset | ✅ | ✅ | ✅ |
| Device Reboot | ❌ | ✅ | ✅ |
| Silent Updates | ❌ | ✅ | ✅ |
| Disable Lock Screen | ❌ | ✅ | ✅ |
| Restart Camera Service | ❌ | ❌ | ✅ |
| Modify SELinux | ❌ | ❌ | ✅ |
| Access /system | ❌ | ❌ | ✅ |
| Required for IP_Cam | ❌ | ✅* | ❌ |

*Device Owner required for reboot; optional for other features

---

## Frequently Asked Questions

### Q: Why can't my app reboot even though I'm Device Admin?

**A:** Device Admin ≠ Device Owner. Device Admin has basic privileges (lock screen, wipe data) but cannot reboot. Only Device Owner can reboot without root.

To check: `adb shell dpm list-owners`
- "Device owner" = can reboot ✅
- "Device admin" or "No device owner" = cannot reboot ❌

---

### Q: Can I set Device Owner without factory reset?

**A:** Usually no. Android requires device to be "not provisioned" (no accounts, fresh state) to set Device Owner. Workarounds exist but are unreliable.

Exception: Some custom ROMs or rooted devices may allow it.

---

### Q: Will Device Owner mode affect other apps?

**A:** By default, no. Device Owner grants privileges to your app, not restrictions to others. However, Samsung Knox may apply default restrictions. The app clears these restrictions automatically on Device Owner setup.

---

### Q: My device reboots but app doesn't auto-start after reboot. Why?

**A:** Check these:
1. Auto-start enabled in app settings
2. Boot receiver is working (`adb logcat | grep BootReceiver`)
3. Battery optimization disabled for app
4. Some devices block boot receivers - check manufacturer settings

---

### Q: Camera reset works but stream returns after 1 hour. Why?

**A:** Likely system camera service degrading over time. This is a known Android issue on some devices. Solutions:
1. Enable watchdog monitoring (already enabled in app)
2. Schedule automatic reboot (3 AM daily)
3. Monitor with camera diagnostics endpoint

---

### Q: Can I remove Device Owner without factory reset?

**A:** Sometimes. Try:
```bash
adb shell dpm remove-active-admin com.ipcam/.DeviceAdminReceiver
```

If fails, see `documentation/DEVICE_OWNER_TROUBLESHOOTING.md` for alternative methods.

---

## Summary

### For Camera Stream Failures

1. ✅ Try "Camera Reset" (no permissions needed)
2. ✅ Check "Camera Diagnostics" for root cause
3. ✅ If reset fails, reboot device (Device Owner required)

### For Device Reboot

1. ✅ Check "Reboot Diagnostics" first
2. ✅ Ensure Device Owner status (not just Device Admin)
3. ✅ Ensure device is unlocked
4. ✅ App tries 3 fallback methods automatically

### Root Access

- ❌ NOT required for camera reset
- ❌ NOT required for device reboot (with Device Owner)
- ✅ Required only for system-level camera service restart

---

## Additional Resources

- **Technical Investigation:** `documentation/CAMERA_RESET_AND_REBOOT_INVESTIGATION.md`
- **Device Owner Setup:** `documentation/DEVICE_OWNER_TROUBLESHOOTING.md`
- **Silent Updates:** `documentation/SILENT_UPDATES.md`

---

## Support

If you continue to have issues:

1. ✅ Check diagnostics endpoints first
2. ✅ Capture logcat: `adb logcat -s IPCam:* CameraService:* Camera2:*`
3. ✅ Note exact error messages
4. ✅ Report device model and Android version

**Most common issues are:**
- Not Device Owner (only Device Admin)
- Device is locked
- Camera permission not granted
- Samsung Knox restrictions

All of these can be diagnosed with the new diagnostic endpoints!
