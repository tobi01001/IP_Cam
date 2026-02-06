package com.ipcam

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

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
 * - Handles pairing authentication automatically
 */
class WiFiDebuggingManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WiFiDebuggingManager"
        private const val CHECK_INTERVAL_MS = 60000L // Check every minute
        private const val DEFAULT_ADB_PORT = 5555 // Standard ADB WiFi port for older Android
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
     * Get the actual ADB WiFi port from system
     * On Android 11+, ADB over WiFi may use dynamic ports
     */
    private fun getActualADBPort(): Int {
        return try {
            // Method 1: Try service.adb.tcp.port property
            var port = getPortFromProperty("service.adb.tcp.port")
            if (port > 0) {
                Log.i(TAG, "Found ADB port from service.adb.tcp.port: $port")
                return port
            }
            
            // Method 2: Try persist.adb.tcp.port property (persistent setting)
            port = getPortFromProperty("persist.adb.tcp.port")
            if (port > 0) {
                Log.i(TAG, "Found ADB port from persist.adb.tcp.port: $port")
                return port
            }
            
            // Method 3: Try Settings.Global adb_port
            try {
                val settingsPort = Settings.Global.getInt(
                    context.contentResolver,
                    "adb_port",
                    -1
                )
                if (settingsPort > 0) {
                    Log.i(TAG, "Found ADB port from Settings.Global.adb_port: $settingsPort")
                    return settingsPort
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not read adb_port: ${e.message}")
            }
            
            // Method 4: On Android 11+, check wireless debugging port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Try adb_wifi_port
                try {
                    val wifiPort = Settings.Global.getInt(
                        context.contentResolver,
                        "adb_wifi_port",
                        -1
                    )
                    if (wifiPort > 0) {
                        Log.i(TAG, "Found wireless debugging port from adb_wifi_port: $wifiPort")
                        return wifiPort
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Could not read adb_wifi_port: ${e.message}")
                }
                
                // Try reading from /proc/net/tcp to find actual listening port
                port = findADBPortFromProcNet()
                if (port > 0) {
                    Log.i(TAG, "Found ADB port from /proc/net/tcp: $port")
                    return port
                }
            }
            
            // Method 5: Check sys.usb.config for adb mode
            val usbConfig = getPortFromProperty("sys.usb.config")
            if (usbConfig > 0) {
                Log.i(TAG, "Found ADB port from sys.usb.config: $usbConfig")
                return usbConfig
            }
            
            // Method 6: Try ro.adb.secure property
            val adbSecure = getPortFromProperty("ro.adb.secure")
            if (adbSecure > 0) {
                Log.i(TAG, "Found ADB port from ro.adb.secure: $adbSecure")
                return adbSecure
            }
            
            Log.w(TAG, "Could not detect ADB port, using default: $DEFAULT_ADB_PORT")
            DEFAULT_ADB_PORT
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting ADB port: ${e.message}", e)
            DEFAULT_ADB_PORT
        }
    }
    
    /**
     * Get port from system property using getprop
     */
    private fun getPortFromProperty(propertyName: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", propertyName))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val value = reader.readLine()
            reader.close()
            process.waitFor()
            
            if (value != null && value.isNotEmpty() && value != "-1" && value != "0") {
                // Try to parse as integer
                value.toIntOrNull() ?: -1
            } else {
                -1
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read property $propertyName: ${e.message}")
            -1
        }
    }
    
    /**
     * Find ADB port by reading /proc/net/tcp and looking for listening sockets
     * ADB typically listens on a high port (> 1024)
     */
    private fun findADBPortFromProcNet(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("cat", "/proc/net/tcp"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var port = -1
            reader.forEachLine { line ->
                // Format: sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode
                // We're looking for LISTEN state (0A) with high port
                if (line.contains("0A") && port < 0) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size > 1) {
                        // local_address is in format IP:PORT (hex)
                        val localAddr = parts[1]
                        if (localAddr.contains(":")) {
                            val portHex = localAddr.split(":")[1]
                            try {
                                val detectedPort = Integer.parseInt(portHex, 16)
                                // ADB typically uses ports > 5000 and < 65536
                                if (detectedPort in 5000..65535) {
                                    Log.d(TAG, "Found potential ADB port in /proc/net/tcp: $detectedPort")
                                    port = detectedPort
                                }
                            } catch (e: NumberFormatException) {
                                // Skip invalid port
                            }
                        }
                    }
                }
            }
            
            reader.close()
            process.waitFor()
            port
        } catch (e: Exception) {
            Log.d(TAG, "Could not read /proc/net/tcp: ${e.message}")
            -1
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
            
            // For Android 11+ (API 30+), enable wireless debugging
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    // Enable wireless debugging
                    dpm.setGlobalSetting(
                        adminComponent,
                        "adb_wifi_enabled",
                        "1"
                    )
                    
                    // Disable pairing requirement for Device Owner (allows passwordless connection)
                    dpm.setGlobalSetting(
                        adminComponent,
                        "adb_wifi_pairing_required",
                        "0"
                    )
                    
                    Log.i(TAG, "Wireless debugging enabled with auto-pairing")
                } catch (e: Exception) {
                    Log.d(TAG, "Could not configure wireless debugging: ${e.message}")
                }
            }
            
            // For older Android or fallback, set TCP/IP port
            try {
                dpm.setGlobalSetting(
                    adminComponent,
                    "adb_port",
                    DEFAULT_ADB_PORT.toString()
                )
                Log.d(TAG, "ADB TCP port set to $DEFAULT_ADB_PORT")
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
        
        // Get actual port (may be dynamic)
        val port = getActualADBPort()
        
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
