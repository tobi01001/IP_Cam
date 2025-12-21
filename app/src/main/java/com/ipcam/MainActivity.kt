package com.ipcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var previewImageView: ImageView
    private lateinit var serverStatusText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var cameraSelectionText: TextView
    private lateinit var endpointsText: TextView
    private lateinit var resolutionSpinner: Spinner
    private lateinit var cameraOrientationSpinner: Spinner
    private lateinit var rotationSpinner: Spinner
    private lateinit var switchCameraButton: Button
    private lateinit var flashlightButton: Button
    private lateinit var startStopButton: Button
    private lateinit var autoStartCheckBox: android.widget.CheckBox
    private lateinit var activeConnectionsText: TextView
    private lateinit var maxConnectionsSpinner: Spinner
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    private var hasCameraPermission = false
    
    companion object {
        private const val PREFS_NAME = "IPCamSettings"
        private const val PREF_AUTO_START = "autoStartServer"
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            hasCameraPermission = true
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            // Start camera service for preview now that we have permission
            startCameraServiceForPreview()
            updateUI()
        } else {
            hasCameraPermission = false
            // Check if we should show rationale (user denied but didn't select "Don't ask again")
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showPermissionRationale()
            } else {
                // User selected "Don't ask again" - guide them to settings
                showPermissionDeniedDialog()
            }
            updateUI()
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            isServiceBound = true
            
            // Set up callbacks to receive updates from the service
            cameraService?.setOnCameraStateChangedCallback { _ ->
                runOnUiThread {
                    updateUI()
                    loadResolutions()
                    loadCameraOrientationOptions()
                    loadRotationOptions()
                }
            }
            
            cameraService?.setOnFrameAvailableCallback { bitmap ->
                runOnUiThread {
                    previewImageView.setImageBitmap(bitmap)
                }
            }
            
            cameraService?.setOnConnectionsChangedCallback {
                runOnUiThread {
                    updateConnectionsUI()
                }
            }
            
            updateUI()
            loadResolutions()
            loadCameraOrientationOptions()
            loadRotationOptions()
            loadMaxConnectionsOptions()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.clearCallbacks()
            cameraService = null
            isServiceBound = false
            updateUI()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewImageView = findViewById(R.id.previewImageView)
        serverStatusText = findViewById(R.id.serverStatusText)
        serverUrlText = findViewById(R.id.serverUrlText)
        cameraSelectionText = findViewById(R.id.cameraSelectionText)
        endpointsText = findViewById(R.id.endpointsText)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        cameraOrientationSpinner = findViewById(R.id.cameraOrientationSpinner)
        rotationSpinner = findViewById(R.id.rotationSpinner)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        flashlightButton = findViewById(R.id.flashlightButton)
        startStopButton = findViewById(R.id.startStopButton)
        autoStartCheckBox = findViewById(R.id.autoStartCheckBox)
        activeConnectionsText = findViewById(R.id.activeConnectionsText)
        maxConnectionsSpinner = findViewById(R.id.maxConnectionsSpinner)
        
        setupEndpointsText()
        setupResolutionSpinner()
        setupCameraOrientationSpinner()
        setupRotationSpinner()
        setupAutoStartCheckBox()
        setupMaxConnectionsSpinner()
        
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
        
        flashlightButton.setOnClickListener {
            toggleFlashlight()
        }
        
        startStopButton.setOnClickListener {
            toggleServer()
        }
        
        checkCameraPermission()
        checkBatteryOptimization()
        
        // Load camera formats immediately on startup (doesn't require service to be running)
        loadResolutions()
        
        // Start the camera service for preview (server may or may not start)
        if (hasCameraPermission) {
            startCameraServiceForPreview()
        }
        
        // Auto-start server if enabled
        checkAutoStart()
    }
    
    private fun startCameraServiceForPreview() {
        // Start the service to enable camera preview, but don't necessarily start the HTTP server
        // The service will run in foreground mode and provide camera frames to the preview
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun setupEndpointsText() {
        val endpoints = """
            ${getString(R.string.endpoint_snapshot)}
            ${getString(R.string.endpoint_stream)}
            ${getString(R.string.endpoint_switch)}
            ${getString(R.string.endpoint_status)}
            ${getString(R.string.endpoint_formats)}
            ${getString(R.string.endpoint_set_format)}
            ${getString(R.string.endpoint_camera_orientation)}
            ${getString(R.string.endpoint_rotation)}
        """.trimIndent()
        endpointsText.text = endpoints
    }
    
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                hasCameraPermission = true
                updateUI()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show rationale before requesting permission
                showPermissionRationale()
            }
            else -> {
                // Request permission directly
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(R.string.permission_rationale_message)
            .setPositiveButton("OK") { _, _ ->
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                updateUI()
            }
            .create()
            .show()
    }
    
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_rationale_title)
            .setMessage(R.string.permission_denied_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                // Open app settings
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
    
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Battery Optimization")
                    .setMessage("To keep the camera service running reliably, please disable battery optimization for this app.\n\nThis prevents Android from stopping the service in the background.")
                    .setPositiveButton("Disable Optimization") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Could not open battery settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Skip") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .create()
                    .show()
            }
        }
    }
    
    private fun setupResolutionSpinner() {
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position) as? String
                if (selectedItem != null && cameraService?.isServerRunning() == true) {
                    applyResolution(selectedItem)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }
    
    private fun setupRotationSpinner() {
        rotationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position) as? String
                if (selectedItem != null && cameraService?.isServerRunning() == true) {
                    applyRotation(selectedItem)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        
        // Set initial rotation options
        loadRotationOptions()
    }
    
    private fun setupCameraOrientationSpinner() {
        cameraOrientationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position) as? String
                if (selectedItem != null && cameraService?.isServerRunning() == true) {
                    applyCameraOrientation(selectedItem)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        
        // Set initial camera orientation options
        loadCameraOrientationOptions()
    }
    
    /**
     * Get camera formats directly using Camera2 API without requiring the service
     * This allows displaying formats even when the server hasn't been started yet
     * Note: Returns all available formats since camera orientation preference is not yet loaded
     * 
     * This logic is intentionally duplicated from CameraService.getSupportedResolutions()
     * to avoid creating dependencies and keep MainActivity changes minimal and isolated.
     */
    private fun getCameraFormatsDirectly(cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA): List<Size> {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }
            
            val sizes = cameraManager.cameraIdList.mapNotNull { id ->
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (facing != targetFacing) return@mapNotNull null
                    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    // Use safe call and elvis operator to handle potential nulls
                    config?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
                } catch (e: Exception) {
                    Log.w("MainActivity", "Error getting formats for camera $id", e)
                    null
                }
            }.flatten()
            
            // Return all formats without orientation filtering since user preference isn't loaded yet
            // Formats will be updated with proper filtering once service connects
            return sizes.distinctBy { Pair(it.width, it.height) }
                .sortedByDescending { it.width * it.height }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting camera formats directly", e)
            return emptyList()
        }
    }
    
    private fun loadResolutions() {
        // Try to get resolutions from service if available, otherwise query directly
        val resolutions = cameraService?.getSupportedResolutions() ?: getCameraFormatsDirectly()
        
        val items = mutableListOf(getString(R.string.resolution_auto))
        items.addAll(resolutions.map { "${it.width}x${it.height}" })
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
        
        // Set current selection if service is available
        val currentResolution = cameraService?.getSelectedResolution()
        if (currentResolution != null) {
            val index = items.indexOf("${currentResolution.width}x${currentResolution.height}")
            if (index >= 0) {
                resolutionSpinner.setSelection(index)
            }
        }
    }
    
    private fun applyResolution(selection: String) {
        val service = cameraService ?: return
        
        if (selection == getString(R.string.resolution_auto)) {
            service.setResolution(null)
        } else {
            val parts = selection.split("x")
            if (parts.size == 2) {
                val width = parts[0].toIntOrNull()
                val height = parts[1].toIntOrNull()
                if (width != null && height != null) {
                    service.setResolution(Size(width, height))
                }
            }
        }
    }
    
    private fun loadRotationOptions() {
        val items = listOf(
            getString(R.string.rotation_0),
            getString(R.string.rotation_90),
            getString(R.string.rotation_180),
            getString(R.string.rotation_270)
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        rotationSpinner.adapter = adapter
        
        // Set current selection based on service state
        val currentRotation = cameraService?.getRotation() ?: 0
        val index = when (currentRotation) {
            0 -> 0
            90 -> 1
            180 -> 2
            270 -> 3
            else -> 0
        }
        rotationSpinner.setSelection(index)
    }
    
    private fun applyRotation(selection: String) {
        val service = cameraService ?: return
        
        val rotation = when (selection) {
            getString(R.string.rotation_0) -> 0
            getString(R.string.rotation_90) -> 90
            getString(R.string.rotation_180) -> 180
            getString(R.string.rotation_270) -> 270
            else -> 0
        }
        
        service.setRotation(rotation)
    }
    
    private fun loadCameraOrientationOptions() {
        val items = listOf(
            getString(R.string.camera_orientation_landscape),
            getString(R.string.camera_orientation_portrait)
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        cameraOrientationSpinner.adapter = adapter
        
        // Set current selection based on service state
        val currentOrientation = cameraService?.getCameraOrientation() ?: "landscape"
        val index = if (currentOrientation == "portrait") 1 else 0
        cameraOrientationSpinner.setSelection(index)
    }
    
    private fun applyCameraOrientation(selection: String) {
        val service = cameraService ?: return
        
        val orientation = when (selection) {
            getString(R.string.camera_orientation_landscape) -> "landscape"
            getString(R.string.camera_orientation_portrait) -> "portrait"
            else -> "landscape"
        }
        
        service.setCameraOrientation(orientation)
    }
    
    private fun setupAutoStartCheckBox() {
        // Load saved autostart preference
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        autoStartCheckBox.isChecked = autoStart
        
        // Save preference when changed
        autoStartCheckBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_AUTO_START, isChecked).apply()
            Log.d("MainActivity", "Auto-start preference changed to: $isChecked")
        }
    }
    
    private fun setupMaxConnectionsSpinner() {
        maxConnectionsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedItem = parent?.getItemAtPosition(position) as? String
                if (selectedItem != null && cameraService?.isServerRunning() == true) {
                    applyMaxConnections(selectedItem)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }
    
    private fun loadMaxConnectionsOptions() {
        val service = cameraService ?: return
        
        // Create options: 4, 8, 16, 32, 64, 100
        val options = listOf(4, 8, 16, 32, 64, 100)
        val items = options.map { it.toString() }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        maxConnectionsSpinner.adapter = adapter
        
        // Set current selection
        val currentMax = service.getMaxConnections()
        val index = options.indexOf(currentMax)
        if (index >= 0) {
            maxConnectionsSpinner.setSelection(index)
        } else {
            // Find closest match
            val closest = options.minByOrNull { kotlin.math.abs(it - currentMax) }
            val closestIndex = closest?.let { options.indexOf(it) } ?: 3 // Default to 32
            maxConnectionsSpinner.setSelection(closestIndex)
        }
    }
    
    private fun applyMaxConnections(selection: String) {
        val service = cameraService ?: return
        val newMax = selection.toIntOrNull() ?: return
        
        if (service.setMaxConnections(newMax)) {
            Toast.makeText(this, "Max connections set to $newMax. Please restart server for changes to take effect.", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun updateConnectionsUI() {
        val service = cameraService ?: return
        
        if (!service.isServerRunning()) {
            activeConnectionsText.text = getString(R.string.connections_count, 0, service.getMaxConnections())
            return
        }
        
        // Get active connection count from tracked connections
        val activeCount = service.getActiveConnectionsCount()
        val maxConns = service.getMaxConnections()
        
        activeConnectionsText.text = getString(R.string.connections_count, activeCount, maxConns)
    }
    
    private fun checkAutoStart() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        
        // Only auto-start if:
        // 1. Auto-start is enabled
        // 2. Camera permission is granted
        // 3. Server is not already running (could be running from boot receiver)
        if (autoStart && hasCameraPermission) {
            // Check if service is already running by trying to bind
            val isServiceRunning = cameraService?.isServerRunning() == true
            
            if (!isServiceRunning) {
                Log.d("MainActivity", "Auto-starting server")
                startServer()
            } else {
                Log.d("MainActivity", "Server already running, skipping auto-start")
            }
        }
    }
    
    private fun switchCamera() {
        val currentCamera = cameraService?.getCurrentCamera() ?: CameraSelector.DEFAULT_BACK_CAMERA
        val newCamera = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        cameraService?.switchCamera(newCamera)
        updateUI()
        loadResolutions()
        loadCameraOrientationOptions()
        loadRotationOptions()
    }
    
    private fun toggleFlashlight() {
        val service = cameraService ?: return
        
        if (!service.isFlashlightAvailable()) {
            Toast.makeText(this, R.string.flashlight_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        
        val newState = service.toggleFlashlight()
        updateFlashlightButton()
        
        val message = if (newState) {
            getString(R.string.flashlight_on)
        } else {
            getString(R.string.flashlight_off)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun updateFlashlightButton() {
        val service = cameraService ?: return
        
        val isAvailable = service.isFlashlightAvailable()
        val isEnabled = service.isFlashlightEnabled()
        
        flashlightButton.isEnabled = isAvailable
        flashlightButton.text = when {
            !isAvailable -> getString(R.string.flashlight_not_available)
            isEnabled -> getString(R.string.flashlight_on)
            else -> getString(R.string.flashlight_off)
        }
    }
    
    private fun toggleServer() {
        if (cameraService?.isServerRunning() == true) {
            stopServer()
        } else {
            startServer()
        }
    }
    
    private fun startServer() {
        // Check permission before starting server
        if (!hasCameraPermission) {
            Toast.makeText(this, R.string.permission_not_granted, Toast.LENGTH_LONG).show()
            checkCameraPermission()
            return
        }
        
        val intent = Intent(this, CameraService::class.java)
        intent.putExtra(CameraService.EXTRA_START_SERVER, true)
        ContextCompat.startForegroundService(this, intent)
        
        // Bind if not already bound
        if (!isServiceBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            // Service already bound, just update UI
            updateUI()
        }
    }
    
    private fun stopServer() {
        // Stop only the HTTP server, not the entire service
        // This keeps the camera running so the preview continues to work
        cameraService?.stopServer()
        updateUI()
    }
    
    private fun updateUI() {
        val isRunning = cameraService?.isServerRunning() == true
        
        serverStatusText.text = getString(
            R.string.server_status,
            if (isRunning) getString(R.string.status_running) else getString(R.string.status_stopped)
        )
        
        serverUrlText.text = getString(
            R.string.server_url,
            cameraService?.getServerUrl() ?: "Not available"
        )
        
        val currentCamera = cameraService?.getCurrentCamera()
        val cameraName = when {
            currentCamera == null -> "Not available"
            currentCamera == CameraSelector.DEFAULT_BACK_CAMERA -> getString(R.string.camera_back)
            else -> getString(R.string.camera_front)
        }
        cameraSelectionText.text = getString(R.string.camera_selection, cameraName)
        
        startStopButton.text = if (isRunning) {
            getString(R.string.stop_server)
        } else {
            getString(R.string.start_server)
        }
        
        // Disable start button if permission not granted
        startStopButton.isEnabled = isRunning || hasCameraPermission
        switchCameraButton.isEnabled = isRunning
        resolutionSpinner.isEnabled = isRunning
        cameraOrientationSpinner.isEnabled = isRunning
        rotationSpinner.isEnabled = isRunning
        maxConnectionsSpinner.isEnabled = isRunning
        
        // Update flashlight button
        updateFlashlightButton()
        
        // Update connections UI
        updateConnectionsUI()
    }
    
    override fun onResume() {
        super.onResume()
        // Rebind to service if it's running
        if (!isServiceBound && cameraService == null) {
            val intent = Intent(this, CameraService::class.java)
            try {
                bindService(intent, serviceConnection, 0)
            } catch (e: Exception) {
                // Service not running, which is fine
            }
        }
        updateUI()
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Configuration changes (like rotation) are automatically handled by OrientationEventListener
        // in the CameraService, so no action needed here
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            cameraService?.clearCallbacks()
            unbindService(serviceConnection)
        }
    }
}
