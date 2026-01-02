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
import android.graphics.Rect
import android.graphics.YuvImage
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

class CameraService : Service(), LifecycleOwner, CameraServiceInterface {
    private val binder = LocalBinder()
    @Volatile private var httpServer: HttpServer? = null
    private var currentCamera = CameraSelector.DEFAULT_BACK_CAMERA
    private var lastFrameBitmap: Bitmap? = null // For MainActivity preview only
    @Volatile private var lastFrameJpegBytes: ByteArray? = null // Pre-compressed for HTTP serving
    @Volatile private var lastFrameTimestamp: Long = 0
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    private val bitmapLock = Any() // Lock for bitmap access synchronization
    private val jpegLock = Any() // Lock for JPEG bytes access
    private var imageAnalysis: ImageAnalysis? = null
    private var selectedResolution: Size? = null
    private val resolutionCache = mutableMapOf<Int, List<Size>>()
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
    // Cache display metrics and battery info to avoid Binder calls from HTTP threads
    private var cachedDensity: Float = 0f
    private var cachedBatteryInfo: BatteryInfo = BatteryInfo(0, false)
    private var lastBatteryUpdate: Long = 0
    @Volatile private var batteryUpdatePending: Boolean = false
    // Battery threshold for wake lock management (20%)
    private val BATTERY_THRESHOLD_PERCENT = 20
    private lateinit var lifecycleRegistry: LifecycleRegistry
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
    private val fpsFrameTimes = mutableListOf<Long>() // Track frame times for FPS calculation
    private var currentFps: Float = 0f // Current calculated FPS
    private var lastFpsCalculation: Long = 0 // Last time FPS was calculated
    // Target FPS settings
    @Volatile private var targetMjpegFps: Int = 10 // Target FPS for MJPEG streaming (default 10)
    @Volatile private var targetRtspFps: Int = 30 // Target FPS for RTSP streaming (default 30)
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
    
    // Callbacks for MainActivity to receive updates
    private var onCameraStateChangedCallback: ((CameraSelector) -> Unit)? = null
    private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
    private var onConnectionsChangedCallback: (() -> Unit)? = null
    
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
        
        // Load saved settings
        loadSettings()
        
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
        
        createNotificationChannel()
        
        // Check for POST_NOTIFICATIONS permission on Android 13+
        // Note: startForeground() will still succeed even without permission on Android 13+,
        // but the notification won't be visible to the user. The service continues to run normally.
        if (!hasNotificationPermission()) {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Foreground service notification may not be visible on Android 13+")
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        acquireLocks()
        registerNetworkReceiver()
        setupOrientationListener()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        // Don't start server automatically in onCreate - wait for explicit start request
        startCamera()
        startWatchdog()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        
        // Check if we should start the server based on intent extra
        val shouldStartServer = intent?.getBooleanExtra(EXTRA_START_SERVER, false) ?: false
        if (shouldStartServer && httpServer?.isAlive() != true) {
            startServer()
        }
        
        if (cameraProvider == null) {
            startCamera()
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IP Camera Server")
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
            Log.e(TAG, "Camera permission not granted")
            return
        }
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindCamera() {
        // Ensure lifecycle is in correct state
        if (lifecycleRegistry.currentState != Lifecycle.State.STARTED) {
            Log.w(TAG, "Cannot bind camera - lifecycle not in STARTED state: ${lifecycleRegistry.currentState}")
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
        }
        
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        
        // Use ResolutionSelector instead of deprecated setTargetResolution
        selectedResolution?.let { resolution ->
            Log.d(TAG, "Attempting to bind camera with resolution: ${resolution.width}x${resolution.height}")
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
            builder.setResolutionSelector(resolutionSelector)
        }
        
        imageAnalysis = builder.build()
            .also {
                it.setAnalyzer(cameraExecutor) { image ->
                    processImage(image)
                }
            }
        
        try {
            Log.d(TAG, "Unbinding all use cases before rebinding...")
            cameraProvider?.unbindAll()
            
            Log.d(TAG, "Binding camera to lifecycle...")
            camera = cameraProvider?.bindToLifecycle(this, currentCamera, imageAnalysis)
            
            if (camera == null) {
                Log.e(TAG, "Camera binding returned null!")
                return
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
            
            // Notify observers that camera state has changed (binding completed)
            onCameraStateChangedCallback?.invoke(currentCamera)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed with exception", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Properly stop camera activities before applying new settings.
     * This ensures clean state transition and prevents resource conflicts.
     */
    private fun stopCamera() {
        try {
            // Clear old analyzer to stop frame processing
            imageAnalysis?.clearAnalyzer()
            
            // Unbind all use cases from lifecycle
            cameraProvider?.unbindAll()
            
            // Clear camera reference
            camera = null
            
            Log.d(TAG, "Camera stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }
    
    /**
     * Stop camera, apply settings, and restart camera.
     * This ensures settings are properly applied without conflicts.
     * Uses proper async handling to avoid blocking the main thread.
     * Prevents overlapping bind operations.
     * 
     * PRIVATE: Only CameraService methods should trigger rebinding.
     * External callers should use methods like switchCamera() or setResolutionAndRebind()
     * that encapsulate both the setting change and rebinding.
     */
    private fun requestBindCamera() {
        // Check if a binding operation is already in progress
        synchronized(bindingLock) {
            if (isBindingInProgress) {
                Log.d(TAG, "requestBindCamera() ignored - binding already in progress")
                return
            }
            isBindingInProgress = true
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
                        // Clear the flag after binding completes or fails
                        synchronized(bindingLock) {
                            isBindingInProgress = false
                            Log.d(TAG, "isBindingInProgress flag cleared")
                        }
                    }
                }, 100)
            } catch (e: Exception) {
                Log.e(TAG, "Error in requestBindCamera()", e)
                // Clear flag if we fail before even scheduling the delayed binding
                synchronized(bindingLock) {
                    isBindingInProgress = false
                }
            }
        }
    }
    
    private fun processImage(image: ImageProxy) {
        val processingStart = System.currentTimeMillis()
        
        try {
            // Track FPS: add current time to frame times list
            synchronized(fpsFrameTimes) {
                fpsFrameTimes.add(processingStart)
                // Keep only the last 2 seconds of frame times
                val cutoffTime = processingStart - 2000
                fpsFrameTimes.removeAll { it < cutoffTime }
                
                // Calculate FPS every 500ms
                if (processingStart - lastFpsCalculation > 500) {
                    if (fpsFrameTimes.size > 1) {
                        val timeSpan = fpsFrameTimes.last() - fpsFrameTimes.first()
                        currentFps = if (timeSpan > 0) {
                            (fpsFrameTimes.size - 1) * 1000f / timeSpan
                        } else {
                            0f
                        }
                    }
                    lastFpsCalculation = processingStart
                }
            }
            
            // DUAL-STREAM ARCHITECTURE:
            // 1. MJPEG Pipeline: bitmap → annotation → JPEG compression (always active)
            // 2. RTSP Pipeline: raw YUV → H.264 encoding (optional, bandwidth-efficient)
            
            // === RTSP Pipeline (if enabled) ===
            // Feed raw YUV to RTSP encoder BEFORE bitmap conversion for efficiency
            // OPTIMIZATION: Both dequeueInputBuffer and dequeueOutputBuffer use 0ms timeout (non-blocking)
            // and drainEncoder processes max 3 buffers per call to minimize camera thread blocking.
            // This ensures RTSP encoding doesn't reduce MJPEG FPS.
            if (rtspEnabled && rtspServer != null) {
                try {
                    rtspServer?.encodeFrame(image)
                } catch (e: Exception) {
                    Log.e(TAG, "RTSP encoding failed", e)
                    // Continue with MJPEG even if RTSP fails
                }
            }
            
            // === MJPEG Pipeline (always active) ===
            val bitmap = imageProxyToBitmap(image)
            // Reduce logging frequency - only log every 30 frames (about 3 seconds at 10fps)
            val frameCount = lastFrameTimestamp.toInt() % 30
            if (frameCount == 0) {
                Log.d(TAG, "Processing frame - ImageProxy size: ${image.width}x${image.height}, Bitmap size: ${bitmap.width}x${bitmap.height}, FPS: ${"%.1f".format(currentFps)}")
            }
            
            // Apply camera orientation and rotation without creating squared bitmaps
            val finalBitmap = applyRotationCorrectly(bitmap)
            if (frameCount == 0) {
                Log.d(TAG, "After rotation - Bitmap size: ${finalBitmap.width}x${finalBitmap.height}, Total rotation: ${(when (cameraOrientation) { "portrait" -> 90; else -> 0 } + rotation) % 360}°")
            }
            
            // Annotate bitmap here on camera executor thread to avoid Canvas/Paint operations in HTTP threads
            val annotatedBitmap = annotateBitmap(finalBitmap)
            
            // Determine JPEG quality - use adaptive quality if enabled, otherwise use default
            val jpegQuality = if (adaptiveQualityEnabled) {
                // Use default client (0L) for non-client-specific compression
                // Client-specific quality will be applied during streaming
                val settings = adaptiveQualityManager.getClientSettings(0L)
                settings.jpegQuality
            } else {
                JPEG_QUALITY_STREAM
            }
            
            // Pre-compress to JPEG for HTTP serving (avoid Bitmap.compress on HTTP threads)
            val encodingStart = System.currentTimeMillis()
            val jpegBytes = ByteArrayOutputStream().use { stream ->
                annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
                stream.toByteArray()
            }
            val encodingTime = System.currentTimeMillis() - encodingStart
            
            // Track encoding performance
            performanceMetrics.recordFrameEncodingTime(encodingTime)
            
            // Update both bitmap (for MainActivity) and JPEG bytes (for HTTP serving)
            synchronized(bitmapLock) {
                val oldBitmap = lastFrameBitmap
                lastFrameBitmap = annotatedBitmap
                // Recycle old bitmap after updating reference, checking if not already recycled
                oldBitmap?.takeIf { !it.isRecycled }?.recycle()
            }
            
            synchronized(jpegLock) {
                lastFrameJpegBytes = jpegBytes
                lastFrameTimestamp = System.currentTimeMillis()
            }
            
            // Track frame processing time
            val processingTime = System.currentTimeMillis() - processingStart
            performanceMetrics.recordFrameProcessingTime(processingTime)
            
            // Notify MainActivity if it's listening - create a copy to avoid recycling issues
            val safeConfig = annotatedBitmap.config ?: Bitmap.Config.ARGB_8888
            onFrameAvailableCallback?.invoke(annotatedBitmap.copy(safeConfig, false))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            performanceMetrics.recordFrameDropped()
        } finally {
            image.close()
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
            // Create bitmap with exact dimensions needed for rotated image
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating bitmap", e)
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
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Error applying camera orientation", e)
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
            // Only recycle the original if a new bitmap was created
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating bitmap", e)
            bitmap
        }
    }
    
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        // Lower compression quality to reduce memory pressure and prevent SIGABRT
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY_CAMERA, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    
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
        requestBindCamera()
    }
    
    /**
     * Check if the current camera has a flash unit using Camera2 API
     */
    private fun checkFlashAvailability() {
        hasFlashUnit = false
        
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (currentCamera == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    hasFlashUnit = available == true
                    Log.d(TAG, "Flash available for camera $id (facing=$targetFacing): $hasFlashUnit")
                    return
                }
            }
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
        onCameraStateChangedCallback?.invoke(currentCamera)
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
    }
    
    /**
     * Set resolution and trigger camera rebinding.
     * This is the recommended way for external callers (MainActivity, HTTP endpoints)
     * to change resolution, as it encapsulates both the setting change and rebinding.
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
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    fun getCameraOrientation(): String = cameraOrientation
    
    override fun setRotation(rot: Int) {
        rotation = rot
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of rotation change
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    fun getRotation(): Int = rotation
    
    override fun setShowResolutionOverlay(show: Boolean) {
        showResolutionOverlay = show
        saveSettings()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of overlay setting change
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    fun getShowResolutionOverlay(): Boolean = showResolutionOverlay
    
    // OSD overlay settings
    override fun setShowDateTimeOverlay(show: Boolean) {
        showDateTimeOverlay = show
        saveSettings()
        broadcastCameraState()
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getShowDateTimeOverlay(): Boolean = showDateTimeOverlay
    
    override fun setShowBatteryOverlay(show: Boolean) {
        showBatteryOverlay = show
        saveSettings()
        broadcastCameraState()
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getShowBatteryOverlay(): Boolean = showBatteryOverlay
    
    override fun setShowFpsOverlay(show: Boolean) {
        showFpsOverlay = show
        saveSettings()
        broadcastCameraState()
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getShowFpsOverlay(): Boolean = showFpsOverlay
    
    // FPS settings
    override fun setTargetMjpegFps(fps: Int) {
        targetMjpegFps = fps.coerceIn(1, 60)
        saveSettings()
        broadcastCameraState()
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getTargetMjpegFps(): Int = targetMjpegFps
    
    override fun setTargetRtspFps(fps: Int) {
        targetRtspFps = fps.coerceIn(1, 60)
        saveSettings()
        broadcastCameraState()
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getTargetRtspFps(): Int = targetRtspFps
    
    override fun getCurrentFps(): Float = currentFps
    
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
        
        // Load RTSP settings (with persistence)
        rtspEnabled = prefs.getBoolean("rtspEnabled", false)
        rtspBitrate = prefs.getInt("rtspBitrate", -1)
        rtspBitrateMode = prefs.getString("rtspBitrateMode", "VBR") ?: "VBR"
        
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
        
        Log.d(TAG, "Loaded settings: camera=$cameraType, orientation=$cameraOrientation, rotation=$rotation, resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}, maxConnections=$maxConnections, flashlight=$isFlashlightOn, mjpegFps=$targetMjpegFps, rtspFps=$targetRtspFps, rtspBitrate=$rtspBitrate, rtspBitrateMode=$rtspBitrateMode, adaptiveQuality=$adaptiveQualityEnabled")
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
            
            // Save RTSP settings (with persistence for bitrate and mode)
            putBoolean("rtspEnabled", rtspEnabled)
            // Save current RTSP metrics if server is running, otherwise save stored values
            if (rtspServer != null) {
                rtspServer?.getMetrics()?.let { metrics ->
                    rtspBitrate = (metrics.bitrateMbps * 1_000_000).toInt()
                    rtspBitrateMode = metrics.bitrateMode
                }
            }
            putInt("rtspBitrate", rtspBitrate)
            putString("rtspBitrateMode", rtspBitrateMode)
            
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
    
    fun clearCallbacks() {
        onCameraStateChangedCallback = null
        onFrameAvailableCallback = null
        onConnectionsChangedCallback = null
    }
    
    override fun getActiveConnectionsCount(): Int {
        // Return the count of connections we can actually track
        return activeStreams.get() + synchronized(sseClientsLock) { sseClients.size }
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
            onCameraStateChangedCallback?.invoke(currentCamera)
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
        onConnectionsChangedCallback?.invoke()
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
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        unregisterNetworkReceiver()
        orientationEventListener?.disable()
        httpServer?.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        streamingExecutor.shutdownNow() // Forcefully terminate streaming threads
        synchronized(bitmapLock) {
            lastFrameBitmap?.takeIf { !it.isRecycled }?.recycle()
            lastFrameBitmap = null
        }
        synchronized(jpegLock) {
            lastFrameJpegBytes = null
        }
        serviceScope.cancel()
        releaseLocks() // Use the new releaseLocks() method
    }
    
    /**
     * Acquire wake locks for optimal streaming performance.
     * Only acquires locks when battery level is above threshold (20%) to prevent
     * excessive battery drain when battery is low.
     * 
     * PARTIAL_WAKE_LOCK: Keeps CPU running for camera processing and streaming
     * WIFI_MODE_FULL_HIGH_PERF: Prevents WiFi from entering power save mode for consistent streaming
     */
    private fun acquireLocks() {
        // Check battery level before acquiring locks
        val batteryInfo = getBatteryInfo()
        val batteryLevel = batteryInfo.level
        val isCharging = batteryInfo.isCharging
        
        // Only acquire locks if battery > 20% OR device is charging
        val shouldHoldLocks = batteryLevel > BATTERY_THRESHOLD_PERCENT || isCharging
        
        if (shouldHoldLocks) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$TAG:WakeLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "Wake lock acquired (battery: $batteryLevel%, charging: $isCharging)")
                }
            }
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock?.isHeld != true) {
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$TAG:WifiLock"
                ).apply {
                    acquire()
                    Log.i(TAG, "WiFi lock acquired (battery: $batteryLevel%, charging: $isCharging)")
                }
            }
        } else {
            // Release locks when battery is low and not charging
            releaseLocks()
            Log.w(TAG, "Wake locks not acquired due to low battery (battery: $batteryLevel%, threshold: $BATTERY_THRESHOLD_PERCENT%)")
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
                
                // Check server health - only restart if it wasn't intentionally stopped
                if (!serverIntentionallyStopped && httpServer?.isAlive() != true) {
                    Log.w(TAG, "Watchdog: Server not alive, restarting...")
                    startServer()
                    needsRecovery = true
                }
                
                // Check camera health
                val frameAge = System.currentTimeMillis() - lastFrameTimestamp
                if (frameAge > FRAME_STALE_THRESHOLD_MS) {
                    Log.w(TAG, "Watchdog: Frame stale (${frameAge}ms), restarting camera...")
                    if (cameraProvider == null) {
                        startCamera()
                    } else {
                        requestBindCamera()
                    }
                    needsRecovery = true
                }
                
                // Check battery level and manage wake locks accordingly
                // Check every watchdog cycle to respond quickly to battery changes
                val batteryInfo = getBatteryInfo()
                val batteryLevel = batteryInfo.level
                val isCharging = batteryInfo.isCharging
                val shouldHoldLocks = batteryLevel > BATTERY_THRESHOLD_PERCENT || isCharging
                val areLocksHeld = wakeLock?.isHeld == true && wifiLock?.isHeld == true
                
                if (shouldHoldLocks && !areLocksHeld) {
                    // Battery recovered or device started charging - acquire locks
                    Log.i(TAG, "Watchdog: Battery level acceptable ($batteryLevel%), acquiring wake locks")
                    acquireLocks()
                } else if (!shouldHoldLocks && areLocksHeld) {
                    // Battery dropped below threshold and not charging - release locks
                    Log.w(TAG, "Watchdog: Battery low ($batteryLevel%) and not charging, releasing wake locks to conserve power")
                    releaseLocks()
                }
                
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
    
    private fun annotateBitmap(source: Bitmap): Bitmap {
        // Safety check: return source if already recycled
        if (source.isRecycled) {
            Log.w(TAG, "annotateBitmap called with recycled bitmap")
            return source
        }
        
        val mutable = try {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, true) ?: return source
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy bitmap for annotation", e)
            return source
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
            "%.1f fps".format(currentFps)
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
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        resolutionCache[targetFacing]?.let { return it }
        val sizes = cameraManager.cameraIdList.mapNotNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing != targetFacing) return@mapNotNull null
            val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            config?.getOutputSizes(ImageFormat.YUV_420_888)?.toList()
        }.flatten()
        
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
        return synchronized(jpegLock) { 
            lastFrameJpegBytes
        }
    }
    
    override fun getSelectedResolutionLabel(): String {
        return selectedResolution?.let { sizeLabel(it) } ?: "auto"
    }
    
    override fun getCameraStateJson(): String {
        val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        val resolutionLabel = selectedResolution?.let { sizeLabel(it) } ?: "auto"
        
        return """
        {
            "camera": "$cameraName",
            "resolution": "$resolutionLabel",
            "cameraOrientation": "$cameraOrientation",
            "rotation": $rotation,
            "showDateTimeOverlay": $showDateTimeOverlay,
            "showBatteryOverlay": $showBatteryOverlay,
            "showResolutionOverlay": $showResolutionOverlay,
            "showFpsOverlay": $showFpsOverlay,
            "currentFps": $currentFps,
            "targetMjpegFps": $targetMjpegFps,
            "targetRtspFps": $targetRtspFps,
            "adaptiveQualityEnabled": $adaptiveQualityEnabled,
            "flashlightAvailable": ${isFlashlightAvailable()},
            "flashlightOn": ${isFlashlightEnabled()}
        }
        """.trimIndent()
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
                initialBitrate = bitrateToUse
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
            saveSettings()
            Log.i(TAG, "RTSP streaming enabled on port 8554 (fps=$targetRtspFps, bitrate=$bitrateToUse, mode=$rtspBitrateMode)")
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
            rtspServer?.stop()
            rtspServer = null
            saveSettings()
            Log.i(TAG, "RTSP streaming disabled")
            
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
}
