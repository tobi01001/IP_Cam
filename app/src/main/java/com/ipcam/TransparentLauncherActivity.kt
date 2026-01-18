package com.ipcam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Transparent launcher activity used to get app into "recent tasks" list at boot.
 * 
 * Android 14/15 requires apps to be in recent tasks AND have a visible activity
 * to access camera. At boot, starting only a service doesn't satisfy this requirement,
 * causing ERROR_CAMERA_DISABLED when trying to access camera.
 * 
 * Solution: Start MainActivity briefly at boot with a special flag, keep it visible
 * for 2 seconds to establish camera eligibility, then finish it automatically.
 * 
 * This allows camera to work without requiring user interaction.
 */
class TransparentLauncherActivity : Activity() {
    companion object {
        private const val TAG = "TransparentLauncher"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "Transparent launcher started - launching MainActivity to establish camera eligibility")
        
        // Launch MainActivity with special flag to indicate boot start
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("BOOT_START", true)
        }
        startActivity(intent)
        
        // Finish this transparent activity immediately
        // MainActivity will handle the visibility requirement
        finish()
        
        Log.d(TAG, "Transparent launcher finished - MainActivity launched for camera eligibility")
    }
}
