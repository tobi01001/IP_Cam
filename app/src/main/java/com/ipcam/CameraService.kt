package com.ipcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraService - Single Source of Truth for Camera Operations
 * 
 * This service manages camera operations, HTTP/RTSP streaming, and provides a unified interface
 * for both the MainActivity UI and web clients.
 * 
 * LIFECYCLE MANAGEMENT:
 * ====================
 * 
 * Service Lifecycle:
 * ------------------
 * - Implements LifecycleOwner with custom LifecycleRegistry
 * - Uses foreground service to persist across Activity lifecycle changes
 * - START_STICKY ensures automatic restart after system kills
 * - onTaskRemoved() schedules restart when app is swiped away
 * 
 * Callback Management:
 * -------------------
 * - Three callbacks communicate with MainActivity:
 *   1. onFrameAvailableCallback: Delivers preview frames (bitmaps)
 *   2. onCameraStateChangedCallback: Notifies of camera/settings changes
 *   3. onConnectionsChangedCallback: Updates connection counts
 * 
 * - Callbacks are @Volatile to ensure visibility across threads
 * - clearCallbacks() MUST be called in MainActivity.onDestroy() to prevent memory leaks
 * - All callback invocations use safe wrappers (safeInvokeXxxCallback) that:
 *   * Check service lifecycle state (skip if DESTROYED)
 *   * Handle null callbacks gracefully
 *   * Recycle bitmaps if callback can't be invoked
 * 
 * Coroutine Management:
 * --------------------
 * - serviceScope: Custom CoroutineScope for long-running operations
 *   * Uses SupervisorJob to isolate failures
 *   * Persists across Activity lifecycle changes
 *   * Cancelled in onDestroy() AFTER all other cleanup
 * 
 * - Coroutines tied to service lifecycle, NOT Activity lifecycle
 * - This ensures camera continues running when MainActivity is destroyed
 * - All coroutines check lifecycle state before critical operations
 * 
 * Executor Management:
 * -------------------
 * - cameraExecutor: Single thread for CameraX analysis callbacks
 * - processingExecutor: 2-thread pool for image processing (rotation, JPEG encoding)
 * - streamingExecutor: Cached thread pool for HTTP streaming connections
 * 
 * - All executors shut down in onDestroy() with proper cleanup:
 *   1. Camera executor (stops frame capture)
 *   2. Processing executor (waits up to 2s, then forces shutdown)
 *   3. Streaming executor (immediate forceful shutdown)
 * 
 * Resource Cleanup:
 * ----------------
 * - onDestroy() cleanup order:
 *   1. Set lifecycle to DESTROYED (stops new callback invocations)
 *   2. Clear callbacks (break reference cycles)
 *   3. Unregister receivers, stop HTTP/RTSP servers
 *   4. Shutdown executors (camera → processing → streaming)
 *   5. Clear bitmap pool and frame references
 *   6. Cancel coroutine scope
 *   7. Release wake locks
 * 
 * Thread Safety:
 * -------------
 * - Callback fields marked @Volatile for thread-safe access
 * - Safe callback wrappers check lifecycle state atomically
 * - Bitmap pool and frame data protected by synchronized locks
 * 
 * Design Rationale:
 * ----------------
 * We use a custom LifecycleOwner instead of LifecycleService because:
 * 1. Service must persist across Activity destroy/recreate cycles
 * 2. Camera operations are independent of MainActivity lifecycle
 * 3. Web clients can stream even when MainActivity is destroyed
 * 4. Custom lifecycle gives precise control over resource management
 * 
 * API Level: Minimum API 30 (Android 11+)
 * - No need for pre-API-30 compatibility checks
 * - Uses modern Android lifecycle and camera APIs
 */
class CameraService : Service(), LifecycleOwner, CameraServiceInterface {
    
    /**
     * ProcessedFrame - Atomic container for frame data
     * 
     * Ensures UI (Bitmap) and web stream (JPEG) always display the same frame by
     * bundling them together with their timestamp. This prevents synchronization
     * issues where the UI and web clients could show frames from different moments.
     * 
     * Using AtomicReference for lock-free, thread-safe updates that guarantee
     * atomicity across all three fields simultaneously.
     */
    private data class ProcessedFrame(
        val bitmap: Bitmap,      // For MainActivity preview
        val jpegBytes: ByteArray, // Pre-compressed for HTTP streaming
        val timestamp: Long       // Capture timestamp for staleness detection
    ) {
        // Override equals/hashCode to handle ByteArray properly
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as ProcessedFrame
            
            if (bitmap != other.bitmap) return false
            if (!jpegBytes.contentEquals(other.jpegBytes)) return false
            if (timestamp != other.timestamp) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = bitmap.hashCode()
            result = 31 * result + jpegBytes.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            return result
        }
    }
    
    private val binder = LocalBinder()
    @Volatile private var httpServer: HttpServer? = null
    private var currentCamera = CameraSelector.DEFAULT_BACK_CAMERA
    
    // Track if service is stopping due to missing permissions or other fatal errors
    @Volatile private var isStopping = false
    
    // ATOMIC FRAME UPDATES: Single source of truth for frame data
    // AtomicReference ensures the UI and web stream always display the same frame
    // by atomically updating bitmap, JPEG bytes, and timestamp together.
    // This eliminates race conditions from separate bitmapLock and jpegLock.
    private val lastProcessedFrame = AtomicReference<ProcessedFrame?>(null)
    
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    // Dedicated executor for expensive image processing (rotation, annotation, JPEG compression)
    // This prevents blocking the CameraX analysis thread
    private val processingExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ImageProcessing-${System.currentTimeMillis()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1 // Slightly lower priority than camera thread
        }
    }
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var selectedResolution: Size? = null
    // H.264 encoder for RTSP streaming (parallel pipeline)
    private var h264Encoder: H264PreviewEncoder? = null
    private var videoCaptureUseCase: androidx.camera.core.Preview? = null // For H.264 encoding
    
    // MJPEG frame throttling
    @Volatile private var lastMjpegFrameProcessedTimeMs: Long = 0
    private val resolutionCache = mutableMapOf<Int, List<Size>>()
    
    /**
     * CachedCameraCharacteristics - Stores hardware capabilities for a camera
     * 
     * Caching camera characteristics avoids repeated expensive IPC calls to CameraManager.
     * These characteristics are static hardware properties that don't change at runtime.
     * All characteristics are queried once per camera during service initialization.
     */
    private data class CachedCameraCharacteristics(
        val cameraId: String,
        val lensFacing: Int, // CameraCharacteristics.LENS_FACING_BACK or LENS_FACING_FRONT
        val hasFlash: Boolean,
        val supportedResolutions: List<Size> // All supported YUV_420_888 output sizes
    )
    
    // Cache camera characteristics to avoid repeated IPC calls
    // Key: camera ID, Value: cached characteristics
    private val cameraCharacteristicsCache = mutableMapOf<String, CachedCameraCharacteristics>()
    @Volatile private var cameraCharacteristicsCacheInitialized = false
    
    // Per-camera resolution memory: store resolution for each camera separately
    private var backCameraResolution: Size? = null
    private var frontCameraResolution: Size? = null
    // Flashlight control
    @Volatile private var camera: androidx.camera.core.Camera? = null
    @Volatile private var isFlashlightOn: Boolean = false
    @Volatile private var hasFlashUnit: Boolean = false
    // Camera binding state management
    @Volatile private var isBindingInProgress: Boolean = false
    private val bindingLock = Any() // Lock for binding synchronization
    @Volatile private var lastBindRequestTime: Long = 0 // Time of last bind request
    private var pendingBindJob: Job? = null // Job for pending bind operation
    @Volatile private var hasPendingRebind: Boolean = false // Track if a rebind was requested while binding in progress
    // Cache display metrics and battery info to avoid Binder calls from HTTP threads
    private var cachedDensity: Float = 0f
    private var cachedBatteryInfo: BatteryInfo = BatteryInfo(0, false)
    private var lastBatteryUpdate: Long = 0
    @Volatile private var batteryUpdatePending: Boolean = false
    
    // Enhanced Battery Management System
    // Battery thresholds for power management
    private val BATTERY_CRITICAL_PERCENT = 10  // Disable streaming below this
    private val BATTERY_LOW_PERCENT = 20       // Release wakelocks below this
    private val BATTERY_RECOVERY_PERCENT = 50  // Auto-restore above this
    
    // Battery management state
    private enum class BatteryManagementMode {
        NORMAL,          // Full operation (battery > 20% OR charging)
        LOW_BATTERY,     // Wakelocks released (battery ≤ 20%, not charging)
        CRITICAL_BATTERY // Streaming disabled (battery ≤ 10%, not charging)
    }
    
    @Volatile private var batteryMode: BatteryManagementMode = BatteryManagementMode.NORMAL
    @Volatile private var userOverrideBatteryLimit: Boolean = false // User can manually override critical mode if battery > 10%
    // State tracking for delta broadcasting (only send changed values via SSE)
    private val lastBroadcastState = mutableMapOf<String, Any>()
    private val broadcastLock = Any() // Lock for broadcast state synchronization
    private lateinit var lifecycleRegistry: LifecycleRegistry
    // LIFECYCLE MANAGEMENT: Use lifecycleScope for lifecycle-aware coroutines
    // This ensures all coroutines are cancelled when service lifecycle ends (DESTROYED state)
    // However, since CameraService needs to persist across Activity lifecycle changes,
    // we use a custom scope for long-running operations independent of Activity lifecycle.
    // The lifecycleScope is still available for operations that should stop with service destroy.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cameraOrientation: String = "landscape" // "portrait" or "landscape" - base camera recording mode
    private var rotation: Int = 0 // 0, 90, 180, 270 - applied to the camera-oriented image
    private var deviceOrientation: Int = 0 // Current device orientation (for app UI only, not camera)
    private var orientationEventListener: OrientationEventListener? = null
    // OSD overlay settings - individually toggleable
    private var showDateTimeOverlay: Boolean = true // Show date/time in top left
    private var showBatteryOverlay: Boolean = true // Show battery in top right
    private var showResolutionOverlay: Boolean = true // Show actual resolution in bottom right for debugging
    private var showFpsOverlay: Boolean = true // Show FPS in bottom left
    // FPS tracking
    private val fpsFrameTimes = mutableListOf<Long>() // Track frame times for FPS calculation (camera)
    private var currentCameraFps: Float = 0f // Current calculated camera FPS
    private var lastFpsCalculation: Long = 0 // Last time FPS was calculated
    
    // MJPEG streaming FPS tracking (actual frames served to clients)
    private val mjpegFpsFrameTimes = mutableListOf<Long>()
    private var currentMjpegFps: Float = 0f
    private var lastMjpegFpsCalculation: Long = 0
    private val mjpegFpsLock = Any()
    
    // RTSP streaming FPS tracking (actual frames encoded)
    private val rtspFpsFrameTimes = mutableListOf<Long>()
    private var currentRtspFps: Float = 0f
    private var lastRtspFpsCalculation: Long = 0
    private val rtspFpsLock = Any()
    
    // Target FPS settings
    @Volatile private var targetMjpegFps: Int = 10 // Target FPS for MJPEG streaming (default 10)
    @Volatile private var targetRtspFps: Int = 30 // Target FPS for RTSP streaming (default 30)
    
    // CPU usage tracking
    @Volatile private var currentCpuUsage: Float = 0f // Current CPU usage percentage (0-100)
    private var lastCpuCheckTime: Long = 0
    
    private var watchdogRetryDelay: Long = WATCHDOG_RETRY_DELAY_MS
    private var networkReceiver: BroadcastReceiver? = null
    @Volatile private var actualPort: Int = PORT // The actual port being used (may differ from PORT if unavailable)
    // Server-Sent Events (SSE) clients for real-time updates
    private val sseClients = mutableListOf<SSEClient>()
    private val sseClientsLock = Any()
    // Dedicated executor for streaming connections (doesn't block HTTP request threads)
    private val streamingExecutor = Executors.newCachedThreadPool { r -> 
        Thread(r, "StreamingThread-${System.currentTimeMillis()}").apply {
            isDaemon = true
        }
    }
    // Track active streaming connections
    private val activeStreams = AtomicInteger(0)
    // Track active connections with details
    private val activeConnections = mutableMapOf<Long, ConnectionInfo>()
    private val connectionsLock = Any()
    // User-configurable max connections setting
    @Volatile private var maxConnections: Int = HTTP_DEFAULT_MAX_POOL_SIZE
    // Track if server was intentionally stopped (don't auto-restart in watchdog)
    @Volatile private var serverIntentionallyStopped: Boolean = false
    
    // Bandwidth optimization and adaptive quality
    private lateinit var bandwidthMonitor: BandwidthMonitor
    private lateinit var performanceMetrics: PerformanceMetrics
    private lateinit var adaptiveQualityManager: AdaptiveQualityManager
    @Volatile private var adaptiveQualityEnabled: Boolean = true // Can be toggled
    private val clientIdCounter = AtomicInteger(0) // For unique client IDs
    
    // RTSP server for hardware-accelerated H.264 streaming
    private var rtspServer: RTSPServer? = null
    @Volatile private var rtspEnabled: Boolean = false
    @Volatile private var rtspBitrate: Int = -1 // Saved bitrate setting (-1 = auto/default)
    @Volatile private var rtspBitrateMode: String = "VBR" // Saved bitrate mode setting
    
    // Device identification
    @Volatile private var deviceName: String = "" // User-defined device name (default: IP_CAM_{deviceModel})
    
    // Callbacks for MainActivity to receive updates
    // LIFECYCLE SAFETY: These callbacks are cleared when MainActivity is destroyed (in MainActivity.onDestroy)
    // and guarded by lifecycle checks before invocation to prevent crashes from dead contexts.
    // Each callback invocation checks: 1) callback is not null, 2) runs on main thread via runOnUiThread wrapper
    @Volatile private var onCameraStateChangedCallback: ((CameraSelector) -> Unit)? = null
    @Volatile private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
    @Volatile private var onConnectionsChangedCallback: (() -> Unit)? = null
    
    // Bitmap pool for memory-efficient bitmap reuse
    private val bitmapPool = BitmapPool(maxPoolSizeBytes = 64L * 1024 * 1024) // 64 MB pool
    
    // ==================== Camera State Management ====================
    
    /**
     * CameraState - Tracks camera lifecycle for on-demand activation
     */
    private enum class CameraState {
        IDLE,           // Camera not initialized, no consumers
        INITIALIZING,   // Camera binding in progress
        ACTIVE,         // Camera bound and providing frames
        STOPPING,       // Camera unbinding in progress
        ERROR           // Camera failed to initialize
    }
    
    @Volatile private var cameraState: CameraState = CameraState.IDLE
    private val cameraStateLock = Any()
    
    /**
     * Consumer tracking for on-demand camera activation
     * Consumers: Preview (MainActivity), MJPEG clients, RTSP clients, Manual API
     */
    private enum class ConsumerType {
        PREVIEW,    // MainActivity preview
        MJPEG,      // MJPEG stream clients
        RTSP,       // RTSP streaming
        MANUAL      // Manual activation via API (for testing/debugging)
    }
    
    private val consumers = mutableSetOf<ConsumerType>()
    private val consumersLock = Any()
    
    companion object {
         private const val TAG = "CameraService"
         private const val CHANNEL_ID = "CameraServiceChannel"
         private const val NOTIFICATION_ID = 1
         private const val PORT = 8080
         private const val MAX_PORT = 65535
         private const val MAX_PORT_ATTEMPTS = 10 // Try up to 10 ports
         private const val FRAME_STALE_THRESHOLD_MS = 5_000L
         private const val RESOLUTION_DELIMITER = "x"
         private const val WATCHDOG_RETRY_DELAY_MS = 1_000L
         private const val WATCHDOG_MAX_RETRY_DELAY_MS = 30_000L
         // Camera rebinding debounce
         private const val CAMERA_REBIND_DEBOUNCE_MS = 500L // Minimum time between rebind requests
         // Intent extras
         const val EXTRA_START_SERVER = "start_server"
         // Thread pool settings for NanoHTTPD
         // Maximum parallel connections: HTTP_MAX_POOL_SIZE (32 concurrent connections)
         // Separate pools for request handlers and long-lived streaming connections
         // Requests beyond max are queued up to HTTP_QUEUE_CAPACITY (50), then rejected
         private const val HTTP_CORE_POOL_SIZE = 4
         private const val HTTP_DEFAULT_MAX_POOL_SIZE = 32  // Default max connections
         private const val HTTP_MIN_MAX_POOL_SIZE = 4  // Minimum allowed max connections
         private const val HTTP_ABSOLUTE_MAX_POOL_SIZE = 100  // Absolute maximum connections
         private const val HTTP_KEEP_ALIVE_TIME = 60L
         private const val HTTP_QUEUE_CAPACITY = 50 // Max queued requests before rejecting
         // JPEG compression quality settings
         private const val JPEG_QUALITY_CAMERA = 70 // Lower quality to reduce memory pressure
         private const val JPEG_QUALITY_SNAPSHOT = 85 // Higher quality for snapshots
         private const val JPEG_QUALITY_STREAM = 75 // Balanced quality for streaming
         // Stream timing
         private const val STREAM_FRAME_DELAY_MS = 100L // ~10 fps
         // Battery cache update interval
         private const val BATTERY_CACHE_UPDATE_INTERVAL_MS = 30_000L // 30 seconds
         // Camera activation delay
         private const val CAMERA_ACTIVATION_DELAY_MS = 500L // Delay before activating camera when consumer registers
         // Settings keys
         private const val PREF_MAX_CONNECTIONS = "maxConnections"
     }
     
     /**
      * Represents details about an active HTTP connection
      */
     data class ConnectionInfo(
         val id: Long,
         val remoteAddr: String,
         val endpoint: String,
         val startTime: Long,
         @Volatile var active: Boolean = true
     )
     
     /**
      * Represents a Server-Sent Events client connection
      */
     private data class SSEClient(
         val id: Long,
         val outputStream: java.io.OutputStream,
         @Volatile var active: Boolean = true
     )
     
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    
    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // Create notification channel immediately (required before startForeground)
        createNotificationChannel()
        
        // CRITICAL: Must call startForeground() within 5 seconds of startForegroundService()
        // Do this BEFORE any other operations to avoid ANR
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
            // If foreground start fails, stop the service gracefully
            stopSelf()
            return
        }
        
        // Note: Permission checks are done BEFORE starting the service (in BootReceiver and MainActivity)
        // This ensures the service only starts when permissions are available
        // If you see this service running, permissions were validated before start
        
        // Load saved settings
        loadSettings()
        
        // Initialize camera characteristics cache early to avoid repeated IPC calls
        // This queries hardware capabilities once and caches them for the service lifetime
        initializeCameraCharacteristicsCache()
        
        // Initialize bandwidth optimization components
        bandwidthMonitor = BandwidthMonitor()
        performanceMetrics = PerformanceMetrics(this)
        adaptiveQualityManager = AdaptiveQualityManager(bandwidthMonitor, performanceMetrics)
        Log.d(TAG, "Bandwidth optimization components initialized")
        
        // Cache display density to avoid Binder calls from HTTP threads
        cachedDensity = resources.displayMetrics.density
        // Schedule initial battery cache update to ensure context is initialized
        serviceScope.launch(Dispatchers.Main) {
            try {
                updateBatteryCache()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize battery cache", e)
            }
        }
        
        // Check for POST_NOTIFICATIONS permission on Android 13+
        // Note: startForeground() will still succeed even without permission on Android 13+,
        // but the notification won't be visible to the user. The service continues to run normally.
        if (!hasNotificationPermission()) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Foreground service notification may not be visible on Android 13+")
        }
        
        acquireLocks()
        registerNetworkReceiver()
        setupOrientationListener()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        // CAMERA INITIALIZATION:
        // DO NOT automatically start camera on service creation
        // Camera will start on-demand when first consumer (preview, MJPEG, RTSP) connects
        // This saves resources when the app is running as a launcher without active use
        Log.d(TAG, "Camera initialization deferred until first consumer connects")
        cameraState = CameraState.IDLE
        
        // AUTO-START RTSP SERVER (always running/idling)
        // RTSP server starts immediately but camera activates only when clients connect
        // This allows RTSP clients to connect without manual web activation
        serviceScope.launch {
            delay(2000) // Brief delay to ensure service is fully initialized
            if (enableRTSPStreaming()) {
                Log.i(TAG, "RTSP server auto-started and ready for connections")
            } else {
                Log.w(TAG, "Failed to auto-start RTSP server")
            }
        }
        
        startWatchdog()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        
        // Check if we should start the server based on intent extra
        val shouldStartServer = intent?.getBooleanExtra(EXTRA_START_SERVER, false) ?: false
        if (shouldStartServer && httpServer?.isAlive() != true) {
            startServer()
        }
        
        // NOTE: Camera is NOT automatically started in onStartCommand
        // Camera will be initialized on-demand when first consumer connects
        // This prevents unnecessary camera usage when app is idle
        
        return START_STICKY
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - restarting service")
        
        // Restart the service immediately when task is removed
        val restartIntent = Intent(applicationContext, CameraService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }
    
    /**
     * Check if all required permissions are granted
     * Service cannot function without these permissions
     */
    private fun hasRequiredPermissions(): Boolean {
        // CAMERA permission is required (runtime permission)
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        // INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE are install-time permissions (automatically granted)
        // No need to check them at runtime
        
        if (!hasCameraPermission) {
            Log.e(TAG, "Missing required permission: CAMERA")
            return false
        }
        
        Log.d(TAG, "All required permissions granted")
        return true
    }
    
    /**
     * Periodically check if camera can be activated after boot
     * This handles cases where device is locked at boot but unlocked later
     * Checks every 15 seconds for up to 5 minutes
     */
    /**
     * Check if POST_NOTIFICATIONS permission is granted (required on Android 13+)
     */
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Permission not required on Android 12 and below
            true
        }
    }
    
    private fun createNotificationChannel() {
        // API 30+ always supports notification channels (introduced in API 26)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IP Camera Service",
            NotificationManager.IMPORTANCE_DEFAULT  // Changed from LOW to DEFAULT for better persistence
        ).apply {
            description = "Keeps camera service running in background"
            setShowBadge(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    private fun isPortAvailable(port: Int): Boolean {
        var serverSocket: ServerSocket? = null
        return try {
            // Create a server socket to check if the port is available
            serverSocket = ServerSocket(port)
            serverSocket.reuseAddress = true
            true
        } catch (e: IOException) {
            // Port is already in use
            Log.w(TAG, "Port $port is not available: ${e.message}")
            false
        } finally {
            // Always close the socket
            serverSocket?.close()
        }
    }
    
    /**
     * Find an available port starting from the specified port.
     * Will try up to MAX_PORT_ATTEMPTS consecutive ports.
     * @param startPort The port to start searching from
     * @return The first available port found, or null if none available
     */
    private fun findAvailablePort(startPort: Int): Int? {
        var port = startPort
        var attempts = 0
        
        while (attempts < MAX_PORT_ATTEMPTS && port <= MAX_PORT) {
            if (isPortAvailable(port)) {
                Log.d(TAG, "Found available port: $port")
                return port
            }
            port++
            attempts++
        }
        
        val reason = if (attempts >= MAX_PORT_ATTEMPTS) {
            "reached maximum attempts ($MAX_PORT_ATTEMPTS)"
        } else {
            "exceeded maximum port number ($MAX_PORT)"
        }
        Log.e(TAG, "Could not find available port - $reason, started from $startPort")
        return null
    }
    
    private fun getNotificationText(): String {
        return if (httpServer?.isAlive() == true) {
            "Server running on ${getServerUrl()}"
        } else {
            "Camera preview active"
        }
    }
    
    private fun createNotification(contentText: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val text = contentText ?: getNotificationText()
        
        // Use device name in notification title if set, otherwise use default
        val title = if (deviceName.isNotEmpty()) deviceName else "IP Camera Server"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // Make notification persistent
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)  // Increase priority
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun startServer() {
        try {
            // Clear the intentionally stopped flag since we're starting the server
            serverIntentionallyStopped = false
            
            // If server is already running, just return
            if (httpServer?.isAlive() == true) {
                Log.d(TAG, "Server is already running on port $actualPort")
                return
            }
            
            // Stop any existing server instance
            httpServer?.stop()
            
            // Try to find an available port, starting with the preferred port
            val availablePort = findAvailablePort(PORT)
            
            if (availablePort == null) {
                val errorMsg = "Could not find an available port. Tried $MAX_PORT_ATTEMPTS ports starting from $PORT."
                Log.e(TAG, errorMsg)
                updateNotification("Server failed to start - no available ports")
                showUserNotification("Server Start Failed", errorMsg)
                return
            }
            
            // Update the actual port being used
            actualPort = availablePort
            
            // Notify user if we're using a different port than the default
            if (actualPort != PORT) {
                val msg = "Port $PORT was unavailable. Using port $actualPort instead."
                Log.w(TAG, msg)
                showUserNotification("Port Changed", msg)
            }
            
            // Create and start the Ktor-based HTTP server
            httpServer = HttpServer(actualPort, this@CameraService)
            httpServer?.start()
            
            val startMsg = if (actualPort != PORT) {
                "Server started on port $actualPort with max $maxConnections connections (default port $PORT was unavailable)"
            } else {
                "Server started on port $actualPort with max $maxConnections connections"
            }
            Log.d(TAG, startMsg)
            
            // Update notification with the actual port
            updateNotification("Server running on ${getServerUrl()}")
            
            // Broadcast state change to web clients (if any connected during start)
            broadcastCameraState()
            
        } catch (e: IOException) {
            val errorMsg = "Failed to start server: ${e.message}"
            Log.e(TAG, errorMsg, e)
            updateNotification("Server failed to start")
            showUserNotification("Server Error", errorMsg)
        } catch (e: Exception) {
            val errorMsg = "Failed to start server: ${e.message}"
            Log.e(TAG, errorMsg, e)
            updateNotification("Server failed to start")
            showUserNotification("Server Error", errorMsg)
        }
    }
    
    /**
     * Update the foreground notification with new text
     */
    private fun updateNotification(contentText: String) {
        // On Android 13+, check if POST_NOTIFICATIONS permission is granted
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Cannot update notification: POST_NOTIFICATIONS permission not granted")
            return
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(contentText))
    }
    
    /**
     * Show a user notification (not the foreground service notification)
     */
    private fun showUserNotification(title: String, message: String) {
        // On Android 13+, check if POST_NOTIFICATIONS permission is granted
        if (!hasNotificationPermission()) {
            Log.w(TAG, "Cannot show user notification: POST_NOTIFICATIONS permission not granted. Title: $title, Message: $message")
            return
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Create a separate notification for user alerts
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        
        // Use a different notification ID for user alerts
        notificationManager?.notify(NOTIFICATION_ID + 1, notification)
    }
    
    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startCamera() called but Camera permission not granted - waiting for permission")
            return
        }
        
        Log.d(TAG, "startCamera() - initializing camera provider...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.i(TAG, "Camera provider initialized successfully")
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider", e)
                cameraProvider = null
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCamera() {
        // Ensure lifecycle is in correct state
        if (lifecycleRegistry.currentState != Lifecycle.State.STARTED) {
            Log.w(TAG, "Cannot bind camera - lifecycle not in STARTED state: ${lifecycleRegistry.currentState}")
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
        
        try {
            Log.d(TAG, "Unbinding all use cases before rebinding...")
            cameraProvider?.unbindAll()
            
            val resolution = selectedResolution ?: Size(1920, 1080)
            Log.d(TAG, "Binding camera with resolution: ${resolution.width}x${resolution.height}")
            
            // === Use Case 1: Preview for H.264 Encoding (Hardware MediaCodec) ===
            // Only create if RTSP is enabled
            if (rtspEnabled) {
                try {
                    // Create H.264 encoder
                    h264Encoder = H264PreviewEncoder(
                        width = resolution.width,
                        height = resolution.height,
                        fps = targetRtspFps,
                        bitrate = if (rtspBitrate > 0) rtspBitrate else RTSPServer.calculateBitrate(resolution.width, resolution.height),
                        rtspServer = rtspServer
                    )
                    h264Encoder?.start()
                    
                    // Create Preview for H.264 encoder (feeds to encoder's surface)
                    // CRITICAL: Must match the resolution that the encoder expects
                    val previewResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionFilter { supportedSizes, _ ->
                            // Try to find exact match for encoder resolution
                            val exactMatch = supportedSizes.filter { size ->
                                size.width == resolution.width && size.height == resolution.height
                            }
                            
                            if (exactMatch.isNotEmpty()) {
                                Log.d(TAG, "Found exact resolution match for H.264 Preview: ${resolution.width}x${resolution.height}")
                                exactMatch
                            } else {
                                // No exact match - find closest
                                val targetPixels = resolution.width * resolution.height
                                val closest = supportedSizes.minByOrNull { size ->
                                    kotlin.math.abs(size.width * size.height - targetPixels)
                                }
                                
                                if (closest != null) {
                                    Log.w(TAG, "Exact resolution ${resolution.width}x${resolution.height} not available for H.264 Preview. Using closest: ${closest.width}x${closest.height}")
                                } else {
                                    Log.e(TAG, "Could not find any suitable resolution for H.264 Preview")
                                }
                                
                                closest?.let { listOf(it) } ?: supportedSizes
                            }
                        }
                        .build()
                    
                    // Configure CameraX Preview with target frame rate for H.264 encoding
                    // This limits the input frames sent to the encoder at the camera level
                    videoCaptureUseCase = androidx.camera.core.Preview.Builder()
                        .setResolutionSelector(previewResolutionSelector)
                        .setTargetFrameRate(android.util.Range(targetRtspFps, targetRtspFps))
                        .build()
                        .apply {
                            // Connect to encoder's input surface
                            setSurfaceProvider { request ->
                                val surface = h264Encoder?.getInputSurface()
                                if (surface != null) {
                                    request.provideSurface(
                                        surface,
                                        cameraExecutor
                                    ) { }
                                    Log.d(TAG, "H.264 encoder surface connected to camera with target FPS: $targetRtspFps")
                                } else {
                                    request.willNotProvideSurface()
                                    Log.w(TAG, "H.264 encoder surface not available - encoder may have failed to initialize or been stopped")
                                }
                            }
                        }
                    Log.i(TAG, "H.264 encoder created and connected with target FPS: $targetRtspFps")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create H.264 encoder", e)
                    h264Encoder = null
                    videoCaptureUseCase = null
                }
            } else {
                // RTSP disabled - clean up encoder if exists
                h264Encoder?.stop()
                h264Encoder = null
                videoCaptureUseCase = null
            }
            
            // === Use Case 2: ImageAnalysis for MJPEG (CPU, throttled to targetMjpegFps) ===
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionFilter { supportedSizes, _ ->
                    // Try to find exact match first
                    val exactMatch = supportedSizes.filter { size ->
                        size.width == resolution.width && size.height == resolution.height
                    }
                    
                    if (exactMatch.isNotEmpty()) {
                        Log.d(TAG, "Found exact resolution match: ${resolution.width}x${resolution.height}")
                        exactMatch
                    } else {
                        // No exact match - find closest by total pixels
                        val targetPixels = resolution.width * resolution.height
                        val targetAspectRatio = resolution.width.toFloat() / resolution.height.toFloat()
                        
                        val closest = supportedSizes.minByOrNull { size ->
                            val pixels = size.width * size.height
                            val aspectRatio = size.width.toFloat() / size.height.toFloat()
                            val pixelDiff = Math.abs(pixels - targetPixels)
                            val aspectDiff = Math.abs(aspectRatio - targetAspectRatio) * 1000000 // Weight aspect ratio heavily
                            pixelDiff + aspectDiff.toInt()
                        }
                        
                        if (closest != null) {
                            Log.w(TAG, "Exact resolution ${resolution.width}x${resolution.height} not available. Using closest: ${closest.width}x${closest.height}")
                        } else {
                            Log.e(TAG, "Could not find any suitable resolution. Using default.")
                        }
                        
                        // Return closest match or all supported if none found
                        closest?.let { listOf(it) } ?: supportedSizes
                    }
                }
                .build()
            
            val mjpegAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                // Note: ImageAnalysis doesn't support setTargetFrameRate directly
                // Frame rate throttling is achieved via KEEP_ONLY_LATEST backpressure
                // which naturally throttles based on processing time
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                // Use RGBA_8888 format to get Bitmaps directly (API 30+)
                // This eliminates inefficient YUV→NV21→JPEG→Bitmap conversion
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                
            mjpegAnalysis.setAnalyzer(cameraExecutor) { image ->
                processMjpegFrame(image)
            }
            
            // Store reference after configuration
            imageAnalysis = mjpegAnalysis
            
            // === Bind Use Cases to Lifecycle ===
            // Build list of use cases to bind based on what's enabled
            val useCases = mutableListOf<androidx.camera.core.UseCase>(
                mjpegAnalysis         // Always bind MJPEG/ImageAnalysis pipeline
            )
            
            // Add H.264 encoder preview if RTSP is enabled
            videoCaptureUseCase?.let { useCases.add(it) }
            
            Log.d(TAG, "Binding ${useCases.size} use cases to lifecycle (ImageAnalysis${if (videoCaptureUseCase != null) " + H264 Preview" else ""})")
            camera = cameraProvider?.bindToLifecycle(this, currentCamera, *useCases.toTypedArray())
            
            if (camera == null) {
                Log.e(TAG, "Camera binding returned null!")
                return
            }
            
            Log.i(TAG, "Camera bound successfully with ${useCases.size} use case(s):")
            Log.i(TAG, "  1. ImageAnalysis (MJPEG + MainActivity preview): ~$targetMjpegFps fps target")
            if (videoCaptureUseCase != null) {
                Log.i(TAG, "  2. Preview → H.264 Encoder (RTSP): $targetRtspFps fps target")
            }
            
            // Check if flash is available for back camera
            checkFlashAvailability()
            
            // Restore flashlight state if enabled for back camera
            // Use post-delayed to ensure camera is fully initialized
            if (isFlashlightOn && currentCamera == CameraSelector.DEFAULT_BACK_CAMERA && hasFlashUnit) {
                // Delay torch enable to ensure camera control is ready
                // Using Handler instead of Thread.sleep to avoid blocking
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    enableTorch(true)
                }, 200)
            }
            
            Log.d(TAG, "Camera bound successfully to ${if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"} camera. Frame processing should resume.")
            
            // Monitor camera state for lifecycle events
            // This helps detect when camera is closed or in error state
            // Use observeForever to avoid triggering multiple times on same state
            val cameraStateObserver = androidx.lifecycle.Observer<androidx.camera.core.CameraState> { cameraState ->
                Log.d(TAG, "Camera state changed: ${cameraState.type}, error: ${cameraState.error?.toString() ?: "none"}")
                
                when (cameraState.type) {
                    androidx.camera.core.CameraState.Type.CLOSED -> {
                        Log.w(TAG, "Camera CLOSED state detected - camera may need rebinding")
                        // Don't automatically rebind here to avoid infinite loops
                        // Let the watchdog handle recovery via frame stale detection
                    }
                    androidx.camera.core.CameraState.Type.CLOSING -> {
                        Log.d(TAG, "Camera closing...")
                    }
                    androidx.camera.core.CameraState.Type.PENDING_OPEN -> {
                        Log.d(TAG, "Camera opening...")
                    }
                    androidx.camera.core.CameraState.Type.OPENING -> {
                        Log.d(TAG, "Camera is opening...")
                    }
                    androidx.camera.core.CameraState.Type.OPEN -> {
                        Log.i(TAG, "Camera is open and ready")
                    }
                }
                
                // Log any errors for debugging
                cameraState.error?.let { error ->
                    Log.e(TAG, "Camera error: code=${error.code}, ${error.cause?.message ?: "no cause"}", error.cause)
                    
                    // For critical errors, clear camera reference so watchdog can retry
                    // Only clear once to avoid repeated logging
                    if (camera != null && 
                        (error.code == androidx.camera.core.CameraState.ERROR_CAMERA_DISABLED ||
                         error.code == androidx.camera.core.CameraState.ERROR_CAMERA_FATAL_ERROR ||
                         error.code == androidx.camera.core.CameraState.ERROR_CAMERA_IN_USE)) {
                        Log.e(TAG, "Critical camera error detected, clearing camera reference for recovery")
                        camera = null
                    }
                }
            }
            
            camera?.cameraInfo?.cameraState?.observe(this, cameraStateObserver)
            
            // Camera binding successful - update state
            synchronized(cameraStateLock) {
                cameraState = CameraState.ACTIVE
                Log.d(TAG, "Camera binding successful → ACTIVE state")
            }
            
            // Notify observers that camera state has changed (binding completed)
            // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
            safeInvokeCameraStateCallback(currentCamera)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed with exception: ${e.message}", e)
            e.printStackTrace()
            
            // Set error state
            synchronized(cameraStateLock) {
                cameraState = CameraState.ERROR
                Log.d(TAG, "Camera binding failed → ERROR state")
            }
            
            // Clear camera reference on failure to allow watchdog to retry
            camera = null
            
            // Show error notification to user
            showUserNotification(
                "Camera Binding Failed",
                "Failed to initialize camera: ${e.message}. The system will retry automatically."
            )
        }
    }
    
    /**
     * Properly stop camera activities before applying new settings.
     * This ensures clean state transition and prevents resource conflicts.
     * Also used for on-demand deactivation when no consumers remain.
     * 
     * MUST be called on main thread due to CameraX unbindAll() requirement.
     */
    private fun stopCamera() {
        try {
            Log.d(TAG, "Stopping camera...")
            
            // Stop H.264 encoder first
            h264Encoder?.stop()
            h264Encoder = null
            videoCaptureUseCase = null
            
            // Clear old analyzer to stop frame processing
            imageAnalysis?.clearAnalyzer()
            
            // Unbind all use cases from lifecycle - MUST be on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    cameraProvider?.unbindAll()
                    Log.d(TAG, "Camera unbound from lifecycle")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding camera", e)
                }
            }
            
            // Clear camera reference
            camera = null
            
            // Clear frame data
            lastProcessedFrame.set(null)
            
            // Update state to IDLE (unless already in ERROR)
            synchronized(cameraStateLock) {
                if (cameraState != CameraState.ERROR) {
                    cameraState = CameraState.IDLE
                    Log.d(TAG, "Camera stopped → IDLE state")
                } else {
                    Log.d(TAG, "Camera stopped (state remains ERROR)")
                }
            }
            
            // Update notification
            updateNotification("Camera inactive - No consumers")
            
            // Broadcast state change
            broadcastCameraState()
            
            Log.d(TAG, "Camera stopped successfully (including H.264 encoder if active)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
            synchronized (cameraStateLock) {
                cameraState = CameraState.ERROR
            }
        }
    }
    
    /**
     * Stop camera, apply settings, and restart camera.
     * This ensures settings are properly applied without conflicts.
     * Uses proper async handling to avoid blocking the main thread.
     * 
     * DEBOUNCING PROTECTION:
     * 1. Time-based debouncing: Minimum 500ms between bind requests
     *    - If called too soon, schedules delayed retry via coroutine Job
     *    - Cancels previous pending job to use latest settings
     * 2. Flag-based debouncing: Prevents overlapping bind operations
     *    - If binding already in progress, sets pending flag for retry after completion
     *    - Ensures camera hardware stability by serializing all bind operations
     * 
     * THREAD SAFETY:
     * - All state checks and updates are synchronized via bindingLock
     * - isBindingInProgress flag prevents concurrent camera access
     * - hasPendingRebind flag queues requests received during binding
     * 
     * PRIVATE: Only CameraService methods should trigger rebinding.
     * External callers should use methods like switchCamera() or setResolutionAndRebind()
     * that encapsulate both the setting change and rebinding.
     */
    private fun requestBindCamera() {
        val now = System.currentTimeMillis()
        
        // Time-based debouncing: Check if enough time has passed since last request
        synchronized(bindingLock) {
            val timeSinceLastRequest = now - lastBindRequestTime
            if (timeSinceLastRequest < CAMERA_REBIND_DEBOUNCE_MS) {
                Log.d(TAG, "requestBindCamera() debounced - too soon (${timeSinceLastRequest}ms < ${CAMERA_REBIND_DEBOUNCE_MS}ms)")
                
                // Cancel any pending bind job and schedule a new one
                // This ensures we use the latest settings if multiple rapid requests occur
                pendingBindJob?.cancel()
                val remainingDelay = CAMERA_REBIND_DEBOUNCE_MS - timeSinceLastRequest
                
                pendingBindJob = serviceScope.launch {
                    delay(remainingDelay)
                    // Recursively call after delay - this time it will pass the debounce check
                    requestBindCamera()
                }
                return
            }
            
            // Flag-based debouncing: Check if a binding operation is already in progress
            if (isBindingInProgress) {
                Log.d(TAG, "requestBindCamera() deferred - binding already in progress, will retry after completion")
                // Instead of silently dropping the request, mark that we have a pending rebind
                // This ensures the latest settings are applied after the current bind completes
                hasPendingRebind = true
                return
            }
            
            isBindingInProgress = true
            lastBindRequestTime = now
        }
        
        // Log stack trace to identify caller
        val stackTrace = Thread.currentThread().stackTrace
        val caller = if (stackTrace.size > 3) {
            "${stackTrace[3].className}.${stackTrace[3].methodName}:${stackTrace[3].lineNumber}"
        } else "unknown"
        Log.d(TAG, "requestBindCamera() called by: $caller")
        
        ContextCompat.getMainExecutor(this).execute {
            try {
                // Stop camera first to ensure clean state
                Log.d(TAG, "Stopping camera before rebinding...")
                stopCamera()
                
                // Schedule rebinding after a short delay to ensure resources are released
                // Using Handler instead of Thread.sleep to avoid blocking main thread
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d(TAG, "Delay complete, rebinding camera now...")
                        bindCamera()
                        // Callback is now invoked directly in bindCamera() after binding succeeds
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in bindCamera() from postDelayed", e)
                    } finally {
                        // Clear the flag and check for pending rebind requests
                        val shouldRetry = synchronized(bindingLock) {
                            isBindingInProgress = false
                            Log.d(TAG, "isBindingInProgress flag cleared")
                            
                            // Check if another rebind was requested while we were binding
                            val retry = hasPendingRebind
                            if (retry) {
                                Log.d(TAG, "Pending rebind detected, will retry after optimized delay")
                                hasPendingRebind = false
                            }
                            retry
                        }
                        
                        // If there was a pending rebind request, execute it now
                        // This ensures the latest settings are applied
                        if (shouldRetry) {
                            // Calculate remaining debounce time to minimize unnecessary delay
                            // Capture both time values within synchronized block to prevent race conditions
                            serviceScope.launch {
                                val (currentTime, lastRequestTime) = synchronized(bindingLock) {
                                    System.currentTimeMillis() to lastBindRequestTime
                                }
                                val timeSinceLastBind = currentTime - lastRequestTime
                                val remainingDelay = (CAMERA_REBIND_DEBOUNCE_MS - timeSinceLastBind).coerceAtLeast(0)
                                
                                if (remainingDelay > 0) {
                                    Log.d(TAG, "Pending rebind: delaying ${remainingDelay}ms for debounce")
                                    delay(remainingDelay)
                                }
                                requestBindCamera()
                            }
                        }
                    }
                }, 100)
            } catch (e: Exception) {
                Log.e(TAG, "Error in requestBindCamera()", e)
                // Clear flag if we fail before even scheduling the delayed binding
                // Also clear pending rebind flag since we couldn't proceed
                synchronized(bindingLock) {
                    isBindingInProgress = false
                    hasPendingRebind = false
                }
            }
        }
    }
    
    /**
     * Process MJPEG frames (CPU-based, throttled to targetMjpegFps)
     * This is called by ImageAnalysis use case, separate from H.264 encoding
     * 
     * PERFORMANCE: Lightweight operations (FPS tracking, throttling) happen on the analyzer thread,
     * while expensive operations (rotation, annotation, JPEG compression) are offloaded to a
     * dedicated processing executor. This prevents blocking the CameraX analysis thread and
     * keeps the frame pipeline flowing smoothly.
     */
    private fun processMjpegFrame(image: ImageProxy) {
        // Skip processing if service is stopping
        if (isStopping) {
            Log.d(TAG, "Skipping frame processing - service is stopping")
            return
        }
        
        val processingStart = System.currentTimeMillis()
        
        try {
            // === LIGHTWEIGHT OPERATIONS ON ANALYZER THREAD ===
            // These operations are fast and don't block the frame pipeline
            
            // Track Camera FPS FIRST (from ImageAnalysis callback rate - ALL frames including skipped)
            synchronized(fpsFrameTimes) {
                fpsFrameTimes.add(processingStart)
                // Keep only the last 2 seconds of frame times
                val cutoffTime = processingStart - 2000
                fpsFrameTimes.removeAll { it < cutoffTime }
                
                // Calculate FPS every 500ms
                if (processingStart - lastFpsCalculation > 500) {
                    if (fpsFrameTimes.size > 1) {
                        val timeSpan = fpsFrameTimes.last() - fpsFrameTimes.first()
                        val newFps = if (timeSpan > 0) {
                            (fpsFrameTimes.size - 1) * 1000f / timeSpan
                        } else {
                            0f
                        }
                        // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                        if (kotlin.math.abs(newFps - currentCameraFps) > 0.5f) {
                            currentCameraFps = newFps
                            broadcastCameraState()
                        } else {
                            currentCameraFps = newFps
                        }
                    }
                    lastFpsCalculation = processingStart
                }
            }
            
            // === MJPEG FPS Throttling ===
            // Skip frame if not enough time has passed since last processed frame
            val minFrameIntervalMs = (1000.0 / targetMjpegFps).toLong()
            val timeSinceLastFrame = processingStart - lastMjpegFrameProcessedTimeMs
            
            if (lastMjpegFrameProcessedTimeMs > 0 && timeSinceLastFrame < minFrameIntervalMs) {
                // Skip this frame to maintain target MJPEG FPS
                // Camera FPS already tracked above, so this only affects MJPEG stream FPS
                image.close()
                return
            }
            
            lastMjpegFrameProcessedTimeMs = processingStart
            
            // === OFFLOAD EXPENSIVE OPERATIONS TO PROCESSING EXECUTOR ===
            // Submit to dedicated processing executor to avoid blocking the analyzer thread
            // The ImageProxy must be closed by the processing task
            try {
                processingExecutor.execute {
                    processImageHeavyOperations(image, processingStart)
                }
            } catch (e: RejectedExecutionException) {
                // Executor queue is full or shutting down - close image and skip frame
                Log.w(TAG, "Processing executor rejected frame, skipping")
                image.close()
                performanceMetrics.recordFrameDropped()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in MJPEG frame analyzer", e)
            image.close()
            performanceMetrics.recordFrameDropped()
        }
    }
    
    /**
     * Heavy image processing operations (rotation, annotation, JPEG compression)
     * Runs on dedicated processing executor to avoid blocking CameraX analyzer thread
     * 
     * @param image ImageProxy to process (must be closed by this method)
     * @param processingStart Timestamp when frame processing started
     */
    private fun processImageHeavyOperations(image: ImageProxy, processingStart: Long) {
        // Skip processing if service is stopping
        if (isStopping) {
            Log.d(TAG, "Skipping heavy operations - service is stopping")
            image.close()
            return
        }
        
        // Use Kotlin's use{} extension to ensure image.close() is always called
        // This provides automatic resource management similar to Java's try-with-resources
        image.use {
            try {
                // === MJPEG Pipeline (CPU-based, throttled) ===
                // Convert RGBA to Bitmap for MJPEG (using pool for memory efficiency)
                val bitmap = imageProxyToBitmap(image)
                if (bitmap == null) {
                    // Failed to allocate bitmap, skip this frame
                    Log.w(TAG, "Skipping MJPEG frame due to bitmap allocation failure")
                    performanceMetrics.recordFrameDropped()
                    return
                }
                
                // Reduce logging frequency - only log every 30 frames (about 3 seconds at 10fps)
                val currentFrame = lastProcessedFrame.get()
                val frameCount = currentFrame?.timestamp?.toInt()?.rem(30) ?: 0
                if (frameCount == 0) {
                    Log.d(TAG, "Processing MJPEG frame - ImageProxy size: ${image.width}x${image.height}, Bitmap size: ${bitmap.width}x${bitmap.height}, Camera FPS: ${"%.1f".format(currentCameraFps)}")
                }
                
                // Apply camera orientation and rotation
                val finalBitmap = applyRotationCorrectly(bitmap)
                if (frameCount == 0) {
                    Log.d(TAG, "After rotation - Bitmap size: ${finalBitmap.width}x${finalBitmap.height}, Total rotation: ${(when (cameraOrientation) { "portrait" -> 90; else -> 0 } + rotation) % 360}°")
                }
                
                // Annotate bitmap (OSD overlays)
                // Note: annotateBitmap creates a new bitmap from pool, so finalBitmap can be cleaned up after
                val annotatedBitmap = annotateBitmap(finalBitmap)
                
                // Clean up finalBitmap after annotation using helper method
                // This handles both pooled and non-pooled bitmaps (e.g., rotated bitmaps)
                if (annotatedBitmap != null && finalBitmap != annotatedBitmap) {
                    bitmapPool.recycleBitmap(finalBitmap)
                }
                
                if (annotatedBitmap == null) {
                    // Failed to annotate, skip frame
                    Log.w(TAG, "Skipping MJPEG frame due to annotation failure")
                    performanceMetrics.recordFrameDropped()
                    return
                }
                
                // Determine JPEG quality - use adaptive quality if enabled
                val jpegQuality = if (adaptiveQualityEnabled) {
                    val settings = adaptiveQualityManager.getClientSettings(0L)
                    settings.jpegQuality
                } else {
                    JPEG_QUALITY_STREAM
                }
                
                // Pre-compress to JPEG for HTTP serving
                val encodingStart = System.currentTimeMillis()
                val jpegBytes = ByteArrayOutputStream().use { stream ->
                    annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                    stream.toByteArray()
                }
                val encodingTime = System.currentTimeMillis() - encodingStart
                
                // Track encoding performance
                performanceMetrics.recordFrameEncodingTime(encodingTime)
                
                // ATOMIC FRAME UPDATE: Create ProcessedFrame and atomically update reference
                // This ensures UI and web stream always display the same frame
                val timestamp = System.currentTimeMillis()
                val newFrame = ProcessedFrame(
                    bitmap = annotatedBitmap,
                    jpegBytes = jpegBytes,
                    timestamp = timestamp
                )
                
                // Atomically replace the old frame with the new one
                val oldFrame = lastProcessedFrame.getAndSet(newFrame)
                
                // Return old frame's bitmap to pool if it exists and is different
                if (oldFrame != null && oldFrame.bitmap != annotatedBitmap) {
                    bitmapPool.returnBitmap(oldFrame.bitmap)
                }
                
                // Track frame processing time
                val processingTime = System.currentTimeMillis() - processingStart
                performanceMetrics.recordFrameProcessingTime(processingTime)
                
                // Note: MJPEG FPS is tracked by HttpServer when frames are actually served to clients
                // Don't track here to avoid double-counting
                
                // Notify MainActivity if it's listening - use pool for copying
                val previewCopy = try {
                    bitmapPool.copy(annotatedBitmap, annotatedBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to copy bitmap for MainActivity preview", t)
                    null
                }
                if (previewCopy != null) {
                    // LIFECYCLE SAFETY: Use safe callback invocation to prevent crashes if MainActivity destroyed
                    safeInvokeFrameCallback(previewCopy)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing MJPEG frame in background", e)
                performanceMetrics.recordFrameDropped()
            } catch (t: Throwable) {
                // Catch OutOfMemoryError and other critical errors
                Log.e(TAG, "Critical error processing MJPEG frame in background", t)
                performanceMetrics.recordFrameDropped()
                // Clear bitmap pool on OOME to free memory
                if (t is OutOfMemoryError) {
                    Log.w(TAG, "OutOfMemoryError in frame processing, clearing bitmap pool")
                    bitmapPool.clear()
                }
            }
            // Note: image.close() is called automatically by use{} when this block exits
            // This happens regardless of normal completion, early return, or exception
        }
    }
    
    private fun applyRotationCorrectly(bitmap: Bitmap): Bitmap {
        // Calculate total rotation: camera orientation + manual rotation
        val baseRotation = when (cameraOrientation) {
            "portrait" -> 90
            "landscape" -> 0
            else -> 0
        }
        
        val totalRotation = (baseRotation + rotation) % 360
        
        if (totalRotation == 0) {
            return bitmap
        }
        
        // Use a properly sized matrix to avoid creating squared bitmaps
        val matrix = Matrix()
        matrix.postRotate(totalRotation.toFloat())
        
        return try {
            // NOTE: Bitmap.createBitmap with matrix creates bitmap outside pool
            // This is acceptable because:
            // 1. Native rotation is very efficient (hardware-accelerated)
            // 2. Rotation happens only on resolution/orientation changes (infrequent)
            // 3. Most frames don't need rotation (rotation == 0)
            // 4. Alternative (manual rotation with pool) would be slower
            // Future: Could implement pool-based rotation for frequently rotated streams
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error rotating bitmap", t)
            bitmap
        }
    }
    
    private fun applyCameraOrientation(bitmap: Bitmap): Bitmap {
        // Determine the base rotation needed to achieve the desired camera orientation
        // Camera sensor is typically landscape-oriented by default
        val baseRotation = when (cameraOrientation) {
            "portrait" -> 90 // Rotate to portrait
            "landscape" -> 0 // Keep landscape (default sensor orientation)
            else -> 0
        }
        
        if (baseRotation == 0) {
            return bitmap
        }
        
        val matrix = Matrix()
        matrix.postRotate(baseRotation.toFloat())
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error applying camera orientation", t)
            bitmap
        }
    }
    
    private fun applyRotation(bitmap: Bitmap): Bitmap {
        // Apply the user-specified rotation on top of the camera orientation
        if (rotation == 0) {
            return bitmap
        }
        
        val matrix = Matrix()
        matrix.postRotate(rotation.toFloat())
        
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            // Only return the original to pool if a new bitmap was created
            if (rotated != bitmap) {
                bitmapPool.returnBitmap(bitmap)
            }
            rotated
        } catch (t: Throwable) {
            Log.e(TAG, "Error rotating bitmap", t)
            bitmap
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        // With OUTPUT_IMAGE_FORMAT_RGBA_8888, ImageProxy provides RGBA data directly
        // This eliminates the inefficient YUV→NV21→JPEG→Bitmap conversion
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        // Get bitmap from pool or create new (with OOME protection)
        val bitmap = try {
            bitmapPool.get(image.width, image.height, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get bitmap from pool for ${image.width}x${image.height}", t)
            null
        }
        
        if (bitmap == null) {
            Log.e(TAG, "Unable to allocate bitmap for frame, skipping")
            return null
        }
        
        // If there's no row padding, we can copy directly
        if (rowPadding == 0) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            // With row padding, copy row by row to exclude padding bytes
            buffer.rewind()
            val pixels = IntArray(image.width)
            
            for (row in 0 until image.height) {
                // Position at start of this row
                buffer.position(row * rowStride)
                
                // Read pixels for this row
                for (col in 0 until image.width) {
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    val a = buffer.get().toInt() and 0xFF
                    pixels[col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                
                // Set this row in the bitmap
                bitmap.setPixels(pixels, 0, image.width, 0, row, image.width, 1)
            }
        }
        
        return bitmap
    }
    
    /**
     * Switch between front and back cameras.
     * Preserves per-camera resolution settings and automatically rebinds the camera.
     * 
     * SAFETY: Uses requestBindCamera() which has built-in debouncing protection.
     * Multiple rapid camera switches are safely queued and executed sequentially.
     * 
     * LIFECYCLE SAFETY: Uses safe callback invocation to notify MainActivity.
     */
    override fun switchCamera(cameraSelector: CameraSelector) {
        // Save current camera's resolution before switching
        if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            backCameraResolution = selectedResolution
        } else {
            frontCameraResolution = selectedResolution
        }
        
        // Switch to new camera
        currentCamera = cameraSelector
        
        // Restore new camera's resolution from memory
        selectedResolution = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            backCameraResolution
        } else {
            frontCameraResolution
        }
        
        saveSettings()
        
        // Turn off flashlight when switching cameras
        if (isFlashlightOn) {
            isFlashlightOn = false
            saveSettings()
        }
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Request bind but DON'T invoke callback here
        // The callback will be invoked naturally when bindCamera() completes
        // requestBindCamera() has debouncing protection to handle rapid calls safely
        requestBindCamera()
    }
    
    /**
     * Initialize camera characteristics cache
     * 
     * Queries all available cameras once and caches their characteristics (facing, flash, resolutions).
     * This avoids repeated expensive IPC calls to CameraManager.getCameraCharacteristics().
     * Should be called once during service initialization.
     * Thread-safe: Uses volatile flag and synchronized block to prevent multiple initializations.
     */
    private fun initializeCameraCharacteristicsCache() {
        // Fast path: check if already initialized without synchronization
        if (cameraCharacteristicsCacheInitialized) {
            return
        }
        
        // Slow path: initialize with synchronization
        synchronized(cameraCharacteristicsCache) {
            // Double-check after acquiring lock
            if (cameraCharacteristicsCacheInitialized) {
                return
            }
            
            try {
                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cameraManager.cameraIdList.forEach { id ->
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val facing = characteristics.get(CameraCharacteristics.LENS_FACING) 
                            ?: CameraCharacteristics.LENS_FACING_BACK
                        val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) 
                            ?: false
                        
                        // Get supported resolutions for this camera
                        val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val resolutions = config?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() 
                            ?: emptyList()
                        
                        val cached = CachedCameraCharacteristics(
                            cameraId = id,
                            lensFacing = facing,
                            hasFlash = hasFlash,
                            supportedResolutions = resolutions
                        )
                        cameraCharacteristicsCache[id] = cached
                        
                        Log.d(TAG, "Cached characteristics for camera $id: facing=$facing, hasFlash=$hasFlash, resolutions=${resolutions.size}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to cache characteristics for camera $id", e)
                    }
                }
                Log.d(TAG, "Camera characteristics cache initialized with ${cameraCharacteristicsCache.size} cameras")
                cameraCharacteristicsCacheInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing camera characteristics cache", e)
                // Don't set initialized flag on failure, allowing retry
            }
        }
    }
    
    /**
     * Check if the current camera has a flash unit using Camera2 API
     * Uses cached characteristics to avoid expensive IPC calls.
     * Assumes cache is already initialized in onCreate().
     */
    private fun checkFlashAvailability() {
        hasFlashUnit = false
        
        // If cache not initialized (e.g., initialization failed), log warning and return
        if (!cameraCharacteristicsCacheInitialized) {
            Log.w(TAG, "Camera characteristics cache not initialized, cannot check flash availability")
            return
        }
        
        try {
            val targetFacing = if (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            
            // Use cached characteristics instead of querying CameraManager
            // Returns the first camera matching the target facing direction
            // (maintains original behavior - most devices have only one camera per facing)
            for ((id, cached) in cameraCharacteristicsCache) {
                if (cached.lensFacing == targetFacing) {
                    hasFlashUnit = cached.hasFlash
                    Log.d(TAG, "Flash available for camera $id (facing=$targetFacing): $hasFlashUnit (from cache)")
                    return
                }
            }
            
            Log.d(TAG, "No camera found with facing=$targetFacing in cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking flash availability", e)
        }
    }
    
    /**
     * Enable or disable the camera torch/flashlight
     */
    private fun enableTorch(enable: Boolean) {
        try {
            if (!hasFlashUnit) {
                Log.w(TAG, "Cannot enable torch: no flash unit available")
                return
            }
            
            camera?.cameraControl?.enableTorch(enable)
            Log.d(TAG, "Torch ${if (enable) "enabled" else "disabled"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error controlling torch", e)
        }
    }
    
    /**
     * Toggle flashlight on/off
     * Only works for back camera with flash unit
     */
    override fun toggleFlashlight(): Boolean {
        if (currentCamera != CameraSelector.DEFAULT_BACK_CAMERA) {
            Log.w(TAG, "Flashlight only available for back camera")
            return false
        }
        
        if (!hasFlashUnit) {
            Log.w(TAG, "No flash unit available")
            return false
        }
        
        isFlashlightOn = !isFlashlightOn
        enableTorch(isFlashlightOn)
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of flashlight state change
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
        return isFlashlightOn
    }
    
    /**
     * Get current flashlight state
     */
    override fun isFlashlightEnabled(): Boolean = isFlashlightOn
    
    /**
     * Check if flashlight is available (back camera with flash unit)
     */
    override fun isFlashlightAvailable(): Boolean {
        return currentCamera == CameraSelector.DEFAULT_BACK_CAMERA && hasFlashUnit
    }
    
    override fun getCurrentCamera(): CameraSelector = currentCamera
    
    override fun getSupportedResolutions(): List<Size> {
        return getSupportedResolutions(currentCamera)
    }
    
    override fun getSelectedResolution(): Size? = selectedResolution
    
    /**
     * Update the resolution for the current camera in both selectedResolution
     * and the per-camera resolution variable.
     */
    private fun updateCurrentCameraResolution(resolution: Size?) {
        selectedResolution = resolution
        if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            backCameraResolution = resolution
        } else {
            frontCameraResolution = resolution
        }
    }
    
    fun setResolution(resolution: Size?) {
        updateCurrentCameraResolution(resolution)
        saveSettings()
        // DON'T call requestBindCamera() here!
        // This method just saves the resolution setting.
        // Callers must explicitly call requestBindCamera() if they want to rebind the camera.
        // If we call it here, it creates infinite loop:
        // bindCamera completes → callback → loadResolutions → setSelection → onItemSelected → setResolution → requestBindCamera → repeat
        // Note: requestBindCamera() has debouncing protection to prevent rapid successive calls
    }
    
    /**
     * Set resolution and trigger camera rebinding.
     * This is the recommended way for external callers (MainActivity, HTTP endpoints)
     * to change resolution, as it encapsulates both the setting change and rebinding.
     * 
     * SAFETY: Uses requestBindCamera() which has built-in debouncing protection.
     * Multiple rapid calls are automatically queued and executed safely.
     */
    override fun setResolutionAndRebind(resolution: Size?) {
        updateCurrentCameraResolution(resolution)
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        requestBindCamera()
    }
    
    override fun setCameraOrientation(orientation: String) {
        cameraOrientation = orientation
        // Clear resolution cache when orientation changes to force refresh with new filter
        resolutionCache.clear()
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of orientation change so it can reload resolutions
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    fun getCameraOrientation(): String = cameraOrientation
    
    override fun setRotation(rot: Int) {
        rotation = rot
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of rotation change
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    fun getRotation(): Int = rotation
    
    override fun setShowResolutionOverlay(show: Boolean) {
        showResolutionOverlay = show
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of overlay setting change
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    fun getShowResolutionOverlay(): Boolean = showResolutionOverlay
    
    // OSD overlay settings
    override fun setShowDateTimeOverlay(show: Boolean) {
        showDateTimeOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    override fun getShowDateTimeOverlay(): Boolean = showDateTimeOverlay
    
    override fun setShowBatteryOverlay(show: Boolean) {
        showBatteryOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    override fun getShowBatteryOverlay(): Boolean = showBatteryOverlay
    
    override fun setShowFpsOverlay(show: Boolean) {
        showFpsOverlay = show
        saveSettings()
        broadcastCameraState()
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeCameraStateCallback(currentCamera)
    }
    
    override fun getShowFpsOverlay(): Boolean = showFpsOverlay
    
    // FPS settings
    override fun setTargetMjpegFps(fps: Int) {
        val newFps = fps.coerceIn(1, 60)
        
        // Only update if value actually changed to avoid unnecessary broadcasts
        if (targetMjpegFps != newFps) {
            targetMjpegFps = newFps
            // Note: MJPEG FPS throttling is applied in processMjpegFrame()
            // No need to rebind camera - frame skipping handles the throttling
            saveSettings()
            broadcastCameraState()
            // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
            safeInvokeCameraStateCallback(currentCamera)
            Log.d(TAG, "Target MJPEG FPS set to $targetMjpegFps (throttling applied in frame processing)")
        }
    }
    
    override fun getTargetMjpegFps(): Int = targetMjpegFps
    
    override fun setTargetRtspFps(fps: Int) {
        val oldFps = targetRtspFps
        val newFps = fps.coerceIn(1, 60)
        
        // Only update if value actually changed
        if (oldFps != newFps) {
            targetRtspFps = newFps
            saveSettings()
            
            // If FPS changed and RTSP is enabled, need to rebind camera to recreate encoder with new FPS
            if (rtspEnabled) {
                Log.d(TAG, "RTSP FPS changed from $oldFps to $targetRtspFps, rebinding camera to apply change")
                broadcastCameraState()
                // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
                safeInvokeCameraStateCallback(currentCamera)
                requestBindCamera()
            } else {
                // RTSP not enabled, just broadcast the setting change
                broadcastCameraState()
                // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
                safeInvokeCameraStateCallback(currentCamera)
            }
        }
    }
    
    override fun getTargetRtspFps(): Int = targetRtspFps
    
    override fun getCurrentFps(): Float = currentCameraFps
    
    /**
     * Get current MJPEG streaming FPS (frames actually served to clients)
     */
    fun getCurrentMjpegFps(): Float = currentMjpegFps
    
    /**
     * Get current RTSP streaming FPS (frames actually encoded)
     */
    fun getCurrentRtspFps(): Float {
        // Return actual encoder output rate instead of frame queue rate
        return rtspServer?.getMetrics()?.encodedFps ?: 0f
    }
    
    /**
     * Record that a frame was served via MJPEG stream
     * Called by HttpServer when a frame is sent to a client
     */
    override fun recordMjpegFrameServed() {
        val now = System.currentTimeMillis()
        synchronized(mjpegFpsLock) {
            mjpegFpsFrameTimes.add(now)
            // Keep only the last 2 seconds of frame times
            val cutoffTime = now - 2000
            mjpegFpsFrameTimes.removeAll { it < cutoffTime }
            
            // Calculate FPS every 500ms
            if (now - lastMjpegFpsCalculation > 500) {
                if (mjpegFpsFrameTimes.size > 1) {
                    val timeSpan = mjpegFpsFrameTimes.last() - mjpegFpsFrameTimes.first()
                    val newFps = if (timeSpan > 0) {
                        (mjpegFpsFrameTimes.size - 1) * 1000f / timeSpan
                    } else {
                        0f
                    }
                    // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                    if (kotlin.math.abs(newFps - currentMjpegFps) > 0.5f) {
                        currentMjpegFps = newFps
                        broadcastCameraState()
                    } else {
                        currentMjpegFps = newFps
                    }
                }
                lastMjpegFpsCalculation = now
            }
        }
    }
    
    /**
     * Record that a frame was encoded via RTSP
     * Called by RTSPServer when a frame is successfully encoded
     */
    fun recordRtspFrameEncoded() {
        val now = System.currentTimeMillis()
        synchronized(rtspFpsLock) {
            rtspFpsFrameTimes.add(now)
            // Keep only the last 2 seconds of frame times
            val cutoffTime = now - 2000
            rtspFpsFrameTimes.removeAll { it < cutoffTime }
            
            // Calculate FPS every 500ms
            if (now - lastRtspFpsCalculation > 500) {
                if (rtspFpsFrameTimes.size > 1) {
                    val timeSpan = rtspFpsFrameTimes.last() - rtspFpsFrameTimes.first()
                    val newFps = if (timeSpan > 0) {
                        (rtspFpsFrameTimes.size - 1) * 1000f / timeSpan
                    } else {
                        0f
                    }
                    // Only broadcast if FPS changed significantly (more than 0.5 fps difference)
                    if (kotlin.math.abs(newFps - currentRtspFps) > 0.5f) {
                        currentRtspFps = newFps
                        broadcastCameraState()
                    } else {
                        currentRtspFps = newFps
                    }
                }
                lastRtspFpsCalculation = now
            }
        }
    }
    
    /**
     * Get current CPU usage percentage for this process
     * Returns value between 0-100
     */
    private fun updateCpuUsage() {
        try {
            val now = System.currentTimeMillis()
            // Update CPU usage every 2 seconds to avoid overhead
            if (now - lastCpuCheckTime < 2000) {
                return
            }
            lastCpuCheckTime = now
            
            // Read /proc/self/stat for process CPU time
            val pid = android.os.Process.myPid()
            val statFile = java.io.File("/proc/$pid/stat")
            if (!statFile.exists()) {
                return
            }
            
            val statContent = statFile.readText()
            val stats = statContent.split(" ")
            
            // CPU time is at indices 13 (utime) and 14 (stime) in /proc/[pid]/stat
            // These are in clock ticks, need to convert to milliseconds
            if (stats.size > 14) {
                val utime = stats[13].toLongOrNull() ?: 0
                val stime = stats[14].toLongOrNull() ?: 0
                val totalTime = utime + stime
                
                // Get number of CPU cores
                val cores = Runtime.getRuntime().availableProcessors()
                
                // Calculate CPU percentage
                // This is a simplified calculation - for more accuracy, we'd need to track
                // the delta between two measurements
                // For now, we'll use a simple heuristic based on active threads
                val activeThreads = Thread.activeCount()
                val estimatedUsage = (activeThreads.toFloat() / (cores * 2)) * 100f
                
                // Clamp to 0-100
                currentCpuUsage = estimatedUsage.coerceIn(0f, 100f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading CPU usage", e)
            currentCpuUsage = 0f
        }
    }
    
    /**
     * Get current CPU usage percentage
     */
    fun getCurrentCpuUsage(): Float {
        updateCpuUsage()
        return currentCpuUsage
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        cameraOrientation = prefs.getString("cameraOrientation", "landscape") ?: "landscape"
        rotation = prefs.getInt("rotation", 0)
        
        // OSD overlay settings
        showDateTimeOverlay = prefs.getBoolean("showDateTimeOverlay", true)
        showBatteryOverlay = prefs.getBoolean("showBatteryOverlay", true)
        showResolutionOverlay = prefs.getBoolean("showResolutionOverlay", true)
        showFpsOverlay = prefs.getBoolean("showFpsOverlay", true)
        
        // FPS settings
        targetMjpegFps = prefs.getInt("targetMjpegFps", 10).coerceIn(1, 60)
        targetRtspFps = prefs.getInt("targetRtspFps", 30).coerceIn(1, 60)
        
        // Adaptive quality setting
        adaptiveQualityEnabled = prefs.getBoolean("adaptiveQualityEnabled", true)
        
        maxConnections = prefs.getInt(PREF_MAX_CONNECTIONS, HTTP_DEFAULT_MAX_POOL_SIZE)
            .coerceIn(HTTP_MIN_MAX_POOL_SIZE, HTTP_ABSOLUTE_MAX_POOL_SIZE)
        isFlashlightOn = prefs.getBoolean("flashlightOn", false)
        
        // NOTE: RTSP is now on-demand only, no persistence of enabled state
        // Only persist RTSP configuration (bitrate, mode) for when it's activated
        rtspBitrate = prefs.getInt("rtspBitrate", -1)
        rtspBitrateMode = prefs.getString("rtspBitrateMode", "VBR") ?: "VBR"
        
        // Load device name with default based on device model
        val defaultDeviceName = "IP_CAM_${Build.MODEL.replace(" ", "_")}"
        deviceName = prefs.getString("deviceName", defaultDeviceName) ?: defaultDeviceName
        
        // Migration: Check for old single resolution format and migrate to per-camera format
        val oldResWidth = prefs.getInt("resolutionWidth", -1)
        val oldResHeight = prefs.getInt("resolutionHeight", -1)
        val hasOldFormat = oldResWidth > 0 && oldResHeight > 0
        
        // Load per-camera resolutions
        val backResWidth = prefs.getInt("backCameraResolutionWidth", -1)
        val backResHeight = prefs.getInt("backCameraResolutionHeight", -1)
        if (backResWidth > 0 && backResHeight > 0) {
            backCameraResolution = Size(backResWidth, backResHeight)
        } else if (hasOldFormat) {
            // Migration: Apply old resolution to back camera (most common default)
            backCameraResolution = Size(oldResWidth, oldResHeight)
            Log.d(TAG, "Migrated old resolution ${oldResWidth}x${oldResHeight} to back camera")
        }
        
        val frontResWidth = prefs.getInt("frontCameraResolutionWidth", -1)
        val frontResHeight = prefs.getInt("frontCameraResolutionHeight", -1)
        if (frontResWidth > 0 && frontResHeight > 0) {
            frontCameraResolution = Size(frontResWidth, frontResHeight)
        }
        
        val cameraType = prefs.getString("cameraType", "back")
        currentCamera = if (cameraType == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        // Set selectedResolution based on current camera
        selectedResolution = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            backCameraResolution
        } else {
            frontCameraResolution
        }
        
        // Migration: Clean up old format keys after successful migration
        if (hasOldFormat && (backCameraResolution != null || frontCameraResolution != null)) {
            prefs.edit().apply {
                remove("resolutionWidth")
                remove("resolutionHeight")
                apply()
            }
            Log.d(TAG, "Cleaned up old resolution format keys")
        }
        
        Log.d(TAG, "Loaded settings: camera=$cameraType, orientation=$cameraOrientation, rotation=$rotation, resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}, maxConnections=$maxConnections, flashlight=$isFlashlightOn, mjpegFps=$targetMjpegFps, rtspFps=$targetRtspFps, rtspBitrate=$rtspBitrate, rtspBitrateMode=$rtspBitrateMode, adaptiveQuality=$adaptiveQualityEnabled, deviceName=$deviceName")
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("cameraOrientation", cameraOrientation)
            putInt("rotation", rotation)
            
            // OSD overlay settings
            putBoolean("showDateTimeOverlay", showDateTimeOverlay)
            putBoolean("showBatteryOverlay", showBatteryOverlay)
            putBoolean("showResolutionOverlay", showResolutionOverlay)
            putBoolean("showFpsOverlay", showFpsOverlay)
            
            // FPS settings
            putInt("targetMjpegFps", targetMjpegFps)
            putInt("targetRtspFps", targetRtspFps)
            
            // Adaptive quality setting
            putBoolean("adaptiveQualityEnabled", adaptiveQualityEnabled)
            
            putInt(PREF_MAX_CONNECTIONS, maxConnections)
            putBoolean("flashlightOn", isFlashlightOn)
            
            // NOTE: RTSP enabled state is NOT persisted (on-demand only)
            // Only save RTSP configuration for when it's activated
            if (rtspServer != null) {
                rtspServer?.getMetrics()?.let { metrics ->
                    rtspBitrate = (metrics.bitrateMbps * 1_000_000).toInt()
                    rtspBitrateMode = metrics.bitrateMode
                }
            }
            putInt("rtspBitrate", rtspBitrate)
            putString("rtspBitrateMode", rtspBitrateMode)
            
            // Save device name
            putString("deviceName", deviceName)
            
            // Save per-camera resolutions
            backCameraResolution?.let {
                putInt("backCameraResolutionWidth", it.width)
                putInt("backCameraResolutionHeight", it.height)
            } ?: run {
                remove("backCameraResolutionWidth")
                remove("backCameraResolutionHeight")
            }
            
            frontCameraResolution?.let {
                putInt("frontCameraResolutionWidth", it.width)
                putInt("frontCameraResolutionHeight", it.height)
            } ?: run {
                remove("frontCameraResolutionWidth")
                remove("frontCameraResolutionHeight")
            }
            
            val cameraType = if (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA) "front" else "back"
            putString("cameraType", cameraType)
            
            apply()
        }
    }
    
    fun onDeviceOrientationChanged() {
        // Device orientation changes only affect the app UI, not the camera recording
        // This method is kept for compatibility but device orientation doesn't affect camera
    }
    
    private fun setupOrientationListener() {
        // Device orientation listener is disabled since camera recording is independent of device orientation
        // The camera orientation mode (portrait/landscape) is set manually or stays at default (landscape)
        orientationEventListener = null
    }
    
    fun setOnCameraStateChangedCallback(callback: (CameraSelector) -> Unit) {
        onCameraStateChangedCallback = callback
    }
    
    fun setOnFrameAvailableCallback(callback: (Bitmap) -> Unit) {
        onFrameAvailableCallback = callback
    }
    
    fun setOnConnectionsChangedCallback(callback: () -> Unit) {
        onConnectionsChangedCallback = callback
    }
    
    /**
     * Clear all callbacks to prevent memory leaks and crashes.
     * MUST be called in MainActivity.onDestroy() to break the reference cycle.
     * 
     * LIFECYCLE SAFETY: This method ensures callbacks are cleared when MainActivity is destroyed,
     * preventing CameraService from holding stale references to destroyed Activity contexts.
     */
    fun clearCallbacks() {
        Log.d(TAG, "Clearing all MainActivity callbacks")
        onCameraStateChangedCallback = null
        onFrameAvailableCallback = null
        onConnectionsChangedCallback = null
    }
    
    /**
     * Safely invoke camera state changed callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state
     * before invoking to prevent crashes from callbacks to destroyed contexts.
     */
    private fun safeInvokeCameraStateCallback(selector: CameraSelector) {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping camera state callback - service lifecycle is DESTROYED")
            return
        }
        
        // Invoke callback if set
        onCameraStateChangedCallback?.invoke(selector)
    }
    
    /**
     * Safely invoke frame available callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state.
     * Bitmap is recycled if callback can't be invoked to prevent memory leaks.
     */
    private fun safeInvokeFrameCallback(bitmap: Bitmap) {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping frame callback - service lifecycle is DESTROYED, recycling bitmap")
            bitmapPool.returnBitmap(bitmap)
            return
        }
        
        // Invoke callback if set, otherwise recycle bitmap
        val callback = onFrameAvailableCallback
        if (callback != null) {
            callback(bitmap)
        } else {
            // No callback set - return bitmap to pool to prevent memory leak
            bitmapPool.returnBitmap(bitmap)
        }
    }
    
    /**
     * Safely invoke connections changed callback with lifecycle checks.
     * Only invokes if callback is set and we're not in a terminal lifecycle state.
     * 
     * LIFECYCLE SAFETY: Checks that callback exists and service is in a valid lifecycle state
     * before invoking to prevent crashes from callbacks to destroyed contexts.
     */
    private fun safeInvokeConnectionsCallback() {
        // Check if service is in valid lifecycle state (not DESTROYED)
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.w(TAG, "Skipping connections callback - service lifecycle is DESTROYED")
            return
        }
        
        // Invoke callback if set
        onConnectionsChangedCallback?.invoke()
    }
    
    override fun getActiveConnectionsCount(): Int {
        // Return the count of connections from HttpServer
        // This includes MJPEG streams + SSE clients
        return (httpServer?.getActiveStreamsCount() ?: 0) + (httpServer?.getActiveSseClientsCount() ?: 0)
    }
    
    override fun getMjpegClientCount(): Int {
        // HTTP-based streaming clients: MJPEG streams + SSE (Server-Sent Events) clients
        // Get the actual count from HttpServer
        return getActiveConnectionsCount()
    }
    
    override fun getRtspClientCount(): Int {
        // Count of active RTSP sessions
        return rtspServer?.getMetrics()?.playingSessions ?: 0
    }
    
    override fun getTotalCameraClientCount(): Int {
        // Total of MJPEG clients plus RTSP clients
        return getMjpegClientCount() + getRtspClientCount()
    }
    
    fun getActiveConnectionsList(): List<ConnectionInfo> {
        synchronized(connectionsLock) {
            return activeConnections.values.filter { it.active }.toList()
        }
    }
    
    override fun getMaxConnections(): Int = maxConnections
    
    override fun setMaxConnections(max: Int): Boolean {
        val newMax = max.coerceIn(HTTP_MIN_MAX_POOL_SIZE, HTTP_ABSOLUTE_MAX_POOL_SIZE)
        if (newMax != maxConnections) {
            maxConnections = newMax
            saveSettings()
            // Notify MainActivity of max connections change
            // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
            safeInvokeCameraStateCallback(currentCamera)
            // Note: Server needs to be restarted for the change to take effect
            return true
        }
        return false
    }
    
    fun closeConnection(connectionId: Long): Boolean {
        synchronized(connectionsLock) {
            val conn = activeConnections[connectionId]
            if (conn != null && conn.active) {
                conn.active = false
                activeConnections.remove(connectionId)
                Log.d(TAG, "Manually closed connection $connectionId")
                notifyConnectionsChanged()
                return true
            }
        }
        return false
    }
    
    private fun registerConnection(id: Long, remoteAddr: String, endpoint: String) {
        synchronized(connectionsLock) {
            activeConnections[id] = ConnectionInfo(id, remoteAddr, endpoint, System.currentTimeMillis())
        }
        notifyConnectionsChanged()
    }
    
    private fun unregisterConnection(id: Long) {
        synchronized(connectionsLock) {
            val conn = activeConnections[id]
            if (conn != null) {
                conn.active = false
                activeConnections.remove(id)
            }
        }
        notifyConnectionsChanged()
    }
    
    private fun notifyConnectionsChanged() {
        // LIFECYCLE SAFETY: Use safe callback to prevent crashes if MainActivity destroyed
        safeInvokeConnectionsCallback()
    }
    
    fun isServerRunning(): Boolean = httpServer?.isAlive() == true
    
    fun stopServer() {
        try {
            serverIntentionallyStopped = true
            httpServer?.stop()
            httpServer = null
            updateNotification("Camera preview active. Server stopped.")
            
            // Broadcast state change to any remaining web clients before they disconnect
            broadcastCameraState()
            
            Log.d(TAG, "Server stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            // Still update notification even if stop failed
            updateNotification("Camera preview active")
        }
    }
    
    /**
     * Restart the server by stopping and starting it.
     * Useful for applying configuration changes that require server restart,
     * or for recovering from server issues remotely.
     * Runs in background thread to avoid blocking.
     */
    override fun restartServer() {
        serviceScope.launch {
            try {
                Log.d(TAG, "Restarting server...")
                stopServer()
                // Give server time to fully stop before restarting
                delay(500)
                startServer()
                Log.d(TAG, "Server restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting server", e)
            }
        }
    }
    
    override fun getServerUrl(): String {
        val ipAddress = getIpAddress()
        return "http://$ipAddress:$actualPort"
    }
    
    @Suppress("DEPRECATION")
    private fun getIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        
        return if (ipInt != 0) {
            InetAddress.getByAddress(
                ByteBuffer.allocate(4).putInt(Integer.reverseBytes(ipInt)).array()
            ).hostAddress ?: "0.0.0.0"
        } else {
            "0.0.0.0"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() - cleaning up service resources")
        
        // LIFECYCLE MANAGEMENT: Transition to DESTROYED state FIRST to stop callback invocations
        // This prevents new callbacks from being invoked during cleanup
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        // Clear all MainActivity callbacks immediately to prevent memory leaks
        // This breaks the reference cycle between Service and Activity
        clearCallbacks()
        
        unregisterNetworkReceiver()
        orientationEventListener?.disable()
        httpServer?.stop()
        cameraProvider?.unbindAll()
        
        // Shutdown executors in proper order
        // 1. Camera executor (stops frame capture)
        cameraExecutor.shutdown()
        
        // 2. Processing executor (stops frame processing)
        processingExecutor.shutdown()
        try {
            // Wait for pending processing tasks to complete (up to 2 seconds)
            if (!processingExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Processing executor did not terminate cleanly, forcing shutdown")
                processingExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for processing executor to terminate")
            processingExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // 3. Streaming executor (stops client connections)
        streamingExecutor.shutdownNow() // Forcefully terminate streaming threads
        
        // ATOMIC FRAME CLEANUP: Clear the processed frame and return bitmap to pool
        val oldFrame = lastProcessedFrame.getAndSet(null)
        if (oldFrame != null) {
            bitmapPool.returnBitmap(oldFrame.bitmap)
            Log.d(TAG, "Last processed frame cleared and bitmap returned to pool")
        }
        
        // Clear bitmap pool to free all memory
        bitmapPool.clear()
        Log.d(TAG, "Bitmap pool cleared on service destroy")
        
        // LIFECYCLE MANAGEMENT: Cancel all coroutines LAST after other cleanup
        // This ensures no coroutines try to use resources that have been cleaned up
        serviceScope.cancel()
        
        releaseLocks() // Use the new releaseLocks() method
        
        Log.i(TAG, "Service destroyed - all resources cleaned up")
    }
    
    /**
     * Determine battery management mode based on current battery level and charging status.
     * Implements hysteresis to prevent flapping between states.
     * 
     * State transitions:
     * - NORMAL → LOW_BATTERY: battery ≤ 20% and not charging
     * - LOW_BATTERY → CRITICAL_BATTERY: battery ≤ 10% and not charging
     * - CRITICAL_BATTERY → LOW_BATTERY: battery > 10% and (charging OR user override)
     * - LOW_BATTERY → NORMAL: battery > 20% OR charging OR (battery > 50% and was in CRITICAL)
     * - Any state → NORMAL: charging = true
     * 
     * @return Current battery management mode
     */
    private fun determineBatteryMode(batteryInfo: BatteryInfo): BatteryManagementMode {
        val batteryLevel = batteryInfo.level
        val isCharging = batteryInfo.isCharging
        
        // If charging, always return to NORMAL mode (unless in CRITICAL and battery still very low)
        if (isCharging) {
            // Only restore from CRITICAL if battery > CRITICAL threshold to avoid immediate re-critical on unplug
            return if (batteryMode == BatteryManagementMode.CRITICAL_BATTERY && batteryLevel < BATTERY_CRITICAL_PERCENT) {
                BatteryManagementMode.CRITICAL_BATTERY
            } else {
                BatteryManagementMode.NORMAL
            }
        }
        
        // Not charging - apply state machine with hysteresis
        return when (batteryMode) {
            BatteryManagementMode.NORMAL -> {
                when {
                    batteryLevel <= BATTERY_CRITICAL_PERCENT -> BatteryManagementMode.CRITICAL_BATTERY
                    batteryLevel <= BATTERY_LOW_PERCENT -> BatteryManagementMode.LOW_BATTERY
                    else -> BatteryManagementMode.NORMAL
                }
            }
            BatteryManagementMode.LOW_BATTERY -> {
                when {
                    batteryLevel <= BATTERY_CRITICAL_PERCENT -> BatteryManagementMode.CRITICAL_BATTERY
                    batteryLevel > BATTERY_LOW_PERCENT -> BatteryManagementMode.NORMAL
                    else -> BatteryManagementMode.LOW_BATTERY
                }
            }
            BatteryManagementMode.CRITICAL_BATTERY -> {
                when {
                    // Auto-recovery: battery charged to recovery threshold
                    batteryLevel >= BATTERY_RECOVERY_PERCENT -> {
                        userOverrideBatteryLimit = false // Clear override on full recovery
                        BatteryManagementMode.NORMAL
                    }
                    // User override: battery > 10% and user explicitly requested restore
                    batteryLevel > BATTERY_CRITICAL_PERCENT && userOverrideBatteryLimit -> {
                        BatteryManagementMode.LOW_BATTERY // Go to LOW mode first, not NORMAL
                    }
                    // Stay in critical if still below 10%
                    else -> BatteryManagementMode.CRITICAL_BATTERY
                }
            }
        }
    }
    
    /**
     * Update battery management mode and apply appropriate actions.
     * Called periodically by watchdog to monitor battery state and adjust behavior.
     */
    private fun updateBatteryManagement() {
        val batteryInfo = getBatteryInfo()
        val newMode = determineBatteryMode(batteryInfo)
        
        // Only log and take action if mode changed
        if (newMode != batteryMode) {
            val oldMode = batteryMode
            batteryMode = newMode
            
            Log.i(TAG, "Battery mode changed: $oldMode → $newMode (battery: ${batteryInfo.level}%, charging: ${batteryInfo.isCharging})")
            
            // Apply mode-specific actions
            when (newMode) {
                BatteryManagementMode.NORMAL -> {
                    // Restore full operation
                    acquireLocks()
                    // Note: Camera remains running, streaming resumes automatically
                    Log.i(TAG, "Full operation restored (battery: ${batteryInfo.level}%)")
                }
                BatteryManagementMode.LOW_BATTERY -> {
                    // Release wakelocks to reduce power consumption
                    releaseLocks()
                    // Camera and streaming continue, but device can sleep more aggressively
                    Log.w(TAG, "Low battery mode: wakelocks released (battery: ${batteryInfo.level}%)")
                }
                BatteryManagementMode.CRITICAL_BATTERY -> {
                    // Disable streaming to preserve battery
                    releaseLocks()
                    // Camera frames still captured but not served to clients (handled in HttpServer)
                    Log.e(TAG, "CRITICAL battery mode: streaming disabled to preserve battery (battery: ${batteryInfo.level}%)")
                    
                    // Show notification to user
                    showUserNotification(
                        "Critical Battery - Streaming Paused",
                        "Battery at ${batteryInfo.level}%. IP camera streaming has been paused to preserve battery. Please charge the device or streaming will resume automatically when battery reaches 50%."
                    )
                }
            }
        }
    }
    
    /**
     * Check if streaming should be allowed based on battery management mode.
     * Called by HttpServer to determine if streaming endpoints should serve frames or info page.
     * 
     * @return true if streaming is allowed, false if battery is too critical
     */
    override fun isStreamingAllowed(): Boolean {
        return batteryMode != BatteryManagementMode.CRITICAL_BATTERY
    }
    
    /**
     * Get current battery mode for status reporting
     */
    override fun getBatteryMode(): String {
        return batteryMode.name
    }
    
    /**
     * Get the critical battery threshold percentage
     */
    override fun getBatteryCriticalPercent(): Int {
        return BATTERY_CRITICAL_PERCENT
    }
    
    /**
     * Get the low battery threshold percentage
     */
    override fun getBatteryLowPercent(): Int {
        return BATTERY_LOW_PERCENT
    }
    
    /**
     * Get the recovery battery threshold percentage
     */
    override fun getBatteryRecoveryPercent(): Int {
        return BATTERY_RECOVERY_PERCENT
    }
    
    /**
     * Allow user to override critical battery mode and restore streaming.
     * Only works if battery > 10%. If battery drops below 10% again, critical mode re-activates.
     * 
     * @return true if override was successful, false if battery still too low
     */
    override fun overrideBatteryLimit(): Boolean {
        val batteryInfo = getBatteryInfo()
        val batteryLevel = batteryInfo.level
        
        // Can only override if battery > CRITICAL threshold (strictly greater than, not equal)
        if (batteryLevel <= BATTERY_CRITICAL_PERCENT) {
            Log.w(TAG, "Battery override rejected: battery too low ($batteryLevel% ≤ $BATTERY_CRITICAL_PERCENT%)")
            return false
        }
        
        // Can only override from CRITICAL mode
        if (batteryMode != BatteryManagementMode.CRITICAL_BATTERY) {
            Log.d(TAG, "Battery override not needed: already in $batteryMode mode")
            return true // Already operating normally
        }
        
        // Set override flag and update mode
        userOverrideBatteryLimit = true
        updateBatteryManagement()
        
        Log.i(TAG, "User override battery limit: streaming restored (battery: $batteryLevel%)")
        showUserNotification(
            "Streaming Restored",
            "Battery at $batteryLevel%. Streaming has been manually restored. It will pause again if battery drops below 10%."
        )
        
        return true
    }
    
    /**
     * Acquire wake locks for optimal streaming performance.
     * Only acquires locks when in NORMAL battery mode.
     * 
     * PARTIAL_WAKE_LOCK: Keeps CPU running for camera processing and streaming
     * WIFI_MODE_FULL_HIGH_PERF: Prevents WiFi from entering power save mode for consistent streaming
     */
    private fun acquireLocks() {
        val batteryInfo = getBatteryInfo()
        val batteryLevel = batteryInfo.level
        val isCharging = batteryInfo.isCharging
        
        // Only acquire locks in NORMAL mode
        if (batteryMode == BatteryManagementMode.NORMAL) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG:WakeLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "Wake lock acquired (battery: $batteryLevel%, charging: $isCharging, mode: $batteryMode)")
                }
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$TAG:WifiLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "WiFi lock acquired (battery: $batteryLevel%, charging: $isCharging, mode: $batteryMode)")
                }
            }
        } else {
            // Ensure locks are released in non-NORMAL modes
            releaseLocks()
            Log.d(TAG, "Wake locks not acquired: battery mode is $batteryMode (battery: $batteryLevel%)")
        }
    }
    
    /**
     * Release wake locks to conserve battery
     */
    private fun releaseLocks() {
        val wasHeld = wakeLock?.isHeld == true || wifiLock?.isHeld == true
        
        wakeLock?.let { 
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        wifiLock?.let { 
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "WiFi lock released")
            }
        }
        
        if (wasHeld) {
            val batteryInfo = getBatteryInfo()
            Log.i(TAG, "All locks released (battery: ${batteryInfo.level}%, charging: ${batteryInfo.isCharging})")
        }
    }
    
    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(watchdogRetryDelay)
                
                var needsRecovery = false
                
                // Only check camera health if there are active consumers
                val hasActiveConsumers = hasConsumers()
                
                if (hasActiveConsumers) {
                    // Check camera provider health - ensure camera is initialized when consumers need it
                    // This handles cases where permission was granted after service started
                    if (cameraProvider == null) {
                        val hasPermission = ContextCompat.checkSelfPermission(
                            this@CameraService, 
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        
                        if (hasPermission) {
                            Log.w(TAG, "Watchdog: Camera provider not initialized but consumers waiting, starting camera...")
                            startCamera()
                            needsRecovery = true
                        } else {
                            // Permission not granted - don't count as recovery needed
                            // Log every 30 seconds to avoid spam
                            if (watchdogRetryDelay >= 30_000L) {
                                Log.d(TAG, "Watchdog: Camera provider not initialized, waiting for permission...")
                            }
                        }
                    } else if (camera == null) {
                        // Camera provider exists but camera not bound, with active consumers
                        // This happens after ERROR_CAMERA_DISABLED or other binding failures
                        Log.w(TAG, "Watchdog: Camera provider exists but camera not bound (${getConsumerCount()} consumers), binding camera...")
                        
                        // CameraX requires main thread for binding operations
                        // Post to main thread to avoid IllegalStateException
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                bindCamera()
                            } catch (e: Exception) {
                                Log.e(TAG, "Watchdog: Error binding camera from main thread", e)
                            }
                        }
                        needsRecovery = true
                    }
                    
                    // Check camera health - only if camera is bound
                    if (camera != null) {
                        // ATOMIC FRAME ACCESS: Get timestamp from atomic reference
                        val currentFrame = lastProcessedFrame.get()
                        val lastTimestamp = currentFrame?.timestamp ?: 0L
                        val frameAge = System.currentTimeMillis() - lastTimestamp
                        if (frameAge > FRAME_STALE_THRESHOLD_MS) {
                            Log.w(TAG, "Watchdog: Frame stale (${frameAge}ms), restarting camera...")
                            requestBindCamera()
                            needsRecovery = true
                        }
                        
                        // Check camera state - detect CLOSED state which indicates camera was released
                        val cameraState = try {
                            camera?.cameraInfo?.cameraState?.value
                        } catch (e: Exception) {
                            Log.w(TAG, "Watchdog: Error reading camera state", e)
                            null
                        }
                        
                        if (cameraState?.type == androidx.camera.core.CameraState.Type.CLOSED) {
                            Log.w(TAG, "Watchdog: Camera is in CLOSED state, rebinding...")
                            requestBindCamera()
                            needsRecovery = true
                        }
                    }
                } else {
                    // No active consumers - camera should be idle
                    // Log consumer count periodically for monitoring
                    if (watchdogRetryDelay >= 10_000L) {
                        synchronized(cameraStateLock) {
                            Log.d(TAG, "Watchdog: No consumers, camera state: $cameraState")
                        }
                    }
                }
                
                // Check server health - only restart if it wasn't intentionally stopped
                if (!serverIntentionallyStopped && httpServer?.isAlive() != true) {
                    Log.w(TAG, "Watchdog: Server not alive, restarting...")
                    startServer()
                    needsRecovery = true
                }
                
                // Check battery level and manage power/streaming accordingly
                // This is the main battery management check that runs every watchdog cycle
                updateBatteryManagement()
                
                // Exponential backoff for retry delay
                if (needsRecovery) {
                    watchdogRetryDelay = (watchdogRetryDelay * 2).coerceAtMost(WATCHDOG_MAX_RETRY_DELAY_MS)
                    Log.d(TAG, "Watchdog: Increasing retry delay to ${watchdogRetryDelay}ms")
                } else {
                    // Reset to initial delay when everything is healthy
                    if (watchdogRetryDelay != WATCHDOG_RETRY_DELAY_MS) {
                        watchdogRetryDelay = WATCHDOG_RETRY_DELAY_MS
                        Log.d(TAG, "Watchdog: System healthy, reset retry delay")
                    }
                }
            }
        }
    }
    
    private fun registerNetworkReceiver() {
        val filter = IntentFilter().apply {
            addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        
        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    android.net.ConnectivityManager.CONNECTIVITY_ACTION,
                    android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        Log.d(TAG, "Network state changed, checking server...")
                        serviceScope.launch {
                            delay(2000) // Wait for network to stabilize
                            if (httpServer?.isAlive() != true) {
                                Log.d(TAG, "Network recovered, restarting server")
                                startServer()
                            }
                        }
                    }
                }
            }
        }
        
        registerReceiver(networkReceiver, filter)
        Log.d(TAG, "Network receiver registered")
    }
    
    private fun unregisterNetworkReceiver() {
        networkReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "Network receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network receiver", e)
            }
        }
        networkReceiver = null
    }
    
    /**
     * Annotate bitmap with OSD overlays (date/time, battery, FPS, resolution).
     * Creates a mutable copy from the bitmap pool for drawing annotations.
     * 
     * @param source Source bitmap to annotate (immutable)
     * @return Annotated bitmap (mutable) from pool, or null if allocation fails or source is recycled
     */
    private fun annotateBitmap(source: Bitmap): Bitmap? {
        // Safety check: return null if already recycled
        if (source.isRecycled) {
            Log.w(TAG, "annotateBitmap called with recycled bitmap")
            return null
        }
        
        // Use bitmap pool for memory-efficient copy with OOME protection
        val mutable = try {
            bitmapPool.copy(source, source.config ?: Bitmap.Config.ARGB_8888, true)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to copy bitmap for annotation", t)
            null
        }
        
        if (mutable == null) {
            Log.e(TAG, "Unable to create mutable bitmap for annotation")
            return null
        }
        
        val canvas = Canvas(mutable)
        // Use cached density to avoid Binder calls from HTTP threads
        val density = cachedDensity
        val padding = 8f * density
        val textSize = 14f * density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
            setShadowLayer(6f, 2f, 2f, Color.BLACK)
        }
        
        fun drawLabel(text: String, alignRight: Boolean, topOffset: Float, bottomAlign: Boolean = false) {
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val left = if (alignRight) {
                canvas.width - padding - textWidth - padding
            } else {
                padding
            }
            
            val verticalPos = if (bottomAlign) {
                // Bottom aligned - calculate from bottom of canvas
                val bottom = canvas.height.toFloat() - padding
                bottom - padding - textPaint.fontMetrics.bottom
            } else {
                // Top aligned - use topOffset
                val bottom = topOffset + textHeight + padding
                bottom - padding - textPaint.fontMetrics.bottom
            }
            
            canvas.drawText(text, left + padding, verticalPos, textPaint)
        }
        
        // Prepare OSD text elements
        val timeText = if (showDateTimeOverlay) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        } else null
        
        val batteryText = if (showBatteryOverlay) {
            val batteryInfo = getCachedBatteryInfo()
            buildString {
                append(batteryInfo.level)
                append("%")
                if (batteryInfo.isCharging) append(" ⚡")
            }
        } else null
        
        val fpsText = if (showFpsOverlay) {
            // Show MJPEG streaming FPS (actual frames served to clients) instead of camera FPS
            "%.1f fps".format(currentMjpegFps)
        } else null
        
        val resolutionText = if (showResolutionOverlay) {
            "${source.width}x${source.height}"
        } else null
        
        // Calculate widths to check for overlap (only for top row)
        val timeWidth = timeText?.let { textPaint.measureText(it) + padding * 3 } ?: 0f
        val batteryWidth = batteryText?.let { textPaint.measureText(it) + padding * 3 } ?: 0f
        val availableWidth = canvas.width.toFloat()
        
        // Check if top labels would overlap
        val wouldOverlap = (timeWidth + batteryWidth) > availableWidth
        val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
        
        // Draw date/time in top left (if enabled)
        if (timeText != null) {
            drawLabel(timeText, alignRight = false, topOffset = padding)
        }
        
        // Draw battery in top right (if enabled)
        if (batteryText != null) {
            if (wouldOverlap && timeText != null) {
                // Stack vertically if they would overlap and both are shown
                drawLabel(batteryText, alignRight = true, topOffset = padding + textHeight + padding * 2)
            } else {
                // Draw side by side
                drawLabel(batteryText, alignRight = true, topOffset = padding)
            }
        }
        
        // Draw FPS in bottom left (if enabled)
        if (fpsText != null) {
            drawLabel(fpsText, alignRight = false, topOffset = 0f, bottomAlign = true)
        }
        
        // Draw resolution in bottom right (if enabled)
        if (resolutionText != null) {
            drawLabel(resolutionText, alignRight = true, topOffset = 0f, bottomAlign = true)
        }
        
        return mutable
    }
    
    private data class BatteryInfo(val level: Int, val isCharging: Boolean)
    
    private fun updateBatteryCache() {
        cachedBatteryInfo = getBatteryInfo()
        lastBatteryUpdate = System.currentTimeMillis()
    }
    
    private fun getCachedBatteryInfo(): BatteryInfo {
        // Update battery cache if it's older than 30 seconds and no update is pending
        if (System.currentTimeMillis() - lastBatteryUpdate > BATTERY_CACHE_UPDATE_INTERVAL_MS 
            && !batteryUpdatePending) {
            batteryUpdatePending = true
            // Schedule update on main thread to avoid Binder issues
            serviceScope.launch(Dispatchers.Main) {
                try {
                    updateBatteryCache()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update battery cache", e)
                } finally {
                    batteryUpdatePending = false
                }
            }
        }
        return cachedBatteryInfo
    }
    
    private fun getBatteryInfo(): BatteryInfo {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            0
        }
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(percentage, isCharging)
    }
    
    override fun sizeLabel(size: Size): String = "${size.width}$RESOLUTION_DELIMITER${size.height}"
    
    private fun getSupportedResolutions(cameraSelector: CameraSelector): List<Size> {
        val targetFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        
        // Check if resolution cache already has filtered/processed resolutions for this facing
        resolutionCache[targetFacing]?.let { return it }
        
        // Use cached camera characteristics instead of making IPC calls
        val sizes = if (cameraCharacteristicsCacheInitialized) {
            // Get resolutions from cache
            cameraCharacteristicsCache.values
                .filter { it.lensFacing == targetFacing }
                .flatMap { it.supportedResolutions }
        } else {
            // Fallback: if cache not initialized, query directly (shouldn't happen in normal flow)
            Log.w(TAG, "Camera characteristics cache not initialized, falling back to direct query")
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.mapNotNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != targetFacing) return@mapNotNull null
                val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                config?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
            }.flatten()
        }
        
        // Filter resolutions based on camera orientation
        val filtered = sizes.filter { size ->
            val isLandscape = size.width > size.height
            val isPortrait = size.height > size.width
            
            when (cameraOrientation) {
                "landscape" -> isLandscape || size.width == size.height
                "portrait" -> isPortrait || size.width == size.height
                else -> true // If orientation not set, allow all
            }
        }
        
        val distinct = filtered.distinctBy { Pair(it.width, it.height) }
            .sortedByDescending { it.width * it.height }
        resolutionCache[targetFacing] = distinct
        return distinct
    }
    
    // ==================== CameraServiceInterface Implementation ====================
    
    override fun getLastFrameJpegBytes(): ByteArray? {
        // ATOMIC FRAME ACCESS: Retrieve JPEG bytes from atomic reference
        // This ensures we get the JPEG bytes that correspond to the current frame
        return lastProcessedFrame.get()?.jpegBytes
    }
    
    override fun getSelectedResolutionLabel(): String {
        return selectedResolution?.let { sizeLabel(it) } ?: "auto"
    }
    
    override fun getCameraStateJson(): String {
        val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        val resolutionLabel = selectedResolution?.let { sizeLabel(it) } ?: "auto"
        
        // Update CPU usage before sending state
        updateCpuUsage()
        
        // Get RTSP encoder output FPS (actual encoded frames/sec)
        val rtspEncodedFps = rtspServer?.getMetrics()?.encodedFps ?: 0f
        
        // Full state JSON - used for /status endpoint and initial SSE connection
        return """{"camera":"$cameraName","resolution":"$resolutionLabel","cameraOrientation":"$cameraOrientation","rotation":$rotation,"showDateTimeOverlay":$showDateTimeOverlay,"showBatteryOverlay":$showBatteryOverlay,"showResolutionOverlay":$showResolutionOverlay,"showFpsOverlay":$showFpsOverlay,"currentCameraFps":$currentCameraFps,"currentMjpegFps":$currentMjpegFps,"currentRtspFps":$rtspEncodedFps,"cpuUsage":$currentCpuUsage,"targetMjpegFps":$targetMjpegFps,"targetRtspFps":$targetRtspFps,"adaptiveQualityEnabled":$adaptiveQualityEnabled,"flashlightAvailable":${isFlashlightAvailable()},"flashlightOn":${isFlashlightEnabled()},"batteryMode":"$batteryMode","streamingAllowed":${isStreamingAllowed()}}"""
    }
    
    /**
     * Get camera state JSON with only changed values (delta broadcasting)
     * This reduces bandwidth and prevents unnecessary UI updates for unchanged values
     * Returns null if nothing has changed since last broadcast
     */
    override fun getCameraStateDeltaJson(): String? {
        val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        val resolutionLabel = selectedResolution?.let { sizeLabel(it) } ?: "auto"
        
        // Update CPU usage before comparing state
        updateCpuUsage()
        
        // Get RTSP encoder output FPS (actual encoded frames/sec)
        val rtspEncodedFps = rtspServer?.getMetrics()?.encodedFps ?: 0f
        
        // Build map of current values
        val currentState = mapOf<String, Any>(
            "camera" to cameraName,
            "resolution" to resolutionLabel,
            "cameraOrientation" to cameraOrientation,
            "rotation" to rotation,
            "showDateTimeOverlay" to showDateTimeOverlay,
            "showBatteryOverlay" to showBatteryOverlay,
            "showResolutionOverlay" to showResolutionOverlay,
            "showFpsOverlay" to showFpsOverlay,
            "currentCameraFps" to currentCameraFps,
            "currentMjpegFps" to currentMjpegFps,
            "currentRtspFps" to rtspEncodedFps,
            "cpuUsage" to currentCpuUsage,
            "targetMjpegFps" to targetMjpegFps,
            "targetRtspFps" to targetRtspFps,
            "adaptiveQualityEnabled" to adaptiveQualityEnabled,
            "flashlightAvailable" to isFlashlightAvailable(),
            "flashlightOn" to isFlashlightEnabled(),
            "batteryMode" to batteryMode.name,
            "streamingAllowed" to isStreamingAllowed()
        )
        
        // Find changed fields
        val changes = mutableListOf<String>()
        
        synchronized(broadcastLock) {
            currentState.forEach { (key, value) ->
                val lastValue = lastBroadcastState[key]
                val hasChanged = when (value) {
                    is Float -> lastValue == null || kotlin.math.abs(value - (lastValue as? Float ?: 0f)) > 0.01f
                    else -> lastValue != value
                }
                
                if (hasChanged) {
                    // Format value for JSON
                    val jsonValue = when (value) {
                        is String -> "\"$value\""
                        is Boolean -> value.toString()
                        is Int -> value.toString()
                        is Float -> value.toString()
                        else -> value.toString()
                    }
                    changes.add("\"$key\":$jsonValue")
                    // Update last broadcast state
                    lastBroadcastState[key] = value
                }
            }
        }
        
        // If nothing changed, return null
        if (changes.isEmpty()) {
            return null
        }
        
        // Build JSON with only changed fields
        return "{${changes.joinToString(",")}}"
    }
    
    /**
     * Initialize last broadcast state with current values
     * Called when a new SSE client connects to prevent sending full state again on next delta
     */
    override fun initializeLastBroadcastState() {
        val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        val resolutionLabel = selectedResolution?.let { sizeLabel(it) } ?: "auto"
        
        // Get RTSP encoder output FPS (actual encoded frames/sec)
        val rtspEncodedFps = rtspServer?.getMetrics()?.encodedFps ?: 0f
        
        synchronized(broadcastLock) {
            lastBroadcastState.clear()
            lastBroadcastState["camera"] = cameraName
            lastBroadcastState["resolution"] = resolutionLabel
            lastBroadcastState["cameraOrientation"] = cameraOrientation
            lastBroadcastState["rotation"] = rotation
            lastBroadcastState["showDateTimeOverlay"] = showDateTimeOverlay
            lastBroadcastState["showBatteryOverlay"] = showBatteryOverlay
            lastBroadcastState["showResolutionOverlay"] = showResolutionOverlay
            lastBroadcastState["showFpsOverlay"] = showFpsOverlay
            lastBroadcastState["currentCameraFps"] = currentCameraFps
            lastBroadcastState["currentMjpegFps"] = currentMjpegFps
            lastBroadcastState["currentRtspFps"] = rtspEncodedFps
            lastBroadcastState["cpuUsage"] = currentCpuUsage
            lastBroadcastState["targetMjpegFps"] = targetMjpegFps
            lastBroadcastState["targetRtspFps"] = targetRtspFps
            lastBroadcastState["adaptiveQualityEnabled"] = adaptiveQualityEnabled
            lastBroadcastState["flashlightAvailable"] = isFlashlightAvailable()
            lastBroadcastState["flashlightOn"] = isFlashlightEnabled()
            lastBroadcastState["batteryMode"] = batteryMode.name
            lastBroadcastState["streamingAllowed"] = isStreamingAllowed()
        }
    }
    
    override fun recordBytesSent(clientId: Long, bytes: Long) {
        bandwidthMonitor.recordBytesSent(clientId, bytes)
    }
    
    override fun removeClient(clientId: Long) {
        bandwidthMonitor.removeClient(clientId)
        adaptiveQualityManager.removeClient(clientId)
    }
    
    override fun getDetailedStats(): String {
        val bandwidthStats = bandwidthMonitor.getDetailedStats()
        val performanceStats = performanceMetrics.getDetailedStats()
        val adaptiveStats = adaptiveQualityManager.getDetailedStats()
        
        return """
            $bandwidthStats
            
            $performanceStats
            
            $adaptiveStats
        """.trimIndent()
    }
    
    override fun getCpuUsagePercent(): Double {
        return performanceMetrics.getCpuUsage().processUsagePercent
    }
    
    override fun getBandwidthBps(): Long {
        // Calculate total bandwidth across all clients
        // This is an estimate based on global stats
        val globalStats = bandwidthMonitor.getGlobalStats()
        // Return average throughput in bits per second
        return (globalStats.averageThroughputMbps * 1_000_000).toLong()
    }
    
    override fun setAdaptiveQualityEnabled(enabled: Boolean) {
        adaptiveQualityEnabled = enabled
        saveSettings()
        Log.d(TAG, "Adaptive quality ${if (enabled) "enabled" else "disabled"} via HTTP")
    }
    
    // ==================== RTSP Streaming Control ====================
    
    /**
     * Enable RTSP streaming
     */
    override fun enableRTSPStreaming(): Boolean {
        if (rtspEnabled && rtspServer != null && rtspServer?.isAlive() == true) {
            Log.d(TAG, "RTSP already enabled and server is running")
            return true
        }
        
        if (rtspEnabled) {
            Log.w(TAG, "RTSP flag was set but server not running, restarting...")
            disableRTSPStreaming()
        }
        
        try {
            // Check if hardware encoder is available
            if (!RTSPServer.isHardwareEncoderAvailable()) {
                Log.w(TAG, "Hardware encoder not available, RTSP may use software fallback")
            }
            
            // Get current camera resolution for encoder
            val resolution = selectedResolution ?: run {
                // Use a default resolution if none is set
                Log.d(TAG, "No resolution set, using 1920x1080 for RTSP")
                Size(1920, 1080)
            }
            
            // Determine bitrate to use: saved value if valid, otherwise calculate default
            val bitrateToUse = if (rtspBitrate > 0) {
                Log.d(TAG, "Using saved RTSP bitrate: $rtspBitrate bps")
                rtspBitrate
            } else {
                val calculated = RTSPServer.calculateBitrate(resolution.width, resolution.height)
                Log.d(TAG, "Using calculated RTSP bitrate: $calculated bps")
                calculated
            }
            
            // Create RTSP server with saved/calculated settings
            rtspServer = RTSPServer(
                port = 8554,
                width = resolution.width,
                height = resolution.height,
                fps = targetRtspFps,  // Use saved target FPS instead of hardcoded 30
                initialBitrate = bitrateToUse,
                cameraService = this@CameraService  // Pass reference for FPS tracking
            )
            
            if (rtspServer?.start() != true) {
                Log.e(TAG, "Failed to start RTSP server")
                rtspServer = null
                rtspEnabled = false
                saveSettings()
                return false
            }
            
            // Apply saved bitrate mode if not default
            if (rtspBitrateMode != "VBR") {
                Log.d(TAG, "Applying saved RTSP bitrate mode: $rtspBitrateMode")
                rtspServer?.setBitrateMode(rtspBitrateMode)
            }
            
            rtspEnabled = true
            
            // NOTE: Camera is NOT activated here - it stays idle
            // RTSP server just starts listening for connections
            // Camera will activate when clients send PLAY command
            
            // Reset RTSP FPS counter when enabling to start fresh
            currentRtspFps = 0f
            synchronized(rtspFpsLock) {
                rtspFpsFrameTimes.clear()
            }
            
            saveSettings()
            Log.i(TAG, "RTSP server started on port 8554 (fps=$targetRtspFps, bitrate=$bitrateToUse, mode=$rtspBitrateMode)")
            Log.i(TAG, "Server is idling - camera will activate when clients connect and send PLAY")
            
            // DO NOT rebind camera here - it will activate on-demand when clients connect
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling RTSP streaming", e)
            rtspEnabled = false
            rtspServer = null
            saveSettings()
            return false
        }
    }
    
    /**
     * Disable RTSP streaming
     */
    override fun disableRTSPStreaming() {
        if (!rtspEnabled) {
            Log.w(TAG, "RTSP already disabled")
            return
        }
        
        try {
            rtspEnabled = false
            
            // Stop RTSP server (this will unregister consumers if clients were connected)
            rtspServer?.stop()
            rtspServer = null
            
            // NOTE: Consumer unregistration is handled by RTSPServer.stop()
            // which checks for active playing sessions and unregisters accordingly
            
            // Reset RTSP FPS counter to avoid showing stale values
            currentRtspFps = 0f
            synchronized(rtspFpsLock) {
                rtspFpsFrameTimes.clear()
            }
            
            saveSettings()
            Log.i(TAG, "RTSP streaming disabled")
            
            // Rebind camera to remove H.264 encoder use case
            // This frees resources when RTSP is disabled
            Log.d(TAG, "Rebinding camera to remove H.264 encoder pipeline")
            requestBindCamera()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling RTSP streaming", e)
        }
    }
    
    /**
     * Check if RTSP is enabled
     */
    override fun isRTSPEnabled(): Boolean = rtspEnabled
    
    /**
     * Get RTSP server metrics
     */
    override fun getRTSPMetrics(): RTSPServer.ServerMetrics? {
        return rtspServer?.getMetrics()
    }
    
    /**
     * Get RTSP URL
     */
    override fun getRTSPUrl(): String {
        val ipAddress = getServerUrl().substringAfter("http://").substringBefore(":")
        return "rtsp://$ipAddress:8554/stream"
    }
    
    /**
     * Set RTSP bitrate
     */
    override fun setRTSPBitrate(bitrate: Int): Boolean {
        if (!rtspEnabled || rtspServer == null) {
            Log.w(TAG, "Cannot set RTSP bitrate: RTSP not enabled")
            return false
        }
        
        return rtspServer?.setBitrate(bitrate) ?: false
    }
    
    /**
     * Set RTSP bitrate mode (VBR/CBR/CQ)
     */
    override fun setRTSPBitrateMode(mode: String): Boolean {
        if (!rtspEnabled || rtspServer == null) {
            Log.w(TAG, "Cannot set RTSP bitrate mode: RTSP not enabled")
            return false
        }
        
        return rtspServer?.setBitrateMode(mode) ?: false
    }
    
    // ==================== End RTSP Streaming Control ====================
    
    /**
     * Broadcast connection count update to all SSE clients via HttpServer
     */
    private fun broadcastConnectionCount() {
        httpServer?.broadcastConnectionCount()
    }
    
    /**
     * Broadcast camera state changes to all SSE clients via HttpServer
     */
    private fun broadcastCameraState() {
        httpServer?.broadcastCameraState()
    }
    
    // ==================== Device Identification ====================
    
    /**
     * Get the user-defined device name
     */
    override fun getDeviceName(): String = deviceName
    
    /**
     * Set the user-defined device name and persist it
     */
    override fun setDeviceName(name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isNotEmpty()) {
            deviceName = trimmedName
            saveSettings()
            Log.d(TAG, "Device name updated to: $deviceName")
            
            // Update notification to reflect new device name
            updateNotification(getNotificationText())
            
            // Broadcast state change to web clients
            broadcastCameraState()
        }
    }
    
    // ==================== Consumer Management ====================
    
    /**
     * Register a consumer (preview, MJPEG, RTSP)
     * Activates camera if this is the first consumer
     */
    private fun registerConsumer(type: ConsumerType) {
        synchronized(consumersLock) {
            val wasEmpty = consumers.isEmpty()
            consumers.add(type)
            Log.d(TAG, "Registered consumer: $type, total consumers: ${consumers.size}")
            
            if (wasEmpty) {
                Log.d(TAG, "First consumer registered, activating camera...")
                activateCameraForConsumers()
            }
        }
    }
    
    /**
     * Unregister a consumer (preview, MJPEG, RTSP)
     * Deactivates camera if this was the last consumer
     */
    private fun unregisterConsumer(type: ConsumerType) {
        synchronized(consumersLock) {
            consumers.remove(type)
            Log.d(TAG, "Unregistered consumer: $type, remaining consumers: ${consumers.size}")
            
            if (consumers.isEmpty()) {
                Log.d(TAG, "Last consumer unregistered, deactivating camera...")
                deactivateCameraForConsumers()
            }
        }
    }
    
    /**
     * Check if camera has any active consumers
     */
    fun hasConsumers(): Boolean {
        synchronized(consumersLock) {
            return consumers.isNotEmpty()
        }
    }
    
    /**
     * Get consumer count
     */
    fun getConsumerCount(): Int {
        synchronized(consumersLock) {
            return consumers.size
        }
    }
    
    /**
     * Activate camera when first consumer appears
     */
    private fun activateCameraForConsumers() {
        synchronized(cameraStateLock) {
            when (cameraState) {
                CameraState.IDLE -> {
                    Log.d(TAG, "Camera IDLE → INITIALIZING (on-demand activation)")
                    cameraState = CameraState.INITIALIZING
                    serviceScope.launch {
                        delay(CAMERA_ACTIVATION_DELAY_MS)
                        startCamera()
                    }
                }
                CameraState.ERROR -> {
                    Log.d(TAG, "Camera in ERROR state, retrying initialization...")
                    cameraState = CameraState.INITIALIZING
                    serviceScope.launch {
                        delay(CAMERA_ACTIVATION_DELAY_MS)
                        startCamera()
                    }
                }
                CameraState.INITIALIZING -> {
                    Log.d(TAG, "Camera already initializing, waiting...")
                }
                CameraState.ACTIVE -> {
                    Log.d(TAG, "Camera already active")
                }
                CameraState.STOPPING -> {
                    Log.d(TAG, "Camera stopping, will restart after stopping completes")
                    // Will be handled by the stopping process
                }
            }
        }
    }
    
    /**
     * Deactivate camera when last consumer disconnects
     * Double-checks consumer count for safety
     */
    private fun deactivateCameraForConsumers() {
        synchronized(cameraStateLock) {
            // Double-check that there are really no consumers
            // This prevents race conditions where a new consumer registered
            // between the check in unregisterConsumer and this call
            if (hasConsumers()) {
                Log.d(TAG, "Consumers still present (${getConsumerCount()}), skipping deactivation")
                return
            }
            
            when (cameraState) {
                CameraState.ACTIVE, CameraState.INITIALIZING -> {
                    Log.d(TAG, "Camera $cameraState → STOPPING (no consumers)")
                    cameraState = CameraState.STOPPING
                    serviceScope.launch {
                        stopCamera()
                    }
                }
                CameraState.IDLE -> {
                    Log.d(TAG, "Camera already idle")
                }
                CameraState.STOPPING -> {
                    Log.d(TAG, "Camera already stopping")
                }
                CameraState.ERROR -> {
                    // On ERROR state, mark as IDLE to allow re-initialization when next consumer appears
                    // This is safe because:
                    // 1. No consumers need the camera right now
                    // 2. Next consumer registration will trigger fresh initialization
                    // 3. ERROR state is preserved in logs for debugging
                    Log.d(TAG, "Camera in ERROR state with no consumers, resetting to IDLE for next activation attempt")
                    cameraState = CameraState.IDLE
                }
            }
        }
    }
    
    // Consumer registration methods for HttpServer interface
    override fun registerMjpegConsumer() {
        registerConsumer(ConsumerType.MJPEG)
    }
    
    override fun unregisterMjpegConsumer() {
        unregisterConsumer(ConsumerType.MJPEG)
    }
    
    override fun getCameraStateString(): String {
        synchronized(cameraStateLock) {
            return cameraState.name
        }
    }
    
    // Public methods for MainActivity to register/unregister preview consumer
    fun registerPreviewConsumer() {
        registerConsumer(ConsumerType.PREVIEW)
    }
    
    fun unregisterPreviewConsumer() {
        unregisterConsumer(ConsumerType.PREVIEW)
    }
    
    // Public methods for RTSP client connections to register/unregister consumers
    fun registerRtspConsumer() {
        Log.d(TAG, "RTSP client connected, registering consumer")
        registerConsumer(ConsumerType.RTSP)
    }
    
    fun unregisterRtspConsumer() {
        Log.d(TAG, "RTSP client disconnected, unregistering consumer")
        unregisterConsumer(ConsumerType.RTSP)
    }
    
    // Manual activation methods implementing interface (for testing/debugging)
    override fun manualActivateCamera() {
        Log.d(TAG, "Manual camera activation requested")
        registerConsumer(ConsumerType.MANUAL)
    }
    
    override fun manualDeactivateCamera() {
        Log.d(TAG, "Manual camera deactivation requested")
        unregisterConsumer(ConsumerType.MANUAL)
    }
}
