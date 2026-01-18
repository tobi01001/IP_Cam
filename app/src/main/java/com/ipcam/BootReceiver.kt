package com.ipcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "IPCamSettings"
        private const val PREF_AUTO_START = "autoStartServer"
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
            
            Log.i(TAG, "Auto-start enabled, starting camera service")
            
            // On Android 14+ (API 34+), start MainActivity to add app to recent tasks
            // This is required for camera access - Android blocks camera unless app is in recent tasks
            // MainActivity will stay open as it's the main interface for the IP camera device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d(TAG, "Android 14+: Starting MainActivity to enable camera access")
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MainActivity at boot: ${e.message}", e)
                }
                
                // Small delay to let activity start before service
                Thread.sleep(500)
            }
            
            val serviceIntent = Intent(context, CameraService::class.java)
            serviceIntent.putExtra(CameraService.EXTRA_START_SERVER, true)
            
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.i(TAG, "Successfully requested foreground service start on boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service on boot: ${e.message}", e)
                Log.e(TAG, "This may indicate missing permissions or Android restrictions")
            }
        } else {
            Log.d(TAG, "Ignoring unhandled broadcast action: $action")
        }
    }
}
