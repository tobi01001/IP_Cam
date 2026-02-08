# Device Owner Troubleshooting Guide

This document provides troubleshooting steps for common Device Owner issues, particularly on custom ROMs like LineageOS.

## Problem: Cannot Remove Device Owner or Reset Admin Rights

### Symptoms:
- Unable to remove Device Owner via `adb shell dpm remove-active-admin`
- "Remove Admin" options in Settings don't work
- Device Owner persists even after uninstalling and reinstalling app
- Cannot set app as launcher after Device Owner is enabled
- Settings access blocked permanently

### Root Cause:
Device Owner state is stored at the **system level**, not app level. It persists across:
- App uninstall/reinstall
- App data clear
- Cache clear
- Normal "Remove Admin" operations

Once Device Owner is set, Android locks down certain system functions for security. This is by design.

### Why This is Worse on LineageOS/Custom ROMs:
- Custom ROMs apply stricter default restrictions
- LineageOS Android 16+ has enhanced security policies
- Stock ROMs (Samsung, Google) have vendor-specific workarounds
- Custom ROMs follow pure AOSP behavior (more restrictive)

## Solution Options (In Order of Increasing Effort)

### Option 1: ADB Command to Remove Device Owner (Try First)
```bash
# Connect via ADB
adb devices

# Remove device owner
adb shell dpm remove-active-admin com.ipcam/.DeviceAdminReceiver

# If that fails, try forcing it
adb shell dpm remove-active-admin --user 0 com.ipcam/.DeviceAdminReceiver

# Verify it's removed
adb shell dpm list-owners

# Should show: "No device owner set"
```

**If this fails, proceed to Option 2.**

### Option 2: Force Unprovision Device (Android 13+, No Data Loss)
This tells Android the device is "not set up yet", allowing Device Owner to be changed.

```bash
# Mark device as not provisioned
adb shell settings put secure user_setup_complete 0
adb shell settings put global device_provisioned 0

# Reboot into special mode
adb reboot --no-provisioning-mode

# Wait for device to boot
adb wait-for-device

# Now try removing device owner again
adb shell dpm remove-active-admin com.ipcam/.DeviceAdminReceiver

# Verify
adb shell dpm list-owners

# Re-provision device
adb shell settings put secure user_setup_complete 1
adb shell settings put global device_provisioned 1
```

**If this fails, proceed to Option 3.**

### Option 3: Factory Reset (100% Success Rate, Loses All Data)

**⚠️ WARNING: This erases ALL data on the device!**

1. Backup any important data
2. Settings → System → Reset options → Erase all data (factory reset)
3. During setup wizard:
   - Skip all optional steps
   - Don't add any Google accounts
   - Don't set up WiFi yet (or use temporary network)
4. Enable USB debugging
5. Install IP_Cam
6. **Test launcher functionality FIRST** (before setting Device Owner)
7. Set Device Owner only if launcher works correctly

### Option 4: Manual System File Editing (Advanced, Requires Root)

**⚠️ Only for experienced users with rooted devices**

Device Owner info is stored in:
- `/data/system/device_owner_2.xml` (Android 8+)
- `/data/system/device_owner.xml` (Android 7 and below)

```bash
# Requires root access
adb root
adb remount

# Backup the file first
adb pull /data/system/device_owner_2.xml

# Delete it
adb shell rm /data/system/device_owner_2.xml

# Reboot
adb reboot

# After reboot, device owner should be cleared
```

## Best Practices to Avoid These Issues

### 1. Test Launcher Functionality First
Before setting Device Owner:
1. Install IP_Cam
2. Try setting as default launcher
3. Press Home button - does it work?
4. Reboot device - does launcher persist?
5. **Only after confirming launcher works → set Device Owner**

### 2. Correct Setup Order for Dedicated IP Camera Devices

**✅ CORRECT ORDER:**
1. Factory reset device
2. Skip all setup wizard steps (no accounts, minimal WiFi)
3. Enable USB debugging
4. Install IP_Cam APK
5. Grant camera permission
6. **Test launcher functionality** (critical!)
7. Test Settings access
8. **Only now set Device Owner** (after confirming everything works)
9. Verify Settings still accessible
10. Enable auto-start, configure camera

**❌ WRONG ORDER (Causes Problems):**
1. Install app
2. Set Device Owner immediately  ← Problem starts here
3. Try to set as launcher ← Now blocked by Device Owner
4. Settings locked ← Cannot fix without factory reset

### 3. Why Device Owner Should Be Last Step

Device Owner applies system-level restrictions that can interfere with:
- Launcher selection
- Settings access
- System app permissions
- Network configuration

By setting Device Owner **last**, you ensure:
- All basic functionality works first
- Launcher is already configured
- You can verify Settings access
- Easy to revert if needed (just uninstall)

### 4. For LineageOS Specifically

LineageOS Android 16+ is particularly strict about Device Owner:
- More default restrictions than stock Android
- Fewer vendor workarounds
- Closer to pure AOSP security model
- May require factory reset more often

**LineageOS-Specific Recommendation:**
- Test on LineageOS **without** Device Owner first
- Verify all features work (launcher, Settings, camera)
- Consider if Device Owner is truly needed
- Silent updates may not be critical if device is accessible
- Device Owner is optional - only for remote, hard-to-reach devices

## Testing Device Owner Setup

After setting Device Owner, immediately verify:

```bash
# Check Device Owner status
adb shell dpm list-owners
# Should show: Device owner: ComponentInfo{com.ipcam/com.ipcam.DeviceAdminReceiver}

# Check if Settings is accessible
adb shell am start -a android.settings.SETTINGS
# Settings app should open

# Check launcher functionality  
# Press Home button on device - should launch IP_Cam

# Check user restrictions
adb shell dpm list-owners --restrictions
# Should show minimal restrictions
```

If any test fails, **remove Device Owner immediately** before the problem gets worse.

## When to Use Device Owner vs Regular Mode

### Use Device Owner When:
- ✅ Device is in hard-to-reach location (ceiling, outdoor, etc.)
- ✅ Need WiFi ADB to stay enabled automatically
- ✅ Need silent updates without user confirmation
- ✅ Device is dedicated IP camera (no other use)
- ✅ You have physical access for initial setup
- ✅ You're willing to factory reset if problems occur

### Use Regular Mode When:
- ✅ Device is easily accessible
- ✅ Don't need silent updates
- ✅ Device has multiple uses (not dedicated IP camera)
- ✅ Want to avoid potential Device Owner issues
- ✅ Using on LineageOS or custom ROM (more restrictions)
- ✅ Want easy uninstall option

**Remember:** IP_Cam works perfectly without Device Owner mode. Device Owner is **optional** and only needed for advanced features like silent updates.

## Recovery from "Broken" State

If you're in a state where:
- Device Owner won't remove
- Can't set as launcher
- Settings is blocked
- Can't reset admin rights

Your only reliable option is **Factory Reset** (Option 3 above).

Then follow the **Correct Setup Order** to avoid repeating the issue.

## Summary

**Key Takeaway:** On LineageOS and custom ROMs, always test basic functionality (launcher, Settings) **before** setting Device Owner. Once Device Owner is set, system-level restrictions apply that may require factory reset to remove.

**Device Owner is powerful but has tradeoffs.** Use it only when needed, and only after verifying everything works without it first.
