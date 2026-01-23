# Auto-Update Implementation Guide

## Overview

This guide provides a complete step-by-step implementation plan for adding automatic over-the-air (OTA) updates to IP_Cam using **GitHub Releases**. This approach is ideal for personal use and small deployments.

**Key Features:**
- ✅ **Zero cost** - Uses GitHub's free hosting and CDN
- ✅ **Simple** - 2-3 days implementation effort
- ✅ **Reliable** - GitHub's 99.9% uptime
- ✅ **Universal** - Works on all Android devices
- ✅ **User-controlled** - User confirms each update

**How It Works:**
```
1. GitHub Actions builds APK on every commit to main
2. Creates GitHub Release with APK attached
3. IP_Cam checks GitHub API for updates (manual or periodic)
4. Downloads APK if newer version available
5. User confirms and installs update (Android requirement)
```

---

## Architecture

### Update Flow

```
┌─────────────────────────────────────────────────────────┐
│                  GITHUB ACTIONS                         │
│  Commit to main → Build APK → Sign → Create Release    │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│                   GITHUB RELEASE                         │
│  APK hosted on GitHub CDN (free, global distribution)   │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│                   IP_CAM APP                            │
│  Manual or periodic check → GitHub API                 │
│  Download APK if newer → Show notification              │
└─────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────┐
│                   USER ACTION                           │
│  User clicks notification/button → Android installer    │
│  User confirms installation → App restarts              │
└─────────────────────────────────────────────────────────┘
```

### Components

1. **GitHub Actions Workflow** - Automated APK building and release
2. **UpdateManager Service** - Checks for updates, downloads APK
3. **HTTP API Endpoints** - `/checkUpdate`, `/triggerUpdate`
4. **Web Interface** - Button to check for updates
5. **MainActivity UI** - Button to check for updates in app

---

## Implementation Steps

### Phase 1: Core Update Infrastructure (Days 1-2)

#### Step 1.1: Create UpdateManager Service

Create `app/src/main/java/com/ipcam/UpdateManager.kt`:

```kotlin
package com.ipcam

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = 
            "https://api.github.com/repos/tobi01001/IP_Cam/releases/latest"
    }
    
    @Serializable
    data class GitHubRelease(
        val tag_name: String,
        val name: String,
        val body: String,
        val assets: List<Asset>
    ) {
        @Serializable
        data class Asset(
            val name: String,
            val size: Long,
            val browser_download_url: String
        )
    }
    
    @Serializable
    data class UpdateInfo(
        val latestVersionCode: Long,
        val latestVersionName: String,
        val apkUrl: String,
        val apkSize: Long,
        val releaseNotes: String,
        val isUpdateAvailable: Boolean
    )
    
    /**
     * Check if an update is available by querying GitHub Releases API
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Checking for updates...")
            
            val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode != 200) {
                Log.e(TAG, "GitHub API returned ${connection.responseCode}")
                return@withContext null
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val release = Json.decodeFromString<GitHubRelease>(response)
            
            // Find APK asset
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            if (apkAsset == null) {
                Log.e(TAG, "No APK found in latest release")
                return@withContext null
            }
            
            // Extract version code from tag (e.g., "v20260117123456")
            val versionCode = release.tag_name.removePrefix("v").toLongOrNull()
            if (versionCode == null) {
                Log.e(TAG, "Invalid version tag: ${release.tag_name}")
                return@withContext null
            }
            
            val currentVersionCode = BuildConfig.BUILD_NUMBER
            val isUpdateAvailable = versionCode > currentVersionCode
            
            Log.i(TAG, "Current: $currentVersionCode, Latest: $versionCode, Update available: $isUpdateAvailable")
            
            UpdateInfo(
                latestVersionCode = versionCode,
                latestVersionName = release.name,
                apkUrl = apkAsset.browser_download_url,
                apkSize = apkAsset.size,
                releaseNotes = release.body,
                isUpdateAvailable = isUpdateAvailable
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            null
        }
    }
    
    /**
     * Download APK from GitHub
     */
    suspend fun downloadAPK(updateInfo: UpdateInfo): File? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Downloading APK from ${updateInfo.apkUrl}")
            
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")
            
            // Delete old APK if exists
            if (apkFile.exists()) {
                apkFile.delete()
            }
            
            val connection = URL(updateInfo.apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            
            connection.inputStream.use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Log progress every 1MB
                        if (totalBytesRead % (1024 * 1024) == 0L) {
                            val progress = (totalBytesRead * 100 / updateInfo.apkSize).toInt()
                            Log.i(TAG, "Download progress: $progress%")
                        }
                    }
                }
            }
            
            Log.i(TAG, "APK downloaded successfully to ${apkFile.absolutePath}")
            apkFile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            null
        }
    }
    
    /**
     * Trigger installation using Android's package installer
     * User will be prompted to confirm installation
     */
    fun installAPK(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
            Log.i(TAG, "Installation intent sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }
    
    /**
     * Complete update flow: check, download, and install
     */
    suspend fun performUpdate(): Boolean {
        val updateInfo = checkForUpdate()
        
        if (updateInfo == null) {
            Log.e(TAG, "Failed to check for update")
            return false
        }
        
        if (!updateInfo.isUpdateAvailable) {
            Log.i(TAG, "No update available")
            return false
        }
        
        val apkFile = downloadAPK(updateInfo)
        
        if (apkFile == null) {
            Log.e(TAG, "Failed to download APK")
            return false
        }
        
        installAPK(apkFile)
        return true
    }
}
```

#### Step 1.2: Add FileProvider Configuration

Create `app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="updates" path="." />
</paths>
```

#### Step 1.3: Update AndroidManifest.xml

Add required permissions and FileProvider:

```xml
<!-- Add these permissions if not already present -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<!-- Add FileProvider inside <application> tag -->
<application>
    <!-- ... existing content ... -->
    
    <provider
        android:name="androidx.core.content.FileProvider"
        android:authorities="${applicationId}.fileprovider"
        android:exported="false"
        android:grantUriPermissions="true">
        <meta-data
            android:name="android.support.FILE_PROVIDER_PATHS"
            android:resource="@xml/file_paths" />
    </provider>
</application>
```

#### Step 1.4: Add HTTP API Endpoints

Update `app/src/main/java/com/ipcam/HttpServer.kt` to add update endpoints:

```kotlin
// Add this to your HttpServer routing

import kotlinx.coroutines.launch

// Inside your routing block:

get("/checkUpdate") {
    try {
        val updateManager = UpdateManager(this@HttpServer.context)
        val updateInfo = updateManager.checkForUpdate()
        
        if (updateInfo != null) {
            call.respondText(
                Json.encodeToString(mapOf(
                    "status" to "ok",
                    "updateAvailable" to updateInfo.isUpdateAvailable,
                    "currentVersion" to BuildConfig.BUILD_NUMBER,
                    "latestVersion" to updateInfo.latestVersionCode,
                    "latestVersionName" to updateInfo.latestVersionName,
                    "apkSize" to updateInfo.apkSize,
                    "releaseNotes" to updateInfo.releaseNotes
                )),
                ContentType.Application.Json
            )
        } else {
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("status" to "error", "message" to "Failed to check for updates")
            )
        }
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("status" to "error", "message" to e.message)
        )
    }
}

get("/triggerUpdate") {
    try {
        val updateManager = UpdateManager(this@HttpServer.context)
        
        // Launch update in background
        launch {
            val success = updateManager.performUpdate()
            Log.i("HttpServer", "Update triggered: $success")
        }
        
        call.respondText(
            Json.encodeToString(mapOf(
                "status" to "ok",
                "message" to "Update check initiated. If update is available, installation will be prompted."
            )),
            ContentType.Application.Json
        )
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.InternalServerError,
            mapOf("status" to "error", "message" to e.message)
        )
    }
}
```

---

### Phase 2: Web Interface Integration (Day 2)

#### Step 2.1: Add Update Section to Web Interface

Update the HTML template in `HttpServer.kt` to include update controls:

```html
<!-- Add this section to your web interface -->
<div class="update-section" style="margin-top: 20px; padding: 15px; border: 1px solid #ddd; border-radius: 5px;">
    <h3>Software Update</h3>
    <p>Current Version: <span id="currentVersion">Loading...</span></p>
    <p id="updateStatus">Check for available updates</p>
    <button onclick="checkForUpdate()" style="margin-right: 10px;">Check for Update</button>
    <button onclick="triggerUpdate()" id="installButton" style="display: none;">Install Update</button>
</div>

<script>
    // Load current version
    fetch('/status')
        .then(response => response.json())
        .then(data => {
            if (data.version && data.version.buildNumber) {
                document.getElementById('currentVersion').textContent = 
                    data.version.versionName + ' (Build ' + data.version.buildNumber + ')';
            }
        });

    async function checkForUpdate() {
        document.getElementById('updateStatus').textContent = 'Checking for updates...';
        
        try {
            const response = await fetch('/checkUpdate');
            const result = await response.json();
            
            if (result.status === 'ok') {
                if (result.updateAvailable) {
                    document.getElementById('updateStatus').innerHTML = 
                        '<strong style="color: green;">Update Available!</strong><br>' +
                        'Latest Version: ' + result.latestVersionName + '<br>' +
                        'Size: ' + (result.apkSize / 1024 / 1024).toFixed(2) + ' MB<br>' +
                        'Release Notes: ' + result.releaseNotes;
                    document.getElementById('installButton').style.display = 'inline-block';
                } else {
                    document.getElementById('updateStatus').innerHTML = 
                        '<span style="color: blue;">You are running the latest version.</span>';
                    document.getElementById('installButton').style.display = 'none';
                }
            } else {
                document.getElementById('updateStatus').innerHTML = 
                    '<span style="color: red;">Error checking for updates: ' + result.message + '</span>';
            }
        } catch (error) {
            document.getElementById('updateStatus').innerHTML = 
                '<span style="color: red;">Error: ' + error.message + '</span>';
        }
    }

    async function triggerUpdate() {
        if (!confirm('This will download and install the update. The app will restart after installation. Continue?')) {
            return;
        }
        
        document.getElementById('updateStatus').textContent = 'Downloading update...';
        
        try {
            const response = await fetch('/triggerUpdate');
            const result = await response.json();
            
            if (result.status === 'ok') {
                document.getElementById('updateStatus').innerHTML = 
                    '<span style="color: green;">Update downloaded. Please confirm installation on the device.</span>';
                document.getElementById('installButton').style.display = 'none';
            } else {
                document.getElementById('updateStatus').innerHTML = 
                    '<span style="color: red;">Error: ' + result.message + '</span>';
            }
        } catch (error) {
            document.getElementById('updateStatus').innerHTML = 
                '<span style="color: red;">Error: ' + error.message + '</span>';
        }
    }
</script>
```

---

### Phase 3: MainActivity Integration (Day 2)

#### Step 3.1: Add Update Button to MainActivity

Update `app/src/main/res/layout/activity_main.xml`:

```xml
<!-- Add this button to your layout -->
<Button
    android:id="@+id/checkUpdateButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Check for Update"
    android:layout_marginTop="8dp" />

<TextView
    android:id="@+id/updateStatusText"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text=""
    android:textSize="12sp"
    android:layout_marginTop="4dp"
    android:visibility="gone" />
```

#### Step 3.2: Add Update Logic to MainActivity

Update `app/src/main/java/com/ipcam/MainActivity.kt`:

```kotlin
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var updateManager: UpdateManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize UpdateManager
        updateManager = UpdateManager(this)
        
        // Set up update button
        binding.checkUpdateButton.setOnClickListener {
            checkForUpdate()
        }
    }
    
    private fun checkForUpdate() {
        binding.checkUpdateButton.isEnabled = false
        binding.updateStatusText.text = "Checking for updates..."
        binding.updateStatusText.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val updateInfo = updateManager.checkForUpdate()
                
                if (updateInfo == null) {
                    binding.updateStatusText.text = "Failed to check for updates"
                    binding.checkUpdateButton.isEnabled = true
                    return@launch
                }
                
                if (updateInfo.isUpdateAvailable) {
                    // Show update available dialog
                    showUpdateDialog(updateInfo)
                } else {
                    binding.updateStatusText.text = "You are running the latest version"
                    binding.checkUpdateButton.isEnabled = true
                }
            } catch (e: Exception) {
                binding.updateStatusText.text = "Error: ${e.message}"
                binding.checkUpdateButton.isEnabled = true
            }
        }
    }
    
    private fun showUpdateDialog(updateInfo: UpdateManager.UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(
                "Current version: ${BuildConfig.BUILD_NUMBER}\n" +
                "Latest version: ${updateInfo.latestVersionCode}\n\n" +
                "Release notes:\n${updateInfo.releaseNotes}\n\n" +
                "Download and install update?"
            )
            .setPositiveButton("Update") { _, _ ->
                downloadAndInstallUpdate(updateInfo)
            }
            .setNegativeButton("Later") { _, _ ->
                binding.updateStatusText.text = "Update postponed"
                binding.checkUpdateButton.isEnabled = true
            }
            .show()
    }
    
    private fun downloadAndInstallUpdate(updateInfo: UpdateManager.UpdateInfo) {
        binding.updateStatusText.text = "Downloading update..."
        
        lifecycleScope.launch {
            try {
                val apkFile = updateManager.downloadAPK(updateInfo)
                
                if (apkFile != null) {
                    binding.updateStatusText.text = "Download complete. Installing..."
                    updateManager.installAPK(apkFile)
                } else {
                    binding.updateStatusText.text = "Download failed"
                    binding.checkUpdateButton.isEnabled = true
                }
            } catch (e: Exception) {
                binding.updateStatusText.text = "Error: ${e.message}"
                binding.checkUpdateButton.isEnabled = true
            }
        }
    }
}
```

---

### Phase 4: GitHub Actions Automation (Day 3)

#### Step 4.1: Create Release Signing Keystore

Generate a release signing keystore (one-time setup):

```bash
keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
```

**Important:** Store the keystore file and passwords securely. Never commit the keystore to the repository.

#### Step 4.2: Add GitHub Secrets

Add these secrets to your GitHub repository (Settings → Secrets and variables → Actions):

1. `SIGNING_KEY` - Base64 encoded keystore file:
   ```bash
   base64 release.keystore | tr -d '\n'
   ```

2. `KEY_STORE_PASSWORD` - Keystore password
3. `ALIAS` - Key alias (e.g., "release")
4. `KEY_PASSWORD` - Key password

#### Step 4.3: Create GitHub Actions Workflow

Create `.github/workflows/release.yml`:

```yaml
name: Build and Release APK

on:
  push:
    branches: [main]
    paths:
      - 'app/src/**'
      - 'app/build.gradle'

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v3
      
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      
      - name: Build Release APK
        run: ./gradlew assembleRelease
      
      - name: Sign APK
        uses: r0adkll/sign-android-release@v1
        id: sign_app
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.SIGNING_KEY }}
          alias: ${{ secrets.ALIAS }}
          keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD }}
          keyPassword: ${{ secrets.KEY_PASSWORD }}
      
      - name: Get version info
        id: version
        run: |
          VERSION_CODE=$(grep -oP 'BUILD_NUMBER\s*=\s*\K\d+' app/build/generated/source/buildConfig/release/com/ipcam/BuildConfig.java 2>/dev/null || echo "$(date +%Y%m%d%H%M%S)")
          echo "code=$VERSION_CODE" >> $GITHUB_OUTPUT
          echo "name=Build $VERSION_CODE" >> $GITHUB_OUTPUT
      
      - name: Create Release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: v${{ steps.version.outputs.code }}
          name: ${{ steps.version.outputs.name }}
          body: |
            Automated build from commit ${{ github.sha }}
            
            **Commit Message:**
            ${{ github.event.head_commit.message }}
            
            **Changes:**
            - View diff: ${{ github.event.compare }}
          files: ${{ steps.sign_app.outputs.signedReleaseFile }}
          draft: false
          prerelease: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

---

## Testing Guide

### Test 1: Update Manager Functionality

```kotlin
// Test in MainActivity onCreate() or a test activity
lifecycleScope.launch {
    val updateManager = UpdateManager(this@MainActivity)
    
    // Test 1: Check for update
    val updateInfo = updateManager.checkForUpdate()
    Log.i("TEST", "Update info: $updateInfo")
    
    // Test 2: Download APK (if update available)
    if (updateInfo?.isUpdateAvailable == true) {
        val apkFile = updateManager.downloadAPK(updateInfo)
        Log.i("TEST", "APK downloaded: ${apkFile?.absolutePath}")
        
        // Test 3: Install APK
        if (apkFile != null) {
            updateManager.installAPK(apkFile)
        }
    }
}
```

### Test 2: HTTP API Endpoints

```bash
# Test check for update endpoint
curl http://DEVICE_IP:8080/checkUpdate

# Expected response when update available:
{
  "status": "ok",
  "updateAvailable": true,
  "currentVersion": 20260117120000,
  "latestVersion": 20260117140000,
  "latestVersionName": "Build 20260117140000",
  "apkSize": 5242880,
  "releaseNotes": "Automated build..."
}

# Expected response when no update:
{
  "status": "ok",
  "updateAvailable": false,
  "currentVersion": 20260117140000,
  "latestVersion": 20260117140000,
  ...
}
```

```bash
# Test trigger update endpoint
curl http://DEVICE_IP:8080/triggerUpdate

# Expected response:
{
  "status": "ok",
  "message": "Update check initiated. If update is available, installation will be prompted."
}
```

### Test 3: Web Interface

1. Open `http://DEVICE_IP:8080` in browser
2. Locate the "Software Update" section
3. Click "Check for Update" button
4. Verify update status is displayed correctly
5. If update available, click "Install Update" button
6. Confirm installation prompt appears on device

### Test 4: MainActivity UI

1. Open IP_Cam app on device
2. Locate "Check for Update" button
3. Tap the button
4. Verify status text updates with progress
5. If update available, confirm dialog appears
6. Tap "Update" to proceed
7. Verify download progress
8. Confirm installation prompt appears

### Test 5: Complete Update Flow

1. **Trigger a new build:**
   - Make a code change (e.g., add a comment)
   - Commit and push to main branch
   - Wait for GitHub Actions to complete (~5 minutes)

2. **Verify release created:**
   - Go to GitHub repository → Releases
   - Confirm new release with APK attached

3. **Test update on device:**
   - Open app or web interface
   - Click "Check for Update"
   - Confirm update is detected
   - Proceed with installation
   - Verify app restarts with new version

---

## Troubleshooting

### Issue: "Failed to check for updates"

**Possible causes:**
- Network connectivity issue
- GitHub API rate limit (60 requests/hour for unauthenticated)
- Invalid release tag format

**Solutions:**
- Check device has internet access
- Wait for rate limit to reset (1 hour)
- Verify release tags follow "v{BUILD_NUMBER}" format

### Issue: "APK download fails"

**Possible causes:**
- Network timeout
- Insufficient storage space
- GitHub CDN issue

**Solutions:**
- Check device storage space
- Retry download
- Check GitHub Release page to verify APK is accessible

### Issue: "Installation fails"

**Possible causes:**
- APK signature mismatch
- Missing REQUEST_INSTALL_PACKAGES permission
- FileProvider not configured

**Solutions:**
- Verify APK is signed with same keystore
- Check AndroidManifest.xml has REQUEST_INSTALL_PACKAGES permission
- Verify FileProvider is properly configured in manifest

### Issue: "GitHub Actions workflow fails"

**Possible causes:**
- Missing or incorrect secrets
- Gradle build errors
- Signing configuration issues

**Solutions:**
- Verify all secrets are added correctly in GitHub
- Check workflow logs for specific error
- Test build locally: `./gradlew assembleRelease`

---

## Security Considerations

### APK Signing

- **Always use release keystore** for production builds
- **Never commit keystore** to repository
- **Store keystore securely** with backups
- **Use GitHub Secrets** for workflow credentials

### Update Verification

- Android automatically verifies APK signature matches installed app
- Only APKs signed with the same keystore can update the app
- This prevents malicious updates from unauthorized sources

### Network Security

- All downloads over HTTPS (GitHub CDN)
- GitHub API uses TLS/SSL encryption
- No sensitive data transmitted during update check

### User Consent

- User must explicitly confirm each installation
- This is an Android security requirement
- Cannot be bypassed without Device Owner mode

---

## Maintenance

### Updating Version Numbers

The build system automatically generates version codes based on timestamp:
- Format: `yyyyMMddHHmmss`
- Example: `20260117143000` = 2026-01-17 14:30:00 UTC
- Automatically increments with each build

To manually update version name:
```gradle
// In app/build.gradle
defaultConfig {
    versionName "2.0"  // Update this for major versions
}
```

### Monitoring Updates

Check GitHub Actions workflow status:
- Repository → Actions tab
- View build logs for each workflow run
- Monitor success/failure of releases

### Managing Releases

- All releases are visible in repository Releases page
- Can delete old releases to save space
- Users will only see latest release

---

## Cost Analysis

| Component | Cost |
|-----------|------|
| GitHub Releases | $0 (free hosting) |
| GitHub Actions | $0 (2,000 minutes/month free for public repos) |
| GitHub CDN | $0 (included) |
| Development Time | 2-3 days one-time |
| Maintenance | Minimal (~1 hour/month) |
| **Total** | **$0/month** |

---

## Summary

This implementation provides:

✅ **Fully automated APK building** via GitHub Actions  
✅ **Zero-cost hosting** via GitHub Releases  
✅ **Web interface controls** for remote update checks  
✅ **In-app update UI** for local users  
✅ **User confirmation** for security (Android requirement)  
✅ **Simple maintenance** with automatic version management  

**Implementation effort:** 2-3 days

**Recommended for:**
- Personal use
- Small deployments (1-20 devices)
- Cost-sensitive projects
- Users comfortable with manual update confirmation

---

**Document Version:** 1.0  
**Last Updated:** 2026-01-17  
**Status:** Production Ready
