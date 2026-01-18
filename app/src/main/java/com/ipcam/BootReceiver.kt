package com.ipcam

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "IPCamSettings"
        private const val PREF_AUTO_START = "autoStartServer"
        
        /**
         * Check if all required permissions are granted
         * This prevents service crashes and ANR when starting at boot
         */
        private fun hasRequiredPermissions(context: Context): Boolean {
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            
            // POST_NOTIFICATIONS only required on Android 13+
            val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not required on older Android versions
            }
            
            if (!hasCameraPermission) {
                Log.e(TAG, "Missing CAMERA permission")
            }
            if (!hasNotificationPermission) {
                Log.e(TAG, "Missing POST_NOTIFICATIONS permission")
            }
            
            return hasCameraPermission && hasNotificationPermission
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received broadcast: $action")
        
        // Handle both BOOT_COMPLETED and LOCKED_BOOT_COMPLETED
        // LOCKED_BOOT_COMPLETED is received earlier (before device unlock) on devices with Direct Boot
        // BOOT_COMPLETED is received after user unlocks device
        if (action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed (action: $action), checking autostart preference")
            
            // Check if autostart is enabled
            // Use device-protected storage context for Direct Boot compatibility
            val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            
            val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
            
            Log.i(TAG, "Auto-start setting: $autoStart, Android API: ${Build.VERSION.SDK_INT}")
            
            if (!autoStart) {
                Log.d(TAG, "Auto-start disabled, not starting service")
                return
            }
            
            Log.i(TAG, "Auto-start enabled - checking permissions before starting")
            
            // Check permissions before attempting to start service
            // This prevents ANR and service crashes due to missing permissions
            if (!hasRequiredPermissions(context)) {
                Log.e(TAG, "Missing required permissions - cannot auto-start service")
                Log.e(TAG, "User must open app and grant permissions for auto-start to work")
                return
            }
            
            Log.i(TAG, "All permissions granted, proceeding with auto-start")
            
            // On Android 14+ (API 34+), start MainActivity to keep app in recent tasks
            // This enables camera access - Android requires app be in recent tasks for camera
            // MainActivity STAYS OPEN since this is the primary interface for the IP camera device
            // MainActivity will delay starting the service until it's fully visible
            if (Build.VERSION.SDK_INT >= 34) { // Android 14 (UPSIDE_DOWN_CAKE)
                Log.d(TAG, "Android 14+: Starting MainActivity for camera eligibility")
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("FROM_BOOT", true) // Flag to indicate boot start
                }
                try {
                    context.startActivity(activityIntent)
                    Log.i(TAG, "MainActivity started - will remain open for camera access")
                    Log.i(TAG, "MainActivity will start service after it becomes fully visible")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MainActivity at boot: ${e.message}", e)
                }
            } else {
                // Android 11-13: Start service directly (no recent tasks requirement)
                Log.i(TAG, "Android 11-13: Starting service directly")
                val serviceIntent = Intent(context, CameraService::class.java)
                serviceIntent.putExtra(CameraService.EXTRA_START_SERVER, true)
                
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.i(TAG, "Camera service started successfully on boot")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot: ${e.message}", e)
                }
            }
        } else {
            Log.d(TAG, "Ignoring unhandled broadcast action: $action")
        }
    }
}
