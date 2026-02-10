package com.ipcam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ktor-based HTTP server for IP Camera streaming.
 * Handles all HTTP endpoints including MJPEG streaming, SSE, and API endpoints.
 * 
 * This server is designed to work with CameraService as the single source of truth
 * for camera operations and frame management.
 * 
 * Web UI Structure:
 * The web interface is separated into modular files for easy maintenance:
 * - `/app/src/main/assets/web/index.html` - Main HTML structure with template placeholders
 * - `/app/src/main/assets/web/styles.css` - All CSS styling (gradients, cards, responsive design)
 * - `/app/src/main/assets/web/script.js` - All JavaScript functionality (SSE, controls, API calls)
 * 
 * Template variables in index.html ({{variable}}) are replaced at runtime with dynamic values.
 * Static assets (CSS, JS) are served directly from the assets directory.
 * No build step required - assets are bundled with the APK automatically.
 */
class HttpServer(
    private val port: Int,
    private val cameraService: CameraServiceInterface,
    private val context: Context
) {
    private var server: ApplicationEngine? = null
    private val activeStreams = AtomicInteger(0)
    private val sseClients = mutableListOf<SSEClient>()
    private val sseClientsLock = Any()
    private val clientIdCounter = AtomicLong(0)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "HttpServer"
        private const val JPEG_QUALITY_STREAM = 75
    }
    
    /**
     * Represents a Server-Sent Events client connection
     */
    private data class SSEClient(
        val id: Long,
        val channel: ByteWriteChannel,
        @Volatile var active: Boolean = true
    )
    
    /**
     * Start the HTTP server on the specified port
     */
    fun start() {
        stop() // Stop any existing server
        
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Options)
                allowHeader(HttpHeaders.ContentType)
                maxAgeInSeconds = 3600
            }
            
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "Request failed: ${call.request.local.uri}", cause)
                    call.respondText(
                        text = "Internal server error: ${cause.message}",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
            
            routing {
                // Root page
                get("/") { serveIndexPage() }
                get("/index.html") { serveIndexPage() }
                
                // Static assets for web UI
                get("/styles.css") { serveStaticAsset("styles.css") }
                get("/script.js") { serveStaticAsset("script.js") }
                
                // Camera snapshot
                get("/snapshot") { serveSnapshot() }
                
                // MJPEG stream
                get("/stream") { serveStream() }
                
                // Camera control
                get("/switch") { serveSwitch() }
                get("/toggleFlashlight") { serveToggleFlashlight() }
                get("/flashOn") { serveFlashlightOn() }
                get("/flashOff") { serveFlashlightOff() }
                
                // Status and monitoring
                get("/status") { serveStatus() }
                get("/events") { serveSSE() }
                get("/connections") { serveConnections() }
                get("/closeConnection") { serveCloseConnection() }
                get("/stats") { serveDetailedStats() }
                
                // Resolution and format endpoints
                get("/formats") { serveFormats() }
                get("/setFormat") { serveSetFormat() }
                get("/setCameraOrientation") { serveSetCameraOrientation() }
                get("/setRotation") { serveSetRotation() }
                get("/setResolutionOverlay") { serveSetResolutionOverlay() }
                
                // OSD overlay endpoints
                get("/setDateTimeOverlay") { serveSetDateTimeOverlay() }
                get("/setBatteryOverlay") { serveSetBatteryOverlay() }
                get("/setFpsOverlay") { serveSetFpsOverlay() }
                
                // FPS control endpoints
                get("/setMjpegFps") { serveSetMjpegFps() }
                get("/setRtspFps") { serveSetRtspFps() }
                
                // Server configuration
                get("/setMaxConnections") { serveSetMaxConnections() }
                get("/restart") { serveRestartServer() }
                
                // Adaptive quality control
                get("/enableAdaptiveQuality") { serveEnableAdaptiveQuality() }
                get("/disableAdaptiveQuality") { serveDisableAdaptiveQuality() }
                
                // RTSP streaming endpoints
                get("/enableRTSP") { serveEnableRTSP() }
                get("/disableRTSP") { serveDisableRTSP() }
                get("/rtspStatus") { serveRTSPStatus() }
                get("/setRTSPBitrate") { serveSetRTSPBitrate() }
                get("/setRTSPBitrateMode") { serveSetRTSPBitrateMode() }
                
                // Battery management endpoints
                get("/overrideBatteryLimit") { serveOverrideBatteryLimit() }
                
                // Camera state management endpoints (for testing/debugging)
                get("/cameraState") { serveCameraState() }
                get("/activateCamera") { serveActivateCamera() }
                get("/deactivateCamera") { serveDeactivateCamera() }
                
                // Camera recovery endpoints
                get("/resetCamera") { serveResetCamera() }
                
                // Diagnostic endpoints
                get("/diagnostics/camera") { serveCameraDiagnostics() }
                get("/diagnostics/reboot") { serveRebootDiagnostics() }
                
                // Auto update endpoints
                get("/checkUpdate") { serveCheckUpdate() }
                get("/triggerUpdate") { serveTriggerUpdate() }
                
                // Device Owner endpoints
                get("/reboot") { serveReboot() }
            }
        }
        
        server?.start(wait = false)
        Log.d(TAG, "Ktor server started on port $port")
    }
    
    /**
     * Stop the HTTP server
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
        
        // Clean up SSE clients
        synchronized(sseClientsLock) {
            sseClients.forEach { it.active = false }
            sseClients.clear()
        }
        
        // Cancel any running update operations
        serverScope.cancel()
        
        Log.d(TAG, "Ktor server stopped")
    }
    
    /**
     * Check if server is running
     */
    fun isAlive(): Boolean = server != null
    
    /**
     * Get the count of active MJPEG streams
     */
    fun getActiveStreamsCount(): Int = activeStreams.get()
    
    /**
     * Get the count of active SSE clients
     */
    fun getActiveSseClientsCount(): Int = synchronized(sseClientsLock) { sseClients.size }
    
    /**
     * Broadcast connection count update to all SSE clients
     */
    fun broadcastConnectionCount() {
        val activeConns = cameraService.getActiveConnectionsCount()
        val maxConns = cameraService.getMaxConnections()
        val message = "data: {\"connections\":\"$activeConns/$maxConns\",\"active\":$activeConns,\"max\":$maxConns}\n\n"
        
        synchronized(sseClientsLock) {
            val iterator = sseClients.iterator()
            while (iterator.hasNext()) {
                val client = iterator.next()
                if (client.active) {
                    try {
                        runBlocking {
                            client.channel.writeStringUtf8(message)
                            client.channel.flush()
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "SSE client ${client.id} disconnected")
                        client.active = false
                        iterator.remove()
                    }
                } else {
                    iterator.remove()
                }
            }
        }
    }
    
    /**
     * Broadcast camera state changes to all SSE clients
     * Uses delta broadcasting - only sends changed values to reduce bandwidth
     * and prevent unnecessary UI updates
     */
    fun broadcastCameraState() {
        // Get delta JSON (only changed fields)
        val stateJson = cameraService.getCameraStateDeltaJson() ?: return

        val message = "event: state\ndata: $stateJson\n\n"
        
        synchronized(sseClientsLock) {
            // Collect inactive clients for removal to avoid concurrent modification
            val toRemove = mutableListOf<SSEClient>()
            
            sseClients.forEach { client ->
                if (client.active) {
                    // Launch coroutine for each client to avoid blocking
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            // Write with timeout to avoid hanging
                            withTimeout(500) {
                                client.channel.writeStringUtf8(message)
                                client.channel.flush()
                            }
                        } catch (e: TimeoutCancellationException) {
                            Log.d(TAG, "SSE client ${client.id} write timeout")
                            client.active = false
                        } catch (e: Exception) {
                            Log.d(TAG, "SSE client ${client.id} write failed: ${e.message}")
                            client.active = false
                        }
                    }
                } else {
                    toRemove.add(client)
                }
            }
            
            // Remove inactive clients
            sseClients.removeAll(toRemove)
        }
    }
    
    // ==================== Asset Loading & Template Engine ====================
    
    /**
     * Load a file from the assets/web directory
     */
    private fun loadAsset(filename: String): String {
        return try {
            context.assets.open("web/$filename").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading asset: $filename", e)
            ""
        }
    }
    
    /**
     * Perform simple template variable substitution
     * Replaces {{variableName}} with the corresponding value from the map
     */
    private fun substituteTemplateVariables(template: String, variables: Map<String, String>): String {
        var result = template
        variables.forEach { (key, value) ->
            result = result.replace("{{$key}}", value)
        }
        return result
    }
    
    // ==================== Route Handlers ====================
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveIndexPage() {
        val activeConns = cameraService.getActiveConnectionsCount()
        val maxConns = cameraService.getMaxConnections()
        val connectionDisplay = "$activeConns/$maxConns"
        val deviceName = cameraService.getDeviceName()
        val displayName = if (deviceName.isNotEmpty()) deviceName else "IP Camera Server"
        val adbInfo = (cameraService as? CameraService)?.getADBConnectionInfo() ?: ""
        
        // Load HTML template from assets
        val htmlTemplate = loadAsset("index.html")
        
        // Prepare template variables
        val variables = mapOf(
            "displayName" to displayName,
            "versionString" to BuildInfo.getVersionString(),
            "buildString" to BuildInfo.getBuildString(),
            "connectionDisplay" to connectionDisplay,
            "adbConnection" to adbInfo
        )
        
        // Substitute variables and serve
        val html = substituteTemplateVariables(htmlTemplate, variables)
        call.respondText(html, ContentType.Text.Html)
    }
    
    /**
     * Serve static assets (CSS, JavaScript) from the web directory
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveStaticAsset(filename: String) {
        val content = loadAsset(filename)
        if (content.isEmpty()) {
            call.respond(HttpStatusCode.NotFound, "Asset not found: $filename")
            return
        }
        
        val contentType = when {
            filename.endsWith(".css") -> ContentType.Text.CSS
            filename.endsWith(".js") -> ContentType.Text.JavaScript
            filename.endsWith(".html") -> ContentType.Text.Html
            else -> ContentType.Text.Plain
        }
        
        call.respondText(content, contentType)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSnapshot() {
        // Check if camera is idle and needs activation
        val cameraState = cameraService.getCameraStateString()
        val needsActivation = cameraState == "IDLE"
        
        if (needsActivation) {
            Log.d(TAG, "Camera IDLE, temporarily activating for snapshot...")
            cameraService.registerMjpegConsumer()
            
            // Poll for camera to become active with timeout
            var attempts = 0
            val maxAttempts = 20 // 2 seconds total (20 * 100ms)
            while (attempts < maxAttempts && cameraService.getCameraStateString() != "ACTIVE") {
                delay(100)
                attempts++
            }
            
            if (cameraService.getCameraStateString() != "ACTIVE") {
                Log.w(TAG, "Camera failed to activate within timeout for snapshot")
            }
        }
        
        val jpegBytes = cameraService.getLastFrameJpegBytes()
        
        if (jpegBytes != null) {
            call.respondBytes(jpegBytes, ContentType.Image.JPEG)
        } else {
            call.respondText(
                "No frame available - Camera may be initializing",
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable
            )
        }
        
        // If we activated camera just for snapshot and no streams are active, deactivate
        if (needsActivation && activeStreams.get() == 0) {
            Log.d(TAG, "No active streams, deactivating camera after snapshot")
            cameraService.unregisterMjpegConsumer()
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveStream() {
        // Check if streaming is allowed based on battery status
        if (!cameraService.isStreamingAllowed()) {
            // Serve battery-limited info page instead of stream
            val batteryMode = cameraService.getBatteryMode()
            val criticalPercent = cameraService.getBatteryCriticalPercent()
            val recoveryPercent = cameraService.getBatteryRecoveryPercent()
            val html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>IP Camera - Streaming Paused</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <meta http-equiv="refresh" content="30">
                    <style>
                        body { font-family: Arial, sans-serif; margin: 0; padding: 20px; background-color: #f0f0f0; text-align: center; }
                        .container { background: white; padding: 40px; border-radius: 8px; max-width: 600px; margin: 0 auto; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                        h1 { color: #d32f2f; margin-bottom: 20px; }
                        .warning-icon { font-size: 64px; margin-bottom: 20px; }
                        p { font-size: 16px; line-height: 1.6; color: #333; margin: 15px 0; }
                        .battery-info { background-color: #ffebee; padding: 15px; border-radius: 4px; margin: 20px 0; border-left: 4px solid #d32f2f; }
                        .action-info { background-color: #e3f2fd; padding: 15px; border-radius: 4px; margin: 20px 0; border-left: 4px solid #2196F3; }
                        button { background-color: #2196F3; color: white; padding: 12px 24px; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; margin: 10px; }
                        button:hover { background-color: #0b7dda; }
                        .note { font-size: 13px; color: #666; font-style: italic; margin-top: 20px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="warning-icon">⚠️🔋</div>
                        <h1>Streaming Paused - Critical Battery</h1>
                        <div class="battery-info">
                            <strong>Battery Status: $batteryMode</strong><br>
                            Camera streaming has been automatically paused to preserve battery and prevent unexpected shutdown.
                        </div>
                        <p>The device battery is critically low. To ensure the device remains accessible, video streaming has been disabled until the battery can recharge.</p>
                        <div class="action-info">
                            <strong>What you can do:</strong>
                            <ul style="text-align: left; margin: 10px 20px;">
                                <li><strong>Recommended:</strong> Connect the device to a power source. Streaming will automatically resume when battery reaches $recoveryPercent%.</li>
                                <li><strong>Manual Override:</strong> If battery is above $criticalPercent%, you can manually restore streaming using the button below. Note: Streaming will pause again if battery drops below $criticalPercent%.</li>
                            </ul>
                        </div>
                        <button onclick="overrideBattery()">Manually Restore Streaming (if battery &gt; $criticalPercent%)</button>
                        <button onclick="checkStatus()">Check Current Status</button>
                        <p class="note">This page will automatically refresh every 30 seconds to check if normal operation has resumed.</p>
                        <p class="note">Server remains accessible for configuration and status checks. Only video streaming is paused.</p>
                    </div>
                    <script>
                        function overrideBattery() {
                            fetch('/overrideBatteryLimit')
                                .then(response => response.json())
                                .then(data => {
                                    if (data.status === 'ok') {
                                        alert(data.message + '\\n\\nThe page will now reload to start streaming.');
                                        window.location.reload();
                                    } else {
                                        alert('Override failed: ' + data.message);
                                    }
                                })
                                .catch(error => {
                                    alert('Error: ' + error);
                                });
                        }
                        
                        function checkStatus() {
                            fetch('/status')
                                .then(response => response.json())
                                .then(data => {
                                    const info = 'Battery Mode: ' + data.batteryMode + '\\n' +
                                                'Streaming Allowed: ' + data.streamingAllowed + '\\n\\n' +
                                                'The page will refresh automatically if streaming becomes available.';
                                    alert(info);
                                })
                                .catch(error => {
                                    alert('Error checking status: ' + error);
                                });
                        }
                    </script>
                </body>
                </html>
            """.trimIndent()
            
            call.respondText(html, ContentType.Text.Html)
            return
        }
        
        // Normal streaming logic
        val clientId = clientIdCounter.incrementAndGet()
        val isFirstStream = activeStreams.incrementAndGet() == 1
        
        // Register MJPEG consumer when first stream connects
        if (isFirstStream) {
            Log.d(TAG, "First MJPEG stream connecting, registering consumer...")
            cameraService.registerMjpegConsumer()
        }
        
        Log.d(TAG, "Stream connection opened. Client $clientId. Active streams: ${activeStreams.get()}")
        
        call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--jpgboundary")) {
            try {
                while (isActive) {
                    // Check if streaming is still allowed (battery might have dropped during stream)
                    if (!cameraService.isStreamingAllowed()) {
                        Log.d(TAG, "Stream client $clientId - streaming no longer allowed (critical battery)")
                        break
                    }
                    
                    val jpegBytes = cameraService.getLastFrameJpegBytes()
                    
                    if (jpegBytes != null) {
                        try {
                            writeStringUtf8("--jpgboundary\r\n")
                            writeStringUtf8("Content-Type: image/jpeg\r\n")
                            writeStringUtf8("Content-Length: ${jpegBytes.size}\r\n\r\n")
                            writeFully(jpegBytes, 0, jpegBytes.size)
                            writeStringUtf8("\r\n")
                            flush()
                            
                            // Track bandwidth
                            cameraService.recordBytesSent(clientId, jpegBytes.size.toLong())
                            
                            // Track MJPEG streaming FPS
                            cameraService.recordMjpegFrameServed()
                        } catch (e: Exception) {
                            Log.d(TAG, "Stream client $clientId disconnected")
                            break
                        }
                    }
                    
                    // Use dynamic frame delay based on target MJPEG FPS
                    val targetFps = cameraService.getTargetMjpegFps()
                    val frameDelayMs = 1000L / targetFps
                    delay(frameDelayMs)
                }
            } finally {
                cameraService.removeClient(clientId)
                val remainingStreams = activeStreams.decrementAndGet()
                Log.d(TAG, "Stream connection closed. Client $clientId. Active streams: $remainingStreams")
                
                // Unregister MJPEG consumer when last stream disconnects
                if (remainingStreams == 0) {
                    Log.d(TAG, "Last MJPEG stream disconnected, unregistering consumer...")
                    cameraService.unregisterMjpegConsumer()
                }
            }
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSwitch() {
        val newCamera = if (cameraService.getCurrentCamera() == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        cameraService.switchCamera(newCamera)
        
        val cameraName = if (cameraService.getCurrentCamera() == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        call.respondText(
            """{"status": "ok", "camera": "$cameraName"}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveStatus() {
        val cameraName = if (cameraService.getCurrentCamera() == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
        val activeConns = cameraService.getActiveConnectionsCount()
        val maxConns = cameraService.getMaxConnections()
        val activeStreamCount = activeStreams.get()
        val sseCount = synchronized(sseClientsLock) { sseClients.size }
        val batteryMode = cameraService.getBatteryMode()
        val streamingAllowed = cameraService.isStreamingAllowed()
        val deviceName = cameraService.getDeviceName()
        val cameraState = cameraService.getCameraStateString()
        
        val endpoints = "[\"/\", \"/snapshot\", \"/stream\", \"/switch\", \"/status\", \"/events\", \"/toggleFlashlight\", \"/flashOn\", \"/flashOff\", \"/formats\", \"/connections\", \"/stats\", \"/overrideBatteryLimit\", \"/cameraState\", \"/activateCamera\", \"/deactivateCamera\", \"/checkUpdate\", \"/triggerUpdate\", \"/reboot\"]"
        
        val json = """
            {
                "status": "running",
                "server": "Ktor",
                "deviceName": "$deviceName",
                "camera": "$cameraName",
                "cameraState": "$cameraState",
                "url": "${cameraService.getServerUrl()}",
                "resolution": "${cameraService.getSelectedResolutionLabel()}",
                "flashlightAvailable": ${cameraService.isFlashlightAvailable()},
                "flashlightOn": ${cameraService.isFlashlightEnabled()},
                "activeConnections": $activeConns,
                "maxConnections": $maxConns,
                "connections": "$activeConns/$maxConns",
                "activeStreams": $activeStreamCount,
                "activeSSEClients": $sseCount,
                "batteryMode": "$batteryMode",
                "streamingAllowed": $streamingAllowed,
                "endpoints": $endpoints,
                "version": {
                    "versionName": "${BuildInfo.versionName}",
                    "versionCode": ${BuildInfo.versionCode},
                    "commitHash": "${BuildInfo.commitHash}",
                    "branch": "${BuildInfo.branch}",
                    "buildTimestamp": "${BuildInfo.buildTimestamp}",
                    "buildNumber": ${BuildInfo.buildNumber}
                }
            }
        """.trimIndent()
        
        call.respondText(json, ContentType.Application.Json)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSSE() {
        val clientId = System.currentTimeMillis()
        Log.d(TAG, "SSE client $clientId connected")
        
        call.respondBytesWriter(ContentType.Text.EventStream) {
            val client = SSEClient(clientId, this)
            
            synchronized(sseClientsLock) {
                sseClients.add(client)
            }
            
            try {
                // Send initial connection count
                val activeConns = cameraService.getActiveConnectionsCount()
                val maxConns = cameraService.getMaxConnections()
                writeStringUtf8("data: {\"connections\":\"$activeConns/$maxConns\",\"active\":$activeConns,\"max\":$maxConns}\n\n")
                flush()
                
                // Send initial camera state (full state for new clients)
                val stateJson = cameraService.getCameraStateJson()
                writeStringUtf8("event: state\ndata: $stateJson\n\n")
                flush()
                
                // Initialize last broadcast state with current values to prevent
                // full state from being sent again on next delta broadcast
                cameraService.initializeLastBroadcastState()
                
                // Keep connection alive with periodic keepalive
                while (client.active && isActive) {
                    delay(30000)
                    writeStringUtf8(": keepalive\n\n")
                    flush()
                }
            } catch (e: Exception) {
                Log.d(TAG, "SSE client $clientId disconnected: ${e.message}")
            } finally {
                client.active = false
                synchronized(sseClientsLock) {
                    sseClients.remove(client)
                }
                Log.d(TAG, "SSE client $clientId closed. Remaining SSE clients: ${sseClients.size}")
            }
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveConnections() {
        val activeStreamCount = activeStreams.get()
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
        
        // Add SSE clients
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
        call.respondText(json, ContentType.Application.Json)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveCloseConnection() {
        val idStr = call.parameters["id"]
        
        if (idStr == null) {
            call.respondText(
                """{"status":"error","message":"Missing id parameter"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val id = idStr.toLongOrNull()
        if (id == null) {
            call.respondText(
                """{"status":"error","message":"Invalid id parameter"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
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
        
        if (closed) {
            call.respondText(
                """{"status":"ok","message":"Connection closed","id":$id}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"info","message":"Connection not found or cannot be closed. Only SSE connections can be manually closed.","id":$id}""",
                ContentType.Application.Json
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveFormats() {
        val formats = cameraService.getSupportedResolutions()
        val jsonFormats = formats.joinToString(",") {
            val label = cameraService.sizeLabel(it)
            """{"value":"$label","label":"$label"}"""
        }
        val selected = cameraService.getSelectedResolution()?.let { "\"${cameraService.sizeLabel(it)}\"" } ?: "null"
        val json = """
            {
                "formats": [$jsonFormats],
                "selected": $selected
            }
        """.trimIndent()
        call.respondText(json, ContentType.Application.Json)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetFormat() {
        val value = call.parameters["value"]
        if (value.isNullOrBlank()) {
            cameraService.setResolutionAndRebind(null)
            call.respondText(
                """{"status":"ok","message":"Resolution reset to auto"}""",
                ContentType.Application.Json
            )
            return
        }
        
        if (value.length > 32) {
            call.respondText(
                """{"status":"error","message":"Format value too long"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val parts = value.lowercase(Locale.getDefault()).split("x")
        val width = parts.getOrNull(0)?.toIntOrNull()
        val height = parts.getOrNull(1)?.toIntOrNull()
        
        if (parts.size != 2 || width == null || height == null || width <= 0 || height <= 0) {
            call.respondText(
                """{"status":"error","message":"Invalid format. Use format: WIDTHxHEIGHT (e.g., 1920x1080)"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val desired = Size(width, height)
        val supported = cameraService.getSupportedResolutions()
        
        val exactMatch = supported.any { it.width == desired.width && it.height == desired.height }
        
        if (exactMatch) {
            cameraService.setResolutionAndRebind(desired)
            Log.d(TAG, "Resolution set via HTTP to ${cameraService.sizeLabel(desired)}")
            call.respondText(
                """{"status":"ok","message":"Resolution set to ${cameraService.sizeLabel(desired)}"}""",
                ContentType.Application.Json
            )
        } else {
            val availableFormats = supported.take(5).joinToString(", ") { cameraService.sizeLabel(it) }
            call.respondText(
                """{"status":"error","message":"Resolution not supported. Available: $availableFormats"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetCameraOrientation() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val newOrientation = when (value) {
            "landscape", null -> "landscape"
            "portrait" -> "portrait"
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid orientation. Use portrait or landscape"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setCameraOrientation(newOrientation)
        call.respondText(
            """{"status":"ok","message":"Camera orientation set to $newOrientation","cameraOrientation":"$newOrientation"}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetRotation() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val newRotation = when (value) {
            "0", null -> 0
            "90" -> 90
            "180" -> 180
            "270" -> 270
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid rotation value. Use 0, 90, 180, or 270"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setRotation(newRotation)
        call.respondText(
            """{"status":"ok","message":"Rotation set to ${newRotation}°","rotation":"$newRotation"}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetResolutionOverlay() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val showOverlay = when (value) {
            "true", "1", "yes" -> true
            "false", "0", "no", null -> false
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid value. Use true or false"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setShowResolutionOverlay(showOverlay)
        call.respondText(
            """{"status":"ok","message":"Resolution overlay ${if (showOverlay) "enabled" else "disabled"}","showResolutionOverlay":$showOverlay}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetDateTimeOverlay() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val showOverlay = when (value) {
            "true", "1", "yes" -> true
            "false", "0", "no", null -> false
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid value. Use true or false"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setShowDateTimeOverlay(showOverlay)
        call.respondText(
            """{"status":"ok","message":"Date/time overlay ${if (showOverlay) "enabled" else "disabled"}","showDateTimeOverlay":$showOverlay}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetBatteryOverlay() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val showOverlay = when (value) {
            "true", "1", "yes" -> true
            "false", "0", "no", null -> false
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid value. Use true or false"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setShowBatteryOverlay(showOverlay)
        call.respondText(
            """{"status":"ok","message":"Battery overlay ${if (showOverlay) "enabled" else "disabled"}","showBatteryOverlay":$showOverlay}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetFpsOverlay() {
        val value = call.parameters["value"]?.lowercase(Locale.getDefault())
        
        val showOverlay = when (value) {
            "true", "1", "yes" -> true
            "false", "0", "no", null -> false
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid value. Use true or false"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setShowFpsOverlay(showOverlay)
        call.respondText(
            """{"status":"ok","message":"FPS overlay ${if (showOverlay) "enabled" else "disabled"}","showFpsOverlay":$showOverlay}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetMjpegFps() {
        val valueStr = call.parameters["value"]
        
        if (valueStr == null) {
            call.respondText(
                """{"status":"error","message":"Missing value parameter. Use ?value=N where N is FPS (1-60)"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val fps = valueStr.toIntOrNull()
        if (fps == null || fps < 1 || fps > 60) {
            call.respondText(
                """{"status":"error","message":"FPS must be between 1 and 60"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        cameraService.setTargetMjpegFps(fps)
        call.respondText(
            """{"status":"ok","message":"MJPEG target FPS set to $fps","targetMjpegFps":$fps}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetRtspFps() {
        val valueStr = call.parameters["value"]
        
        if (valueStr == null) {
            call.respondText(
                """{"status":"error","message":"Missing value parameter. Use ?value=N where N is FPS (1-60)"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val fps = valueStr.toIntOrNull()
        if (fps == null || fps < 1 || fps > 60) {
            call.respondText(
                """{"status":"error","message":"FPS must be between 1 and 60"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        cameraService.setTargetRtspFps(fps)
        call.respondText(
            """{"status":"ok","message":"RTSP target FPS set to $fps. RTSP server must be restarted for changes to take effect.","targetRtspFps":$fps,"requiresRtspRestart":true}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetMaxConnections() {
        val valueStr = call.parameters["value"]
        
        if (valueStr == null) {
            call.respondText(
                """{"status":"error","message":"Missing value parameter"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val newMax = valueStr.toIntOrNull()
        if (newMax == null || newMax < 4 || newMax > 100) {
            call.respondText(
                """{"status":"error","message":"Max connections must be between 4 and 100"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val changed = cameraService.setMaxConnections(newMax)
        if (changed) {
            call.respondText(
                """{"status":"ok","message":"Max connections set to $newMax. Server restart required for changes to take effect.","maxConnections":$newMax,"requiresRestart":true}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"ok","message":"Max connections already set to $newMax","maxConnections":$newMax,"requiresRestart":false}""",
                ContentType.Application.Json
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveToggleFlashlight() {
        if (!cameraService.isFlashlightAvailable()) {
            call.respondText(
                """{"status":"error","message":"Flashlight not available. Ensure back camera is selected and device has flash unit.","available":false}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val newState = cameraService.toggleFlashlight()
        call.respondText(
            """{"status":"ok","message":"Flashlight ${if (newState) "enabled" else "disabled"}","flashlight":$newState}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveFlashlightOn() {
        if (!cameraService.isFlashlightAvailable()) {
            call.respondText(
                """{"status":"error","message":"Flashlight not available. Ensure back camera is selected and device has flash unit.","available":false}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val success = (cameraService as? CameraService)?.setFlashlight(true) ?: false
        if (success) {
            call.respondText(
                """{"status":"ok","message":"Flashlight turned on","flashlight":true}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to turn on flashlight"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveFlashlightOff() {
        if (!cameraService.isFlashlightAvailable()) {
            call.respondText(
                """{"status":"error","message":"Flashlight not available. Ensure back camera is selected and device has flash unit.","available":false}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val success = (cameraService as? CameraService)?.setFlashlight(false) ?: false
        if (success) {
            call.respondText(
                """{"status":"ok","message":"Flashlight turned off","flashlight":false}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to turn off flashlight"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveRestartServer() {
        cameraService.restartServer()
        call.respondText(
            """{"status":"ok","message":"Server restart initiated. Please wait 2-3 seconds before reconnecting."}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveDetailedStats() {
        val stats = cameraService.getDetailedStats()
        call.respondText(stats, ContentType.Text.Plain)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveEnableAdaptiveQuality() {
        cameraService.setAdaptiveQualityEnabled(true)
        call.respondText(
            """{"status":"ok","message":"Adaptive quality enabled","adaptiveQualityEnabled":true}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveDisableAdaptiveQuality() {
        cameraService.setAdaptiveQualityEnabled(false)
        call.respondText(
            """{"status":"ok","message":"Adaptive quality disabled. Using fixed quality settings.","adaptiveQualityEnabled":false}""",
            ContentType.Application.Json
        )
    }
    
    // ==================== RTSP Streaming Endpoints ====================
    
    /**
     * Enable RTSP streaming
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveEnableRTSP() {
        val success = cameraService.enableRTSPStreaming()
        
        if (success) {
            val metrics = cameraService.getRTSPMetrics()
            val rtspUrl = cameraService.getRTSPUrl()
            val encoderName = metrics?.encoderName?.replace("\"", "\\\"")?.replace("\n", "\\n") ?: "unknown"
            val colorFormat = metrics?.colorFormat?.replace("\"", "\\\"") ?: "unknown"
            val colorFormatHex = metrics?.colorFormatHex ?: "unknown"
            
            call.respondText(
                """{"status":"ok","message":"RTSP streaming enabled","rtspEnabled":true,"encoder":"$encoderName","isHardware":${metrics?.isHardware ?: false},"colorFormat":"$colorFormat","colorFormatHex":"$colorFormatHex","url":"$rtspUrl","port":8554}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to enable RTSP streaming. Check logs for details.","rtspEnabled":false}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Disable RTSP streaming
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveDisableRTSP() {
        cameraService.disableRTSPStreaming()
        call.respondText(
            """{"status":"ok","message":"RTSP streaming disabled","rtspEnabled":false}""",
            ContentType.Application.Json
        )
    }
    
    /**
     * Get RTSP status and metrics
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveRTSPStatus() {
        val rtspEnabled = cameraService.isRTSPEnabled()
        val metrics = cameraService.getRTSPMetrics()
        val rtspUrl = cameraService.getRTSPUrl()
        
        if (rtspEnabled && metrics != null) {
            val encoderName = metrics.encoderName.replace("\"", "\\\"").replace("\n", "\\n")
            val colorFormat = metrics.colorFormat.replace("\"", "\\\"")
            val colorFormatHex = metrics.colorFormatHex
            val resolution = metrics.resolution
            val bitrateMbps = metrics.bitrateMbps
            val bitrateMode = metrics.bitrateMode
            call.respondText(
                """{"status":"ok","rtspEnabled":true,"encoder":"$encoderName","isHardware":${metrics.isHardware},"colorFormat":"$colorFormat","colorFormatHex":"$colorFormatHex","resolution":"$resolution","bitrateMbps":$bitrateMbps,"bitrateMode":"$bitrateMode","activeSessions":${metrics.activeSessions},"playingSessions":${metrics.playingSessions},"framesEncoded":${metrics.framesEncoded},"droppedFrames":${metrics.droppedFrames},"targetFps":${metrics.targetFps},"encodedFps":${metrics.encodedFps},"url":"$rtspUrl","port":8554}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"ok","rtspEnabled":false,"message":"RTSP streaming is not enabled"}""",
                ContentType.Application.Json
            )
        }
    }
    
    /**
     * Set RTSP bitrate (in Mbps)
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetRTSPBitrate() {
        val mbps = call.request.queryParameters["value"]?.toFloatOrNull()
        
        if (mbps == null || mbps <= 0) {
            call.respondText(
                """{"status":"error","message":"Invalid bitrate value. Provide ?value=X where X is bitrate in Mbps (e.g., 3.5)"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val success = cameraService.setRTSPBitrate((mbps * 1_000_000).toInt())
        
        if (success) {
            call.respondText(
                """{"status":"ok","message":"RTSP bitrate set to $mbps Mbps","bitrateMbps":$mbps}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to set RTSP bitrate. Ensure RTSP is enabled."}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Set RTSP bitrate mode (VBR or CBR)
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetRTSPBitrateMode() {
        val mode = call.request.queryParameters["value"]?.uppercase()
        
        if (mode == null || (mode != "VBR" && mode != "CBR" && mode != "CQ")) {
            call.respondText(
                """{"status":"error","message":"Invalid mode. Use ?value=VBR, ?value=CBR, or ?value=CQ"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val success = cameraService.setRTSPBitrateMode(mode)
        
        if (success) {
            call.respondText(
                """{"status":"ok","message":"RTSP bitrate mode set to $mode","bitrateMode":"$mode"}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to set RTSP bitrate mode. Ensure RTSP is enabled."}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    // ==================== End RTSP Streaming Endpoints ====================
    
    // ==================== Battery Management Endpoints ====================
    
    /**
     * Override critical battery limit and restore streaming
     * Only works if battery > CRITICAL threshold
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveOverrideBatteryLimit() {
        val criticalPercent = cameraService.getBatteryCriticalPercent()
        val success = cameraService.overrideBatteryLimit()
        
        if (success) {
            call.respondText(
                """{"status":"ok","message":"Streaming restored successfully. Streaming will pause again if battery drops below $criticalPercent%.","streamingAllowed":true}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Cannot override: battery level is still too low (≤$criticalPercent%). Please charge the device or wait for battery to exceed $criticalPercent%.","streamingAllowed":false}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
        }
    }
    
    // ==================== End Battery Management Endpoints ====================
    
    // ==================== Camera State Management Endpoints ====================
    
    /**
     * Get current camera state (IDLE, INITIALIZING, ACTIVE, STOPPING, ERROR)
     * Includes consumer count for debugging
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveCameraState() {
        val cameraState = cameraService.getCameraStateString()
        val activeStreamCount = activeStreams.get()
        
        call.respondText(
            """{"status":"ok","cameraState":"$cameraState","mjpegStreams":$activeStreamCount,"message":"Camera state retrieved"}""",
            ContentType.Application.Json
        )
    }
    
    /**
     * Manually activate camera (for testing/debugging)
     * This registers a temporary consumer to keep camera active
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveActivateCamera() {
        try {
            cameraService.manualActivateCamera()
            val cameraState = cameraService.getCameraStateString()
            call.respondText(
                """{"status":"ok","cameraState":"$cameraState","message":"Camera activation requested - registered MANUAL consumer"}""",
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error activating camera", e)
            call.respondText(
                """{"status":"error","message":"Failed to activate camera: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Manually deactivate camera (for testing/debugging)
     * This unregisters the temporary consumer, camera will stop if no other consumers
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveDeactivateCamera() {
        try {
            cameraService.manualDeactivateCamera()
            val cameraState = cameraService.getCameraStateString()
            call.respondText(
                """{"status":"ok","cameraState":"$cameraState","message":"Camera deactivation requested - unregistered MANUAL consumer. Camera will stop if no other consumers remain."}""",
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deactivating camera", e)
            call.respondText(
                """{"status":"error","message":"Failed to deactivate camera: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Manual camera reset endpoint
     * Triggers a full camera service reset to recover from frozen/broken camera states
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveResetCamera() {
        try {
            Log.i(TAG, "Manual camera reset requested via API")
            val success = cameraService.fullCameraReset()
            val cameraState = cameraService.getCameraStateString()
            
            if (success) {
                call.respondText(
                    """{"status":"ok","cameraState":"$cameraState","message":"Camera reset successfully initiated"}""",
                    ContentType.Application.Json
                )
            } else {
                call.respondText(
                    """{"status":"error","cameraState":"$cameraState","message":"Camera reset failed"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting camera", e)
            call.respondText(
                """{"status":"error","message":"Failed to reset camera: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    // ==================== End Camera State Management Endpoints ====================
    
    // ==================== Auto Update Endpoints ====================
    
    /**
     * Check if a software update is available from GitHub Releases.
     * 
     * This endpoint queries the GitHub API for the latest release and compares
     * the version with the current BUILD_NUMBER. Returns update information
     * including version details, download size, and release notes.
     * 
     * Response includes:
     * - status: "ok" or "error"
     * - updateAvailable: boolean indicating if update is available
     * - currentVersion: current BUILD_NUMBER
     * - latestVersion: latest BUILD_NUMBER from GitHub
     * - latestVersionName: human-readable version name
     * - apkSize: size of APK in bytes
     * - releaseNotes: release notes from GitHub Release
     * 
     * Example successful response:
     * {
     *   "status": "ok",
     *   "updateAvailable": true,
     *   "currentVersion": 20260117120000,
     *   "latestVersion": 20260117140000,
     *   "latestVersionName": "Build 20260117140000",
     *   "apkSize": 5242880,
     *   "releaseNotes": "Automated build from commit abc123..."
     * }
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveCheckUpdate() {
        try {
            val updateManager = UpdateManager(this@HttpServer.context)
            val updateInfo = updateManager.checkForUpdate()
            
            if (updateInfo != null) {
                // Escape release notes for JSON (replace quotes and newlines)
                val escapedNotes = updateInfo.releaseNotes
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                
                val json = """
                {
                    "status": "ok",
                    "updateAvailable": ${updateInfo.isUpdateAvailable},
                    "currentVersion": ${BuildInfo.buildNumber},
                    "latestVersion": ${updateInfo.latestVersionCode},
                    "latestVersionName": "${updateInfo.latestVersionName}",
                    "apkSize": ${updateInfo.apkSize},
                    "releaseNotes": "$escapedNotes"
                }
                """.trimIndent()
                
                call.respondText(json, ContentType.Application.Json)
            } else {
                call.respondText(
                    """{"status":"error","message":"Failed to check for updates. Please check network connectivity."}""",
                    ContentType.Application.Json,
                    HttpStatusCode.InternalServerError
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            call.respondText(
                """{"status":"error","message":"Error checking for update: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Trigger the update process: check, download, and install.
     * 
     * This endpoint initiates the complete update flow in the background:
     * 1. Checks GitHub API for latest release
     * 2. Downloads APK if update is available
     * 3. Triggers Android package installer (requires user confirmation)
     * 
     * The update process runs asynchronously, so this endpoint returns immediately.
     * The actual download and installation happen in the background.
     * 
     * User will see Android's installation prompt when the download completes.
     * 
     * Response:
     * {
     *   "status": "ok",
     *   "message": "Update check initiated. If update is available, installation will be prompted."
     * }
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveTriggerUpdate() {
        try {
            val updateManager = UpdateManager(this@HttpServer.context)
            
            // Launch update in scoped coroutine
            serverScope.launch {
                val success = updateManager.performUpdate()
                if (success) {
                    Log.i(TAG, "Update triggered successfully")
                } else {
                    Log.i(TAG, "No update available or update failed")
                }
            }
            
            call.respondText(
                """{"status":"ok","message":"Update check initiated. If update is available, installation will be prompted."}""",
                ContentType.Application.Json
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering update", e)
            call.respondText(
                """{"status":"error","message":"Error triggering update: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveReboot() {
        try {
            Log.i(TAG, "Remote reboot requested via HTTP API")
            
            // Use RebootHelper for comprehensive diagnostics and multiple fallback methods
            val result = RebootHelper.rebootDevice(this@HttpServer.context)
            
            when (result) {
                is RebootResult.Success -> {
                    // Respond before device reboots
                    call.respondText(
                        result.toJson(),
                        ContentType.Application.Json
                    )
                }
                is RebootResult.NotDeviceOwner -> {
                    Log.w(TAG, "Reboot failed: Not Device Owner")
                    call.respondText(
                        result.toJson(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                }
                is RebootResult.DeviceLocked -> {
                    Log.w(TAG, "Reboot failed: Device is locked")
                    call.respondText(
                        result.toJson(),
                        ContentType.Application.Json,
                        HttpStatusCode.BadRequest
                    )
                }
                is RebootResult.SecurityException -> {
                    Log.e(TAG, "Reboot failed: ${result.message}")
                    call.respondText(
                        result.toJson(),
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden
                    )
                }
                is RebootResult.AllMethodsFailed -> {
                    Log.e(TAG, "All reboot methods failed")
                    call.respondText(
                        result.toJson(),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing reboot request", e)
            call.respondText(
                """{"status":"error","message":"Error processing reboot request: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Diagnostic endpoint for camera status
     * Returns detailed information about camera health and state
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveCameraDiagnostics() {
        try {
            val cameraState = cameraService.getCameraStateString()
            val consumerCount = cameraService.getTotalCameraClientCount()
            val lastFrameBytes = cameraService.getLastFrameJpegBytes()
            val currentFps = cameraService.getCurrentFps()
            
            // Check camera permission
            val permissionGranted = context.checkSelfPermission(android.Manifest.permission.CAMERA) == 
                android.content.pm.PackageManager.PERMISSION_GRANTED
            
            // Create diagnostics JSON
            val diagnostics = """
                {
                    "status": "ok",
                    "cameraState": "$cameraState",
                    "consumerCount": $consumerCount,
                    "hasLastFrame": ${lastFrameBytes != null},
                    "lastFrameSizeBytes": ${lastFrameBytes?.size ?: 0},
                    "currentFps": $currentFps,
                    "permissionGranted": $permissionGranted,
                    "mjpegClients": ${cameraService.getMjpegClientCount()},
                    "rtspClients": ${cameraService.getRtspClientCount()},
                    "rtspEnabled": ${cameraService.isRTSPEnabled()},
                    "serverUrl": "${cameraService.getServerUrl()}",
                    "deviceName": "${cameraService.getDeviceName()}"
                }
            """.trimIndent()
            
            call.respondText(
                diagnostics,
                ContentType.Application.Json
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating camera diagnostics", e)
            call.respondText(
                """{"status":"error","message":"Error generating diagnostics: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    /**
     * Diagnostic endpoint for reboot capability
     * Returns detailed information about device reboot capability and restrictions
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveRebootDiagnostics() {
        try {
            val diagnostics = RebootHelper.diagnoseRebootCapability(this@HttpServer.context)
            
            call.respondText(
                """{"status":"ok","diagnostics":${diagnostics.toJson()}}""",
                ContentType.Application.Json
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating reboot diagnostics", e)
            call.respondText(
                """{"status":"error","message":"Error generating diagnostics: ${e.message}"}""",
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }
    
    // ==================== End Auto Update Endpoints ====================
}
