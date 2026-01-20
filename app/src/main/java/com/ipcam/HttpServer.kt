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
                
                // Camera snapshot
                get("/snapshot") { serveSnapshot() }
                
                // MJPEG stream
                get("/stream") { serveStream() }
                
                // Camera control
                get("/switch") { serveSwitch() }
                get("/toggleFlashlight") { serveToggleFlashlight() }
                get("/activateCamera") { serveActivateCamera() }
                
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
    
    // ==================== Route Handlers ====================
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveIndexPage() {
        val activeConns = cameraService.getActiveConnectionsCount()
        val maxConns = cameraService.getMaxConnections()
        val connectionDisplay = "$activeConns/$maxConns"
        val deviceName = cameraService.getDeviceName()
        val displayName = if (deviceName.isNotEmpty()) deviceName else "IP Camera Server"
        
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>$displayName</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; background-color: #f0f0f0; }
                    h1 { color: #333; }
                    .container { background: white; padding: 20px; border-radius: 8px; max-width: 800px; margin: 0 auto; }
                    img { max-width: 100%; height: auto; border: 1px solid #ddd; background: #000; }
                    button { background-color: #4CAF50; color: white; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; margin: 5px; }
                    button:hover { background-color: #45a049; }
                    #streamContainer:fullscreen { background: #000; display: flex; align-items: center; justify-content: center; width: 100vw; height: 100vh; }
                    #streamContainer:-webkit-full-screen { background: #000; display: flex; align-items: center; justify-content: center; width: 100vw; height: 100vh; }
                    #streamContainer:-moz-full-screen { background: #000; display: flex; align-items: center; justify-content: center; width: 100vw; height: 100vh; }
                    #streamContainer:-ms-fullscreen { background: #000; display: flex; align-items: center; justify-content: center; width: 100vw; height: 100vh; }
                    #streamContainer:fullscreen #stream { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; }
                    #streamContainer:-webkit-full-screen #stream { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; }
                    #streamContainer:-moz-full-screen #stream { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; }
                    #streamContainer:-ms-fullscreen #stream { max-width: 100%; max-height: 100%; width: auto; height: auto; object-fit: contain; }
                    #fullscreenBtn { background-color: #2196F3; }
                    #fullscreenBtn:hover { background-color: #0b7dda; }
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
                    .battery-status { background-color: #fff3cd; padding: 10px; border-radius: 4px; margin: 10px 0; border-left: 4px solid #ffc107; }
                    .battery-status.normal { background-color: #d4edda; border-left-color: #28a745; }
                    .battery-status.low { background-color: #fff3cd; border-left-color: #ffc107; }
                    .battery-status.critical { background-color: #f8d7da; border-left-color: #dc3545; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>$displayName</h1>
                    <div class="status-info">
                        <strong>Server Status:</strong> Running (Ktor) | 
                        <strong>Active Connections:</strong> <span id="connectionCount">$connectionDisplay</span>
                    </div>
                    <div id="batteryStatusDisplay" class="battery-status">
                        <strong>Battery Status:</strong> <span id="batteryModeText">Loading...</span> | 
                        <strong>Streaming:</strong> <span id="streamingStatusText">Checking...</span>
                    </div>
                    <div id="cameraStatusDisplay" class="status-info" style="display: none; background-color: #fff3cd; border-left-color: #ffc107;">
                        <strong>⚠️ Camera Not Active:</strong> <span id="cameraStatusText">Camera initialization deferred</span>
                        <br>
                        <button id="activateCameraBtn" onclick="activateCamera()" style="margin-top: 10px; background-color: #2196F3;">
                            🎥 Activate Camera
                        </button>
                        <p class="note" style="margin: 5px 0 0 0;"><em>Camera will auto-activate when you start streaming, or click the button above to activate manually.</em></p>
                    </div>
                    <p class="note"><em>Connection count and battery status update in real-time via Server-Sent Events. Initial count: $connectionDisplay</em></p>
                    <h2>Live Stream</h2>
                    <div id="streamContainer" style="text-align: center; background: #000; min-height: 300px; display: flex; align-items: center; justify-content: center;">
                        <img id="stream" style="display: none; max-width: 100%; height: auto;" alt="Camera Stream">
                        <div id="streamPlaceholder" style="color: #888; font-size: 18px;">Click "Start Stream" to begin</div>
                    </div>
                    <br>
                    <div class="row">
                        <button id="toggleStreamBtn" onclick="toggleStream()">Start Stream</button>
                        <button onclick="reloadStream()">Refresh</button>
                        <button id="fullscreenBtn" onclick="toggleFullscreen()">Fullscreen</button>
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
                    <h2>OSD Overlays</h2>
                    <p class="note">Customize what information appears on the video stream overlay.</p>
                    <div class="row">
                        <label>
                            <input type="checkbox" id="dateTimeOverlayCheckbox" checked onchange="toggleDateTimeOverlay()">
                            Show Date/Time (Top Left)
                        </label>
                    </div>
                    <div class="row">
                        <label>
                            <input type="checkbox" id="batteryOverlayCheckbox" checked onchange="toggleBatteryOverlay()">
                            Show Battery (Top Right)
                        </label>
                    </div>
                    <div class="row">
                        <label>
                            <input type="checkbox" id="resolutionOverlayCheckbox" checked onchange="toggleResolutionOverlay()">
                            Show Resolution (Bottom Right)
                        </label>
                    </div>
                    <div class="row">
                        <label>
                            <input type="checkbox" id="fpsOverlayCheckbox" checked onchange="toggleFpsOverlay()">
                            Show FPS (Bottom Left)
                        </label>
                    </div>
                    <h2>FPS Settings</h2>
                    <p class="note">Adjust streaming frame rates. 
                        <strong>Camera FPS:</strong> <span id="currentCameraFpsDisplay">0.0</span> fps | 
                        <strong>MJPEG FPS:</strong> <span id="currentMjpegFpsDisplay">0.0</span> fps | 
                        <strong>RTSP FPS:</strong> <span id="currentRtspFpsDisplay">0.0</span> fps | 
                        <strong>CPU:</strong> <span id="cpuUsageDisplay">0.0</span>%
                    </p>
                    <div class="row">
                        <label for="mjpegFpsSelect">MJPEG Target FPS:</label>
                        <select id="mjpegFpsSelect">
                            <option value="1">1 fps</option>
                            <option value="5">5 fps</option>
                            <option value="10" selected>10 fps</option>
                            <option value="15">15 fps</option>
                            <option value="20">20 fps</option>
                            <option value="24">24 fps</option>
                            <option value="30">30 fps</option>
                            <option value="60">60 fps</option>
                        </select>
                        <button onclick="applyMjpegFps()">Apply FPS</button>
                    </div>
                    <div class="row">
                        <label for="rtspFpsSelect">RTSP Target FPS:</label>
                        <select id="rtspFpsSelect">
                            <option value="1">1 fps</option>
                            <option value="5">5 fps</option>
                            <option value="10">10 fps</option>
                            <option value="15">15 fps</option>
                            <option value="20">20 fps</option>
                            <option value="24">24 fps</option>
                            <option value="30" selected>30 fps</option>
                            <option value="60">60 fps</option>
                        </select>
                        <button onclick="applyRtspFps()">Apply FPS</button>
                    </div>
                    <p class="note"><em>Note: RTSP FPS change requires RTSP server restart (disable/enable) to take effect.</em></p>
                    <div class="note" id="formatStatus"></div>
                    <h2>Active Connections</h2>
                    <p class="note"><em>Note: Shows active streaming and real-time event connections. Short-lived HTTP requests (status, snapshot, etc.) are not displayed.</em></p>
                    <div id="connectionsContainer">
                        <p>Loading connections...</p>
                    </div>
                    <button onclick="refreshConnections()">Refresh Connections</button>
                    <h2>Server Management</h2>
                    <div class="row">
                        <button onclick="restartServer()">Restart Server</button>
                    </div>
                    <p class="note"><em>Note: Server restart will briefly interrupt all connections. Clients will automatically reconnect.</em></p>
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
                    <div class="endpoint">
                        <strong>Restart Server:</strong> <a href="/restart" target="_blank"><code>GET /restart</code></a><br>
                        Restart the HTTP server remotely. Useful for applying configuration changes or recovering from issues.
                    </div>
                    <h2>RTSP Streaming (Hardware-Accelerated H.264)</h2>
                    <p class="note"><em>RTSP provides hardware-accelerated H.264 streaming with ~500ms-1s latency. Industry standard for IP cameras compatible with VLC, FFmpeg, ZoneMinder, Shinobi, Blue Iris, and MotionEye.</em></p>
                    <div class="row">
                        <button id="enableRTSPBtn" onclick="enableRTSP()">Enable RTSP</button>
                        <button id="disableRTSPBtn" onclick="disableRTSP()">Disable RTSP</button>
                        <button onclick="checkRTSPStatus()">Check Status</button>
                    </div>
                    <div id="rtspStatus" class="note" style="margin-top: 10px;"></div>
                    
                    <h3 style="margin-top: 20px;">Encoder Settings</h3>
                    <div class="row">
                        <label for="bitrateInput" style="margin-right: 10px;">Bitrate (Mbps):</label>
                        <input type="number" id="bitrateInput" min="0.5" max="20" step="0.5" value="3.0" style="width: 80px; margin-right: 10px;">
                        <button onclick="setBitrate()">Set Bitrate</button>
                    </div>
                    <div class="row" style="margin-top: 10px;">
                        <label for="bitrateModeSelect" style="margin-right: 10px;">Bitrate Mode:</label>
                        <select id="bitrateModeSelect" style="margin-right: 10px; padding: 5px;">
                            <option value="VBR">VBR (Variable, best quality)</option>
                            <option value="CBR">CBR (Constant, stable bandwidth)</option>
                            <option value="CQ">CQ (Constant Quality)</option>
                        </select>
                        <button onclick="setBitrateMode()">Set Mode</button>
                    </div>
                    <div id="encoderSettings" class="note" style="margin-top: 10px;"></div>
                    
                    <div class="endpoint">
                        <strong>Enable RTSP:</strong> <a href="/enableRTSP" target="_blank"><code>GET /enableRTSP</code></a><br>
                        Enable hardware-accelerated RTSP streaming on port 8554
                    </div>
                    <div class="endpoint">
                        <strong>Disable RTSP:</strong> <a href="/disableRTSP" target="_blank"><code>GET /disableRTSP</code></a><br>
                        Disable RTSP streaming to save resources
                    </div>
                    <div class="endpoint">
                        <strong>RTSP Status:</strong> <a href="/rtspStatus" target="_blank"><code>GET /rtspStatus</code></a><br>
                        Get RTSP server status and metrics (JSON)
                    </div>
                    <div class="endpoint">
                        <strong>Set Bitrate:</strong> <a href="/setRTSPBitrate?value=5.0" target="_blank"><code>GET /setRTSPBitrate?value=5.0</code></a><br>
                        Set encoder bitrate (value in Mbps, e.g., 3.0, 5.0, 8.0)
                    </div>
                    <div class="endpoint">
                        <strong>Set Bitrate Mode:</strong> <a href="/setRTSPBitrateMode?value=VBR" target="_blank"><code>GET /setRTSPBitrateMode?value=VBR</code></a><br>
                        Set bitrate mode: VBR (variable), CBR (constant), or CQ (constant quality)
                    </div>
                    <h2>Keep the stream alive</h2>
                    <ul>
                        <li>Disable battery optimizations for IP_Cam in Android Settings &gt; Battery</li>
                        <li>Allow background activity and keep the phone plugged in for long sessions</li>
                        <li>Lock the app in recents (swipe-down or lock icon on many devices)</li>
                        <li>Set Wi-Fi to stay on during sleep and place device where signal is strong</li>
                    </ul>
                    <hr style="margin-top: 30px; margin-bottom: 15px; border: none; border-top: 1px solid #ddd;">
                    <div style="text-align: center; color: #666; font-size: 11px; font-style: italic;">
                        <p style="margin: 5px 0;">IP Camera Server</p>
                        <p style="margin: 5px 0;">${BuildInfo.getVersionString()}</p>
                        <p style="margin: 5px 0;">${BuildInfo.getBuildString()}</p>
                    </div>
                </div>
                <script>
                    // Configuration constants
                    const STREAM_RELOAD_DELAY_MS = 200;  // Delay before reloading stream after state change
                    const CONNECTIONS_REFRESH_DEBOUNCE_MS = 500;  // Debounce time for connection list refresh
                    
                    const streamImg = document.getElementById('stream');
                    const streamPlaceholder = document.getElementById('streamPlaceholder');
                    const toggleStreamBtn = document.getElementById('toggleStreamBtn');
                    let lastFrame = Date.now();
                    let streamActive = false;
                    let autoReloadInterval = null;

                    // Toggle stream on/off with a single button
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
                        toggleStreamBtn.style.backgroundColor = '#f44336';  // Red for stop
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
                        streamPlaceholder.style.display = 'block';
                        toggleStreamBtn.textContent = 'Start Stream';
                        toggleStreamBtn.style.backgroundColor = '#4CAF50';  // Green for start
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
                        // Remember if stream was active before switching
                        const wasStreamActive = streamActive;
                        
                        fetch('/switch')
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = 'Switched to ' + data.camera + ' camera';
                                
                                // If stream was active, keep it active with the new camera
                                if (wasStreamActive) {
                                    // Reload stream after a short delay to allow camera to switch
                                    setTimeout(() => {
                                        reloadStream();
                                    }, STREAM_RELOAD_DELAY_MS);
                                }
                                
                                // Reload formats and update flashlight button for new camera
                                loadFormats();
                                updateFlashlightButton();
                            })
                            .catch(error => {
                                document.getElementById('formatStatus').textContent = 'Error switching camera: ' + error;
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
                    
                    function updateBatteryStatusDisplay(batteryMode, streamingAllowed) {
                        const statusDiv = document.getElementById('batteryStatusDisplay');
                        const modeText = document.getElementById('batteryModeText');
                        const streamingText = document.getElementById('streamingStatusText');
                        
                        // Update mode text with descriptive labels
                        let modeLabel = batteryMode;
                        let modeClass = 'normal';
                        
                        if (batteryMode === 'NORMAL') {
                            modeLabel = 'Normal (Full Operation)';
                            modeClass = 'normal';
                        } else if (batteryMode === 'LOW_BATTERY') {
                            modeLabel = 'Low Battery (Wakelocks Released)';
                            modeClass = 'low';
                        } else if (batteryMode === 'CRITICAL_BATTERY') {
                            modeLabel = 'CRITICAL - Streaming Paused';
                            modeClass = 'critical';
                        }
                        
                        modeText.textContent = modeLabel;
                        streamingText.textContent = streamingAllowed ? 'Active' : 'Paused (Battery Too Low)';
                        streamingText.style.color = streamingAllowed ? '#28a745' : '#dc3545';
                        streamingText.style.fontWeight = streamingAllowed ? 'normal' : 'bold';
                        
                        // Update status div styling based on mode
                        statusDiv.className = 'battery-status ' + modeClass;
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
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
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
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
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
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
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
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to toggle resolution overlay';
                            });
                    }

                    function toggleDateTimeOverlay() {
                        const checkbox = document.getElementById('dateTimeOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setDateTimeOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to toggle date/time overlay';
                            });
                    }

                    function toggleBatteryOverlay() {
                        const checkbox = document.getElementById('batteryOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setBatteryOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to toggle battery overlay';
                            });
                    }

                    function toggleFpsOverlay() {
                        const checkbox = document.getElementById('fpsOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setFpsOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to toggle FPS overlay';
                            });
                    }

                    function applyMjpegFps() {
                        const fps = document.getElementById('mjpegFpsSelect').value;
                        const url = '/setMjpegFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to set MJPEG FPS';
                            });
                    }

                    function applyRtspFps() {
                        const fps = document.getElementById('rtspFpsSelect').value;
                        const url = '/setRtspFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                            })
                            .catch(() => {
                                document.getElementById('formatStatus').textContent = 'Failed to set RTSP FPS';
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

                    function restartServer() {
                        if (!confirm('Restart server? All active connections will be briefly interrupted.')) {
                            return;
                        }
                        
                        document.getElementById('formatStatus').textContent = 'Restarting server...';
                        
                        // Remember if stream was active before restart
                        const wasStreamActive = streamActive;
                        
                        fetch('/restart')
                            .then(response => response.json())
                            .then(data => {
                                document.getElementById('formatStatus').textContent = data.message;
                                // Stop the stream during restart
                                if (streamActive) {
                                    stopStream();
                                }
                                // Auto-reconnect after 3 seconds if stream was active
                                setTimeout(() => {
                                    document.getElementById('formatStatus').textContent = 'Server restarted. Reconnecting...';
                                    if (wasStreamActive) {
                                        startStream();
                                    }
                                }, 3000);
                            })
                            .catch(error => {
                                document.getElementById('formatStatus').textContent = 'Error restarting server: ' + error;
                            });
                    }

                    function toggleFullscreen() {
                        const container = document.getElementById('streamContainer');
                        const fullscreenBtn = document.getElementById('fullscreenBtn');
                        
                        if (!document.fullscreenElement && !document.webkitFullscreenElement && 
                            !document.mozFullScreenElement && !document.msFullscreenElement) {
                            // Enter fullscreen
                            if (container.requestFullscreen) {
                                container.requestFullscreen();
                            } else if (container.webkitRequestFullscreen) {
                                container.webkitRequestFullscreen();
                            } else if (container.mozRequestFullScreen) {
                                container.mozRequestFullScreen();
                            } else if (container.msRequestFullscreen) {
                                container.msRequestFullscreen();
                            }
                        } else {
                            // Exit fullscreen
                            if (document.exitFullscreen) {
                                document.exitFullscreen();
                            } else if (document.webkitExitFullscreen) {
                                document.webkitExitFullscreen();
                            } else if (document.mozCancelFullScreen) {
                                document.mozCancelFullScreen();
                            } else if (document.msExitFullscreen) {
                                document.msExitFullscreen();
                            }
                        }
                    }
                    
                    // Update fullscreen button text based on fullscreen state
                    document.addEventListener('fullscreenchange', updateFullscreenButton);
                    document.addEventListener('webkitfullscreenchange', updateFullscreenButton);
                    document.addEventListener('mozfullscreenchange', updateFullscreenButton);
                    document.addEventListener('msfullscreenchange', updateFullscreenButton);
                    
                    function updateFullscreenButton() {
                        const fullscreenBtn = document.getElementById('fullscreenBtn');
                        if (document.fullscreenElement || document.webkitFullscreenElement || 
                            document.mozFullScreenElement || document.msFullscreenElement) {
                            fullscreenBtn.textContent = 'Exit Fullscreen';
                        } else {
                            fullscreenBtn.textContent = 'Fullscreen';
                        }
                    }

                    loadFormats();
                    refreshConnections();
                    updateFlashlightButton();
                    
                    // Load max connections and battery status from server status
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
                            
                            // Initialize battery status display
                            if (data.batteryMode && data.streamingAllowed !== undefined) {
                                updateBatteryStatusDisplay(data.batteryMode, data.streamingAllowed);
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
                                    setTimeout(refreshConnections, CONNECTIONS_REFRESH_DEBOUNCE_MS);
                                }
                            }
                        } catch (e) {
                            console.error('Failed to parse SSE data:', e);
                        }
                    };
                    
                    // Handle camera state updates pushed by server
                    // Server now sends delta updates (only changed values) to reduce bandwidth
                    let lastReceivedState = {};  // Track cumulative state
                    
                    eventSource.addEventListener('state', function(event) {
                        try {
                            const deltaState = JSON.parse(event.data);
                            console.log('Received delta state update from server:', deltaState);
                            
                            // Merge delta into cumulative state
                            Object.assign(lastReceivedState, deltaState);
                            const state = lastReceivedState;
                            
                            // Update resolution spinner if changed
                            if (deltaState.resolution !== undefined) {
                                const formatSelect = document.getElementById('formatSelect');
                                const options = formatSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (options[i].value === state.resolution || 
                                        (state.resolution === 'auto' && options[i].value === '')) {
                                        if (formatSelect.selectedIndex !== i) {
                                            formatSelect.selectedIndex = i;
                                            console.log('Updated resolution spinner to:', state.resolution);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update camera orientation spinner if delta contains it
                            if (deltaState.cameraOrientation !== undefined) {
                                const orientationSelect = document.getElementById('orientationSelect');
                                const options = orientationSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (options[i].value === state.cameraOrientation) {
                                        if (orientationSelect.selectedIndex !== i) {
                                            orientationSelect.selectedIndex = i;
                                            console.log('Updated orientation spinner to:', state.cameraOrientation);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update rotation spinner if delta contains it
                            if (deltaState.rotation !== undefined) {
                                const rotationSelect = document.getElementById('rotationSelect');
                                const options = rotationSelect.options;
                                for (let i = 0; i < options.length; i++) {
                                    if (parseInt(options[i].value) === state.rotation) {
                                        if (rotationSelect.selectedIndex !== i) {
                                            rotationSelect.selectedIndex = i;
                                            console.log('Updated rotation spinner to:', state.rotation);
                                        }
                                        break;
                                    }
                                }
                            }
                            
                            // Update resolution overlay checkbox if delta contains it
                            if (deltaState.showResolutionOverlay !== undefined) {
                                const checkbox = document.getElementById('resolutionOverlayCheckbox');
                                if (checkbox.checked !== state.showResolutionOverlay) {
                                    checkbox.checked = state.showResolutionOverlay;
                                    console.log('Updated resolution overlay checkbox to:', state.showResolutionOverlay);
                                }
                            }
                            
                            // Update OSD overlay checkboxes if delta contains them
                            if (deltaState.showDateTimeOverlay !== undefined) {
                                const checkbox = document.getElementById('dateTimeOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showDateTimeOverlay) {
                                    checkbox.checked = state.showDateTimeOverlay;
                                    console.log('Updated date/time overlay checkbox to:', state.showDateTimeOverlay);
                                }
                            }
                            
                            if (deltaState.showBatteryOverlay !== undefined) {
                                const checkbox = document.getElementById('batteryOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showBatteryOverlay) {
                                    checkbox.checked = state.showBatteryOverlay;
                                    console.log('Updated battery overlay checkbox to:', state.showBatteryOverlay);
                                }
                            }
                            
                            if (deltaState.showFpsOverlay !== undefined) {
                                const checkbox = document.getElementById('fpsOverlayCheckbox');
                                if (checkbox && checkbox.checked !== state.showFpsOverlay) {
                                    checkbox.checked = state.showFpsOverlay;
                                    console.log('Updated FPS overlay checkbox to:', state.showFpsOverlay);
                                }
                            }
                            
                            // Update FPS displays if delta contains them (live updates)
                            if (deltaState.currentCameraFps !== undefined) {
                                const cameraFpsDisplay = document.getElementById('currentCameraFpsDisplay');
                                if (cameraFpsDisplay) {
                                    cameraFpsDisplay.textContent = state.currentCameraFps.toFixed(1);
                                }
                            }
                            
                            if (deltaState.currentMjpegFps !== undefined) {
                                const mjpegFpsDisplay = document.getElementById('currentMjpegFpsDisplay');
                                if (mjpegFpsDisplay) {
                                    mjpegFpsDisplay.textContent = state.currentMjpegFps.toFixed(1);
                                }
                            }
                            
                            if (deltaState.currentRtspFps !== undefined) {
                                const rtspFpsDisplay = document.getElementById('currentRtspFpsDisplay');
                                if (rtspFpsDisplay) {
                                    rtspFpsDisplay.textContent = state.currentRtspFps.toFixed(1);
                                }
                            }
                            
                            if (deltaState.cpuUsage !== undefined) {
                                const cpuUsageDisplay = document.getElementById('cpuUsageDisplay');
                                if (cpuUsageDisplay) {
                                    cpuUsageDisplay.textContent = state.cpuUsage.toFixed(1);
                                }
                            }
                            
                            if (deltaState.targetMjpegFps !== undefined) {
                                const mjpegSelect = document.getElementById('mjpegFpsSelect');
                                if (mjpegSelect) {
                                    const options = mjpegSelect.options;
                                    for (let i = 0; i < options.length; i++) {
                                        if (parseInt(options[i].value) === state.targetMjpegFps) {
                                            if (mjpegSelect.selectedIndex !== i) {
                                                mjpegSelect.selectedIndex = i;
                                                console.log('Updated MJPEG FPS select to:', state.targetMjpegFps);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            if (deltaState.targetRtspFps !== undefined) {
                                const rtspSelect = document.getElementById('rtspFpsSelect');
                                if (rtspSelect) {
                                    const options = rtspSelect.options;
                                    for (let i = 0; i < options.length; i++) {
                                        if (parseInt(options[i].value) === state.targetRtspFps) {
                                            if (rtspSelect.selectedIndex !== i) {
                                                rtspSelect.selectedIndex = i;
                                                console.log('Updated RTSP FPS select to:', state.targetRtspFps);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            
                            // Update battery status display
                            if (deltaState.batteryMode !== undefined || deltaState.streamingAllowed !== undefined) {
                                updateBatteryStatusDisplay(state.batteryMode, state.streamingAllowed);
                            }
                            
                            // Update flashlight button state
                            updateFlashlightButton();
                            
                            // Reload stream if it's active and settings changed (not just status)
                            const settingsChanged = deltaState.camera || deltaState.resolution || deltaState.rotation;
                            if (streamActive && settingsChanged) {
                                console.log('Reloading stream to reflect state changes');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            }
                            
                            // If streaming was disabled due to battery, stop the stream
                            if (deltaState.streamingAllowed !== undefined && !state.streamingAllowed && streamActive) {
                                console.log('Streaming disabled due to battery, stopping stream');
                                stopStream();
                            }
                            
                            // If camera switched, reload formats
                            if (deltaState.camera !== undefined) {
                                console.log('Camera changed, reloading formats');
                                loadFormats();
                            }
                            
                        } catch (e) {
                            console.error('Failed to handle state update:', e);
                        }
                    });
                    
                    eventSource.onerror = function(error) {
                        console.error('SSE connection error:', error);
                        // EventSource will automatically try to reconnect
                    };
                    
                    // Clean up on page unload
                    window.addEventListener('beforeunload', function() {
                        eventSource.close();
                    });
                    
                    // RTSP Control Functions
                    function enableRTSP() {
                        document.getElementById('rtspStatus').textContent = 'Enabling RTSP...';
                        fetch('/enableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: green;">✓ RTSP Enabled</strong><br>' +
                                        'Encoder: ' + data.encoder + ' (Hardware: ' + data.isHardware + ')<br>' +
                                        'Color Format: ' + data.colorFormat + ' (' + data.colorFormatHex + ')<br>' +
                                        'URL: <a href="' + data.url + '" target="_blank">' + data.url + '</a><br>' +
                                        'Port: ' + data.port + '<br>' +
                                        'Use with VLC, FFmpeg, ZoneMinder, Shinobi, Blue Iris, MotionEye';
                                } else {
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: red;">✗ Failed to enable RTSP</strong><br>' + data.message;
                                }
                            })
                            .catch(error => {
                                document.getElementById('rtspStatus').innerHTML = 
                                    '<strong style="color: red;">Error:</strong> ' + error;
                            });
                    }
                    
                    function disableRTSP() {
                        document.getElementById('rtspStatus').textContent = 'Disabling RTSP...';
                        fetch('/disableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: orange;">RTSP Disabled</strong>';
                                } else {
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: red;">Error:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                document.getElementById('rtspStatus').innerHTML = 
                                    '<strong style="color: red;">Error:</strong> ' + error;
                            });
                    }
                    
                    function checkRTSPStatus() {
                        document.getElementById('rtspStatus').textContent = 'Checking RTSP status...';
                        fetch('/rtspStatus')
                            .then(response => response.json())
                            .then(data => {
                                if (data.rtspEnabled) {
                                    const encodedFps = data.encodedFps > 0 ? data.encodedFps.toFixed(1) : '0.0';
                                    const dropRate = data.framesEncoded > 0 
                                        ? (data.droppedFrames / (data.framesEncoded + data.droppedFrames) * 100).toFixed(1)
                                        : '0.0';
                                    const bandwidthMbps = (data.bitrateMbps * encodedFps / data.targetFps).toFixed(2);
                                    
                                    // Update status display
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: green;">✓ RTSP Active</strong><br>' +
                                        'Encoder: ' + data.encoder + ' (Hardware: ' + data.isHardware + ')<br>' +
                                        'Color Format: ' + data.colorFormat + ' (' + data.colorFormatHex + ')<br>' +
                                        'Resolution: ' + data.resolution + ' @ ' + data.bitrateMbps.toFixed(1) + ' Mbps (' + data.bitrateMode + ')<br>' +
                                        'Camera FPS: ' + encodedFps + ' fps (encoder configured: ' + data.targetFps + ' fps)<br>' +
                                        'Frames: ' + data.framesEncoded + ' encoded, ' + data.droppedFrames + ' dropped (' + dropRate + '%)<br>' +
                                        'Bandwidth: ~' + bandwidthMbps + ' Mbps (actual)<br>' +
                                        'Active Sessions: ' + data.activeSessions + ' | Playing: ' + data.playingSessions + '<br>' +
                                        'URL: <a href="' + data.url + '" target="_blank">' + data.url + '</a><br>' +
                                        'Port: ' + data.port;
                                    
                                    // Update encoder settings controls to reflect current values
                                    document.getElementById('bitrateInput').value = data.bitrateMbps.toFixed(1);
                                    document.getElementById('bitrateModeSelect').value = data.bitrateMode;
                                } else {
                                    document.getElementById('rtspStatus').innerHTML = 
                                        '<strong style="color: orange;">RTSP Not Enabled</strong><br>' +
                                        'Use "Enable RTSP" button to start hardware-accelerated H.264 streaming';
                                }
                            })
                            .catch(error => {
                                document.getElementById('rtspStatus').innerHTML = 
                                    '<strong style="color: red;">Error:</strong> ' + error;
                            });
                    }
                    
                    // Check RTSP status on page load
                    window.addEventListener('load', function() {
                        checkRTSPStatus();
                    });
                    
                    function setBitrate() {
                        const bitrate = document.getElementById('bitrateInput').value;
                        document.getElementById('encoderSettings').textContent = 'Setting bitrate to ' + bitrate + ' Mbps...';
                        
                        fetch('/setRTSPBitrate?value=' + bitrate)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    document.getElementById('encoderSettings').innerHTML = 
                                        '<strong style="color: green;">✓ Bitrate set to ' + bitrate + ' Mbps</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    document.getElementById('encoderSettings').innerHTML = 
                                        '<strong style="color: red;">✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                document.getElementById('encoderSettings').innerHTML = 
                                    '<strong style="color: red;">Error:</strong> ' + error;
                            });
                    }
                    
                    function setBitrateMode() {
                        const mode = document.getElementById('bitrateModeSelect').value;
                        document.getElementById('encoderSettings').textContent = 'Setting bitrate mode to ' + mode + '...';
                        
                        fetch('/setRTSPBitrateMode?value=' + mode)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    document.getElementById('encoderSettings').innerHTML = 
                                        '<strong style="color: green;">✓ Bitrate mode set to ' + mode + '</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    document.getElementById('encoderSettings').innerHTML = 
                                        '<strong style="color: red;">✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                document.getElementById('encoderSettings').innerHTML = 
                                    '<strong style="color: red;">Error:</strong> ' + error;
                            });
                    }
                    
                    // Camera activation functions
                    function activateCamera() {
                        const btn = document.getElementById('activateCameraBtn');
                        const statusText = document.getElementById('cameraStatusText');
                        
                        btn.disabled = true;
                        btn.textContent = '⏳ Activating...';
                        statusText.textContent = 'Initializing camera... Please wait.';
                        
                        fetch('/activateCamera')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    statusText.textContent = data.message;
                                    btn.style.backgroundColor = '#4CAF50';
                                    btn.textContent = '✓ Camera Activated';
                                    
                                    // Hide camera status after 3 seconds
                                    setTimeout(() => {
                                        checkCameraStatus();
                                    }, 3000);
                                } else {
                                    statusText.textContent = 'Error: ' + data.message;
                                    btn.disabled = false;
                                    btn.textContent = '🎥 Activate Camera';
                                }
                            })
                            .catch(error => {
                                statusText.textContent = 'Error activating camera: ' + error;
                                btn.disabled = false;
                                btn.textContent = '🎥 Activate Camera';
                            });
                    }
                    
                    function checkCameraStatus() {
                        fetch('/status')
                            .then(response => response.json())
                            .then(data => {
                                const statusDisplay = document.getElementById('cameraStatusDisplay');
                                const statusText = document.getElementById('cameraStatusText');
                                
                                if (data.cameraDeferred) {
                                    // Camera is deferred - show activation UI
                                    statusDisplay.style.display = 'block';
                                    statusText.textContent = 'Camera initialization deferred (Android 14+ boot). Activate to start streaming.';
                                } else if (data.cameraActive) {
                                    // Camera is active - hide activation UI
                                    statusDisplay.style.display = 'none';
                                } else {
                                    // Camera is not active but also not deferred (might be starting)
                                    statusDisplay.style.display = 'block';
                                    statusDisplay.style.backgroundColor = '#e3f2fd';
                                    statusDisplay.style.borderLeftColor = '#2196F3';
                                    statusText.textContent = 'Camera is initializing...';
                                    document.getElementById('activateCameraBtn').style.display = 'none';
                                }
                            })
                            .catch(error => {
                                console.error('Failed to check camera status:', error);
                            });
                    }
                    
                    // Check camera status on page load
                    checkCameraStatus();
                    // Recheck every 5 seconds to catch camera activation
                    setInterval(checkCameraStatus, 5000);
                </script>
            </body>
            </html>
        """.trimIndent()
        
        call.respondText(html, ContentType.Text.Html)
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSnapshot() {
        // Auto-activate camera if deferred (Android 14+ boot scenario)
        if (cameraService.isCameraDeferred()) {
            Log.i(TAG, "First snapshot request received - auto-activating camera")
            cameraService.activateCamera()
            // Give camera a moment to initialize
            delay(2000)
        }
        
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
        // Auto-activate camera if deferred (Android 14+ boot scenario)
        if (cameraService.isCameraDeferred()) {
            Log.i(TAG, "First stream request received - auto-activating camera")
            cameraService.activateCamera()
            // Give camera a moment to initialize
            delay(2000)
        }
        
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
        activeStreams.incrementAndGet()
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
        val batteryMode = cameraService.getBatteryMode()
        val streamingAllowed = cameraService.isStreamingAllowed()
        val deviceName = cameraService.getDeviceName()
        val cameraActive = cameraService.isCameraActive()
        val cameraDeferred = cameraService.isCameraDeferred()
        
        val endpoints = "[\"/\", \"/snapshot\", \"/stream\", \"/switch\", \"/status\", \"/events\", \"/toggleFlashlight\", \"/activateCamera\", \"/formats\", \"/connections\", \"/stats\", \"/overrideBatteryLimit\"]"
        
        val json = """
            {
                "status": "running",
                "server": "Ktor",
                "deviceName": "$deviceName",
                "camera": "$cameraName",
                "cameraActive": $cameraActive,
                "cameraDeferred": $cameraDeferred,
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
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveActivateCamera() {
        // Check if camera is deferred (waiting for activation)
        if (!cameraService.isCameraDeferred()) {
            // Camera is already active or was never deferred
            if (cameraService.isCameraActive()) {
                call.respondText(
                    """{"status":"ok","message":"Camera already active","cameraActive":true,"wasDeferred":false}""",
                    ContentType.Application.Json
                )
            } else {
                call.respondText(
                    """{"status":"error","message":"Camera initialization was not deferred. Camera may be starting or failed.","cameraActive":false}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
            }
            return
        }
        
        // Attempt to activate camera
        val activated = cameraService.activateCamera()
        if (activated) {
            call.respondText(
                """{"status":"ok","message":"Camera activation initiated successfully. Camera should be ready in 2-3 seconds.","cameraActive":true,"activated":true}""",
                ContentType.Application.Json
            )
        } else {
            call.respondText(
                """{"status":"error","message":"Failed to activate camera. Camera may already be active or initialization failed.","cameraActive":false,"activated":false}""",
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
}
