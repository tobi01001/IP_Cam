// Configuration constants
                    const STREAM_RELOAD_DELAY_MS = 200;
                    const CONNECTIONS_REFRESH_DEBOUNCE_MS = 500;
                    
                    // Tab switching functionality
                    function switchTab(tabName) {
                        // Hide all tab contents
                        const contents = document.querySelectorAll('.tab-content');
                        contents.forEach(content => content.classList.remove('active'));
                        
                        // Remove active class from all tabs
                        const tabs = document.querySelectorAll('.tab');
                        tabs.forEach(tab => tab.classList.remove('active'));
                        
                        // Show selected tab content
                        document.getElementById('tab-' + tabName).classList.add('active');
                        
                        // Add active class to clicked tab
                        event.target.classList.add('active');
                    }
                    
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
                        toggleStreamBtn.className = 'danger';
                        streamActive = true;
                        
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
                        toggleStreamBtn.className = 'success';
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
                        const wasStreamActive = streamActive;
                        
                        fetch('/switch')
                            .then(response => response.json())
                            .then(data => {
                                showAlert('Switched to ' + data.camera + ' camera', 'success');
                                
                                if (wasStreamActive) {
                                    setTimeout(() => {
                                        reloadStream();
                                    }, STREAM_RELOAD_DELAY_MS);
                                }
                                
                                loadFormats();
                                updateFlashlightButton();
                            })
                            .catch(error => {
                                showAlert('Error switching camera: ' + error, 'danger');
                            });
                    }

                    function toggleFlashlight() {
                        fetch('/toggleFlashlight')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    showAlert(data.message, 'success');
                                    updateFlashlightButton();
                                } else {
                                    showAlert(data.message, 'warning');
                                }
                            })
                            .catch(error => {
                                showAlert('Error toggling flashlight: ' + error, 'danger');
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
                                    button.className = data.flashlightOn ? 'warning' : 'success';
                                } else {
                                    button.disabled = true;
                                    button.textContent = 'Flashlight N/A';
                                    button.className = 'secondary';
                                }
                            })
                            .catch(error => {
                                console.error('Error updating flashlight button:', error);
                            });
                    }
                    
                    function updateBatteryStatusDisplay(batteryMode, streamingAllowed) {
                        const modeText = document.getElementById('batteryModeText');
                        const streamingText = document.getElementById('streamingStatusText');
                        
                        let modeLabel = batteryMode;
                        let modeClass = 'success';
                        
                        if (batteryMode === 'NORMAL') {
                            modeLabel = 'Normal';
                            modeClass = 'success';
                        } else if (batteryMode === 'LOW_BATTERY') {
                            modeLabel = 'Low Battery';
                            modeClass = 'warning';
                        } else if (batteryMode === 'CRITICAL_BATTERY') {
                            modeLabel = 'CRITICAL';
                            modeClass = 'danger';
                        }
                        
                        modeText.textContent = modeLabel;
                        modeText.className = 'status-badge ' + modeClass;
                        
                        streamingText.textContent = streamingAllowed ? 'Active' : 'Paused';
                        streamingText.className = streamingAllowed ? 'status-badge success' : 'status-badge danger';
                    }
                    
                    function showAlert(message, type) {
                        const formatStatus = document.getElementById('formatStatus');
                        if (formatStatus) {
                            formatStatus.textContent = message;
                            formatStatus.className = 'alert ' + type;
                            setTimeout(() => {
                                formatStatus.textContent = '';
                                formatStatus.className = 'alert info';
                            }, 5000);
                        }
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
                                showAlert(data.selected ? 'Selected: ' + data.selected : 'Selected: Auto', 'info');
                            });
                    }

                    function applyFormat() {
                        const value = document.getElementById('formatSelect').value;
                        const url = value ? '/setFormat?value=' + encodeURIComponent(value) : '/setFormat';
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set format', 'danger');
                            });
                    }

                    function applyCameraOrientation() {
                        const value = document.getElementById('orientationSelect').value;
                        const url = '/setCameraOrientation?value=' + encodeURIComponent(value);
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set camera orientation', 'danger');
                            });
                    }

                    function applyRotation() {
                        const value = document.getElementById('rotationSelect').value;
                        const url = '/setRotation?value=' + encodeURIComponent(value);
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to set rotation', 'danger');
                            });
                    }

                    function toggleResolutionOverlay() {
                        const checkbox = document.getElementById('resolutionOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setResolutionOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle resolution overlay', 'danger');
                            });
                    }

                    function toggleDateTimeOverlay() {
                        const checkbox = document.getElementById('dateTimeOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setDateTimeOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle date/time overlay', 'danger');
                            });
                    }

                    function toggleBatteryOverlay() {
                        const checkbox = document.getElementById('batteryOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setBatteryOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle battery overlay', 'danger');
                            });
                    }

                    function toggleFpsOverlay() {
                        const checkbox = document.getElementById('fpsOverlayCheckbox');
                        const value = checkbox.checked ? 'true' : 'false';
                        const url = '/setFpsOverlay?value=' + value;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                setTimeout(reloadStream, STREAM_RELOAD_DELAY_MS);
                            })
                            .catch(() => {
                                showAlert('Failed to toggle FPS overlay', 'danger');
                            });
                    }

                    function applyMjpegFps() {
                        const fps = document.getElementById('mjpegFpsSelect').value;
                        const url = '/setMjpegFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                            })
                            .catch(() => {
                                showAlert('Failed to set MJPEG FPS', 'danger');
                            });
                    }

                    function applyRtspFps() {
                        const fps = document.getElementById('rtspFpsSelect').value;
                        const url = '/setRtspFps?value=' + fps;
                        fetch(url)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                            })
                            .catch(() => {
                                showAlert('Failed to set RTSP FPS', 'danger');
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
                                    '<div class="alert danger">Error loading connections. Please refresh the page or check server status.</div>';
                            });
                    }

                    function displayConnections(connections) {
                        const container = document.getElementById('connectionsContainer');
                        
                        if (!connections || connections.length === 0) {
                            container.innerHTML = '<p style="color: #666;">No active connections</p>';
                            return;
                        }
                        
                        let html = '<table><tr><th>ID</th><th>Remote Address</th><th>Endpoint</th><th>Duration (s)</th><th>Action</th></tr>';
                        
                        connections.forEach(conn => {
                            html += '<tr>';
                            html += '<td>' + conn.id + '</td>';
                            html += '<td>' + conn.remoteAddr + '</td>';
                            html += '<td>' + conn.endpoint + '</td>';
                            html += '<td>' + Math.floor(conn.duration / 1000) + '</td>';
                            html += '<td><button onclick="closeConnection(' + conn.id + ')" class="danger" style="padding: 6px 12px; font-size: 12px;">Close</button></td>';
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
                                showAlert(data.message, 'success');
                                refreshConnections();
                            })
                            .catch(error => {
                                showAlert('Error closing connection: ' + error, 'danger');
                            });
                    }

                    function applyMaxConnections() {
                        const value = document.getElementById('maxConnectionsSelect').value;
                        fetch('/setMaxConnections?value=' + value)
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                            })
                            .catch(error => {
                                showAlert('Error setting max connections: ' + error, 'danger');
                            });
                    }

                    function restartServer() {
                        if (!confirm('Restart server? All active connections will be briefly interrupted.')) {
                            return;
                        }
                        
                        showAlert('Restarting server...', 'info');
                        const wasStreamActive = streamActive;
                        
                        fetch('/restart')
                            .then(response => response.json())
                            .then(data => {
                                showAlert(data.message, 'success');
                                if (streamActive) {
                                    stopStream();
                                }
                                setTimeout(() => {
                                    showAlert('Server restarted. Reconnecting...', 'info');
                                    if (wasStreamActive) {
                                        startStream();
                                    }
                                }, 3000);
                            })
                            .catch(error => {
                                showAlert('Error restarting server: ' + error, 'danger');
                            });
                    }

                    function toggleFullscreen() {
                        const container = document.getElementById('streamContainer');
                        
                        if (!document.fullscreenElement && !document.webkitFullscreenElement && 
                            !document.mozFullScreenElement && !document.msFullscreenElement) {
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
                            
                            if (data.batteryMode && data.streamingAllowed !== undefined) {
                                updateBatteryStatusDisplay(data.batteryMode, data.streamingAllowed);
                            }
                        });
                    
                    // Set up Server-Sent Events for real-time updates
                    const eventSource = new EventSource('/events');
                    let lastConnectionCount = '';
                    
                    eventSource.onmessage = function(event) {
                        try {
                            const data = JSON.parse(event.data);
                            const connectionCount = document.getElementById('connectionCount');
                            if (connectionCount && data.connections) {
                                connectionCount.textContent = data.connections;
                                if (lastConnectionCount !== data.connections) {
                                    lastConnectionCount = data.connections;
                                    setTimeout(refreshConnections, CONNECTIONS_REFRESH_DEBOUNCE_MS);
                                }
                            }
                        } catch (e) {
                            console.error('Failed to parse SSE data:', e);
                        }
                    };
                    
                    let lastReceivedState = {};
                    
                    eventSource.addEventListener('state', function(event) {
                        try {
                            const deltaState = JSON.parse(event.data);
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
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Enabling RTSP...';
                        statusEl.className = 'alert info';
                        
                        fetch('/enableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    statusEl.className = 'alert success';
                                    statusEl.innerHTML = 
                                        '<strong>✓ RTSP Enabled</strong><br>' +
                                        'Encoder: ' + data.encoder + ' (Hardware: ' + data.isHardware + ')<br>' +
                                        'Color Format: ' + data.colorFormat + ' (' + data.colorFormatHex + ')<br>' +
                                        'URL: <a href="' + data.url + '" target="_blank">' + data.url + '</a><br>' +
                                        'Port: ' + data.port + '<br>' +
                                        'Use with VLC, FFmpeg, ZoneMinder, Shinobi, Blue Iris, MotionEye';
                                } else {
                                    statusEl.className = 'alert danger';
                                    statusEl.innerHTML = '<strong>✗ Failed to enable RTSP</strong><br>' + data.message;
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function disableRTSP() {
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Disabling RTSP...';
                        statusEl.className = 'alert info';
                        
                        fetch('/disableRTSP')
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    statusEl.className = 'alert warning';
                                    statusEl.innerHTML = '<strong>RTSP Disabled</strong>';
                                } else {
                                    statusEl.className = 'alert danger';
                                    statusEl.innerHTML = '<strong>Error:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function checkRTSPStatus() {
                        const statusEl = document.getElementById('rtspStatus');
                        statusEl.textContent = 'Checking RTSP status...';
                        statusEl.className = 'alert info';
                        
                        fetch('/rtspStatus')
                            .then(response => response.json())
                            .then(data => {
                                if (data.rtspEnabled) {
                                    const encodedFps = data.encodedFps > 0 ? data.encodedFps.toFixed(1) : '0.0';
                                    const dropRate = data.framesEncoded > 0 
                                        ? (data.droppedFrames / (data.framesEncoded + data.droppedFrames) * 100).toFixed(1)
                                        : '0.0';
                                    const bandwidthMbps = (data.bitrateMbps * encodedFps / data.targetFps).toFixed(2);
                                    
                                    statusEl.className = 'alert success';
                                    statusEl.innerHTML = 
                                        '<strong>✓ RTSP Active</strong><br>' +
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
                                    statusEl.className = 'alert info';
                                    statusEl.innerHTML = 
                                        '<strong>RTSP Not Enabled</strong><br>' +
                                        'Use "Enable RTSP" button to start hardware-accelerated H.264 streaming';
                                }
                            })
                            .catch(error => {
                                statusEl.className = 'alert danger';
                                statusEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    // Check RTSP status on page load
                    window.addEventListener('load', function() {
                        checkRTSPStatus();
                    });
                    
                    function setBitrate() {
                        const bitrate = document.getElementById('bitrateInput').value;
                        const settingsEl = document.getElementById('encoderSettings');
                        settingsEl.textContent = 'Setting bitrate to ' + bitrate + ' Mbps...';
                        settingsEl.className = 'alert info';
                        
                        fetch('/setRTSPBitrate?value=' + bitrate)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    settingsEl.className = 'alert success';
                                    settingsEl.innerHTML = 
                                        '<strong>✓ Bitrate set to ' + bitrate + ' Mbps</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    settingsEl.className = 'alert danger';
                                    settingsEl.innerHTML = '<strong>✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                settingsEl.className = 'alert danger';
                                settingsEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }
                    
                    function setBitrateMode() {
                        const mode = document.getElementById('bitrateModeSelect').value;
                        const settingsEl = document.getElementById('encoderSettings');
                        settingsEl.textContent = 'Setting bitrate mode to ' + mode + '...';
                        settingsEl.className = 'alert info';
                        
                        fetch('/setRTSPBitrateMode?value=' + mode)
                            .then(response => response.json())
                            .then(data => {
                                if (data.status === 'ok') {
                                    settingsEl.className = 'alert success';
                                    settingsEl.innerHTML = 
                                        '<strong>✓ Bitrate mode set to ' + mode + '</strong><br>' +
                                        'Encoder will restart with new settings. Check status for confirmation.';
                                    setTimeout(checkRTSPStatus, 2000);
                                } else {
                                    settingsEl.className = 'alert danger';
                                    settingsEl.innerHTML = '<strong>✗ Failed:</strong> ' + data.message;
                                }
                            })
                            .catch(error => {
                                settingsEl.className = 'alert danger';
                                settingsEl.innerHTML = '<strong>Error:</strong> ' + error;
                            });
                    }                    
                    // ==================== Software Update Functions ====================
                    
                    /**
                     * Check for available software updates from GitHub Releases
                     */
                    async function checkForUpdate() {
                        const checkBtn = document.getElementById('checkUpdateBtn');
                        const installBtn = document.getElementById('installUpdateBtn');
                        const statusEl = document.getElementById('updateStatus');
                        const statusContainer = document.getElementById('updateStatusContainer');
                        
                        // Disable button and show loading state
                        checkBtn.disabled = true;
                        checkBtn.textContent = 'Checking...';
                        statusEl.textContent = 'Checking for updates...';
                        statusContainer.style.background = '#f5f5f5';
                        installBtn.style.display = 'none';
                        
                        try {
                            const response = await fetch('/checkUpdate');
                            const result = await response.json();
                            
                            if (result.status === 'ok') {
                                if (result.updateAvailable) {
                                    // Update available
                                    const sizeMB = (result.apkSize / 1024 / 1024).toFixed(2);
                                    statusContainer.style.background = '#e8f5e9';
                                    statusEl.innerHTML = 
                                        '<strong style="color: #2e7d32;">✓ Update Available!</strong><br>' +
                                        '<div style="margin-top: 8px; line-height: 1.6;">' +
                                        '<strong>Latest Version:</strong> ' + result.latestVersionName + '<br>' +
                                        '<strong>Current Version:</strong> Build ' + result.currentVersion + '<br>' +
                                        '<strong>Download Size:</strong> ' + sizeMB + ' MB<br>' +
                                        '<strong>Release Notes:</strong><br>' +
                                        '<div style="padding: 8px; background: white; border-radius: 4px; margin-top: 4px; max-height: 150px; overflow-y: auto; white-space: pre-wrap; font-size: 13px;">' +
                                        escapeHtml(result.releaseNotes) +
                                        '</div>' +
                                        '</div>';
                                    installBtn.style.display = 'inline-block';
                                } else {
                                    // No update available
                                    statusContainer.style.background = '#e3f2fd';
                                    statusEl.innerHTML = 
                                        '<strong style="color: #1976d2;">✓ You are running the latest version</strong><br>' +
                                        '<div style="margin-top: 4px; color: #666;">Current version: Build ' + result.currentVersion + '</div>';
                                }
                            } else {
                                // Error from server
                                statusContainer.style.background = '#ffebee';
                                statusEl.innerHTML = 
                                    '<strong style="color: #c62828;">✗ Error</strong><br>' +
                                    '<div style="margin-top: 4px;">' + escapeHtml(result.message) + '</div>';
                            }
                        } catch (error) {
                            // Network or other error
                            statusContainer.style.background = '#ffebee';
                            statusEl.innerHTML = 
                                '<strong style="color: #c62828;">✗ Error</strong><br>' +
                                '<div style="margin-top: 4px;">' + escapeHtml(String(error)) + '</div>';
                        } finally {
                            // Re-enable button
                            checkBtn.disabled = false;
                            checkBtn.textContent = 'Check for Update';
                        }
                    }
                    
                    /**
                     * Trigger update download and installation
                     */
                    async function triggerUpdate() {
                        const installBtn = document.getElementById('installUpdateBtn');
                        const statusEl = document.getElementById('updateStatus');
                        const statusContainer = document.getElementById('updateStatusContainer');
                        
                        // Confirm with user
                        if (!confirm('This will download and install the update. The app will restart after installation. Continue?')) {
                            return;
                        }
                        
                        // Disable button and show downloading state
                        installBtn.disabled = true;
                        installBtn.textContent = 'Downloading...';
                        statusContainer.style.background = '#fff3e0';
                        statusEl.innerHTML = 
                            '<strong style="color: #f57c00;">⏳ Downloading update...</strong><br>' +
                            '<div style="margin-top: 4px;">This may take a minute depending on your connection speed.</div>';
                        
                        try {
                            const response = await fetch('/triggerUpdate');
                            const result = await response.json();
                            
                            if (result.status === 'ok') {
                                statusContainer.style.background = '#e8f5e9';
                                statusEl.innerHTML = 
                                    '<strong style="color: #2e7d32;">✓ Download Complete</strong><br>' +
                                    '<div style="margin-top: 4px;">Please confirm installation on your device. The update will be installed and the app will restart.</div>';
                                installBtn.style.display = 'none';
                            } else {
                                statusContainer.style.background = '#ffebee';
                                statusEl.innerHTML = 
                                    '<strong style="color: #c62828;">✗ Error</strong><br>' +
                                    '<div style="margin-top: 4px;">' + escapeHtml(result.message) + '</div>';
                                installBtn.disabled = false;
                                installBtn.textContent = 'Install Update';
                            }
                        } catch (error) {
                            statusContainer.style.background = '#ffebee';
                            statusEl.innerHTML = 
                                '<strong style="color: #c62828;">✗ Error</strong><br>' +
                                '<div style="margin-top: 4px;">' + escapeHtml(String(error)) + '</div>';
                            installBtn.disabled = false;
                            installBtn.textContent = 'Install Update';
                        }
                    }
                    
                    /**
                     * Helper function to escape HTML to prevent XSS
                     */
                    function escapeHtml(text) {
                        const div = document.createElement('div');
                        div.textContent = text;
                        return div.innerHTML;
                    }
                    
                    // ==================== End Software Update Functions ====================
                    
                    // ==================== ADB Connection Info Display ====================
                    
                    // Show ADB connection info if available on page load
                    document.addEventListener('DOMContentLoaded', function() {
                        const adbInfoElement = document.getElementById('adbConnectionInfo');
                        if (adbInfoElement) {
                            const adbText = adbInfoElement.textContent.trim();
                            if (adbText && adbText !== '') {
                                adbInfoElement.style.display = 'inline';
                            }
                        }
                    });
                    
                    // ==================== End ADB Connection Info ====================
