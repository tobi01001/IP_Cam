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

/**
 * Manages automatic over-the-air (OTA) updates for IP_Cam.
 * 
 * This class handles checking for updates from GitHub Releases, downloading APK files,
 * and triggering installation. The update process follows these steps:
 * 
 * 1. Check GitHub API for latest release
 * 2. Compare latest version with current BUILD_NUMBER
 * 3. Download APK if update is available
 * 4. Trigger Android's package installer (requires user confirmation)
 * 
 * Architecture:
 * - Uses GitHub Releases API (no authentication required for public repos)
 * - Downloads APK to app's external files directory
 * - Uses FileProvider for secure file access during installation
 * - All network operations run on IO dispatcher
 * 
 * Security:
 * - Android verifies APK signature matches installed app
 * - Only APKs signed with same keystore can update the app
 * - User must explicitly confirm each installation (Android requirement)
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
     * Trigger installation using Android's package installer.
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
     * Complete update flow: check, download, and install.
     * 
     * This is a convenience method that chains all update operations.
     * Use individual methods for more granular control.
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
        
        installAPK(apkFile)
        return true
    }
}
