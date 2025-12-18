package com.ipcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CameraService : Service(), LifecycleOwner {
    private val binder = LocalBinder()
    @Volatile private var httpServer: CameraHttpServer? = null
    private var currentCamera = CameraSelector.DEFAULT_BACK_CAMERA
    private var lastFrameBitmap: Bitmap? = null
    @Volatile private var lastFrameTimestamp: Long = 0
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var selectedResolution: Size? = null
    private val resolutionCache = mutableMapOf<Int, List<Size>>()
    private lateinit var lifecycleRegistry: LifecycleRegistry
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var manualRotation: Int = -1 // -1 means auto, otherwise 0, 90, 180, 270
    private var deviceOrientation: Int = 0 // Current device orientation
    private var orientationEventListener: OrientationEventListener? = null
    
    // Callbacks for MainActivity to receive updates
    private var onCameraStateChangedCallback: ((CameraSelector) -> Unit)? = null
    private var onFrameAvailableCallback: ((Bitmap) -> Unit)? = null
    
    companion object {
         private const val TAG = "CameraService"
         private const val CHANNEL_ID = "CameraServiceChannel"
         private const val NOTIFICATION_ID = 1
         private const val PORT = 8080
         private const val FRAME_STALE_THRESHOLD_MS = 5_000L
         private const val RESOLUTION_DELIMITER = "x"
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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        acquireLocks()
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
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IP Camera Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IP Camera Server")
            .setContentText("Server running on ${getServerUrl()}")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun startServer() {
        try {
            httpServer?.stop()
            httpServer = CameraHttpServer(PORT)
            httpServer?.start()
            Log.d(TAG, "Server started on port $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start server", e)
        }
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
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .apply {
                selectedResolution?.let { setTargetResolution(it) }
            }
            .build()
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
            // Apply rotation before saving
            val rotatedBitmap = applyRotation(bitmap)
            synchronized(this) {
                lastFrameBitmap?.recycle()
                lastFrameBitmap = rotatedBitmap
                lastFrameTimestamp = System.currentTimeMillis()
            }
            // Notify MainActivity if it's listening - create a copy to avoid recycling issues
            val safeConfig = rotatedBitmap.config ?: Bitmap.Config.ARGB_8888
            onFrameAvailableCallback?.invoke(annotateBitmap(rotatedBitmap).copy(safeConfig, false))
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            image.close()
        }
    }
    
    private fun applyRotation(bitmap: Bitmap): Bitmap {
        val rotation = if (manualRotation == -1) deviceOrientation else manualRotation
        
        // Avoid unnecessary bitmap creation if no rotation is needed
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
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    
    fun switchCamera(cameraSelector: CameraSelector) {
        currentCamera = cameraSelector
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
        requestBindCamera()
    }
    
    fun setRotation(rotation: Int) {
        manualRotation = rotation
    }
    
    fun getRotation(): Int = manualRotation
    
    fun onDeviceOrientationChanged() {
        // This is called when device orientation changes
        // The OrientationEventListener handles this automatically now
        // This method is kept for compatibility but not actively used
    }
    
    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            private var lastReportedOrientation = -1
            
            override fun onOrientationChanged(orientation: Int) {
                if (manualRotation == -1 && orientation != ORIENTATION_UNKNOWN) {
                    // Update device orientation based on sensor with hysteresis to prevent jitter
                    val newOrientation = when {
                        orientation >= 330 || orientation < 30 -> 0
                        orientation in 60..120 -> 270
                        orientation in 150..210 -> 180
                        orientation in 240..300 -> 90
                        else -> lastReportedOrientation // Keep current orientation in transition zones
                    }
                    // Only update if orientation actually changed
                    if (newOrientation != -1 && deviceOrientation != newOrientation) {
                        deviceOrientation = newOrientation
                        lastReportedOrientation = newOrientation
                    }
                }
            }
        }
        
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
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
        return "http://$ipAddress:$PORT"
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
        orientationEventListener?.disable()
        httpServer?.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        lastFrameBitmap?.recycle()
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
                delay(FRAME_STALE_THRESHOLD_MS)
                if (httpServer?.isAlive != true) {
                    startServer()
                }
                val frameAge = System.currentTimeMillis() - lastFrameTimestamp
                if (frameAge > FRAME_STALE_THRESHOLD_MS) {
                    if (cameraProvider == null) {
                        startCamera()
                    } else {
                        requestBindCamera()
                    }
                }
            }
        }
    }
    
    private fun annotateBitmap(source: Bitmap): Bitmap {
        val mutable = source.copy(source.config ?: Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(mutable)
        val density = resources.displayMetrics.density
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
        val batteryInfo = getBatteryInfo()
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
            canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint)
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
        
        return mutable
    }
    
    private data class BatteryInfo(val level: Int, val isCharging: Boolean)
    
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
        val distinct = sizes.distinctBy { Pair(it.width, it.height) }
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
                uri == "/setRotation" -> serveSetRotation(session)
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
                        .row { display: flex; gap: 10px; flex-wrap: wrap; align-items: center; }
                        select { padding: 8px; border-radius: 4px; }
                        .note { font-size: 13px; color: #444; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>IP Camera Server</h1>
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
                            <label for="rotationSelect">Rotation:</label>
                            <select id="rotationSelect">
                                <option value="auto">Auto (Follow Device)</option>
                                <option value="0">0° (Normal)</option>
                                <option value="90">90° (Right)</option>
                                <option value="180">180° (Upside Down)</option>
                                <option value="270">270° (Left)</option>
                            </select>
                            <button onclick="applyRotation()">Apply Rotation</button>
                        </div>
                        <div class="note" id="formatStatus"></div>
                        <p class="note">Overlay shows date/time (top left) and battery status (top right). Stream auto-reconnects if interrupted.</p>
                        <h2>API Endpoints</h2>
                        <div class="endpoint">
                            <strong>Snapshot:</strong> <code>GET /snapshot</code><br>
                            Returns a single JPEG image
                        </div>
                        <div class="endpoint">
                            <strong>Stream:</strong> <code>GET /stream</code><br>
                            Returns MJPEG stream
                        </div>
                        <div class="endpoint">
                            <strong>Switch Camera:</strong> <code>GET /switch</code><br>
                            Switches between front and back camera
                        </div>
                        <div class="endpoint">
                            <strong>Status:</strong> <code>GET /status</code><br>
                            Returns server status as JSON
                        </div>
                        <div class="endpoint">
                            <strong>Formats:</strong> <code>GET /formats</code><br>
                            Lists supported camera resolutions for the active lens
                        </div>
                        <div class="endpoint">
                            <strong>Set Format:</strong> <code>GET /setFormat?value=WIDTHxHEIGHT</code><br>
                            Apply a supported resolution (or omit to return to auto)
                        </div>
                        <div class="endpoint">
                            <strong>Set Rotation:</strong> <code>GET /setRotation?value=auto|0|90|180|270</code><br>
                            Set camera rotation (auto follows device orientation)
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

                        loadFormats();
                    </script>
                </body>
                </html>
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }
        
        private fun serveSnapshot(): Response {
            val bitmap = synchronized(this@CameraService) { lastFrameBitmap }
            
            return if (bitmap != null) {
                val stream = ByteArrayOutputStream()
                annotateBitmap(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val bytes = stream.toByteArray()
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "No frame available")
            }
        }
        
        private fun serveStream(): Response {
            // Create a custom InputStream for MJPEG streaming
            val inputStream = object : java.io.InputStream() {
                private var buffer = ByteArray(0)
                private var position = 0
                
                override fun read(): Int {
                    while (position >= buffer.size) {
                        // Generate next frame
                        val bitmap = synchronized(this@CameraService) { lastFrameBitmap }
                        
                        if (bitmap != null) {
                            val annotated = annotateBitmap(bitmap)
                            val jpegStream = ByteArrayOutputStream()
                            annotated.compress(Bitmap.CompressFormat.JPEG, 80, jpegStream)
                            val jpegBytes = jpegStream.toByteArray()
                            
                            val frameStream = ByteArrayOutputStream()
                            frameStream.write("--jpgboundary\r\n".toByteArray())
                            frameStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                            frameStream.write("Content-Length: ${jpegBytes.size}\r\n\r\n".toByteArray())
                            frameStream.write(jpegBytes)
                            frameStream.write("\r\n".toByteArray())
                            
                            buffer = frameStream.toByteArray()
                            position = 0
                            
                            // Small delay for ~10 fps
                            try {
                                Thread.sleep(100)
                            } catch (e: InterruptedException) {
                                return -1
                            }
                            break
                        } else {
                            // No frame available, wait a bit and retry
                            try {
                                Thread.sleep(100)
                            } catch (e: InterruptedException) {
                                return -1
                            }
                            // Continue loop to retry
                        }
                    }
                    
                    return if (position < buffer.size) {
                        buffer[position++].toInt() and 0xFF
                    } else {
                        -1
                    }
                }
                
                override fun available(): Int {
                    return buffer.size - position
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
            val rotationStr = if (manualRotation == -1) "auto" else manualRotation.toString()
            val json = """
                {
                    "status": "running",
                    "camera": "$cameraName",
                    "url": "${getServerUrl()}",
                    "resolution": "${selectedResolution?.let { sizeLabel(it) } ?: "auto"}",
                    "rotation": "$rotationStr",
                    "endpoints": ["/", "/snapshot", "/stream", "/switch", "/status", "/formats", "/setFormat", "/setRotation"]
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
            
            val rotation = when (value) {
                "auto", null -> -1
                "0" -> 0
                "90" -> 90
                "180" -> 180
                "270" -> 270
                else -> {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        "application/json",
                        """{"status":"error","message":"Invalid rotation value. Use auto, 0, 90, 180, or 270"}"""
                    )
                }
            }
            
            manualRotation = rotation
            val message = if (rotation == -1) {
                "Rotation set to auto (following device orientation)"
            } else {
                "Rotation set to ${rotation}°"
            }
            
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"status":"ok","message":"$message","rotation":"${if (rotation == -1) "auto" else rotation.toString()}"}"""
            )
        }
    }
}
