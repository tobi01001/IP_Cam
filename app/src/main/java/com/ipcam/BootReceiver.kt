package com.ipcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "IPCamSettings"
        private const val PREF_AUTO_START = "autoStartServer"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed, checking autostart preference")
            
            // Check if autostart is enabled
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
            
            if (autoStart) {
                Log.d(TAG, "Autostart enabled, starting CameraService")
                // Note: Service will check for camera permission on startup and handle accordingly
                // The service is designed to handle missing permissions gracefully
                val serviceIntent = Intent(context, CameraService::class.java)
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service on boot", e)
                }
            } else {
                Log.d(TAG, "Autostart disabled, not starting service")
            }
        }
    }
}
