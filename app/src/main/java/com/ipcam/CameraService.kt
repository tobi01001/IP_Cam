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
    
    // Callbacks for MainActivity to receive updates
    private var onCameraStateChangedCallback: ((CameraSelector) -> Unit)? = null
    private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
    
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
         // Thread pool settings for NanoHTTPD
         // Maximum parallel connections: HTTP_MAX_POOL_SIZE (8 concurrent connections)
         // Requests beyond max are queued up to HTTP_QUEUE_CAPACITY (50), then rejected
         private const val HTTP_CORE_POOL_SIZE = 2
         private const val HTTP_MAX_POOL_SIZE = 8
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
     }
     
     /**
      * Custom AsyncRunner for NanoHTTPD with bounded thread pool.
      * Prevents resource exhaustion from unbounded thread creation.
      */
    private inner class BoundedAsyncRunner : NanoHTTPD.AsyncRunner {
        private val threadCounter = AtomicInteger(0)
        private val activeConnections = AtomicInteger(0)
        private val threadPoolExecutor = ThreadPoolExecutor(
            HTTP_CORE_POOL_SIZE,
            HTTP_MAX_POOL_SIZE,
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
        fun getActiveConnections(): Int = activeConnections.get()
        
        /**
         * Get the maximum number of connections that can be processed simultaneously
         */
        fun getMaxConnections(): Int = HTTP_MAX_POOL_SIZE
        
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
            activeConnections.decrementAndGet()
        }
        
        override fun exec(code: NanoHTTPD.ClientHandler?) {
            if (code == null) return
            
            // Increment counter when accepting a connection. This provides an accurate
            // view of server load, even if the connection is immediately rejected.
            // The brief moment where counter is higher before rejection is intentional.
            activeConnections.incrementAndGet()
            
            try {
                threadPoolExecutor.execute(code)
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "HTTP request rejected due to thread pool saturation", e)
                // Decrement counter since the connection was rejected and won't be processed
                activeConnections.decrementAndGet()
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
        startServer()
        startCamera()
        startWatchdog()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        if (httpServer?.isAlive != true) {
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
        
        val text = contentText ?: "Server running on ${getServerUrl()}"
        
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
                asyncRunner = BoundedAsyncRunner()
                setAsyncRunner(asyncRunner!!)
            }
            httpServer?.start()
            
            val startMsg = if (actualPort != PORT) {
                "Server started on port $actualPort (default port $PORT was unavailable)"
            } else {
                "Server started on port $actualPort with bounded thread pool"
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
            cameraProvider?.bindToLifecycle(this, currentCamera, imageAnalysis)
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
        requestBindCamera()
        // Notify MainActivity of the camera change
        onCameraStateChangedCallback?.invoke(currentCamera)
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
        
        Log.d(TAG, "Loaded settings: camera=$cameraType, orientation=$cameraOrientation, rotation=$rotation, resolution=${selectedResolution?.let { "${it.width}x${it.height}" } ?: "auto"}")
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("IPCamSettings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("cameraOrientation", cameraOrientation)
            putInt("rotation", rotation)
            putBoolean("showResolutionOverlay", showResolutionOverlay)
            
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
    
    fun clearCallbacks() {
        onCameraStateChangedCallback = null
        onFrameAvailableCallback = null
    }
    
    fun isServerRunning(): Boolean = httpServer?.isAlive == true
    
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
                
                // Check server health
                if (httpServer?.isAlive != true) {
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
            
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
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
            Log.d(TAG, "Request: $method $uri")
            
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
                uri == "/formats" -> serveFormats()
                uri == "/setFormat" -> serveSetFormat(session)
                uri == "/setCameraOrientation" -> serveSetCameraOrientation(session)
                uri == "/setRotation" -> serveSetRotation(session)
                uri == "/setResolutionOverlay" -> serveSetResolutionOverlay(session)
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
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>IP Camera Server</h1>
                        <div class="status-info">
                            <strong>Server Status:</strong> Running | 
                            <strong>Active Connections:</strong> <span id="connectionCount">0/8</span>
                        </div>
                        <h2>Live Stream</h2>
                        <img id="stream" src="/stream" alt="Camera Stream">
                        <br>
                        <div class="row">
                            <button onclick="reloadStream()">Refresh</button>
                            <button onclick="switchCamera()">Switch Camera</button>
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
                        let lastFrame = Date.now();

                        function reloadStream() {
                            streamImg.src = '/stream?ts=' + Date.now();
                        }
                        streamImg.onerror = () => setTimeout(reloadStream, 1000);
                        streamImg.onload = () => { lastFrame = Date.now(); };

                        setInterval(() => {
                            if (Date.now() - lastFrame > 5000) {
                                reloadStream();
                            }
                        }, 3000);

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

                        function updateConnectionCount() {
                            fetch('/status')
                                .then(response => response.json())
                                .then(data => {
                                    const connectionCount = document.getElementById('connectionCount');
                                    if (connectionCount && data.connections) {
                                        connectionCount.textContent = data.connections;
                                    }
                                })
                                .catch(error => {
                                    console.error('Failed to fetch connection count:', error);
                                });
                        }

                        loadFormats();
                        updateConnectionCount();
                        // Update connection count every 2 seconds
                        setInterval(updateConnectionCount, 2000);
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
            // Create a custom InputStream for MJPEG streaming
            val inputStream = object : java.io.InputStream() {
                private var buffer = ByteArray(0)
                private var position = 0
                private var streamActive = true
                
                override fun read(): Int {
                    while (position >= buffer.size && streamActive) {
                        // Get pre-compressed JPEG bytes to avoid Bitmap operations on HTTP thread
                        val jpegBytes = synchronized(jpegLock) { 
                            lastFrameJpegBytes
                        }
                        
                        if (jpegBytes != null) {
                            try {
                                // Just package the pre-compressed JPEG bytes
                                val frameStream = ByteArrayOutputStream()
                                frameStream.write("--jpgboundary\r\n".toByteArray())
                                frameStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                                frameStream.write("Content-Length: ${jpegBytes.size}\r\n\r\n".toByteArray())
                                frameStream.write(jpegBytes)
                                frameStream.write("\r\n".toByteArray())
                                
                                buffer = frameStream.toByteArray()
                                position = 0
                            } catch (e: Exception) {
                                Log.e(TAG, "Error packaging frame for stream", e)
                                // Continue to retry on next iteration
                            }
                            
                            // Small delay for ~10 fps
                            try {
                                Thread.sleep(STREAM_FRAME_DELAY_MS)
                            } catch (e: InterruptedException) {
                                streamActive = false
                                return -1
                            }
                            break
                        } else {
                            // No frame available, wait a bit and retry
                            try {
                                Thread.sleep(STREAM_FRAME_DELAY_MS)
                            } catch (e: InterruptedException) {
                                streamActive = false
                                return -1
                            }
                            // Continue loop to retry
                        }
                    }
                    
                    return if (position < buffer.size && streamActive) {
                        buffer[position++].toInt() and 0xFF
                    } else {
                        -1
                    }
                }
                
                override fun available(): Int {
                    return if (streamActive) buffer.size - position else 0
                }
                
                override fun close() {
                    streamActive = false
                    super.close()
                }
            }
            
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--jpgboundary", inputStream)
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
            val activeConns = this@CameraService.asyncRunner?.getActiveConnections() ?: 0
            val maxConns = this@CameraService.asyncRunner?.getMaxConnections() ?: HTTP_MAX_POOL_SIZE
            val json = """
                {
                    "status": "running",
                    "camera": "$cameraName",
                    "url": "${getServerUrl()}",
                    "resolution": "${selectedResolution?.let { sizeLabel(it) } ?: "auto"}",
                    "cameraOrientation": "$cameraOrientation",
                    "rotation": "$rotation",
                    "showResolutionOverlay": $showResolutionOverlay,
                    "activeConnections": $activeConns,
                    "maxConnections": $maxConns,
                    "connections": "$activeConns/$maxConns",
                    "endpoints": ["/", "/snapshot", "/stream", "/switch", "/status", "/formats", "/setFormat", "/setCameraOrientation", "/setRotation", "/setResolutionOverlay"]
                }
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
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
    }
}
