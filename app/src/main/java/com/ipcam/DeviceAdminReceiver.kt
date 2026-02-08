package com.ipcam

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import android.widget.Toast

/**
 * Device Admin Receiver for IP_Cam.
 * 
 * This receiver enables Device Owner mode for silent app updates on dedicated surveillance devices.
 * 
 * Setup Instructions:
 * 1. Factory reset device (or use fresh device without Google accounts)
 * 2. Install IP_Cam APK via USB/adb
 * 3. Run: adb shell dpm set-device-owner com.ipcam/.DeviceAdminReceiver
 * 4. Verify: adb shell dpm list-owners
 * 
 * Once set as Device Owner, the app can:
 * - Install updates silently without user confirmation
 * - Manage device settings and policies
 * - Lock down device for dedicated IP camera use
 * 
 * IMPORTANT: Device Owner cannot be set if:
 * - Device has user accounts (Google, etc.)
 * - Another device admin is active
 * - Device is already provisioned
 * 
 * To remove Device Owner (if needed):
 * adb shell dpm remove-active-admin com.ipcam/.DeviceAdminReceiver
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"
        
        /**
         * Reboot the device (requires Device Owner mode).
         * 
         * @param context Application context
         * @return true if reboot was initiated, false if not Device Owner
         */
        fun rebootDevice(context: Context): Boolean {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                if (dpm == null) {
                    Log.e(TAG, "DevicePolicyManager not available")
                    return false
                }
                
                val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
                
                // Check if we're Device Owner
                if (!dpm.isDeviceOwnerApp(context.packageName)) {
                    Log.w(TAG, "Reboot requested but app is not Device Owner")
                    return false
                }
                
                Log.i(TAG, "Device Owner reboot initiated via API")
                
                // Use PowerManager to reboot the device
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                if (powerManager != null) {
                    // Reboot the device (requires REBOOT permission)
                    powerManager.reboot("IP_Cam remote reboot")
                    return true
                } else {
                    Log.e(TAG, "PowerManager not available")
                    return false
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error rebooting device", e)
                return false
            }
        }
    }

    /**
     * Called when this application is approved to be a device administrator.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled for IP_Cam")
        
        // Clear restrictive user restrictions to ensure Settings access
        clearRestrictiveUserRestrictions(context)
        
        Toast.makeText(context, "IP_Cam Device Admin enabled", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Clear user restrictions that might block access to Settings or system features.
     * 
     * This is critical for LineageOS and other custom ROMs that may apply default
     * restrictions when Device Owner is set. We clear only restrictions that would
     * interfere with basic device configuration while maintaining security.
     */
    private fun clearRestrictiveUserRestrictions(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm == null) {
                Log.w(TAG, "DevicePolicyManager not available")
                return
            }
            
            val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)
            
            // Only proceed if we're actually Device Owner
            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.d(TAG, "Not Device Owner, skipping restriction clearing")
                return
            }
            
            // List of restrictions to clear for better Settings access
            // These restrictions can block access to Settings or configuration
            val restrictionsToClear = listOf(
                UserManager.DISALLOW_MODIFY_ACCOUNTS,      // Allow account management
                UserManager.DISALLOW_CONFIG_WIFI,          // Allow WiFi configuration
                UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, // Allow mobile network config
                UserManager.DISALLOW_ADJUST_VOLUME,        // Allow volume adjustment
                UserManager.DISALLOW_CONFIG_BLUETOOTH,     // Allow Bluetooth config
                UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT, // Allow screen timeout config
                UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS, // Allow error dialogs
                UserManager.DISALLOW_CONFIG_DATE_TIME,     // Allow date/time config
                UserManager.DISALLOW_CONFIG_LOCATION,      // Allow location config
                UserManager.DISALLOW_SAFE_BOOT,            // Allow safe boot
                UserManager.DISALLOW_APPS_CONTROL,         // Allow app management
                UserManager.DISALLOW_CONFIG_CELL_BROADCASTS, // Allow cell broadcast config
                UserManager.DISALLOW_CONFIG_VPN,           // Allow VPN config
                UserManager.DISALLOW_DEBUGGING_FEATURES,   // Allow debugging features
                UserManager.DISALLOW_INSTALL_APPS,         // Allow app installation (for updates)
                UserManager.DISALLOW_UNINSTALL_APPS,       // Allow app uninstallation
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, // Allow mounting media
                UserManager.DISALLOW_NETWORK_RESET,        // Allow network reset
                UserManager.DISALLOW_FACTORY_RESET         // Allow factory reset
            )
            
            var clearedCount = 0
            restrictionsToClear.forEach { restriction ->
                try {
                    // Clear the restriction if it exists
                    dpm.clearUserRestriction(adminComponent, restriction)
                    clearedCount++
                    Log.d(TAG, "Cleared restriction: $restriction")
                } catch (e: Exception) {
                    Log.d(TAG, "Could not clear restriction $restriction: ${e.message}")
                }
            }
            
            Log.i(TAG, "Cleared $clearedCount user restrictions to ensure Settings access")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user restrictions", e)
        }
    }

    /**
     * Called when this application is no longer the device administrator.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled for IP_Cam")
        Toast.makeText(context, "IP_Cam Device Admin disabled", Toast.LENGTH_SHORT).show()
    }

    /**
     * Called when the device owner status is granted to this app.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Device Owner provisioning complete")
        
        // Clear restrictive user restrictions to ensure Settings access
        clearRestrictiveUserRestrictions(context)
        
        Toast.makeText(context, "IP_Cam is now Device Owner - silent updates enabled", Toast.LENGTH_LONG).show()
    }

    /**
     * Called when the user has disabled the administrator.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "Device Admin disable requested")
        return "Disabling Device Admin will prevent silent app updates for this surveillance device. Continue?"
    }

    /**
     * Called when the password has been changed.
     */
    override fun onPasswordChanged(context: Context, intent: Intent) {
        super.onPasswordChanged(context, intent)
        Log.i(TAG, "Device password changed")
    }

    /**
     * Called when the password has failed.
     */
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Device password failed")
    }

    /**
     * Called when the password has succeeded.
     */
    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        Log.i(TAG, "Device password succeeded")
    }

    /**
     * Called when the device lock has changed.
     */
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "Lock task mode entering: $pkg")
    }

    /**
     * Called when the device lock task has exited.
     */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.i(TAG, "Lock task mode exiting")
    }
}
