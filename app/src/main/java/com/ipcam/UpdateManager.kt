package com.ipcam

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages automatic over-the-air (OTA) updates for IP_Cam.
 * 
 * This class handles checking for updates from GitHub Releases, downloading APK files,
 * and triggering installation. The update process follows these steps:
 * 
 * 1. Check GitHub API for latest release
 * 2. Compare latest version with current BUILD_NUMBER
 * 3. Download APK if update is available
 * 4. Trigger installation:
 *    - If Device Owner: Silent installation (no user confirmation)
 *    - If not: Standard Android installer (requires user confirmation)
 * 
 * Architecture:
 * - Uses GitHub Releases API (no authentication required for public repos)
 * - Downloads APK to app's external files directory
 * - Detects Device Owner mode automatically
 * - Uses PackageInstaller API for silent installs (Device Owner)
 * - Falls back to FileProvider for standard installs
 * - All network operations run on IO dispatcher
 * 
 * Security:
 * - Android verifies APK signature matches installed app
 * - Only APKs signed with same keystore can update the app
 * - Device Owner mode required for silent installation
 * - User must explicitly confirm installation in non-Device Owner mode
 * 
 * @param context Application context for file operations and starting intents
 */
class UpdateManager(private val context: Context) {
    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_API_URL = 
            "https://api.github.com/repos/tobi01001/IP_Cam/releases/latest"
        
        // JSON parser with lenient settings to handle GitHub API responses
        private val json = Json { 
            ignoreUnknownKeys = true
            isLenient = true
        }
        
        // Intent action for installation result broadcast
        private const val ACTION_INSTALL_COMPLETE = "com.ipcam.INSTALL_COMPLETE"
    }
    
    /**
     * Represents a GitHub Release from the API response
     */
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
    
    /**
     * Contains information about an available update
     */
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
     * Check if app is running as Device Owner.
     * Device Owner mode enables silent installations without user confirmation.
     * 
     * @return true if app is Device Owner, false otherwise
     */
    private fun isDeviceOwner(): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            dpm?.isDeviceOwnerApp(context.packageName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Device Owner status", e)
            false
        }
    }
    
    /**
     * Check if an update is available by querying GitHub Releases API.
     * 
     * This method:
     * 1. Queries GitHub API for the latest release
     * 2. Finds the APK asset in the release
     * 3. Extracts version code from tag (format: "v20260117123456")
     * 4. Compares with current BUILD_NUMBER
     * 
     * @return UpdateInfo object if check succeeds, null on error
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
            val release = json.decodeFromString<GitHubRelease>(response)
            
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
            
            val currentVersionCode = BuildInfo.buildNumber
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
     * Download APK from GitHub to app's external files directory.
     * 
     * This method:
     * 1. Deletes any existing update.apk file
     * 2. Downloads APK in chunks (8KB buffer)
     * 3. Logs progress every 1MB
     * 
     * @param updateInfo Update information containing APK URL and size
     * @return Downloaded APK file if successful, null on error
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
                    var lastLoggedMB = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Log progress every 1MB
                        val currentMB = totalBytesRead / (1024 * 1024)
                        if (currentMB > lastLoggedMB) {
                            lastLoggedMB = currentMB
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
     * Install APK silently using PackageInstaller API (Device Owner mode).
     * 
     * This method uses Android's PackageInstaller API to install updates without
     * user confirmation. This only works when the app is set as Device Owner.
     * 
     * Process:
     * 1. Creates a PackageInstaller session
     * 2. Writes APK content to the session
     * 3. Commits the session (triggers installation)
     * 4. Installation happens silently in the background
     * 5. App will restart automatically after installation
     * 
     * @param apkFile The APK file to install
     * @return true if installation was triggered successfully, false otherwise
     */
    private fun installAPKSilently(apkFile: File): Boolean {
        return try {
            Log.i(TAG, "Installing APK silently (Device Owner mode)")
            
            val packageInstaller = context.packageManager.packageInstaller
            
            // Create session parameters
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            
            // Require user action is not needed for Device Owner
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            // Create installation session
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            try {
                // Write APK content to session
                apkFile.inputStream().use { input ->
                    session.openWrite("package", 0, -1).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                
                // Create pending intent for installation result
                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = ACTION_INSTALL_COMPLETE
                }
                
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                
                // Commit the session (triggers installation)
                session.commit(pendingIntent.intentSender)
                session.close()
                
                Log.i(TAG, "Silent installation session committed successfully")
                return true
                
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK silently", e)
            false
        }
    }
    
    /**
     * Install APK using standard Android installer (requires user confirmation).
     * 
     * This method:
     * 1. Creates a content URI using FileProvider for secure access
     * 2. Launches ACTION_VIEW intent with APK MIME type
     * 3. User will be prompted to confirm installation (Android security requirement)
     * 
     * Requirements:
     * - FileProvider must be configured in AndroidManifest.xml
     * - REQUEST_INSTALL_PACKAGES permission must be declared
     * 
     * @param apkFile The APK file to install
     */
    private fun installAPKWithUserConfirmation(apkFile: File) {
        try {
            Log.i(TAG, "Installing APK with user confirmation")
            
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
            Log.i(TAG, "Installation intent sent - user confirmation required")
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
        }
    }
    
    /**
     * Install APK automatically detecting best method.
     * 
     * This method automatically chooses the appropriate installation method:
     * - If Device Owner: Silent installation (no user interaction)
     * - If not: Standard installation (requires user confirmation)
     * 
     * @param apkFile The APK file to install
     * @return true if installation was triggered successfully, false otherwise
     */
    fun installAPK(apkFile: File): Boolean {
        val isDeviceOwner = isDeviceOwner()
        
        if (isDeviceOwner) {
            Log.i(TAG, "Device Owner mode detected - attempting silent installation")
            val success = installAPKSilently(apkFile)
            if (success) {
                return true
            } else {
                Log.w(TAG, "Silent installation failed, falling back to user confirmation")
                installAPKWithUserConfirmation(apkFile)
                return false
            }
        } else {
            Log.i(TAG, "Not Device Owner - using standard installation (user confirmation required)")
            installAPKWithUserConfirmation(apkFile)
            return false
        }
    }
    
    /**
     * Complete update flow: check, download, and install.
     * 
     * This is a convenience method that chains all update operations.
     * Use individual methods for more granular control.
     * 
     * Installation method is automatically chosen based on Device Owner status:
     * - Device Owner: Silent installation (no user interaction)
     * - Non-Device Owner: User must confirm installation
     * 
     * @return true if update was triggered successfully, false otherwise
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
        
        return installAPK(apkFile)
    }
    
    /**
     * BroadcastReceiver to handle installation results for silent installations.
     * 
     * This receiver is triggered after a silent installation completes (Device Owner mode).
     * It logs the installation result and can be extended to handle errors or notifications.
     */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Log.i(TAG, "Installation pending user action (shouldn't happen in Device Owner mode)")
                    
                    // This shouldn't happen in Device Owner mode, but handle it anyway
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(confirmIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting confirmation intent", e)
                        }
                    }
                }
                
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "Silent installation successful! App will restart.")
                }
                
                PackageInstaller.STATUS_FAILURE,
                PackageInstaller.STATUS_FAILURE_ABORTED,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                PackageInstaller.STATUS_FAILURE_INVALID,
                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.e(TAG, "Silent installation failed with status $status: $msg")
                }
                
                else -> {
                    Log.w(TAG, "Unknown installation status: $status")
                }
            }
        }
    }
}
