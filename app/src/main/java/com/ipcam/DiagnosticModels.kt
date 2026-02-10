package com.ipcam

import android.os.Build

/**
 * Models for diagnostic information about camera reset and device reboot capabilities.
 * These classes help track and communicate the state of reset/reboot operations.
 */

/**
 * Result of a camera reset operation with detailed diagnostic information
 */
sealed class CameraResetResult {
    /**
     * Reset completed successfully
     */
    data class Success(
        val message: String = "Camera reset successful",
        val preResetState: String,
        val postResetState: String,
        val durationMs: Long
    ) : CameraResetResult()
    
    /**
     * System-level camera service failure that cannot be fixed by app-level reset
     */
    data class SystemLevelFailure(
        val reason: String,
        val recommendation: String = "Device reboot required to recover camera service"
    ) : CameraResetResult()
    
    /**
     * Camera restart failed after reset
     */
    data class RestartFailed(
        val reason: String,
        val recommendation: String = "Check camera permissions and try again"
    ) : CameraResetResult()
    
    /**
     * Camera bound successfully but frames not flowing
     */
    data class NoFrames(
        val reason: String,
        val recommendation: String = "Camera may be in use by another app or system service"
    ) : CameraResetResult()
    
    /**
     * Permission denied during reset
     */
    data class PermissionDenied(
        val details: String?,
        val recommendation: String = "Grant camera permission in Settings"
    ) : CameraResetResult()
    
    /**
     * Camera is in use by another process
     */
    data class CameraInUse(
        val details: String?,
        val recommendation: String = "Close other camera apps and try again"
    ) : CameraResetResult()
    
    /**
     * Unknown error during reset
     */
    data class UnknownError(
        val details: String?,
        val exceptionType: String
    ) : CameraResetResult()
    
    /**
     * Convert result to JSON string for HTTP API responses
     */
    fun toJson(): String {
        return when (this) {
            is Success -> """{"status":"ok","message":"$message","preResetState":"$preResetState","postResetState":"$postResetState","durationMs":$durationMs}"""
            is SystemLevelFailure -> """{"status":"error","error":"system_level_failure","reason":"$reason","recommendation":"$recommendation"}"""
            is RestartFailed -> """{"status":"error","error":"restart_failed","reason":"$reason","recommendation":"$recommendation"}"""
            is NoFrames -> """{"status":"error","error":"no_frames","reason":"$reason","recommendation":"$recommendation"}"""
            is PermissionDenied -> """{"status":"error","error":"permission_denied","details":"$details","recommendation":"$recommendation"}"""
            is CameraInUse -> """{"status":"error","error":"camera_in_use","details":"$details","recommendation":"$recommendation"}"""
            is UnknownError -> """{"status":"error","error":"unknown","details":"$details","exceptionType":"$exceptionType"}"""
        }
    }
    
    /**
     * Whether the reset was successful
     */
    fun isSuccess(): Boolean = this is Success
}

/**
 * Diagnostic information about device reboot capability
 */
data class RebootDiagnostics(
    val isDeviceOwner: Boolean,
    val isDeviceAdmin: Boolean,
    val isDeviceLocked: Boolean,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: Int,
    val androidVersionName: String,
    val selinuxStatus: String,
    val knoxVersion: String?,
    val rebootPossible: Boolean,
    val blockingReason: String?
) {
    /**
     * Convert diagnostics to JSON string
     */
    fun toJson(): String {
        return """
            {
                "isDeviceOwner": $isDeviceOwner,
                "isDeviceAdmin": $isDeviceAdmin,
                "isDeviceLocked": $isDeviceLocked,
                "deviceManufacturer": "$deviceManufacturer",
                "deviceModel": "$deviceModel",
                "androidVersion": $androidVersion,
                "androidVersionName": "$androidVersionName",
                "selinuxStatus": "$selinuxStatus",
                "knoxVersion": ${if (knoxVersion != null) "\"$knoxVersion\"" else "null"},
                "rebootPossible": $rebootPossible,
                "blockingReason": ${if (blockingReason != null) "\"$blockingReason\"" else "null"}
            }
        """.trimIndent()
    }
    
    companion object {
        /**
         * Create diagnostics with basic device info
         */
        fun create(
            isDeviceOwner: Boolean,
            isDeviceAdmin: Boolean,
            isDeviceLocked: Boolean,
            selinuxStatus: String,
            knoxVersion: String?
        ): RebootDiagnostics {
            // Determine if reboot is possible
            val (rebootPossible, blockingReason) = when {
                !isDeviceOwner -> false to "Not Device Owner (only Device Admin)"
                isDeviceLocked -> false to "Device is locked (unlock required)"
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) && knoxVersion != null -> 
                    false to "Samsung Knox may restrict reboot (uncertain)"
                else -> true to null
            }
            
            return RebootDiagnostics(
                isDeviceOwner = isDeviceOwner,
                isDeviceAdmin = isDeviceAdmin,
                isDeviceLocked = isDeviceLocked,
                deviceManufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
                androidVersion = Build.VERSION.SDK_INT,
                androidVersionName = Build.VERSION.RELEASE,
                selinuxStatus = selinuxStatus,
                knoxVersion = knoxVersion,
                rebootPossible = rebootPossible,
                blockingReason = blockingReason
            )
        }
    }
}

/**
 * Result of a device reboot attempt
 */
sealed class RebootResult {
    /**
     * Reboot command executed successfully
     */
    data object Success : RebootResult()
    
    /**
     * App is not Device Owner (only Device Admin)
     */
    data object NotDeviceOwner : RebootResult()
    
    /**
     * Device is locked - cannot reboot
     */
    data object DeviceLocked : RebootResult()
    
    /**
     * Security exception during reboot attempt
     */
    data class SecurityException(val message: String, val method: String) : RebootResult()
    
    /**
     * All reboot methods failed
     */
    data class AllMethodsFailed(
        val method1: String,  // DevicePolicyManager.reboot() result
        val method2: String,  // PowerManager.reboot() result
        val method3: String,  // Shell exec result
        val diagnostics: RebootDiagnostics
    ) : RebootResult()
    
    /**
     * Convert result to JSON string
     */
    fun toJson(): String {
        return when (this) {
            is Success -> """{"status":"ok","message":"Device rebooting..."}"""
            is NotDeviceOwner -> """{"status":"error","error":"not_device_owner","message":"Reboot requires Device Owner mode. App is only Device Admin."}"""
            is DeviceLocked -> """{"status":"error","error":"device_locked","message":"Device must be unlocked to reboot"}"""
            is SecurityException -> """{"status":"error","error":"security_exception","message":"$message","method":"$method"}"""
            is AllMethodsFailed -> """{"status":"error","error":"all_methods_failed","method1":"$method1","method2":"$method2","method3":"$method3","diagnostics":${diagnostics.toJson()}}"""
        }
    }
    
    /**
     * Whether reboot was successful
     */
    fun isSuccess(): Boolean = this is Success
}

/**
 * Camera diagnostics information
 */
data class CameraDiagnostics(
    val cameraState: String,
    val consumerCount: Int,
    val hasProvider: Boolean,
    val lastFrameAgeMs: Long,
    val cameraServiceHealthy: Boolean,
    val permissionGranted: Boolean,
    val cameraId: String?,
    val errorCount: Int
) {
    /**
     * Convert to JSON string
     */
    fun toJson(): String {
        return """
            {
                "cameraState": "$cameraState",
                "consumerCount": $consumerCount,
                "hasProvider": $hasProvider,
                "lastFrameAgeMs": $lastFrameAgeMs,
                "cameraServiceHealthy": $cameraServiceHealthy,
                "permissionGranted": $permissionGranted,
                "cameraId": ${if (cameraId != null) "\"$cameraId\"" else "null"},
                "errorCount": $errorCount
            }
        """.trimIndent()
    }
}
