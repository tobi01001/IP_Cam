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
import fi.iki.elonen.NanoHTTPD
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

class CameraService : Service(), LifecycleOwner {
    private val binder = LocalBinder()
    @Volatile private var httpServer: CameraHttpServer? = null
    @Volatile private var asyncRunner: BoundedAsyncRunner? = null
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
    // Flashlight control
    @Volatile private var camera: androidx.camera.core.Camera? = null
    @Volatile private var isFlashlightOn: Boolean = false
    @Volatile private var hasFlashUnit: Boolean = false
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
     
     /**
      * Custom AsyncRunner for NanoHTTPD with bounded thread pool.
      * Prevents resource exhaustion from unbounded thread creation.
      * Note: Thread pool max size is fixed at creation time. To change max connections,
      * the server must be restarted.
      */
    private inner class BoundedAsyncRunner(private val maxPoolSize: Int) : NanoHTTPD.AsyncRunner {
        private val threadCounter = AtomicInteger(0)
        private val activeConnectionsCount = AtomicInteger(0)
        private val threadPoolExecutor = ThreadPoolExecutor(
            HTTP_CORE_POOL_SIZE,
            maxPoolSize,
            HTTP_KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(HTTP_QUEUE_CAPACITY),
            { r -> Thread(r, "NanoHttpd-${threadCounter.incrementAndGet()}") },
            ThreadPoolExecutor.CallerRunsPolicy() // Graceful degradation under load
        ).apply {
            // Allow core threads to timeout to conserve resources
            allowCoreThreadTimeOut(true)
        }
        
        /**
         * Get the current number of active connections being processed
         */
        fun getActiveConnections(): Int = activeConnectionsCount.get()
        
        /**
         * Get the maximum number of connections that can be processed simultaneously
         */
        fun getMaxConnections(): Int = maxPoolSize
        
        override fun closeAll() {
            threadPoolExecutor.shutdown()
            try {
                if (!threadPoolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    threadPoolExecutor.shutdownNow()
                }
            } catch (e: InterruptedException) {
                threadPoolExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        
        override fun closed(clientHandler: NanoHTTPD.ClientHandler?) {
            // Decrement active connection counter when a client handler finishes
            val newCount = activeConnectionsCount.decrementAndGet()
            Log.d(TAG, "Connection closed. Active connections: $newCount")
            // Broadcast update to SSE clients and MainActivity
            broadcastConnectionCount()
        }
        
        override fun exec(code: NanoHTTPD.ClientHandler?) {
            if (code == null) return
            
            // Increment counter when accepting a connection. This provides an accurate
            // view of server load, even if the connection is immediately rejected.
            // The brief moment where counter is higher before rejection is intentional.
            val newCount = activeConnectionsCount.incrementAndGet()
            Log.d(TAG, "Connection accepted. Active connections: $newCount")
            // Broadcast update to SSE clients and MainActivity
            broadcastConnectionCount()
            
            try {
                threadPoolExecutor.execute(code)
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "HTTP request rejected due to thread pool saturation", e)
                // Decrement counter since the connection was rejected and won't be processed
                val rejectedCount = activeConnectionsCount.decrementAndGet()
                Log.d(TAG, "Connection rejected. Active connections: $rejectedCount")
                // Broadcast update to SSE clients and MainActivity
                broadcastConnectionCount()
                // Request will be dropped - client will need to retry
            }
        }
    }
    
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
        if (shouldStartServer && httpServer?.isAlive != true) {
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
    
    private fun createNotification(contentText: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val text = contentText ?: if (httpServer?.isAlive == true) {
            "Server running on ${getServerUrl()}"
        } else {
            "Camera preview active"
        }
        
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
            if (httpServer?.isAlive == true) {
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
            
            httpServer = CameraHttpServer(actualPort).apply {
                // Use custom bounded thread pool to prevent resource exhaustion
                asyncRunner = BoundedAsyncRunner(maxConnections)
                setAsyncRunner(asyncRunner!!)
            }
            httpServer?.start()
            
            val startMsg = if (actualPort != PORT) {
                "Server started on port $actualPort with max $maxConnections connections (default port $PORT was unavailable)"
            } else {
                "Server started on port $actualPort with max $maxConnections connections"
            }
            Log.d(TAG, startMsg)
            
            // Update notification with the actual port
            updateNotification("Server running on ${getServerUrl()}")
            
        } catch (e: IOException) {
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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createNotification(contentText))
    }
    
    /**
     * Show a user notification (not the foreground service notification)
     */
    private fun showUserNotification(title: String, message: String) {
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
        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        
        // Use ResolutionSelector instead of deprecated setTargetResolution
        selectedResolution?.let { resolution ->
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionFilter { supportedSizes, _ ->
                    // Filter to find exact match or closest
                    supportedSizes.filter { size ->
                        size.width == resolution.width && size.height == resolution.height
                    }.ifEmpty { supportedSizes }
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
            cameraProvider?.unbindAll()
            camera = cameraProvider?.bindToLifecycle(this, currentCamera, imageAnalysis)
            
            // Check if flash is available for back camera
            checkFlashAvailability()
            
            // Restore flashlight state if enabled for back camera
            if (isFlashlightOn && currentCamera == CameraSelector.DEFAULT_BACK_CAMERA && hasFlashUnit) {
                enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }
    
    private fun requestBindCamera() {
        ContextCompat.getMainExecutor(this).execute {
            bindCamera()
        }
    }
    
    private fun processImage(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            Log.d(TAG, "ImageProxy size: ${image.width}x${image.height}, Bitmap size: ${bitmap.width}x${bitmap.height}")
            
            // Apply camera orientation and rotation without creating squared bitmaps
            val finalBitmap = applyRotationCorrectly(bitmap)
            Log.d(TAG, "After rotation - Bitmap size: ${finalBitmap.width}x${finalBitmap.height}, Total rotation: ${(when (cameraOrientation) { "portrait" -> 90; else -> 0 } + rotation) % 360}°")
            
            // Annotate bitmap here on camera executor thread to avoid Canvas/Paint operations in HTTP threads
            val annotatedBitmap = annotateBitmap(finalBitmap)
            
            // Pre-compress to JPEG for HTTP serving (avoid Bitmap.compress on HTTP threads)
            val jpegBytes = ByteArrayOutputStream().use { stream ->
                annotatedBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_STREAM, stream)
                stream.toByteArray()
            }
            
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
            
            // Notify MainActivity if it's listening - create a copy to avoid recycling issues
            val safeConfig = annotatedBitmap.config ?: Bitmap.Config.ARGB_8888
            onFrameAvailableCallback?.invoke(annotatedBitmap.copy(safeConfig, false))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
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
    
    fun switchCamera(cameraSelector: CameraSelector) {
        currentCamera = cameraSelector
        saveSettings()
        
        // Turn off flashlight when switching cameras
        if (isFlashlightOn) {
            isFlashlightOn = false
            saveSettings()
        }
        
        requestBindCamera()
        // Notify MainActivity of the camera change
        onCameraStateChangedCallback?.invoke(currentCamera)
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
    fun toggleFlashlight(): Boolean {
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
        return isFlashlightOn
    }
    
    /**
     * Get current flashlight state
     */
    fun isFlashlightEnabled(): Boolean = isFlashlightOn
    
    /**
     * Check if flashlight is available (back camera with flash unit)
     */
    fun isFlashlightAvailable(): Boolean {
        return currentCamera == CameraSelector.DEFAULT_BACK_CAMERA && hasFlashUnit
    }
    
    fun getCurrentCamera(): CameraSelector = currentCamera
    
    fun getSupportedResolutions(): List<Size> {
        return getSupportedResolutions(currentCamera)
    }
    
    fun getSelectedResolution(): Size? = selectedResolution
    
    fun setResolution(resolution: Size?) {
        selectedResolution = resolution
        saveSettings()
        requestBindCamera()
    }
    
    fun setCameraOrientation(orientation: String) {
        cameraOrientation = orientation
        // Clear resolution cache when orientation changes to force refresh with new filter
        resolutionCache.clear()
        saveSettings()
    }
    
    fun getCameraOrientation(): String = cameraOrientation
    
    fun setRotation(rot: Int) {
        rotation = rot
        saveSettings()
    }
    
    fun getRotation(): Int = rotation
    
    fun setShowResolutionOverlay(show: Boolean) {
        showResolutionOverlay = show
        saveSettings()
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
        
        val resWidth = prefs.getInt("resolutionWidth", -1)
        val resHeight = prefs.getInt("resolutionHeight", -1)
        if (resWidth > 0 && resHeight > 0) {
            selectedResolution = Size(resWidth, resHeight)
        }
        
        val cameraType = prefs.getString("cameraType", "back")
        currentCamera = if (cameraType == "front") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        Log.d(TAG, "Loaded settings: camera=$cameraType, orientation=$cameraOrientation, rotation=$rotation, resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}, maxConnections=$maxConnections, flashlight=$isFlashlightOn")
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("cameraOrientation", cameraOrientation)
            putInt("rotation", rotation)
            putBoolean("showResolutionOverlay", showResolutionOverlay)
            putInt(PREF_MAX_CONNECTIONS, maxConnections)
            putBoolean("flashlightOn", isFlashlightOn)
            
            selectedResolution?.let {
                putInt("resolutionWidth", it.width)
                putInt("resolutionHeight", it.height)
            } ?: run {
                remove("resolutionWidth")
                remove("resolutionHeight")
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
    
    fun getActiveConnectionsCount(): Int {
        // Return the count of connections we can actually track
        return activeStreams.get() + synchronized(sseClientsLock) { sseClients.size }
    }
    
    fun getActiveConnectionsList(): List<ConnectionInfo> {
        synchronized(connectionsLock) {
            return activeConnections.values.filter { it.active }.toList()
        }
    }
    
    fun getMaxConnections(): Int = maxConnections
    
    fun setMaxConnections(max: Int): Boolean {
        val newMax = max.coerceIn(HTTP_MIN_MAX_POOL_SIZE, HTTP_ABSOLUTE_MAX_POOL_SIZE)
        if (newMax != maxConnections) {
            maxConnections = newMax
            saveSettings()
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
    
    fun isServerRunning(): Boolean = httpServer?.isAlive == true
    
    fun stopServer() {
        try {
            serverIntentionallyStopped = true
            httpServer?.stop()
            httpServer = null
            updateNotification("Camera preview active. Server stopped.")
            Log.d(TAG, "Server stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            // Still update notification even if stop failed
            updateNotification("Camera preview active")
        }
    }
    
    fun getServerUrl(): String {
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
                if (!serverIntentionallyStopped && httpServer?.isAlive != true) {
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
                            if (httpServer?.isAlive != true) {
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
    
    /**
     * Broadcast connection count update to all SSE clients and MainActivity
     */
    private fun broadcastConnectionCount() {
        val activeConns = asyncRunner?.getActiveConnections() ?: 0
        val maxConns = asyncRunner?.getMaxConnections() ?: maxConnections
        val message = "data: {\"connections\":\"$activeConns/$maxConns\",\"active\":$activeConns,\"max\":$maxConns}\n\n"
        
        synchronized(sseClientsLock) {
            val iterator = sseClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                try {
                    if (client.active) {
                        client.outputStream.write(message.toByteArray())
                        client.outputStream.flush()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "SSE client ${client.id} disconnected")
                    client.active = false
                    iterator.remove()
                }
            }
        }
        
        // Notify MainActivity of connection count change
        notifyConnectionsChanged()
    }
    
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
    
    private fun sizeLabel(size: Size): String = "${size.width}$RESOLUTION_DELIMITER${size.height}"
    
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
    
    inner class CameraHttpServer(port: Int) : NanoHTTPD(port) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            val activeConns = this@CameraService.asyncRunner?.getActiveConnections() ?: 0
            Log.d(TAG, "Request: $method $uri (Active connections: $activeConns)")
            
            // Handle CORS preflight requests
            if (method == Method.OPTIONS) {
                val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type")
                response.addHeader("Access-Control-Max-Age", "3600")
                return response
            }
            
            val response = when {
                uri == "/" || uri == "/index.html" -> serveIndexPage()
                uri == "/snapshot" -> serveSnapshot()
                uri == "/stream" -> serveStream()
                uri == "/switch" -> serveSwitch()
                uri == "/status" -> serveStatus()
                uri == "/events" -> serveSSE()
                uri == "/connections" -> serveConnections()
                uri == "/closeConnection" -> serveCloseConnection(session)
                uri == "/formats" -> serveFormats()
                uri == "/setFormat" -> serveSetFormat(session)
                uri == "/setCameraOrientation" -> serveSetCameraOrientation(session)
                uri == "/setRotation" -> serveSetRotation(session)
                uri == "/setResolutionOverlay" -> serveSetResolutionOverlay(session)
                uri == "/setMaxConnections" -> serveSetMaxConnections(session)
                uri == "/toggleFlashlight" -> serveToggleFlashlight()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
            
            // Add CORS headers for browser compatibility
            // Note: Using wildcard (*) for local network IP camera is acceptable
            // as it's intended for use on trusted local networks only.
            // For production use with internet exposure, implement proper authentication.
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type")
            
            return response
        }
        
        private fun serveIndexPage(): Response {
            // Calculate active connections at the time of serving the page
            // Exclude the current page request from the count to show actual active streaming connections
            val rawActiveConns = this@CameraService.asyncRunner?.getActiveConnections() ?: 0
            val activeConns = (rawActiveConns - 1).coerceAtLeast(0)
            val maxConns = this@CameraService.maxConnections
            val connectionDisplay = "$activeConns/$maxConns"
            
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>IP Camera</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f0f0f0; }
                        h1 { color: #333; }
                        .container { background: white; padding: 20px; border-radius: 8px; max-width: 800px; margin: 0 auto; }
                        img { max-width: 100%; height: auto; border: 1px solid #ddd; background: #000; }
                        button { background-color: #4CAF50; color: white; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; margin: 5px; }
                        button:hover { background-color: #45a049; }
                        .endpoint { background-color: #f9f9f9; padding: 10px; margin: 10px 0; border-left: 4px solid #4CAF50; }
                        code { background-color: #e0e0e0; padding: 2px 6px; border-radius: 3px; }
                        .endpoint a { color: #1976D2; text-decoration: none; font-weight: 500; }
                        .endpoint a:hover { text-decoration: underline; color: #1565C0; }
                        .row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
                        select { padding: 8px; border-radius: 4px; }
                        .note { font-size: 13px; color: #444; }
                        .status-info { background-color: #e8f5e9; padding: 12px; margin: 10px 0; border-radius: 4px; border-left: 4px solid #4CAF50; }
                        .status-info strong { color: #2e7d32; }
                        #connectionsContainer { margin: 10px 0; overflow-x: auto; }
                        #connectionsContainer table { width: 100%; border-collapse: collapse; }
                        #connectionsContainer th { padding: 8px; text-align: left; border: 1px solid #ddd; background-color: #f0f0f0; font-weight: bold; }
                        #connectionsContainer td { padding: 8px; border: 1px solid #ddd; }
                        #connectionsContainer tr:hover { background-color: #f5f5f5; }
                        #connectionsContainer button { padding: 4px 8px; font-size: 12px; background-color: #f44336; }
                        #connectionsContainer button:hover { background-color: #d32f2f; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>IP Camera Server</h1>
                        <div class="status-info">
                            <strong>Server Status:</strong> Running | 
                            <strong>Active Connections:</strong> <span id="connectionCount">$connectionDisplay</span>
                        </div>
                        <p class="note"><em>Connection count updates in real-time via Server-Sent Events. Initial count: $connectionDisplay</em></p>
                        <h2>Live Stream</h2>
                        <div id="streamContainer" style="text-align: center; background: #000; min-height: 300px; display: flex; align-items: center; justify-content: center;">
                            <button id="startStreamBtn" onclick="startStream()" style="font-size: 16px; padding: 12px 24px;">Start Stream</button>
                            <img id="stream" style="display: none; max-width: 100%; height: auto;" alt="Camera Stream">
                        </div>
                        <br>
                        <div class="row">
                            <button onclick="stopStream()">Stop Stream</button>
                            <button onclick="reloadStream()">Refresh</button>
                            <button onclick="switchCamera()">Switch Camera</button>
                            <button id="flashlightButton" onclick="toggleFlashlight()">Toggle Flashlight</button>
                            <select id="formatSelect"></select>
                            <button onclick="applyFormat()">Apply Format</button>
                        </div>
                        <div class="row">
                            <label for="orientationSelect">Camera Orientation:</label>
                            <select id="orientationSelect">
                                <option value="landscape">Landscape (Default)</option>
                                <option value="portrait">Portrait</option>
                            </select>
                            <button onclick="applyCameraOrientation()">Apply Orientation</button>
                        </div>
                        <div class="row">
                            <label for="rotationSelect">Rotation:</label>
                            <select id="rotationSelect">
                                <option value="0">0° (Normal)</option>
                                <option value="90">90° (Right)</option>
                                <option value="180">180° (Upside Down)</option>
                                <option value="270">270° (Left)</option>
                            </select>
                            <button onclick="applyRotation()">Apply Rotation</button>
                        </div>
                        <div class="row">
                            <label>
                                <input type="checkbox" id="resolutionOverlayCheckbox" checked onchange="toggleResolutionOverlay()">
                                Show Resolution Overlay (Bottom Right)
                            </label>
                        </div>
                        <div class="note" id="formatStatus"></div>
                        <p class="note">Overlay shows date/time (top left) and battery status (top right). Stream auto-reconnects if interrupted.</p>
                        <p class="note"><strong>Camera Orientation:</strong> Sets the base recording mode (landscape/portrait). <strong>Rotation:</strong> Rotates the video feed by the specified degrees.</p>
                        <p class="note"><strong>Resolution Overlay:</strong> Shows actual bitmap resolution in bottom right for debugging resolution issues.</p>
                        <h2>Active Connections</h2>
                        <p class="note"><em>Note: Shows active streaming and real-time event connections. Short-lived HTTP requests (status, snapshot, etc.) are not displayed.</em></p>
                        <div id="connectionsContainer">
                            <p>Loading connections...</p>
                        </div>
                        <button onclick="refreshConnections()">Refresh Connections</button>
                        <h2>Max Connections</h2>
                        <div class="row">
                            <label for="maxConnectionsSelect">Max Connections:</label>
                            <select id="maxConnectionsSelect">
                                <option value="4">4</option>
                                <option value="8">8</option>
                                <option value="16">16</option>
                                <option value="32" selected>32</option>
                                <option value="64">64</option>
                                <option value="100">100</option>
                            </select>
                            <button onclick="applyMaxConnections()">Apply</button>
                        </div>
                        <p class="note"><em>Note: Server restart required for max connections change to take effect.</em></p>
                        <h2>API Endpoints</h2>
                        <div class="endpoint">
                            <strong>Snapshot:</strong> <a href="/snapshot" target="_blank"><code>GET /snapshot</code></a><br>
                            Returns a single JPEG image
                        </div>
                        <div class="endpoint">
                            <strong>Stream:</strong> <a href="/stream" target="_blank"><code>GET /stream</code></a><br>
                            Returns MJPEG stream
                        </div>
                        <div class="endpoint">
                            <strong>Switch Camera:</strong> <a href="/switch" target="_blank"><code>GET /switch</code></a><br>
                            Switches between front and back camera
                        </div>
                        <div class="endpoint">
                            <strong>Status:</strong> <a href="/status" target="_blank"><code>GET /status</code></a><br>
                            Returns server status as JSON
                        </div>
                        <div class="endpoint">
                            <strong>Events (SSE):</strong> <a href="/events" target="_blank"><code>GET /events</code></a><br>
                            Server-Sent Events stream for real-time connection count updates
                        </div>
                        <div class="endpoint">
                            <strong>Formats:</strong> <a href="/formats" target="_blank"><code>GET /formats</code></a><br>
                            Lists supported camera resolutions for the active lens
                        </div>
                        <div class="endpoint">
                            <strong>Set Format:</strong> <code>GET /setFormat?value=WIDTHxHEIGHT</code><br>
                            Apply a supported resolution (or omit to return to auto). Requires <code>value</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Set Camera Orientation:</strong> <code>GET /setCameraOrientation?value=landscape|portrait</code><br>
                            Set the base camera recording mode (landscape or portrait). Requires <code>value</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Set Rotation:</strong> <code>GET /setRotation?value=0|90|180|270</code><br>
                            Rotate the video feed by the specified degrees. Requires <code>value</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Set Resolution Overlay:</strong> <code>GET /setResolutionOverlay?value=true|false</code><br>
                            Toggle resolution display in bottom right corner for debugging. Requires <code>value</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Connections:</strong> <a href="/connections" target="_blank"><code>GET /connections</code></a><br>
                            Returns list of active connections as JSON
                        </div>
                        <div class="endpoint">
                            <strong>Close Connection:</strong> <code>GET /closeConnection?id=&lt;id&gt;</code><br>
                            Close a specific connection by ID. Requires <code>id</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Set Max Connections:</strong> <code>GET /setMaxConnections?value=&lt;number&gt;</code><br>
                            Set maximum number of simultaneous connections (4-100). Requires server restart. Requires <code>value</code> parameter.
                        </div>
                        <div class="endpoint">
                            <strong>Toggle Flashlight:</strong> <a href="/toggleFlashlight" target="_blank"><code>GET /toggleFlashlight</code></a><br>
                            Toggle flashlight on/off for back camera. Only works if back camera is active and device has flash unit.
                        </div>
                        <h2>Keep the stream alive</h2>
                        <ul>
                            <li>Disable battery optimizations for IP_Cam in Android Settings &gt; Battery</li>
                            <li>Allow background activity and keep the phone plugged in for long sessions</li>
                            <li>Lock the app in recents (swipe-down or lock icon on many devices)</li>
                            <li>Set Wi-Fi to stay on during sleep and place device where signal is strong</li>
                        </ul>
                    </div>
                    <script>
                        const streamImg = document.getElementById('stream');
                        const startStreamBtn = document.getElementById('startStreamBtn');
                        let lastFrame = Date.now();
                        let streamActive = false;
                        let autoReloadInterval = null;

                        function startStream() {
                            streamImg.src = '/stream?ts=' + Date.now();
                            streamImg.style.display = 'block';
                            startStreamBtn.style.display = 'none';
                            streamActive = true;
                            
                            // Auto-reload if stream stops
                            if (autoReloadInterval) clearInterval(autoReloadInterval);
                            autoReloadInterval = setInterval(() => {
                                if (streamActive && Date.now() - lastFrame > 5000) {
                                    reloadStream();
                                }
                            }, 3000);
                        }

                        function stopStream() {
                            streamImg.src = '';
                            streamImg.style.display = 'none';
                            startStreamBtn.style.display = 'block';
                            streamActive = false;
                            if (autoReloadInterval) {
                                clearInterval(autoReloadInterval);
                                autoReloadInterval = null;
                            }
                        }

                        function reloadStream() {
                            if (streamActive) {
                                streamImg.src = '/stream?ts=' + Date.now();
                            }
                        }
                        
                        streamImg.onerror = () => {
                            if (streamActive) {
                                setTimeout(reloadStream, 1000);
                            }
                        };
                        streamImg.onload = () => { lastFrame = Date.now(); };

                        function switchCamera() {
                            fetch('/switch')
                                .then(response => response.json())
                                .then(data => {
                                    alert('Switched to ' + data.camera);
                                    setTimeout(() => location.reload(), 500);
                                })
                                .catch(error => {
                                    alert('Error switching camera: ' + error);
                                });
                        }

                        function toggleFlashlight() {
                            fetch('/toggleFlashlight')
                                .then(response => response.json())
                                .then(data => {
                                    if (data.status === 'ok') {
                                        document.getElementById('formatStatus').textContent = data.message;
                                        updateFlashlightButton();
                                    } else {
                                        document.getElementById('formatStatus').textContent = data.message;
                                    }
                                })
                                .catch(error => {
                                    document.getElementById('formatStatus').textContent = 'Error toggling flashlight: ' + error;
                                });
                        }

                        function updateFlashlightButton() {
                            fetch('/status')
                                .then(response => response.json())
                                .then(data => {
                                    const button = document.getElementById('flashlightButton');
                                    if (data.flashlightAvailable) {
                                        button.disabled = false;
                                        button.textContent = data.flashlightOn ? 'Flashlight: ON' : 'Flashlight: OFF';
                                        button.style.backgroundColor = data.flashlightOn ? '#FFA500' : '#4CAF50';
                                    } else {
                                        button.disabled = true;
                                        button.textContent = 'Flashlight N/A';
                                        button.style.backgroundColor = '#9E9E9E';
                                    }
                                })
                                .catch(error => {
                                    console.error('Error updating flashlight button:', error);
                                });
                        }

                        function loadFormats() {
                            fetch('/formats')
                                .then(response => response.json())
                                .then(data => {
                                    const select = document.getElementById('formatSelect');
                                    select.innerHTML = '';
                                    const auto = document.createElement('option');
                                    auto.value = '';
                                    auto.textContent = 'Auto (Camera default)';
                                    select.appendChild(auto);
                                    data.formats.forEach(fmt => {
                                        const option = document.createElement('option');
                                        option.value = fmt.value;
                                        option.textContent = fmt.label;
                                        if (data.selected === fmt.value) {
                                            option.selected = true;
                                        }
                                        select.appendChild(option);
                                    });
                                    document.getElementById('formatStatus').textContent = data.selected ? 
                                        'Selected: ' + data.selected : 'Selected: Auto';
                                });
                        }

                        function applyFormat() {
                            const value = document.getElementById('formatSelect').value;
                            const url = value ? '/setFormat?value=' + encodeURIComponent(value) : '/setFormat';
                            fetch(url)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                    setTimeout(reloadStream, 200);
                                })
                                .catch(() => {
                                    document.getElementById('formatStatus').textContent = 'Failed to set format';
                                });
                        }

                        function applyCameraOrientation() {
                            const value = document.getElementById('orientationSelect').value;
                            const url = '/setCameraOrientation?value=' + encodeURIComponent(value);
                            fetch(url)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                    setTimeout(reloadStream, 200);
                                })
                                .catch(() => {
                                    document.getElementById('formatStatus').textContent = 'Failed to set camera orientation';
                                });
                        }

                        function applyRotation() {
                            const value = document.getElementById('rotationSelect').value;
                            const url = '/setRotation?value=' + encodeURIComponent(value);
                            fetch(url)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                    setTimeout(reloadStream, 200);
                                })
                                .catch(() => {
                                    document.getElementById('formatStatus').textContent = 'Failed to set rotation';
                                });
                        }

                        function toggleResolutionOverlay() {
                            const checkbox = document.getElementById('resolutionOverlayCheckbox');
                            const value = checkbox.checked ? 'true' : 'false';
                            const url = '/setResolutionOverlay?value=' + value;
                            fetch(url)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                    setTimeout(reloadStream, 200);
                                })
                                .catch(() => {
                                    document.getElementById('formatStatus').textContent = 'Failed to toggle resolution overlay';
                                });
                        }

                        function refreshConnections() {
                            fetch('/connections')
                                .then(response => {
                                    if (!response.ok) {
                                        throw new Error('HTTP ' + response.status);
                                    }
                                    return response.json();
                                })
                                .then(data => {
                                    displayConnections(data.connections);
                                })
                                .catch(error => {
                                    console.error('Connection fetch error:', error);
                                    document.getElementById('connectionsContainer').innerHTML = 
                                        '<p style="color: #f44336;">Error loading connections. Please refresh the page or check server status.</p>';
                                });
                        }

                        function displayConnections(connections) {
                            const container = document.getElementById('connectionsContainer');
                            
                            if (!connections || connections.length === 0) {
                                container.innerHTML = '<p>No active connections</p>';
                                return;
                            }
                            
                            let html = '<table style="width: 100%; border-collapse: collapse;">';
                            html += '<tr style="background-color: #f0f0f0;">';
                            html += '<th style="padding: 8px; text-align: left; border: 1px solid #ddd;">ID</th>';
                            html += '<th style="padding: 8px; text-align: left; border: 1px solid #ddd;">Remote Address</th>';
                            html += '<th style="padding: 8px; text-align: left; border: 1px solid #ddd;">Endpoint</th>';
                            html += '<th style="padding: 8px; text-align: left; border: 1px solid #ddd;">Duration (s)</th>';
                            html += '<th style="padding: 8px; text-align: left; border: 1px solid #ddd;">Action</th>';
                            html += '</tr>';
                            
                            connections.forEach(conn => {
                                html += '<tr>';
                                html += '<td style="padding: 8px; border: 1px solid #ddd;">' + conn.id + '</td>';
                                html += '<td style="padding: 8px; border: 1px solid #ddd;">' + conn.remoteAddr + '</td>';
                                html += '<td style="padding: 8px; border: 1px solid #ddd;">' + conn.endpoint + '</td>';
                                html += '<td style="padding: 8px; border: 1px solid #ddd;">' + Math.floor(conn.duration / 1000) + '</td>';
                                html += '<td style="padding: 8px; border: 1px solid #ddd;">';
                                html += '<button onclick="closeConnection(' + conn.id + ')" style="padding: 4px 8px; font-size: 12px;">Close</button>';
                                html += '</td>';
                                html += '</tr>';
                            });
                            
                            html += '</table>';
                            container.innerHTML = html;
                        }

                        function closeConnection(id) {
                            if (!confirm('Close connection ' + id + '?')) {
                                return;
                            }
                            
                            fetch('/closeConnection?id=' + id)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                    refreshConnections();
                                })
                                .catch(error => {
                                    document.getElementById('formatStatus').textContent = 'Error closing connection: ' + error;
                                });
                        }

                        function applyMaxConnections() {
                            const value = document.getElementById('maxConnectionsSelect').value;
                            fetch('/setMaxConnections?value=' + value)
                                .then(response => response.json())
                                .then(data => {
                                    document.getElementById('formatStatus').textContent = data.message;
                                })
                                .catch(error => {
                                    document.getElementById('formatStatus').textContent = 'Error setting max connections: ' + error;
                                });
                        }

                        loadFormats();
                        refreshConnections();
                        updateFlashlightButton();
                        
                        // Load max connections from server status
                        fetch('/status')
                            .then(response => response.json())
                            .then(data => {
                                const select = document.getElementById('maxConnectionsSelect');
                                const options = select.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (parseInt(options[i].value) === data.maxConnections) {
                                        select.selectedIndex = i;
                                        break;
                                    }
                                }
                            });
                        
                        // Set up Server-Sent Events for real-time connection count updates
                        const eventSource = new EventSource('/events');
                        let lastConnectionCount = '';
                        
                        eventSource.onmessage = function(event) {
                            try {
                                const data = JSON.parse(event.data);
                                const connectionCount = document.getElementById('connectionCount');
                                if (connectionCount && data.connections) {
                                    connectionCount.textContent = data.connections;
                                    // Only refresh connection list if count changed
                                    if (lastConnectionCount !== data.connections) {
                                        lastConnectionCount = data.connections;
                                        // Debounce refresh to avoid too many requests
                                        setTimeout(refreshConnections, 500);
                                    }
                                }
                            } catch (e) {
                                console.error('Failed to parse SSE data:', e);
                            }
                        };
                        
                        eventSource.onerror = function(error) {
                            console.error('SSE connection error:', error);
                            // EventSource will automatically try to reconnect
                        };
                        
                        // Clean up on page unload
                        window.addEventListener('beforeunload', function() {
                            eventSource.close();
                        });
                    </script>
                </body>
                </html>
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun serveSnapshot(): Response {
            // Use pre-compressed JPEG bytes to avoid Bitmap operations on HTTP thread
            val jpegBytes = synchronized(jpegLock) { 
                lastFrameJpegBytes
            }
            
            return if (jpegBytes != null) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", jpegBytes.inputStream(), jpegBytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "No frame available")
            }
        }
        
        private fun serveStream(): Response {
            // Track this streaming connection
            activeStreams.incrementAndGet()
            Log.d(TAG, "Stream connection opened. Active streams: ${activeStreams.get()}")
            
            // Use PipedInputStream/PipedOutputStream to avoid blocking HTTP handler thread
            val pipedOutputStream = java.io.PipedOutputStream()
            val pipedInputStream = java.io.PipedInputStream(pipedOutputStream, 1024 * 1024) // 1MB buffer
            
            // Submit streaming work to dedicated executor (doesn't block HTTP thread)
            streamingExecutor.submit {
                try {
                    var streamActive = true
                    while (streamActive && !Thread.currentThread().isInterrupted) {
                        // Get pre-compressed JPEG bytes to avoid Bitmap operations
                        val jpegBytes = synchronized(jpegLock) { 
                            lastFrameJpegBytes
                        }
                        
                        if (jpegBytes != null) {
                            try {
                                // Write MJPEG frame boundary and headers
                                pipedOutputStream.write("--jpgboundary\r\n".toByteArray())
                                pipedOutputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                                pipedOutputStream.write("Content-Length: ${jpegBytes.size}\r\n\r\n".toByteArray())
                                pipedOutputStream.write(jpegBytes)
                                pipedOutputStream.write("\r\n".toByteArray())
                                pipedOutputStream.flush()
                            } catch (e: IOException) {
                                // Client disconnected or pipe broken
                                streamActive = false
                                Log.d(TAG, "Stream client disconnected")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error writing frame to stream", e)
                                streamActive = false
                            }
                            
                            // Delay for ~10 fps
                            if (streamActive) {
                                try {
                                    Thread.sleep(STREAM_FRAME_DELAY_MS)
                                } catch (e: InterruptedException) {
                                    streamActive = false
                                }
                            }
                        } else {
                            // No frame available, wait a bit and retry
                            try {
                                Thread.sleep(STREAM_FRAME_DELAY_MS)
                            } catch (e: InterruptedException) {
                                streamActive = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming error", e)
                } finally {
                    try {
                        pipedOutputStream.close()
                    } catch (e: IOException) {
                        // Ignore
                    }
                    activeStreams.decrementAndGet()
                    Log.d(TAG, "Stream connection closed. Active streams: ${activeStreams.get()}")
                }
            }
            
            // Wrap the piped input stream to track when it's closed
            val wrappedInputStream = object : java.io.InputStream() {
                override fun read(): Int = pipedInputStream.read()
                override fun read(b: ByteArray): Int = pipedInputStream.read(b)
                override fun read(b: ByteArray, off: Int, len: Int): Int = pipedInputStream.read(b, off, len)
                override fun available(): Int = pipedInputStream.available()
                
                override fun close() {
                    pipedInputStream.close()
                }
            }
            
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--jpgboundary", wrappedInputStream)
        }
        
        private fun serveSwitch(): Response {
            currentCamera = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            selectedResolution = null
            requestBindCamera()
            // Notify MainActivity of the camera change
            onCameraStateChangedCallback?.invoke(currentCamera)
            
            val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            val json = """{"status": "ok", "camera": "$cameraName"}"""
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
        
        private fun serveStatus(): Response {
            val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            // Get active connections, excluding this status request itself
            val rawActiveConns = this@CameraService.asyncRunner?.getActiveConnections() ?: 0
            val activeConns = (rawActiveConns - 1).coerceAtLeast(0)
            val maxConns = this@CameraService.maxConnections
            val activeStreamCount = this@CameraService.activeStreams.get()
            val sseCount = synchronized(sseClientsLock) { sseClients.size }
            val json = """
                {
                    "status": "running",
                    "camera": "$cameraName",
                    "url": "${getServerUrl()}",
                    "resolution": "${selectedResolution?.let { sizeLabel(it) } ?: "auto"}",
                    "cameraOrientation": "$cameraOrientation",
                    "rotation": "$rotation",
                    "showResolutionOverlay": $showResolutionOverlay,
                    "flashlightAvailable": ${isFlashlightAvailable()},
                    "flashlightOn": ${isFlashlightEnabled()},
                    "activeConnections": $activeConns,
                    "maxConnections": $maxConns,
                    "connections": "$activeConns/$maxConns",
                    "activeStreams": $activeStreamCount,
                    "activeSSEClients": $sseCount,
                    "endpoints": ["/", "/snapshot", "/stream", "/switch", "/status", "/events", "/connections", "/closeConnection", "/formats", "/setFormat", "/setCameraOrientation", "/setRotation", "/setResolutionOverlay", "/setMaxConnections", "/toggleFlashlight"]
                }
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
        
        private fun serveConnections(): Response {
            // Only show connections we can actually track accurately
            val activeStreamCount = this@CameraService.activeStreams.get()
            val sseCount = synchronized(sseClientsLock) { sseClients.size }
            
            val jsonArray = mutableListOf<String>()
            
            // Add active streaming connections
            for (i in 1..activeStreamCount) {
                jsonArray.add("""
                {
                    "id": ${System.currentTimeMillis() + i},
                    "remoteAddr": "Stream Connection $i",
                    "endpoint": "/stream",
                    "startTime": ${System.currentTimeMillis()},
                    "duration": 0,
                    "active": true,
                    "type": "stream"
                }
                """.trimIndent())
            }
            
            // Add SSE clients with accurate tracking
            synchronized(sseClientsLock) {
                sseClients.forEachIndexed { index, client ->
                    jsonArray.add("""
                    {
                        "id": ${client.id},
                        "remoteAddr": "Real-time Events Connection ${index + 1}",
                        "endpoint": "/events",
                        "startTime": ${client.id},
                        "duration": ${System.currentTimeMillis() - client.id},
                        "active": ${client.active},
                        "type": "sse"
                    }
                    """.trimIndent())
                }
            }
            
            val json = """{"connections": [${jsonArray.joinToString(",")}]}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
        
        private fun serveCloseConnection(session: IHTTPSession): Response {
            val params = session.parameters
            val idStr = params["id"]?.firstOrNull()
            
            if (idStr == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Missing id parameter"}"""
                )
            }
            
            val id = idStr.toLongOrNull()
            if (id == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Invalid id parameter"}"""
                )
            }
            
            // Try to close SSE client with this ID
            var closed = false
            synchronized(sseClientsLock) {
                val client = sseClients.find { it.id == id }
                if (client != null) {
                    client.active = false
                    sseClients.remove(client)
                    closed = true
                }
            }
            
            return if (closed) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok","message":"Connection closed","id":$id}"""
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"info","message":"Connection not found or cannot be closed. Only SSE connections can be manually closed.","id":$id}"""
                )
            }
        }
        
        private fun serveSetMaxConnections(session: IHTTPSession): Response {
            val params = session.parameters
            val valueStr = params["value"]?.firstOrNull()
            
            if (valueStr == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Missing value parameter"}"""
                )
            }
            
            val newMax = valueStr.toIntOrNull()
            if (newMax == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Invalid value parameter"}"""
                )
            }
            
            if (newMax < HTTP_MIN_MAX_POOL_SIZE || newMax > HTTP_ABSOLUTE_MAX_POOL_SIZE) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Max connections must be between $HTTP_MIN_MAX_POOL_SIZE and $HTTP_ABSOLUTE_MAX_POOL_SIZE"}"""
                )
            }
            
            val changed = setMaxConnections(newMax)
            return if (changed) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok","message":"Max connections set to $newMax. Server restart required for changes to take effect.","maxConnections":$newMax,"requiresRestart":true}"""
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok","message":"Max connections already set to $newMax","maxConnections":$newMax,"requiresRestart":false}"""
                )
            }
        }
        
        /**
         * Server-Sent Events (SSE) endpoint for real-time updates
         * Clients can connect to /events to receive live connection count updates
         */
        private fun serveSSE(): Response {
            val clientId = System.currentTimeMillis()
            Log.d(TAG, "SSE client $clientId connected")
            
            // Use PipedInputStream/PipedOutputStream to avoid blocking HTTP handler thread
            val pipedOutputStream = java.io.PipedOutputStream()
            val pipedInputStream = java.io.PipedInputStream(pipedOutputStream, 64 * 1024) // 64KB buffer
            
            // Create a blocking queue for this client's messages
            val messageQueue = java.util.concurrent.LinkedBlockingQueue<String>()
            
            val client = SSEClient(clientId, object : java.io.OutputStream() {
                override fun write(b: Int) {
                    // Not used - we use the queue instead
                }
                
                override fun write(b: ByteArray) {
                    messageQueue.offer(String(b))
                }
            })
            
            // Register the SSE client
            synchronized(sseClientsLock) {
                sseClients.add(client)
            }
            
            // Send initial connection count
            val activeConns = this@CameraService.asyncRunner?.getActiveConnections() ?: 0
            val maxConns = this@CameraService.maxConnections
            val initialMessage = "data: {\"connections\":\"$activeConns/$maxConns\",\"active\":$activeConns,\"max\":$maxConns}\n\n"
            messageQueue.offer(initialMessage)
            
            // Submit SSE work to dedicated executor (doesn't block HTTP thread)
            streamingExecutor.submit {
                try {
                    while (client.active && !Thread.currentThread().isInterrupted) {
                        // Try to get a message from the queue (with timeout)
                        val message = messageQueue.poll(30, TimeUnit.SECONDS)
                        
                        if (message != null) {
                            try {
                                pipedOutputStream.write(message.toByteArray())
                                pipedOutputStream.flush()
                            } catch (e: IOException) {
                                // Client disconnected
                                client.active = false
                                Log.d(TAG, "SSE client $clientId disconnected (write error)")
                            }
                        } else if (client.active) {
                            // Send keepalive comment every 30 seconds
                            try {
                                pipedOutputStream.write(": keepalive\n\n".toByteArray())
                                pipedOutputStream.flush()
                            } catch (e: IOException) {
                                // Client disconnected
                                client.active = false
                                Log.d(TAG, "SSE client $clientId disconnected (keepalive error)")
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "SSE client $clientId interrupted")
                } catch (e: Exception) {
                    Log.e(TAG, "SSE error for client $clientId", e)
                } finally {
                    try {
                        pipedOutputStream.close()
                    } catch (e: IOException) {
                        // Ignore
                    }
                    synchronized(sseClientsLock) {
                        sseClients.remove(client)
                    }
                    Log.d(TAG, "SSE client $clientId closed. Remaining SSE clients: ${sseClients.size}")
                }
            }
            
            // Wrap the piped input stream to track when it's closed
            val wrappedInputStream = object : java.io.InputStream() {
                override fun read(): Int = pipedInputStream.read()
                override fun read(b: ByteArray): Int = pipedInputStream.read(b)
                override fun read(b: ByteArray, off: Int, len: Int): Int = pipedInputStream.read(b, off, len)
                override fun available(): Int = pipedInputStream.available()
                
                override fun close() {
                    client.active = false
                    pipedInputStream.close()
                }
            }
            
            val response = newChunkedResponse(Response.Status.OK, "text/event-stream", wrappedInputStream)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "keep-alive")
            response.addHeader("X-Accel-Buffering", "no") // Disable nginx buffering
            return response
        }
        
        private fun serveFormats(): Response {
            val formats = getSupportedResolutions(currentCamera)
            val jsonFormats = formats.joinToString(",") {
                val label = sizeLabel(it)
                """{"value":"$label","label":"$label"}"""
            }
            val selected = selectedResolution?.let { "\"${sizeLabel(it)}\"" } ?: "null"
            val json = """
                {
                    "formats": [$jsonFormats],
                    "selected": $selected
                }
            """.trimIndent()
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
        
        private fun serveSetFormat(session: IHTTPSession): Response {
            val params = session.parameters
            val value = params["value"]?.firstOrNull()
            if (value.isNullOrBlank()) {
                selectedResolution = null
                requestBindCamera()
                val message = """{"status":"ok","message":"Resolution reset to auto"}"""
                return newFixedLengthResponse(Response.Status.OK, "application/json", message)
            }
            if (value.length > 32) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Format value too long"}"""
                )
            }
            val parts = value.lowercase(Locale.getDefault()).split(RESOLUTION_DELIMITER)
            val width = parts.getOrNull(0)?.toIntOrNull()
            val height = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || width == null || height == null || width <= 0 || height <= 0) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Invalid format"}"""
                )
            }
            val desired = Size(width, height)
            val supported = getSupportedResolutions(currentCamera)
            return if (supported.any { it.width == desired.width && it.height == desired.height }) {
                selectedResolution = desired
                requestBindCamera()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok","message":"Resolution set to ${sizeLabel(desired)}"}"""
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Resolution not supported"}"""
                )
            }
        }
        
        private fun serveSetRotation(session: IHTTPSession): Response {
            val params = session.parameters
            val value = params["value"]?.firstOrNull()?.lowercase(Locale.getDefault())
            
            val newRotation = when (value) {
                "0", null -> 0
                "90" -> 90
                "180" -> 180
                "270" -> 270
                else -> {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"status":"error","message":"Invalid rotation value. Use 0, 90, 180, or 270"}"""
                    )
                }
            }
            
            rotation = newRotation
            val message = "Rotation set to ${newRotation}°"
            
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok","message":"$message","rotation":"$newRotation"}"""
            )
        }
        
        private fun serveSetCameraOrientation(session: IHTTPSession): Response {
            val params = session.parameters
            val value = params["value"]?.firstOrNull()?.lowercase(Locale.getDefault())
            
            val newOrientation = when (value) {
                "landscape", null -> "landscape"
                "portrait" -> "portrait"
                else -> {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"status":"error","message":"Invalid orientation. Use portrait or landscape"}"""
                    )
                }
            }
            
            cameraOrientation = newOrientation
            val message = "Camera orientation set to $newOrientation"
            
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok","message":"$message","cameraOrientation":"$newOrientation"}"""
            )
        }
        
        private fun serveSetResolutionOverlay(session: IHTTPSession): Response {
            val params = session.parameters
            val value = params["value"]?.firstOrNull()?.lowercase(Locale.getDefault())
            
            val showOverlay = when (value) {
                "true", "1", "yes" -> true
                "false", "0", "no", null -> false
                else -> {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"status":"error","message":"Invalid value. Use true or false"}"""
                    )
                }
            }
            
            showResolutionOverlay = showOverlay
            val message = "Resolution overlay ${if (showOverlay) "enabled" else "disabled"}"
            
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok","message":"$message","showResolutionOverlay":$showOverlay}"""
            )
        }
        
        private fun serveToggleFlashlight(): Response {
            if (!isFlashlightAvailable()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"status":"error","message":"Flashlight not available. Ensure back camera is selected and device has flash unit.","available":false}"""
                )
            }
            
            val newState = toggleFlashlight()
            val message = "Flashlight ${if (newState) "enabled" else "disabled"}"
            
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok","message":"$message","flashlight":$newState}"""
            )
        }
    }
}
