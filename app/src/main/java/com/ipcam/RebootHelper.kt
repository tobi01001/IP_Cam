package com.ipcam

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility class for device reboot operations with multiple fallback methods.
 * 
 * This class attempts to reboot the device using various methods in order of preference:
 * 1. DevicePolicyManager.reboot() - Official Device Owner API (preferred)
 * 2. PowerManager.reboot() - Alternative that may work with Device Owner privileges
 * 3. Shell exec "reboot" - Last resort, may work on some devices
 * 
 * All methods require Device Owner mode. Root access is NOT required.
 */
object RebootHelper {
    private const val TAG = "RebootHelper"
    
    /**
     * Diagnose device reboot capability and return detailed information.
     * 
     * @param context Application context
     * @return RebootDiagnostics object with capability assessment
     */
    fun diagnoseRebootCapability(context: Context): RebootDiagnostics {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) ?: false
        val isDeviceAdmin = dpm?.isAdminActive(adminComponent) ?: false
        val isDeviceLocked = keyguardManager?.isKeyguardLocked ?: true
        val selinuxStatus = getSelinuxStatus()
        val knoxVersion = getKnoxVersion()
        
        Log.i(TAG, "=== REBOOT CAPABILITY DIAGNOSTICS ===")
        Log.i(TAG, "Device Owner: $isDeviceOwner")
        Log.i(TAG, "Device Admin: $isDeviceAdmin")
        Log.i(TAG, "Device Locked: $isDeviceLocked")
        Log.i(TAG, "Manufacturer: ${Build.MANUFACTURER}")
        Log.i(TAG, "Model: ${Build.MODEL}")
        Log.i(TAG, "Android Version: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        Log.i(TAG, "SELinux: $selinuxStatus")
        Log.i(TAG, "Knox: ${knoxVersion ?: "Not present"}")
        
        return RebootDiagnostics.create(
            isDeviceOwner = isDeviceOwner,
            isDeviceAdmin = isDeviceAdmin,
            isDeviceLocked = isDeviceLocked,
            selinuxStatus = selinuxStatus,
            knoxVersion = knoxVersion
        )
    }
    
    /**
     * Attempt to reboot the device using multiple methods with fallbacks.
     * 
     * This method tries three different approaches:
     * 1. DevicePolicyManager.reboot() - Official Device Owner API
     * 2. PowerManager.reboot() - May work with Device Owner privileges
     * 3. Shell exec "reboot" - Last resort
     * 
     * @param context Application context
     * @return RebootResult indicating success or failure with diagnostics
     */
    fun rebootDevice(context: Context): RebootResult {
        Log.i(TAG, "=== DEVICE REBOOT REQUESTED ===")
        
        // Run diagnostics first
        val diagnostics = diagnoseRebootCapability(context)
        
        // Check Device Owner status
        if (!diagnostics.isDeviceOwner) {
            Log.e(TAG, "Cannot reboot: Not Device Owner")
            return RebootResult.NotDeviceOwner
        }
        
        // Warn if device is locked (may cause failure)
        if (diagnostics.isDeviceLocked) {
            Log.w(TAG, "WARNING: Device is locked - reboot may fail")
            // Continue anyway - some devices allow it
        }
        
        // Try Method 1: DevicePolicyManager.reboot()
        Log.d(TAG, "Attempting Method 1: DevicePolicyManager.reboot()...")
        val method1Result = tryDevicePolicyManagerReboot(context)
        if (method1Result.isSuccess()) {
            Log.i(TAG, "Method 1 successful - device rebooting")
            return method1Result
        }
        Log.w(TAG, "Method 1 failed: ${method1Result.toJson()}")
        
        // Try Method 2: PowerManager.reboot()
        Log.d(TAG, "Attempting Method 2: PowerManager.reboot()...")
        val method2Result = tryPowerManagerReboot(context)
        if (method2Result.isSuccess()) {
            Log.i(TAG, "Method 2 successful - device rebooting")
            return method2Result
        }
        Log.w(TAG, "Method 2 failed: ${method2Result.toJson()}")
        
        // Try Method 3: Shell exec
        Log.d(TAG, "Attempting Method 3: Shell reboot command...")
        val method3Result = tryShellReboot()
        if (method3Result.isSuccess()) {
            Log.i(TAG, "Method 3 successful - device rebooting")
            return method3Result
        }
        Log.w(TAG, "Method 3 failed: ${method3Result.toJson()}")
        
        // All methods failed
        Log.e(TAG, "=== ALL REBOOT METHODS FAILED ===")
        return RebootResult.AllMethodsFailed(
            method1 = method1Result.toJson(),
            method2 = method2Result.toJson(),
            method3 = method3Result.toJson(),
            diagnostics = diagnostics
        )
    }
    
    /**
     * Try rebooting via DevicePolicyManager (official Device Owner API)
     */
    private fun tryDevicePolicyManagerReboot(context: Context): RebootResult {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm == null) {
                Log.e(TAG, "DevicePolicyManager not available")
                return RebootResult.SecurityException("DevicePolicyManager not available", "DPM.reboot()")
            }
            
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            Log.d(TAG, "Calling DevicePolicyManager.reboot()...")
            dpm.reboot(adminComponent)
            
            // If we reach here, reboot was accepted
            Log.i(TAG, "DevicePolicyManager.reboot() accepted - device should reboot shortly")
            RebootResult.Success
            
        } catch (e: SecurityException) {
            Log.e(TAG, "DevicePolicyManager.reboot() SecurityException", e)
            RebootResult.SecurityException(
                message = e.message ?: "Security exception",
                method = "DevicePolicyManager.reboot()"
            )
        } catch (e: Exception) {
            Log.e(TAG, "DevicePolicyManager.reboot() exception", e)
            RebootResult.SecurityException(
                message = "${e.javaClass.simpleName}: ${e.message}",
                method = "DevicePolicyManager.reboot()"
            )
        }
    }
    
    /**
     * Try rebooting via PowerManager (may work with Device Owner privileges)
     */
    private fun tryPowerManagerReboot(context: Context): RebootResult {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm == null) {
                Log.e(TAG, "PowerManager not available")
                return RebootResult.SecurityException("PowerManager not available", "PowerManager.reboot()")
            }
            
            Log.d(TAG, "Calling PowerManager.reboot()...")
            pm.reboot("IP_Cam restart")
            
            // If we reach here, reboot was accepted
            Log.i(TAG, "PowerManager.reboot() accepted - device should reboot shortly")
            RebootResult.Success
            
        } catch (e: SecurityException) {
            Log.e(TAG, "PowerManager.reboot() SecurityException", e)
            RebootResult.SecurityException(
                message = e.message ?: "Security exception",
                method = "PowerManager.reboot()"
            )
        } catch (e: Exception) {
            Log.e(TAG, "PowerManager.reboot() exception", e)
            RebootResult.SecurityException(
                message = "${e.javaClass.simpleName}: ${e.message}",
                method = "PowerManager.reboot()"
            )
        }
    }
    
    /**
     * Try rebooting via shell command (last resort)
     */
    private fun tryShellReboot(): RebootResult {
        return try {
            Log.d(TAG, "Executing shell command: reboot")
            val process = Runtime.getRuntime().exec("reboot")
            
            // Give command time to execute
            Thread.sleep(1000)
            
            // If process exited with error, check exit code
            try {
                val exitCode = process.exitValue()
                if (exitCode != 0) {
                    Log.e(TAG, "Shell reboot failed with exit code: $exitCode")
                    return RebootResult.SecurityException(
                        message = "Shell command failed with exit code $exitCode",
                        method = "shell exec reboot"
                    )
                }
            } catch (e: IllegalThreadStateException) {
                // Process still running - this is actually good, means command accepted
                Log.d(TAG, "Shell reboot command accepted (process still running)")
            }
            
            Log.i(TAG, "Shell reboot command executed - device should reboot shortly")
            RebootResult.Success
            
        } catch (e: Exception) {
            Log.e(TAG, "Shell reboot exception", e)
            RebootResult.SecurityException(
                message = "${e.javaClass.simpleName}: ${e.message}",
                method = "shell exec reboot"
            )
        }
    }
    
    /**
     * Get SELinux enforcement status
     */
    private fun getSelinuxStatus(): String {
        return try {
            val process = Runtime.getRuntime().exec("getenforce")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val status = reader.readLine() ?: "Unknown"
            reader.close()
            status.trim()
        } catch (e: Exception) {
            Log.d(TAG, "Could not determine SELinux status", e)
            "Unknown"
        }
    }
    
    /**
     * Get Samsung Knox version if present
     */
    private fun getKnoxVersion(): String? {
        return try {
            // Try to get Knox version from system property
            val knoxVersion = Build::class.java
                .getDeclaredField("VERSION")
                .get(null)
                ?.let { versionClass ->
                    versionClass.javaClass
                        .getDeclaredField("KNOX_VERSION")
                        .get(versionClass) as? String
                }
            
            if (knoxVersion != null) {
                Log.d(TAG, "Knox version detected: $knoxVersion")
                return knoxVersion
            }
            
            // Alternative: Check system properties
            val process = Runtime.getRuntime().exec("getprop ro.config.knox")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val knoxProp = reader.readLine()
            reader.close()
            
            if (!knoxProp.isNullOrBlank() && knoxProp != "0") {
                Log.d(TAG, "Knox detected via property: $knoxProp")
                return knoxProp
            }
            
            null
        } catch (e: Exception) {
            Log.d(TAG, "Knox not detected or could not be determined", e)
            null
        }
    }
}
