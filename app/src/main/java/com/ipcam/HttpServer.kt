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
                
                // H.264 stream (hardware accelerated, low bandwidth)
                get("/stream.mp4") { serveH264Stream() }
                get("/stream.h264") { serveH264Stream() }
                
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
                
                // Streaming mode control
                get("/streamingMode") { serveGetStreamingMode() }
                get("/setStreamingMode") { serveSetStreamingMode() }
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
                        <img id="streamImg" style="display: none; max-width: 100%; height: auto;" alt="Camera Stream">
                        <video id="streamVideo" style="display: none; max-width: 100%; height: auto;" controls autoplay muted></video>
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
                    <div class="row">
                        <label for="streamingModeSelect">Streaming Mode:</label>
                        <select id="streamingModeSelect">
                            <option value="mjpeg">MJPEG (Low Latency ~100ms)</option>
                            <option value="mp4">MP4/H.264 (Better Bandwidth, ~1-2s latency)</option>
                        </select>
                        <button onclick="applyStreamingMode()">Apply Streaming Mode</button>
                    </div>
                    <div class="row">
                        <label>
                            <input type="checkbox" id="resolutionOverlayCheckbox" checked onchange="toggleResolutionOverlay()">
                            Show Resolution Overlay (Bottom Right)
                        </label>
                    </div>
                    <div class="note" id="formatStatus"></div>
                    <div class="note" id="streamingModeStatus"></div>
                    <p class="note">Overlay shows date/time (top left) and battery status (top right). Stream auto-reconnects if interrupted.</p>
                    <p class="note"><strong>Camera Orientation:</strong> Sets the base recording mode (landscape/portrait). <strong>Rotation:</strong> Rotates the video feed by the specified degrees.</p>
                    <p class="note"><strong>Streaming Mode:</strong> MJPEG offers lowest latency, MP4/H.264 uses 30-50% less bandwidth but has 1-2 second latency. Mode change requires camera rebind.</p>
                    <p class="note"><strong>Resolution Overlay:</strong> Shows actual bitmap resolution in bottom right for debugging resolution issues.</p>
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
                    <h2>Keep the stream alive</h2>
                    <ul>
                        <li>Disable battery optimizations for IP_Cam in Android Settings &gt; Battery</li>
                        <li>Allow background activity and keep the phone plugged in for long sessions</li>
                        <li>Lock the app in recents (swipe-down or lock icon on many devices)</li>
                        <li>Set Wi-Fi to stay on during sleep and place device where signal is strong</li>
                    </ul>
                </div>
                <script>
                    // Configuration constants
                    const STREAM_RELOAD_DELAY_MS = 200;  // Delay before reloading stream after state change
                    const CONNECTIONS_REFRESH_DEBOUNCE_MS = 500;  // Debounce time for connection list refresh
                    
                    const streamImg = document.getElementById('streamImg');
                    const streamVideo = document.getElementById('streamVideo');
                    const streamPlaceholder = document.getElementById('streamPlaceholder');
                    const toggleStreamBtn = document.getElementById('toggleStreamBtn');
                    let lastFrame = Date.now();
                    let streamActive = false;
                    let autoReloadInterval = null;
                    let currentStreamingMode = 'mjpeg'; // Will be loaded from server

                    // Toggle stream on/off with a single button
                    function toggleStream() {
                        if (streamActive) {
                            stopStream();
                        } else {
                            startStream();
                        }
                    }

                    function startStream() {
                        const isMp4 = currentStreamingMode === 'mp4';
                        const streamUrl = isMp4 ? '/stream.mp4' : '/stream';
                        
                        // Use video tag for MP4, img tag for MJPEG
                        if (isMp4) {
                            streamImg.style.display = 'none';
                            streamVideo.src = streamUrl + '?ts=' + Date.now();
                            streamVideo.style.display = 'block';
                        } else {
                            streamVideo.style.display = 'none';
                            streamImg.src = streamUrl + '?ts=' + Date.now();
                            streamImg.style.display = 'block';
                        }
                        
                        streamPlaceholder.style.display = 'none';
                        toggleStreamBtn.textContent = 'Stop Stream';
                        toggleStreamBtn.style.backgroundColor = '#f44336';  // Red for stop
                        streamActive = true;
                        
                        // Auto-reload if stream stops (only for MJPEG, MP4 is continuous)
                        if (currentStreamingMode === 'mjpeg') {
                            if (autoReloadInterval) clearInterval(autoReloadInterval);
                            autoReloadInterval = setInterval(() => {
                                if (streamActive && Date.now() - lastFrame > 5000) {
                                    reloadStream();
                                }
                            }, 3000);
                        }
                    }

                    function stopStream() {
                        streamImg.src = '';
                        streamImg.style.display = 'none';
                        streamVideo.src = '';
                        streamVideo.style.display = 'none';
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
                            const isMp4 = currentStreamingMode === 'mp4';
                            const streamUrl = isMp4 ? '/stream.mp4' : '/stream';
                            
                            if (isMp4) {
                                streamVideo.src = streamUrl + '?ts=' + Date.now();
                            } else {
                                streamImg.src = streamUrl + '?ts=' + Date.now();
                            }
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

                    function loadStreamingMode() {
                        fetch('/streamingMode')
                            .then(response => response.json())
                            .then(data => {
                                const select = document.getElementById('streamingModeSelect');
                                const mode = data.streamingMode || 'mjpeg';
                                select.value = mode;
                                currentStreamingMode = mode; // Update the current mode variable
                                document.getElementById('streamingModeStatus').textContent = 
                                    'Current mode: ' + mode.toUpperCase();
                            })
                            .catch(() => {
                                document.getElementById('streamingModeStatus').textContent = 
                                    'Failed to load streaming mode';
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

                    function applyStreamingMode() {
                        const value = document.getElementById('streamingModeSelect').value;
                        const url = '/setStreamingMode?value=' + encodeURIComponent(value);
                        const statusElement = document.getElementById('streamingModeStatus');
                        const wasStreamActive = streamActive;
                        
                        statusElement.textContent = 'Changing streaming mode to ' + value.toUpperCase() + '...';
                        
                        // Stop current stream before mode change
                        if (streamActive) {
                            stopStream();
                        }
                        
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                // Update current streaming mode
                                currentStreamingMode = value;
                                statusElement.textContent = data.message + ' Reloading stream in 2 seconds...';
                                
                                // Wait 2 seconds for camera to rebind, then reload stream if it was active
                                setTimeout(() => {
                                    statusElement.textContent = 'Streaming mode changed to ' + value.toUpperCase();
                                    if (wasStreamActive) {
                                        startStream();
                                    }
                                }, 2000);
                            })
                            .catch((error) => {
                                console.error('Streaming mode change error:', error);
                                statusElement.textContent = 'Failed to change streaming mode. Error: ' + error;
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
                    loadStreamingMode();
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
                                    setTimeout(refreshConnections, CONNECTIONS_REFRESH_DEBOUNCE_MS);
                                }
                            }
                        } catch (e) {
                            console.error('Failed to parse SSE data:', e);
                        }
                    };
                    
                    // Handle camera state updates pushed by server
                    let lastReceivedState = null;  // Track last state to detect actual changes
                    
                    eventSource.addEventListener('state', function(event) {
                        try {
                            const state = JSON.parse(event.data);
                            console.log('Received state update from server:', state);
                            
                            // Check if this is an actual change
                            const cameraChanged = !lastReceivedState || lastReceivedState.camera !== state.camera;
                            const resolutionChanged = !lastReceivedState || lastReceivedState.resolution !== state.resolution;
                            
                            // Update resolution spinner if changed
                            if (state.resolution && resolutionChanged) {
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
                            
                            // Update camera orientation spinner if changed
                            if (state.cameraOrientation) {
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
                            
                            // Update rotation spinner if changed
                            if (state.rotation !== undefined) {
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
                            
                            // Update resolution overlay checkbox if changed
                            if (state.showResolutionOverlay !== undefined) {
                                const checkbox = document.getElementById('resolutionOverlayCheckbox');
                                if (checkbox.checked !== state.showResolutionOverlay) {
                                    checkbox.checked = state.showResolutionOverlay;
                                    console.log('Updated resolution overlay checkbox to:', state.showResolutionOverlay);
                                }
                            }
                            
                            // Update flashlight button state
                            updateFlashlightButton();
                            
                            // Reload stream if it's active to reflect changes immediately
                            if (streamActive) {
                                console.log('Reloading stream to reflect state changes');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            }
                            
                            // If camera switched or resolution actually changed, reload formats
                            if (cameraChanged) {
                                console.log('Camera changed, reloading formats');
                                loadFormats();
                            }
                            
                            // Store current state for next comparison
                            lastReceivedState = state;
                            
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
                "streamingMode": "${cameraService.getStreamingMode()}",
                "flashlightAvailable": ${cameraService.isFlashlightAvailable()},
                "flashlightOn": ${cameraService.isFlashlightEnabled()},
                "activeConnections": $activeConns,
                "maxConnections": $maxConns,
                "connections": "$activeConns/$maxConns",
                "activeStreams": $activeStreamCount,
                "activeSSEClients": $sseCount,
                "endpoints": ["/", "/snapshot", "/stream", "/stream.mp4", "/switch", "/status", "/events", "/toggleFlashlight", "/formats", "/connections", "/stats", "/streamingMode", "/setStreamingMode"]
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
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveGetStreamingMode() {
        val mode = cameraService.getStreamingMode()
        call.respondText(
            """{"status":"ok","streamingMode":"$mode"}""",
            ContentType.Application.Json
        )
    }
    
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveSetStreamingMode() {
        val valueStr = call.parameters["value"]?.lowercase()
        
        if (valueStr == null) {
            call.respondText(
                """{"status":"error","message":"Missing value parameter. Use value=mjpeg or value=mp4"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val newMode = when (valueStr) {
            "mjpeg", "mjpg" -> StreamingMode.MJPEG
            "mp4", "h264", "h.264" -> StreamingMode.MP4
            else -> {
                call.respondText(
                    """{"status":"error","message":"Invalid streaming mode. Use mjpeg or mp4"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return
            }
        }
        
        cameraService.setStreamingMode(newMode)
        call.respondText(
            """{"status":"ok","message":"Streaming mode set to $newMode. Stream will switch to new mode.","streamingMode":"$newMode"}""",
            ContentType.Application.Json
        )
    }
    
    /**
     * Serve raw H.264 stream (Annex B format with start codes)
     * Hardware accelerated, low bandwidth, good quality
     * Compatible with VLC, ffplay, and most players
     */
    private suspend fun PipelineContext<Unit, ApplicationCall>.serveH264Stream() {
        val currentMode = cameraService.getStreamingMode()
        
        Log.d(TAG, "[H264] Stream endpoint accessed. Current mode: $currentMode")
        
        if (currentMode != StreamingMode.MP4) {
            val errorMsg = "H.264 streaming not active. Current mode: $currentMode. Use /setStreamingMode?value=mp4 to enable."
            Log.w(TAG, "[H264] Stream rejected: $errorMsg")
            call.respondText(
                errorMsg,
                ContentType.Text.Plain,
                HttpStatusCode.BadRequest
            )
            return
        }
        
        val clientId = clientIdCounter.incrementAndGet()
        activeStreams.incrementAndGet()
        Log.d(TAG, "[H264] Stream connection opened. Client $clientId. Active streams: ${activeStreams.get()}")
        
        // Stream raw H.264 (video/h264 or application/octet-stream)
        call.respondBytesWriter(ContentType.parse("video/h264")) {
            try {
                Log.d(TAG, "[H264] Client $clientId: Starting H.264 stream...")
                
                var framesStreamed = 0
                var lastLogTime = System.currentTimeMillis()
                
                while (isActive) {
                    // Get next encoded frame from camera service
                    val frame = cameraService.getMp4EncodedFrame()
                    
                    if (frame != null) {
                        // Stream raw H.264 frame data (already in Annex B format with start codes)
                        writeFully(frame.data, 0, frame.data.size)
                        flush()
                        
                        // Track bandwidth
                        cameraService.recordBytesSent(clientId, frame.data.size.toLong())
                        
                        framesStreamed++
                        
                        // Log keyframes and periodic updates
                        if (frame.isKeyFrame) {
                            Log.d(TAG, "[H264] Client $clientId: Sent keyframe, total frames=$framesStreamed")
                        }
                        
                        // Log streaming progress every 3 seconds
                        val now = System.currentTimeMillis()
                        if (now - lastLogTime > 3000) {
                            Log.d(TAG, "[H264] Client $clientId: Streaming progress - $framesStreamed frames sent")
                            lastLogTime = now
                        }
                    } else {
                        // No frame available, wait a bit
                        delay(10)
                    }
                    
                    if (!isActive) {
                        break
                    }
                }
                
                Log.d(TAG, "[H264] Client $clientId: Stream ended after $framesStreamed frames")
                
            } catch (e: Exception) {
                Log.e(TAG, "[H264] Client $clientId: Error streaming H.264", e)
            } finally {
                cameraService.removeClient(clientId)
                activeStreams.decrementAndGet()
                Log.d(TAG, "[H264] Client $clientId: Connection closed. Active streams: ${activeStreams.get()}")
            }
        }
    }
}
