package com.ipcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class CameraService : LifecycleService() {
    private val binder = LocalBinder()
    private var httpServer: CameraHttpServer? = null
    private var currentCamera = CameraSelector.DEFAULT_BACK_CAMERA
    private var lastFrameBitmap: Bitmap? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    
    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "CameraServiceChannel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 8080
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startServer()
        startCamera()
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun startServer() {
        try {
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
    
    private fun processImage(image: ImageProxy) {
        try {
            val bitmap = imageProxyToBitmap(image)
            synchronized(this) {
                lastFrameBitmap?.recycle()
                lastFrameBitmap = bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
        } finally {
            image.close()
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
        bindCamera()
    }
    
    fun isServerRunning(): Boolean = httpServer?.isAlive == true
    
    fun getServerUrl(): String {
        val ipAddress = getIpAddress()
        return "http://$ipAddress:$PORT"
    }
    
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
        httpServer?.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        lastFrameBitmap?.recycle()
    }
    
    inner class CameraHttpServer(port: Int) : NanoHTTPD(port) {
        private val streamingClients = mutableListOf<OutputStream>()
        private var streamingJob: Job? = null
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "Request: $uri")
            
            return when {
                uri == "/" || uri == "/index.html" -> serveIndexPage()
                uri == "/snapshot" -> serveSnapshot()
                uri == "/stream" -> serveStream()
                uri == "/switch" -> serveSwitch()
                uri == "/status" -> serveStatus()
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not Found"
                )
            }
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
                        img { max-width: 100%; height: auto; border: 1px solid #ddd; }
                        button { background-color: #4CAF50; color: white; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; margin: 5px; }
                        button:hover { background-color: #45a049; }
                        .endpoint { background-color: #f9f9f9; padding: 10px; margin: 10px 0; border-left: 4px solid #4CAF50; }
                        code { background-color: #e0e0e0; padding: 2px 6px; border-radius: 3px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>IP Camera Server</h1>
                        <h2>Live Stream</h2>
                        <img id="stream" src="/stream" alt="Camera Stream">
                        <br>
                        <button onclick="location.reload()">Refresh</button>
                        <button onclick="switchCamera()">Switch Camera</button>
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
                    </div>
                    <script>
                        function switchCamera() {
                            fetch('/switch')
                                .then(response => response.json())
                                .then(data => {
                                    alert('Switched to ' + data.camera);
                                    setTimeout(() => location.reload(), 500);
                                });
                        }
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
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val bytes = stream.toByteArray()
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", bytes.inputStream(), bytes.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "No frame available")
            }
        }
        
        private fun serveStream(): Response {
            val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--jpgboundary", null)
            
            streamingJob?.cancel()
            streamingJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val outputStream = response.data
                    
                    while (isActive) {
                        val bitmap = synchronized(this@CameraService) { lastFrameBitmap }
                        
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            val bytes = stream.toByteArray()
                            
                            outputStream.write("--jpgboundary\r\n".toByteArray())
                            outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                            outputStream.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            outputStream.write(bytes)
                            outputStream.write("\r\n".toByteArray())
                            outputStream.flush()
                        }
                        
                        delay(100) // ~10 fps
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming error", e)
                }
            }
            
            return response
        }
        
        private fun serveSwitch(): Response {
            currentCamera = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            bindCamera()
            
            val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            val json = """{"status": "ok", "camera": "$cameraName"}"""
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
        
        private fun serveStatus(): Response {
            val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            val json = """
                {
                    "status": "running",
                    "camera": "$cameraName",
                    "url": "${getServerUrl()}",
                    "endpoints": ["/", "/snapshot", "/stream", "/switch", "/status"]
                }
            """.trimIndent()
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }
    }
}
