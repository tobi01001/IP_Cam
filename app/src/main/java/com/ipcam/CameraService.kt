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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
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
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var cameraOrientation: String = "landscape" // "portrait" or "landscape" - base camera recording mode
    private var rotation: Int = 0 // 0, 90, 180, 270 - applied to the camera-oriented image
    private var deviceOrientation: Int = 0 // Current device orientation (for app UI only, not camera)
    private var orientationEventListener: OrientationEventListener? = null
    private var showResolutionOverlay: Boolean = true // Show actual resolution in overlay for debugging
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
    
    // Streaming mode: MJPEG or MP4
    @Volatile private var streamingMode: StreamingMode = StreamingMode.MJPEG
    private var mp4StreamWriter: Mp4StreamWriter? = null
    private val mp4StreamLock = Any() // Lock for MP4 stream access
    
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
         private const val PREF_STREAMING_MODE = "streamingMode"
         // MP4 encoder settings
         private const val MP4_ENCODER_BITRATE = 2_000_000 // 2 Mbps default
         private const val MP4_ENCODER_FRAME_RATE = 30 // 30 fps
         private const val MP4_ENCODER_I_FRAME_INTERVAL = 2 // I-frame every 2 seconds
         private const val MP4_ENCODER_PROCESSING_INTERVAL_MS = 10L // Process encoder output every 10ms
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
        
        try {
            Log.d(TAG, "Unbinding all use cases before rebinding...")
            cameraProvider?.unbindAll()
            
            // Determine resolution for encoder
            val targetResolution = selectedResolution ?: Size(1920, 1080)
            
            when (streamingMode) {
                StreamingMode.MJPEG -> {
                    Log.d(TAG, "Binding camera for MJPEG streaming mode")
                    bindCameraForMjpeg(targetResolution)
                }
                StreamingMode.MP4 -> {
                    Log.d(TAG, "Binding camera for MP4 streaming mode")
                    bindCameraForMp4(targetResolution)
                }
            }
            
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
            
            Log.d(TAG, "Camera bound successfully to ${if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"} camera in $streamingMode mode. Frame processing should resume.")
            
            // Notify observers that camera state has changed (binding completed)
            onCameraStateChangedCallback?.invoke(currentCamera)
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed with exception", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Bind camera for MJPEG streaming using ImageAnalysis
     */
    private fun bindCameraForMjpeg(targetResolution: Size) {
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        
        // Use ResolutionSelector instead of deprecated setTargetResolution
        Log.d(TAG, "Attempting to bind camera with resolution: ${targetResolution.width}x${targetResolution.height}")
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionFilter { supportedSizes, _ ->
                // Try to find exact match first
                val exactMatch = supportedSizes.filter { size ->
                    size.width == targetResolution.width && size.height == targetResolution.height
                }
                
                if (exactMatch.isNotEmpty()) {
                    Log.d(TAG, "Found exact resolution match: ${targetResolution.width}x${targetResolution.height}")
                    exactMatch
                } else {
                    // No exact match - find closest by total pixels
                    val targetPixels = targetResolution.width * targetResolution.height
                    val targetAspectRatio = targetResolution.width.toFloat() / targetResolution.height.toFloat()
                    
                    val closest = supportedSizes.minByOrNull { size ->
                        val pixels = size.width * size.height
                        val aspectRatio = size.width.toFloat() / size.height.toFloat()
                        val pixelDiff = Math.abs(pixels - targetPixels)
                        val aspectDiff = Math.abs(aspectRatio - targetAspectRatio) * 1000000 // Weight aspect ratio heavily
                        pixelDiff + aspectDiff.toInt()
                    }
                    
                    if (closest != null) {
                        Log.w(TAG, "Exact resolution ${targetResolution.width}x${targetResolution.height} not available. Using closest: ${closest.width}x${closest.height}")
                    } else {
                        Log.e(TAG, "Could not find any suitable resolution. Using default.")
                    }
                    
                    // Return closest match or all supported if none found
                    closest?.let { listOf(it) } ?: supportedSizes
                }
            }
            .build()
        builder.setResolutionSelector(resolutionSelector)
        
        imageAnalysis = builder.build()
            .also {
                it.setAnalyzer(cameraExecutor) { image ->
                    processImage(image)
                }
            }
        
        Log.d(TAG, "Binding camera to lifecycle with ImageAnalysis for MJPEG...")
        camera = cameraProvider?.bindToLifecycle(this, currentCamera, imageAnalysis)
    }
    
    /**
     * Bind camera for MP4 streaming using Preview with MediaCodec surface
     * Initializes encoder on background thread to avoid ANR
     */
    private fun bindCameraForMp4(targetResolution: Size) {
        Log.d(TAG, "[MP4] Starting MP4 camera binding process for resolution ${targetResolution.width}x${targetResolution.height}")
        
        // Initialize encoder on background thread to avoid blocking main thread (ANR)
        serviceScope.launch(Dispatchers.IO) {
            var encoder: Mp4StreamWriter? = null
            try {
                Log.d(TAG, "[MP4] Initializing MP4 encoder on background thread...")
                
                // Create and initialize encoder (can take 100-500ms)
                encoder = Mp4StreamWriter(
                    resolution = targetResolution,
                    bitrate = MP4_ENCODER_BITRATE,
                    frameRate = MP4_ENCODER_FRAME_RATE,
                    iFrameInterval = MP4_ENCODER_I_FRAME_INTERVAL
                )
                
                Log.d(TAG, "[MP4] Calling encoder.initialize()...")
                encoder.initialize()
                
                Log.d(TAG, "[MP4] Calling encoder.start()...")
                encoder.start()
                
                // Store encoder reference
                synchronized(mp4StreamLock) {
                    mp4StreamWriter = encoder
                }
                
                Log.d(TAG, "[MP4] Encoder initialized and started successfully. Surface available: ${encoder.getInputSurface() != null}")
                
                // Switch to main thread for camera binding (CameraX requirement)
                withContext(Dispatchers.Main) {
                    try {
                        // Double-check we're still in MP4 mode
                        if (streamingMode != StreamingMode.MP4) {
                            Log.w(TAG, "[MP4] Mode changed during init, aborting MP4 binding")
                            synchronized(mp4StreamLock) {
                                encoder?.stop()
                                encoder?.release()
                                mp4StreamWriter = null
                            }
                            return@withContext
                        }
                        
                        Log.d(TAG, "[MP4] Creating Preview use case...")
                        // Create Preview use case with encoder's input surface
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider { request ->
                            val surface = synchronized(mp4StreamLock) { mp4StreamWriter?.getInputSurface() }
                            if (surface != null) {
                                Log.d(TAG, "[MP4] Providing encoder input surface to camera preview")
                                request.provideSurface(surface, cameraExecutor) { 
                                    Log.d(TAG, "[MP4] Surface provision result received")
                                }
                            } else {
                                Log.e(TAG, "[MP4] FATAL: Encoder input surface is null!")
                            }
                        }
                        
                        Log.d(TAG, "[MP4] Creating ImageAnalysis for app preview...")
                        // Create ImageAnalysis for app preview
                        val builder = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        
                        val previewResolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionFilter { supportedSizes, _ ->
                                val maxPreviewPixels = 1280 * 720
                                val suitable = supportedSizes.filter { size ->
                                    val pixels = size.width * size.height
                                    pixels <= maxPreviewPixels
                                }.sortedByDescending { it.width * it.height }
                                
                                if (suitable.isNotEmpty()) {
                                    Log.d(TAG, "[MP4] App preview resolution: ${suitable.first().width}x${suitable.first().height}")
                                    listOf(suitable.first())
                                } else {
                                    val smallest = supportedSizes.minByOrNull { it.width * it.height }
                                    smallest?.let { listOf(it) } ?: supportedSizes
                                }
                            }
                            .build()
                        builder.setResolutionSelector(previewResolutionSelector)
                        
                        imageAnalysis = builder.build()
                            .also {
                                it.setAnalyzer(cameraExecutor) { image ->
                                    processImageForPreviewOnly(image)
                                }
                            }
                        
                        Log.d(TAG, "[MP4] Binding camera to lifecycle...")
                        // Bind both Preview (for encoding) and ImageAnalysis (for app preview)
                        camera = cameraProvider?.bindToLifecycle(this@CameraService, currentCamera, preview, imageAnalysis)
                        
                        if (camera != null) {
                            Log.d(TAG, "[MP4] Camera bound successfully!")
                            
                            // Start processing encoder output
                            startMp4EncoderProcessing()
                            
                            Log.d(TAG, "[MP4] MP4 mode initialization complete")
                        } else {
                            Log.e(TAG, "[MP4] FATAL: Camera binding returned null!")
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "[MP4] FATAL: Failed to bind camera", e)
                        e.printStackTrace()
                        // Fallback to MJPEG
                        streamingMode = StreamingMode.MJPEG
                        saveSettings()
                        synchronized(mp4StreamLock) {
                            encoder?.stop()
                            encoder?.release()
                            mp4StreamWriter = null
                        }
                        bindCameraForMjpeg(targetResolution)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[MP4] FATAL: Failed to initialize encoder", e)
                e.printStackTrace()
                // Fallback to MJPEG
                withContext(Dispatchers.Main) {
                    streamingMode = StreamingMode.MJPEG
                    saveSettings()
                    bindCameraForMjpeg(targetResolution)
                }
            }
        }
    }
    
    /**
     * Lightweight image processing for app preview only (MP4 mode)
     * Does NOT do JPEG compression or heavy processing - just updates the preview bitmap
     */
    private fun processImageForPreviewOnly(image: ImageProxy) {
        // Log first few frames to confirm processing is working
        val timestamp = System.currentTimeMillis()
        if (timestamp % 3000 < 100) {  // Log approximately every 3 seconds
            Log.d(TAG, "[MP4] Processing preview frame: ${image.width}x${image.height}, format=${image.format}")
        }
        
        try {
            // Simple conversion to bitmap for preview
            val bitmap = imageProxyToBitmap(image)
            
            // Apply rotation for preview
            val finalBitmap = applyRotationCorrectly(bitmap)
            
            // Simple annotation (just timestamp, no heavy processing)
            val annotatedBitmap = annotateBitmapLightweight(finalBitmap)
            
            // Update preview callback for MainActivity (no JPEG compression needed)
            val safeConfig = annotatedBitmap.config ?: Bitmap.Config.ARGB_8888
            onFrameAvailableCallback?.invoke(annotatedBitmap.copy(safeConfig, false))
            
            // Clean up
            if (bitmap != finalBitmap) {
                bitmap.recycle()
            }
            if (finalBitmap != annotatedBitmap) {
                finalBitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "[MP4] Error in lightweight preview processing", e)
        } finally {
            image.close()
        }
    }
    
    /**
     * Lightweight bitmap annotation for preview (MP4 mode)
     * Only adds minimal overlays, no heavy processing
     */
    private fun annotateBitmapLightweight(bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(bitmap.config, true)
        val canvas = Canvas(mutableBitmap)
        
        val paint = Paint().apply {
            textSize = 30f
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(3f, 2f, 2f, Color.BLACK)
        }
        
        // Only add timestamp (skip battery/resolution overlays to save CPU)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        canvas.drawText(timestamp, 10f, 40f, paint)
        
        return mutableBitmap
    }
    
    /**
     * Start background coroutine to process MP4 encoder output
     * Runs continuously to maintain a buffer of encoded frames
     */
    private fun startMp4EncoderProcessing() {
        serviceScope.launch {
            Log.d(TAG, "[MP4] Started MP4 encoder processing coroutine")
            var frameCount = 0
            var lastLogTime = System.currentTimeMillis()
            
            try {
                while (isActive && mp4StreamWriter?.isRunning() == true) {
                    mp4StreamWriter?.processEncoderOutput()
                    
                    // Check if frames are being produced
                    if (mp4StreamWriter?.hasEncodedFrames() == true) {
                        frameCount++
                        // Log every 30 frames (about every 3 seconds at 10fps)
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 3000) {
                            Log.d(TAG, "[MP4] Encoder producing frames: $frameCount frames total, queue has frames: ${mp4StreamWriter?.hasEncodedFrames()}")
                            lastLogTime = now
                        }
                    }
                    
                    delay(MP4_ENCODER_PROCESSING_INTERVAL_MS)
                }
                Log.d(TAG, "[MP4] Encoder processing loop ended. isActive=$isActive, encoder running=${mp4StreamWriter?.isRunning()}")
            } catch (e: Exception) {
                Log.e(TAG, "[MP4] FATAL: Error in MP4 encoder processing", e)
                e.printStackTrace()
            } finally {
                Log.d(TAG, "[MP4] MP4 encoder processing coroutine ended. Total frames processed: $frameCount")
            }
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
            
            // Stop and release MP4 encoder if active - do async to avoid blocking main thread
            val encoderToStop = synchronized(mp4StreamLock) { mp4StreamWriter }
            if (encoderToStop != null) {
                Log.d(TAG, "Stopping MP4 encoder (async)...")
                // Stop on background thread to avoid blocking
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        encoderToStop.stop()
                        // Small delay to let processing coroutine exit
                        delay(100)
                        encoderToStop.release()
                        Log.d(TAG, "MP4 encoder stopped and released")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping MP4 encoder", e)
                    }
                }
                // Clear reference immediately
                synchronized(mp4StreamLock) {
                    mp4StreamWriter = null
                }
            }
            
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
        
        // Run camera operations on main thread, but use coroutine with delay to avoid blocking UI
        serviceScope.launch(Dispatchers.Main) {
            try {
                // Stop camera first to ensure clean state
                // Must be on main thread for CameraX operations
                Log.d(TAG, "Stopping camera before rebinding...")
                stopCamera()
                
                // Brief delay to ensure resources are released
                // This delay is non-blocking (coroutine suspends, doesn't block thread)
                delay(100)
                
                // Rebind camera (already on main thread)
                try {
                    Log.d(TAG, "Delay complete, rebinding camera now...")
                    bindCamera()
                    // Callback is now invoked directly in bindCamera() after binding succeeds
                } catch (e: Exception) {
                    Log.e(TAG, "Error in bindCamera() from coroutine", e)
                } finally {
                    // Clear the flag after binding completes or fails
                    synchronized(bindingLock) {
                        isBindingInProgress = false
                        Log.d(TAG, "isBindingInProgress flag cleared")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in requestBindCamera()", e)
                // Clear flag if we fail before binding
                synchronized(bindingLock) {
                    isBindingInProgress = false
                }
            }
        }
    }
    
    private fun processImage(image: ImageProxy) {
        val processingStart = System.currentTimeMillis()
        
        try {
            val bitmap = imageProxyToBitmap(image)
            // Reduce logging frequency - only log every 30 frames (about 3 seconds at 10fps)
            val frameCount = lastFrameTimestamp.toInt() % 30
            if (frameCount == 0) {
                Log.d(TAG, "Processing frame - ImageProxy size: ${image.width}x${image.height}, Bitmap size: ${bitmap.width}x${bitmap.height}")
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
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        cameraOrientation = prefs.getString("cameraOrientation", "landscape") ?: "landscape"
        rotation = prefs.getInt("rotation", 0)
        showResolutionOverlay = prefs.getBoolean("showResolutionOverlay", true)
        maxConnections = prefs.getInt(PREF_MAX_CONNECTIONS, HTTP_DEFAULT_MAX_POOL_SIZE)
            .coerceIn(HTTP_MIN_MAX_POOL_SIZE, HTTP_ABSOLUTE_MAX_POOL_SIZE)
        isFlashlightOn = prefs.getBoolean("flashlightOn", false)
        
        // Load streaming mode
        val streamingModeStr = prefs.getString(PREF_STREAMING_MODE, "mjpeg")
        streamingMode = StreamingMode.fromString(streamingModeStr)
        
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
        
        Log.d(TAG, "Loaded settings: camera=$cameraType, orientation=$cameraOrientation, rotation=$rotation, resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}, maxConnections=$maxConnections, flashlight=$isFlashlightOn, streamingMode=$streamingMode")
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("cameraOrientation", cameraOrientation)
            putInt("rotation", rotation)
            putBoolean("showResolutionOverlay", showResolutionOverlay)
            putInt(PREF_MAX_CONNECTIONS, maxConnections)
            putBoolean("flashlightOn", isFlashlightOn)
            
            // Save streaming mode
            putString(PREF_STREAMING_MODE, streamingMode.toString())
            
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }
    
    private fun acquireLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$TAG:WakeLock"
            ).apply {
                acquire()
            }
        }
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock?.isHeld != true) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$TAG:WifiLock"
            ).apply {
                acquire()
            }
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
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66000000")
            style = Paint.Style.FILL
        }
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        // Use cached battery info to avoid Binder calls from HTTP threads
        val batteryInfo = getCachedBatteryInfo()
        val batteryText = buildString {
            append(batteryInfo.level)
            append("%")
            if (batteryInfo.isCharging) append(" ⚡")
        }
        
        fun drawLabel(text: String, alignRight: Boolean, topOffset: Float) {
            val textWidth = textPaint.measureText(text)
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val left = if (alignRight) {
                canvas.width - padding - textWidth - padding
            } else {
                padding
            }
            val top = topOffset
            val right = if (alignRight) canvas.width - padding else padding + textWidth + padding
            val bottom = top + textHeight + padding

            canvas.drawText(text, left + padding, bottom - padding - textPaint.fontMetrics.bottom, textPaint)
        }
        
        // Calculate widths to check for overlap
        val timeWidth = textPaint.measureText(timeText) + padding * 3
        val batteryWidth = textPaint.measureText(batteryText) + padding * 3
        val availableWidth = canvas.width.toFloat()
        
        // Check if labels would overlap
        val wouldOverlap = (timeWidth + batteryWidth) > availableWidth
        
        if (wouldOverlap) {
            // Stack vertically if they would overlap
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            drawLabel(timeText, alignRight = false, topOffset = padding)
            drawLabel(batteryText, alignRight = true, topOffset = padding + textHeight + padding * 2)
        } else {
            // Draw side by side
            drawLabel(timeText, alignRight = false, topOffset = padding)
            drawLabel(batteryText, alignRight = true, topOffset = padding)
        }
        
        // Add resolution overlay in bottom right for debugging
        if (showResolutionOverlay) {
            val resolutionText = "${source.width}x${source.height}"
            val textHeight = textPaint.fontMetrics.bottom - textPaint.fontMetrics.top
            val textWidth = textPaint.measureText(resolutionText)
            val left = canvas.width - padding - textWidth - padding
            val bottom = canvas.height.toFloat() - padding
            val top = bottom - textHeight - padding
            val right = canvas.width.toFloat() - padding
            canvas.drawText(resolutionText, left + padding, bottom - padding - textPaint.fontMetrics.bottom, textPaint)
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
            "showResolutionOverlay": $showResolutionOverlay,
            "flashlightAvailable": ${isFlashlightAvailable()},
            "flashlightOn": ${isFlashlightEnabled()},
            "streamingMode": "$streamingMode"
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
        Log.d(TAG, "Adaptive quality ${if (enabled) "enabled" else "disabled"} via HTTP")
    }
    
    override fun getStreamingMode(): StreamingMode = streamingMode
    
    override fun setStreamingMode(mode: StreamingMode) {
        if (streamingMode == mode) {
            Log.d(TAG, "Streaming mode already set to $mode")
            return
        }
        
        Log.d(TAG, "Changing streaming mode from $streamingMode to $mode")
        streamingMode = mode
        saveSettings()
        
        // Rebind camera to apply new streaming mode
        // This will stop the old encoder and start the new one
        requestBindCamera()
        
        // Broadcast state change to web clients
        broadcastCameraState()
        
        // Notify MainActivity of mode change
        onCameraStateChangedCallback?.invoke(currentCamera)
    }
    
    override fun getMp4EncodedFrame(): Mp4StreamWriter.EncodedFrame? {
        synchronized(mp4StreamLock) {
            return mp4StreamWriter?.getNextEncodedFrame()
        }
    }
    
    override fun getMp4InitSegment(): ByteArray? {
        synchronized(mp4StreamLock) {
            return mp4StreamWriter?.generateInitSegment()
        }
    }
    
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
