package com.ipcam

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
 */
class HttpServer(
    private val port: Int,
    private val cameraService: CameraServiceInterface
) {
    private var server: ApplicationEngine? = null
    private val activeStreams = AtomicInteger(0)
    private val sseClients = mutableListOf<SSEClient>()
    private val sseClientsLock = Any()
    private val clientIdCounter = AtomicLong(0)
    
    companion object {
        private const val TAG = "HttpServer"
        private const val STREAM_FRAME_DELAY_MS = 100L // ~10 fps
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
                    Log.e(TAG, "Request failed: ${call.request.uri}", cause)
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
                
                // Camera snapshot
                get("/snapshot") { serveSnapshot() }
                
                // MJPEG stream
                get("/stream") { serveStream() }
                
                // Camera control
                get("/switch") { serveSwitch() }
                get("/toggleFlashlight") { serveToggleFlashlight() }
                
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
                
                // Server configuration
                get("/setMaxConnections") { serveSetMaxConnections() }
                get("/restart") { serveRestartServer() }
                
                // Adaptive quality control
                get("/enableAdaptiveQuality") { serveEnableAdaptiveQuality() }
                get("/disableAdaptiveQuality") { serveDisableAdaptiveQuality() }
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
        
        Log.d(TAG, "Ktor server stopped")
    }
    
    /**
     * Check if server is running
     */
    fun isAlive(): Boolean = server != null
    
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
     */
    fun broadcastCameraState() {
        val stateJson = cameraService.getCameraStateJson()
        val message = "event: state\ndata: $stateJson\n\n"
        
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
    
    // ==================== Route Handlers ====================
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveIndexPage() {
        val activeConns = cameraService.getActiveConnectionsCount()
        val maxConns = cameraService.getMaxConnections()
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
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>IP Camera Server</h1>
                    <div class="status-info">
                        <strong>Server Status:</strong> Running (Ktor) | 
                        <strong>Active Connections:</strong> <span id="connectionCount">$connectionDisplay</span>
                    </div>
                    <p class="note"><em>Connection count updates in real-time via Server-Sent Events. Initial count: $connectionDisplay</em></p>
                    <h2>Live Stream</h2>
                    <div id="streamContainer" style="text-align: center; background: #000; min-height: 300px; display: flex; align-items: center; justify-content: center;">
                        <img id="stream" style="display: none; max-width: 100%; height: auto;" alt="Camera Stream">
                        <div id="streamPlaceholder" style="color: #888; font-size: 18px;">Click "Start Stream" to begin</div>
                    </div>
                    <br>
                    <div class="row">
                        <button id="toggleStreamBtn" onclick="toggleStream()">Start Stream</button>
                        <button onclick="reloadStream()">Refresh</button>
                        <button onclick="switchCamera()">Switch Camera</button>
                        <button id="flashlightButton" onclick="toggleFlashlight()">Toggle Flashlight</button>
                    </div>
                    <p class="note">Overlay shows date/time (top left) and battery status (top right). Stream auto-reconnects if interrupted.</p>
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
                        <strong>Toggle Flashlight:</strong> <a href="/toggleFlashlight" target="_blank"><code>GET /toggleFlashlight</code></a><br>
                        Toggle flashlight on/off for back camera
                    </div>
                    <h2>Keep the stream alive</h2>
                    <ul>
                        <li>Disable battery optimizations for IP_Cam in Android Settings &gt; Battery</li>
                        <li>Allow background activity and keep the phone plugged in for long sessions</li>
                    </ul>
                </div>
                <script>
                    const streamImg = document.getElementById('stream');
                    const streamPlaceholder = document.getElementById('streamPlaceholder');
                    const toggleStreamBtn = document.getElementById('toggleStreamBtn');
                    let streamActive = false;

                    function toggleStream() {
                        if (streamActive) {
                            stopStream();
                        } else {
                            startStream();
                        }
                    }

                    function startStream() {
                        streamImg.src = '/stream?ts=' + Date.now();
                        streamImg.style.display = 'block';
                        streamPlaceholder.style.display = 'none';
                        toggleStreamBtn.textContent = 'Stop Stream';
                        toggleStreamBtn.style.backgroundColor = '#f44336';
                        streamActive = true;
                    }

                    function stopStream() {
                        streamImg.src = '';
                        streamImg.style.display = 'none';
                        streamPlaceholder.style.display = 'block';
                        toggleStreamBtn.textContent = 'Start Stream';
                        toggleStreamBtn.style.backgroundColor = '#4CAF50';
                        streamActive = false;
                    }

                    function reloadStream() {
                        if (streamActive) {
                            streamImg.src = '/stream?ts=' + Date.now();
                        }
                    }
                    
                    function switchCamera() {
                        fetch('/switch')
                            .then(response => response.json())
                            .then(data => {
                                console.log('Switched to ' + data.camera + ' camera');
                                if (streamActive) {
                                    setTimeout(reloadStream, 200);
                                }
                            });
                    }
                    
                    function toggleFlashlight() {
                        fetch('/toggleFlashlight')
                            .then(response => response.json())
                            .then(data => console.log(data.message));
                    }
                    
                    // Set up Server-Sent Events for real-time connection count updates
                    const eventSource = new EventSource('/events');
                    eventSource.onmessage = function(event) {
                        try {
                            const data = JSON.parse(event.data);
                            const connectionCount = document.getElementById('connectionCount');
                            if (connectionCount && data.connections) {
                                connectionCount.textContent = data.connections;
                            }
                        } catch (e) {
                            console.error('Failed to parse SSE data:', e);
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
        
        call.respondText(html, ContentType.Text.Html)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSnapshot() {
        val jpegBytes = cameraService.getLastFrameJpegBytes()
        
        if (jpegBytes != null) {
            call.respondBytes(jpegBytes, ContentType.Image.JPEG)
        } else {
            call.respondText(
                "No frame available",
                ContentType.Text.Plain,
                HttpStatusCode.ServiceUnavailable
            )
        }
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveStream() {
        val clientId = clientIdCounter.incrementAndGet()
        activeStreams.incrementAndGet()
        Log.d(TAG, "Stream connection opened. Client $clientId. Active streams: ${activeStreams.get()}")
        
        call.respondBytesWriter(ContentType.parse("multipart/x-mixed-replace; boundary=--jpgboundary")) {
            try {
                while (isActive) {
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
                        } catch (e: Exception) {
                            Log.d(TAG, "Stream client $clientId disconnected")
                            break
                        }
                    }
                    
                    delay(STREAM_FRAME_DELAY_MS)
                }
            } finally {
                cameraService.removeClient(clientId)
                activeStreams.decrementAndGet()
                Log.d(TAG, "Stream connection closed. Client $clientId. Active streams: ${activeStreams.get()}")
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
        
        val json = """
            {
                "status": "running",
                "server": "Ktor",
                "camera": "$cameraName",
                "url": "${cameraService.getServerUrl()}",
                "resolution": "${cameraService.getSelectedResolutionLabel()}",
                "flashlightAvailable": ${cameraService.isFlashlightAvailable()},
                "flashlightOn": ${cameraService.isFlashlightEnabled()},
                "activeConnections": $activeConns,
                "maxConnections": $maxConns,
                "connections": "$activeConns/$maxConns",
                "activeStreams": $activeStreamCount,
                "activeSSEClients": $sseCount,
                "endpoints": ["/", "/snapshot", "/stream", "/switch", "/status", "/events", "/toggleFlashlight", "/formats", "/connections", "/stats"]
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
                
                // Send initial camera state
                val stateJson = cameraService.getCameraStateJson()
                writeStringUtf8("event: state\ndata: $stateJson\n\n")
                flush()
                
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
}
