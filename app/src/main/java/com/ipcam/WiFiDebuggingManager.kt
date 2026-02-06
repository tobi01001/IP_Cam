package com.ipcam

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*

/**
 * WiFi Debugging Manager
 * 
 * Maintains WiFi debugging (ADB over network) for remote device access.
 * Only works when app is Device Owner.
 * 
 * This enables remote maintenance of IP camera devices without physical access:
 * - Keeps WiFi debugging enabled
 * - Maintains ADB over network connection
 * - Automatically re-enables if disabled
 * - Provides ADB connection port information
 */
class WiFiDebuggingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WiFiDebuggingManager"
        private const val CHECK_INTERVAL_MS = 60000L // Check every minute
        private const val ADB_WIFI_PORT = 5555 // Standard ADB WiFi port
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null
    
    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    
    private val adminComponent: ComponentName by lazy {
        ComponentName(context, DeviceAdminReceiver::class.java)
    }
    
    /**
     * Start monitoring and maintaining WiFi debugging
     */
    fun startMonitoring() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner - cannot manage WiFi debugging")
            return
        }
        
        if (monitoringJob?.isActive == true) {
            Log.d(TAG, "Monitoring already active")
            return
        }
        
        monitoringJob = scope.launch {
            Log.i(TAG, "WiFi debugging monitoring started")
            
            while (isActive) {
                try {
                    ensureWiFiDebuggingEnabled()
                } catch (e: Exception) {
                    Log.e(TAG, "Error maintaining WiFi debugging", e)
                }
                
                delay(CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Log.i(TAG, "WiFi debugging monitoring stopped")
    }
    
    /**
     * Check if app is Device Owner
     */
    private fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Device Owner status", e)
            false
        }
    }
    
    /**
     * Ensure WiFi debugging and ADB over network are enabled
     */
    private fun ensureWiFiDebuggingEnabled() {
        if (!isDeviceOwner()) {
            return
        }
        
        try {
            // Check current ADB status
            val adbEnabled = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
            
            // Enable ADB if not already enabled
            if (!adbEnabled) {
                Log.i(TAG, "ADB is disabled, enabling...")
                dpm.setGlobalSetting(
                    adminComponent,
                    Settings.Global.ADB_ENABLED,
                    "1"
                )
                Log.i(TAG, "ADB enabled")
            }
            
            // Try to enable WiFi debugging (may not be available on all Android versions)
            try {
                // This setting may require Android 11+ and specific device support
                dpm.setGlobalSetting(
                    adminComponent,
                    "adb_wifi_enabled",
                    "1"
                )
                Log.d(TAG, "WiFi debugging enabled")
            } catch (e: Exception) {
                // WiFi debugging setting may not be available
                Log.d(TAG, "WiFi debugging setting not available on this device: ${e.message}")
            }
            
            // Set TCP/IP port for ADB
            try {
                dpm.setGlobalSetting(
                    adminComponent,
                    "adb_port",
                    ADB_WIFI_PORT.toString()
                )
                Log.d(TAG, "ADB port set to $ADB_WIFI_PORT")
            } catch (e: Exception) {
                Log.d(TAG, "Could not set ADB port: ${e.message}")
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception - insufficient permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling WiFi debugging", e)
        }
    }
    
    /**
     * Get ADB connection information
     */
    fun getADBConnectionInfo(): ADBConnectionInfo {
        val adbEnabled = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            false
        }
        
        val port = try {
            Settings.Global.getInt(
                context.contentResolver,
                "adb_port",
                ADB_WIFI_PORT
            )
        } catch (e: Exception) {
            ADB_WIFI_PORT
        }
        
        return ADBConnectionInfo(
            enabled = adbEnabled,
            port = port,
            isDeviceOwner = isDeviceOwner()
        )
    }
    
    /**
     * Force enable WiFi debugging now (immediate check)
     */
    fun forceEnableNow() {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not Device Owner - cannot enable WiFi debugging")
            return
        }
        
        scope.launch {
            ensureWiFiDebuggingEnabled()
        }
    }
    
    /**
     * ADB connection information
     */
    data class ADBConnectionInfo(
        val enabled: Boolean,
        val port: Int,
        val isDeviceOwner: Boolean
    ) {
        fun getConnectionString(ipAddress: String): String {
            return if (enabled && isDeviceOwner) {
                "adb connect $ipAddress:$port"
            } else {
                "ADB not available"
            }
        }
    }
}
