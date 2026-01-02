package com.ipcam

/**
 * Centralized build and version information for the IP Camera application.
 * 
 * This object provides access to git-based version info, build numbers, and timestamps
 * that are automatically generated during the build process via Gradle BuildConfig.
 */
object BuildInfo {
    /**
     * Application version name (e.g., "1.0")
     */
    val versionName: String = BuildConfig.VERSION_NAME
    
    /**
     * Application version code (integer)
     */
    val versionCode: Int = BuildConfig.VERSION_CODE
    
    /**
     * Git commit hash (short form, 7 characters)
     */
    val commitHash: String = BuildConfig.GIT_COMMIT_HASH
    
    /**
     * Git branch name
     */
    val branch: String = BuildConfig.GIT_BRANCH
    
    /**
     * Build timestamp in format yyyyMMdd-HHmmss (UTC)
     */
    val buildTimestamp: String = BuildConfig.BUILD_TIMESTAMP
    
    /**
     * Auto-incrementing build number based on timestamp (format: yyyyMMddHHmmss)
     * This ensures each build has a unique, monotonically increasing number
     */
    val buildNumber: Long = BuildConfig.BUILD_NUMBER
    
    /**
     * Get a formatted version string suitable for display
     * Format: "v{versionName} ({branch}@{commitHash})"
     * Example: "v1.0 (main@a4c656b)"
     */
    fun getVersionString(): String {
        return "v$versionName ($branch@$commitHash)"
    }
    
    /**
     * Get a formatted build info string suitable for display
     * Format: "Build {buildNumber} - {buildTimestamp}"
     * Example: "Build 20260102161234 - 20260102-161234"
     */
    fun getBuildString(): String {
        return "Build $buildNumber - $buildTimestamp"
    }
    
    /**
     * Get complete version and build information as a single formatted string
     * Format: "v{versionName} ({branch}@{commitHash}) | Build {buildNumber}"
     * Example: "v1.0 (main@a4c656b) | Build 20260102161234"
     */
    fun getFullVersionString(): String {
        return "${getVersionString()} | Build $buildNumber"
    }
    
    /**
     * Get version information as a map for JSON serialization
     */
    fun toMap(): Map<String, Any> {
        return mapOf(
            "versionName" to versionName,
            "versionCode" to versionCode,
            "commitHash" to commitHash,
            "branch" to branch,
            "buildTimestamp" to buildTimestamp,
            "buildNumber" to buildNumber
        )
    }
}
