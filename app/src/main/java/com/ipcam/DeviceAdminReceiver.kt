package com.ipcam

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
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
    }

    /**
     * Called when this application is approved to be a device administrator.
     */
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled for IP_Cam")
        Toast.makeText(context, "IP_Cam Device Admin enabled", Toast.LENGTH_SHORT).show()
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
