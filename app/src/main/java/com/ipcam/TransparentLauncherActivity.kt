package com.ipcam

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Transparent launcher activity used to get app into "recent tasks" list at boot.
 * 
 * Android 14/15 requires apps to be in recent tasks to access camera.
 * At boot, starting a service doesn't add the app to recent tasks, causing
 * ERROR_CAMERA_DISABLED when trying to access camera.
 * 
 * Solution: Start this transparent activity briefly at boot to satisfy the
 * "recent tasks" requirement, then finish immediately.
 * 
 * This activity:
 * - Has no UI (transparent theme)
 * - Finishes immediately in onCreate()
 * - Adds app to recent tasks list
 * - Makes app "eligible" for camera access
 */
class TransparentLauncherActivity : Activity() {
    companion object {
        private const val TAG = "TransparentLauncher"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "Transparent launcher activity started - adding app to recent tasks")
        
        // This activity has done its job just by being created:
        // - App is now in recent tasks list
        // - Camera access will be allowed
        // Finish immediately so user doesn't see anything
        finish()
        
        Log.d(TAG, "Transparent launcher activity finished - app now eligible for camera access")
    }
}
