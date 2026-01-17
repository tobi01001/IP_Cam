# Silent Auto-Update Implementation for Remote Cameras

## Overview

This document addresses **fully automated, silent updates** for IP_Cam deployments on remote, dedicated surveillance camera devices where manual user intervention is not feasible.

**Problem**: The recommended GitHub Releases approach requires user confirmation for APK installation (Android security requirement). For remote cameras, this is impractical.

**Solution**: Combine approaches to achieve true silent updates while maintaining the benefits of GitHub Releases.

---

## Understanding Android's Installation Restrictions

### Standard Installation (Android 8+)

By default, apps cannot silently install APKs. Android requires:
1. User must grant `REQUEST_INSTALL_PACKAGES` permission
2. User must confirm each installation via system dialog
3. APK signature must match installed app

**This is a security feature and cannot be bypassed in normal operation.**

### Methods to Achieve Silent Installation

There are **3 legitimate ways** to bypass user confirmation:

1. **Device Owner Mode** (Recommended for dedicated devices)
2. **System App with INSTALL_PACKAGES permission** (Requires root/system partition)
3. **ADB Installation** (Requires USB debugging, not practical for remote)

---

## Solution 1: Device Owner + GitHub Releases (RECOMMENDED)

### Overview

Combine the benefits of both approaches:
- **GitHub Releases**: Free hosting, automated building, version management
- **Device Owner**: Silent installation capability

### Architecture

```
GitHub Actions → Build APK → Create GitHub Release
                                    ↓
                          APK hosted on GitHub CDN
                                    ↓
IP_Cam (Device Owner) checks API → Downloads APK → SILENTLY installs
                                                            ↓
                                                   App restarts automatically
```

### Key Benefits

✅ **Silent updates** - No user interaction required  
✅ **Free hosting** - GitHub provides infrastructure  
✅ **Remote control** - Can be triggered via web interface  
✅ **Automated** - Fully hands-off operation  
✅ **Reliable** - GitHub's infrastructure + privileged install  

### Implementation Steps

#### Step 1: Set Up Device Owner Mode

**During Initial Device Setup:**

1. **Factory reset device** (required for Device Owner provisioning)
2. **During setup wizard**, provision as Device Owner using one of:
   - **QR Code**: Generate QR with device owner config, scan during setup
   - **NFC Bump**: Program NFC tag with config, bump during setup
   - **ADB Command** (before any accounts added):
     ```bash
     adb shell dpm set-device-owner com.ipcam/.IPCamDeviceAdminReceiver
     ```

**QR Code Method (Easiest for Multiple Devices):**

Generate QR code with this JSON payload:
```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.ipcam/.IPCamDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "YOUR_APK_SIGNATURE_HASH",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://github.com/tobi01001/IP_Cam/releases/latest/download/app-release.apk",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": false,
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "initial_setup": true
  }
}
```

During device setup, when prompted "Set up your device", scan the QR code to automatically:
- Download and install IP_Cam
- Set it as Device Owner
- Complete provisioning

#### Step 2: Implement Device Admin Receiver

**Add to `IPCamDeviceAdminReceiver.kt`:**

```kotlin
package com.ipcam

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class IPCamDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val TAG = "IPCamDeviceAdmin"
        
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, IPCamDeviceAdminReceiver::class.java)
        }
        
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
    
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device owner enabled")
    }
    
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device owner disabled")
    }
}
```

**Add device admin metadata (`res/xml/device_admin.xml`):**

```xml
<?xml version="1.0" encoding="utf-8"?>
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies>
        <force-lock />
        <wipe-data />
    </uses-policies>
</device-admin>
```

**Update AndroidManifest.xml:**

```xml
<receiver
    android:name=".IPCamDeviceAdminReceiver"
    android:permission="android.permission.BIND_DEVICE_ADMIN"
    android:exported="true">
    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />
    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
        <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
    </intent-filter>
</receiver>
```

#### Step 3: Implement Silent Installation

**Update `UpdateManager.kt` with silent installation:**

```kotlin
class UpdateManager(private val context: Context) {
    private val dpm: DevicePolicyManager = 
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    
    suspend fun downloadAndInstallSilently(updateInfo: UpdateInfo): Result<Unit> {
        return try {
            // Check if we have Device Owner privileges
            if (!IPCamDeviceAdminReceiver.isDeviceOwner(context)) {
                return Result.failure(Exception("Device Owner mode required for silent installation"))
            }
            
            // Download APK
            val apkFile = downloadAPK(updateInfo)
            
            // Verify checksum
            if (!verifyChecksum(apkFile, updateInfo.sha256)) {
                apkFile.delete()
                return Result.failure(Exception("Checksum verification failed"))
            }
            
            // Install silently using PackageInstaller
            installPackageSilently(apkFile)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Silent installation failed", e)
            Result.failure(e)
        }
    }
    
    private suspend fun installPackageSilently(apkFile: File) = withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
        }
        
        // Create installation session
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        
        try {
            // Write APK to session
            session.openWrite("package", 0, -1).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }
            
            // Create pending intent for installation result
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            // Commit installation (will happen silently due to Device Owner)
            session.commit(pendingIntent.intentSender)
            
            Log.i(TAG, "Silent installation initiated")
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
}

// Receiver to handle installation result
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i("UpdateInstall", "Installation successful")
                // App will restart automatically
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e("UpdateInstall", "Installation failed: $msg")
            }
        }
    }
}
```

**Register receiver in AndroidManifest.xml:**

```xml
<receiver
    android:name=".UpdateInstallReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="com.ipcam.INSTALL_COMPLETE" />
    </intent-filter>
</receiver>
```

#### Step 4: Add Web Interface Trigger

**Add HTTP endpoint to trigger update check:**

```kotlin
// In HttpServer.kt

get("/triggerUpdate") {
    try {
        // Check if update is available
        val updateInfo = updateManager.checkForUpdate()
        
        if (updateInfo != null) {
            // Trigger download and installation
            launch {
                val result = updateManager.downloadAndInstallSilently(updateInfo)
                if (result.isSuccess) {
                    Log.i(TAG, "Update triggered successfully via web interface")
                } else {
                    Log.e(TAG, "Update failed: ${result.exceptionOrNull()?.message}")
                }
            }
            
            call.respondText(
                Json.encodeToString(mapOf(
                    "status" to "ok",
                    "message" to "Update initiated",
                    "version" to updateInfo.latestVersionName
                )),
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                Json.encodeToString(mapOf(
                    "status" to "ok",
                    "message" to "Already on latest version"
                )),
                ContentType.Application.Json
            )
        }
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("status" to "error", "message" to e.message)
        )
    }
}

get("/updateStatus") {
    val isDeviceOwner = IPCamDeviceAdminReceiver.isDeviceOwner(this@HttpServer.context)
    val canSilentInstall = isDeviceOwner
    
    call.respondText(
        Json.encodeToString(mapOf(
            "deviceOwner" to isDeviceOwner,
            "silentInstallCapable" to canSilentInstall,
            "autoUpdateEnabled" to settings.autoUpdateEnabled,
            "currentVersion" to BuildConfig.BUILD_NUMBER
        )),
        ContentType.Application.Json
    )
}
```

**Add web UI controls:**

```html
<!-- In web interface -->
<div class="update-section">
    <h3>Update Management</h3>
    <button onclick="checkUpdate()">Check for Updates</button>
    <button onclick="triggerUpdate()">Install Update Now</button>
    <div id="updateStatus"></div>
</div>

<script>
async function checkUpdate() {
    const response = await fetch('/updateStatus');
    const status = await response.json();
    document.getElementById('updateStatus').innerHTML = `
        <p>Device Owner: ${status.deviceOwner ? 'Yes ✓' : 'No ✗'}</p>
        <p>Silent Install: ${status.silentInstallCapable ? 'Enabled ✓' : 'Disabled ✗'}</p>
        <p>Current Version: ${status.currentVersion}</p>
    `;
}

async function triggerUpdate() {
    const response = await fetch('/triggerUpdate');
    const result = await response.json();
    alert(result.message);
    if (result.status === 'ok' && result.version) {
        setTimeout(checkUpdate, 2000); // Refresh status after 2 seconds
    }
}
</script>
```

### Implementation Effort

**Time Estimate: 3-4 days**

1. **Day 1**: Implement Device Admin Receiver and silent installation logic (6 hours)
2. **Day 2**: Test provisioning methods (QR code, ADB) (6 hours)
3. **Day 3**: Add web interface triggers and status endpoints (4 hours)
4. **Day 4**: Test complete flow on multiple devices (6 hours)

### Provisioning Workflow

**For New Devices:**

1. Generate provisioning QR code (one-time)
2. Factory reset device
3. During setup, scan QR code
4. Device automatically:
   - Downloads IP_Cam APK
   - Installs it
   - Sets as Device Owner
   - Completes setup
5. Configure camera settings via web interface
6. Updates install silently from now on

**For Existing Devices:**

If devices are already set up without Device Owner:
- **Option A**: Factory reset and re-provision (destructive)
- **Option B**: Continue using standard installation with user confirmation
- **Option C**: Use ADB to set Device Owner if no accounts are added yet

---

## Solution 2: System App Installation (Requires Root)

### Overview

Install IP_Cam as a system app with `INSTALL_PACKAGES` permission. This allows silent installation without Device Owner mode.

### Requirements

- **Root access** to device
- Ability to mount `/system` partition as read-write
- Knowledge of how to push apps to `/system/priv-app/`

### Pros & Cons

✅ Silent installation without Device Owner  
✅ No factory reset required  
✅ Works alongside other apps  

❌ Requires root access  
❌ System partition modification  
❌ May void warranty  
❌ Complex to maintain  
❌ Not suitable for production deployments  

### NOT RECOMMENDED

This approach is mentioned for completeness but is **not recommended** for production use due to:
- Security risks of rooting devices
- Complexity of maintaining system apps
- Difficulty updating system apps
- Potential to brick devices

---

## Solution 3: ADB Over Network (Development/Testing Only)

### Overview

Enable ADB over network and use `adb install` for silent installation.

### Setup

```bash
# On device (via ADB over USB once)
adb tcpip 5555
adb connect DEVICE_IP:5555

# Install APK silently
adb install -r app-release.apk
```

### Pros & Cons

✅ Silent installation  
✅ No Device Owner required  
✅ Easy for development  

❌ Security risk (ADB exposed on network)  
❌ Requires USB debugging enabled  
❌ Not suitable for production  
❌ Requires network access to device  

### NOT RECOMMENDED FOR PRODUCTION

---

## Comparison: Silent Installation Methods

| Method | Effort | Security | Suitable For Production | Maintenance |
|--------|--------|----------|------------------------|-------------|
| **Device Owner** | Medium | High | ✓ Yes | Low |
| **System App (Root)** | High | Low | ✗ No | High |
| **ADB Network** | Low | Very Low | ✗ No | Medium |

---

## Updated Recommendation for Remote Cameras

### For Dedicated Surveillance Cameras

**Use Device Owner + GitHub Releases:**

1. **Initial Setup**: Provision devices as Device Owner during first setup
2. **Update Distribution**: GitHub Releases (free, reliable)
3. **Installation**: Silent via Device Owner privileges
4. **Control**: Web interface can trigger updates
5. **Automation**: Fully hands-off after initial provisioning

### Implementation Priority

**Phase 1 (Essential):**
1. Implement Device Admin Receiver
2. Create provisioning QR codes
3. Test Device Owner provisioning
4. Implement silent installation via PackageInstaller

**Phase 2 (Enhancement):**
1. Add web interface update triggers
2. Add update status endpoint
3. Implement update scheduling (e.g., 3 AM only)

**Phase 3 (Advanced):**
1. Staged rollout (test on 1 device first)
2. Automatic rollback on failure
3. Update analytics and monitoring

### Total Effort: 3-4 days

---

## Provisioning Tools

### QR Code Generator Script

```python
#!/usr/bin/env python3
import json
import qrcode
import hashlib

# Calculate APK signature
def get_apk_signature(apk_path):
    # Use keytool to extract certificate
    # keytool -printcert -jarfile app-release.apk
    # Extract SHA-256 hash
    return "YOUR_SIGNATURE_HASH_HERE"

# Generate provisioning payload
payload = {
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": 
        "com.ipcam/.IPCamDeviceAdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": 
        get_apk_signature("app-release.apk"),
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": 
        "https://github.com/tobi01001/IP_Cam/releases/latest/download/app-release.apk",
    "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": False,
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": True
}

# Generate QR code
qr = qrcode.QRCode(version=1, box_size=10, border=5)
qr.add_data(json.dumps(payload))
qr.make(fit=True)

img = qr.make_image(fill_color="black", back_color="white")
img.save("ipcam_provisioning.png")
print("QR code generated: ipcam_provisioning.png")
```

---

## Security Considerations

### Device Owner Security

✅ **Secure by design**: Device Owner mode is designed for enterprise management  
✅ **Signature verification**: Android verifies APK signature before installation  
✅ **Isolated**: Device Owner apps have limited access to user data  
✅ **Auditable**: All actions logged by Android  

⚠️ **Considerations**:
- Device Owner cannot be removed without factory reset (by design)
- App has elevated privileges (use responsibly)
- Factory reset protection (FRP) may apply

### Best Practices

1. **Test thoroughly** before deploying to production
2. **Backup device data** before provisioning
3. **Document provisioning process** for team
4. **Monitor update success/failure rates**
5. **Implement rollback mechanism** for failed updates
6. **Use staged rollout** for large deployments

---

## Troubleshooting

### Device Owner Provisioning Fails

**Problem**: Cannot set Device Owner  
**Solutions**:
- Ensure device is factory reset
- No accounts added (not even Google account)
- Use ADB method if QR code fails
- Check APK signature hash is correct

### Silent Installation Fails

**Problem**: Installation requires user confirmation  
**Solutions**:
- Verify Device Owner status: `adb shell dpm list-owners`
- Check app has Device Admin permission
- Ensure APK signature matches installed app
- Review logcat for PackageInstaller errors

### Web Interface Cannot Trigger Update

**Problem**: `/triggerUpdate` endpoint fails  
**Solutions**:
- Check Device Owner status via `/updateStatus`
- Ensure GitHub Releases are accessible from device
- Verify network connectivity
- Check UpdateManager logs

---

## Cost Analysis: Silent Updates

| Component | Cost | Notes |
|-----------|------|-------|
| **GitHub Releases** | $0 | Free hosting and distribution |
| **Device Owner Mode** | $0 | Built-in Android feature |
| **Development Effort** | 3-4 days | One-time implementation |
| **Provisioning** | ~5 min/device | One-time per device |
| **Maintenance** | Minimal | Automated after setup |

**Total: $0 infrastructure cost**

---

## Conclusion

For IP_Cam's use case of **remote surveillance cameras**, the **Device Owner + GitHub Releases** approach provides:

✅ **True silent updates** - No user interaction  
✅ **Zero infrastructure cost** - GitHub provides hosting  
✅ **Remote control** - Web interface triggers  
✅ **Production-ready** - Secure and reliable  
✅ **Scalable** - Works for fleets of devices  

**Trade-off**: Requires factory reset for initial Device Owner provisioning.

**Implementation**: 3-4 days of development + 5 minutes per device for provisioning.

This is the **optimal solution** for dedicated surveillance camera deployments where devices are remote and inaccessible for manual updates.

---

**Document Version**: 1.0  
**Last Updated**: 2026-01-17  
**Status**: Ready for Implementation
