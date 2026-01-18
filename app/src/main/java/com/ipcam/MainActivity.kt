package com.ipcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private lateinit var versionInfoText: TextView
    // Device name controls
    private lateinit var deviceNameEditText: android.widget.EditText
    private lateinit var saveDeviceNameButton: Button
    // OSD overlay checkboxes
    private lateinit var showDateTimeCheckBox: android.widget.CheckBox
    private lateinit var showBatteryCheckBox: android.widget.CheckBox
    private lateinit var showResolutionCheckBox: android.widget.CheckBox
    private lateinit var showFpsCheckBox: android.widget.CheckBox
    // FPS controls
    private lateinit var currentFpsText: TextView
    private lateinit var mjpegFpsSpinner: Spinner
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    private var hasCameraPermission = false
    private var hasNotificationPermission = false
    private var allPermissionsGranted = false
    
    // Flag to prevent spinner listeners from triggering during programmatic updates
    private var isUpdatingSpinners = false
    
    // Track last applied resolution to prevent infinite loop when setSelection triggers onItemSelected
    private var lastAppliedResolution: String? = null
    
    companion object {
        private const val PREFS_NAME = "IPCamSettings"
        private const val PREF_AUTO_START = "autoStartServer"
        private const val TAG = "MainActivity"
    }
    
    // Request multiple permissions at once
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
        
        // POST_NOTIFICATIONS is only relevant on Android 13+
        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] == true
        } else {
            true // Not required on older versions
        }
        
        allPermissionsGranted = hasCameraPermission && hasNotificationPermission
        
        if (allPermissionsGranted) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            
            // Recreate activity to start fresh with all permissions
            // This avoids needing to force-close the app
            recreate()
        } else {
            // Show which permissions are missing
            val missingPermissions = mutableListOf<String>()
            if (!hasCameraPermission) missingPermissions.add("Camera")
            if (!hasNotificationPermission) missingPermissions.add("Notifications")
            
            val message = "Missing permissions: ${missingPermissions.joinToString(", ")}"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            // Show rationale or guide to settings
            if (!hasCameraPermission && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                showPermissionDeniedDialog()
            } else {
                showPermissionRationale()
            }
            updateUI()
        }
    }
    
    // Keep old single permission launcher for backwards compatibility / fallback
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            hasCameraPermission = true
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
            // Check if we need notification permission
            checkNotificationPermission()
            // Start camera service for preview now that we have permission
            if (allPermissionsGranted || hasNotificationPermission) {
                startCameraServiceForPreview()
            }
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
    
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            hasNotificationPermission = true
            Toast.makeText(this, R.string.notification_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            hasNotificationPermission = false
            // Notification permission is optional, just inform the user
            Toast.makeText(this, R.string.notification_permission_optional, Toast.LENGTH_LONG).show()
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
                    // Set flag to prevent spinner listeners from triggering during programmatic updates
                    isUpdatingSpinners = true
                    try {
                        updateUI()
                        // Don't call loadResolutions() here - it causes constant spinner refreshing
                        // Resolution spinner only needs updating when:
                        // 1. Camera switches (different supported resolutions) - handled in switchCamera()
                        // 2. User explicitly changes resolution - handled in applyResolution()
                        loadCameraOrientationOptions()
                        loadRotationOptions()
                    } finally {
                        // Use post() to defer resetting the flag until AFTER all pending UI events are processed
                        // This ensures setSelection() completes before flag is cleared, preventing infinite rebind loop
                        // setSelection() posts to UI thread, so we must also post the flag reset
                        resolutionSpinner.post {
                            isUpdatingSpinners = false
                        }
                    }
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
                    updateFpsDisplay()
                }
            }
            
            // Initial UI load - set flag to prevent spinner listeners from triggering
            isUpdatingSpinners = true
            try {
                updateUI()
                loadResolutions()
                loadCameraOrientationOptions()
                loadRotationOptions()
                loadMaxConnectionsOptions()
                loadOsdSettings()
                loadFpsSettings()
                loadDeviceName()
            } finally {
                isUpdatingSpinners = false
            }
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
        versionInfoText = findViewById(R.id.versionInfoText)
        // Device name controls
        deviceNameEditText = findViewById(R.id.deviceNameEditText)
        saveDeviceNameButton = findViewById(R.id.saveDeviceNameButton)
        // OSD overlay checkboxes
        showDateTimeCheckBox = findViewById(R.id.showDateTimeCheckBox)
        showBatteryCheckBox = findViewById(R.id.showBatteryCheckBox)
        showResolutionCheckBox = findViewById(R.id.showResolutionCheckBox)
        showFpsCheckBox = findViewById(R.id.showFpsCheckBox)
        // FPS controls
        currentFpsText = findViewById(R.id.currentFpsText)
        mjpegFpsSpinner = findViewById(R.id.mjpegFpsSpinner)
        
        // Set version information
        versionInfoText.text = BuildInfo.getFullVersionString()
        
        setupEndpointsText()
        setupResolutionSpinner()
        setupCameraOrientationSpinner()
        setupRotationSpinner()
        setupAutoStartCheckBox()
        setupMaxConnectionsSpinner()
        setupOsdCheckBoxes()
        setupMjpegFpsSpinner()
        setupDeviceNameControls()
        
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
        
        flashlightButton.setOnClickListener {
            toggleFlashlight()
        }
        
        startStopButton.setOnClickListener {
            toggleServer()
        }
        
        // Request all permissions upfront
        checkAllPermissions()
        checkBatteryOptimization()
        
        // Only start camera service if we already have all permissions
        if (allPermissionsGranted) {
            startCameraServiceForPreview()
        }
        
        // Auto-start server if enabled
        if (allPermissionsGranted) {
            checkAutoStart()
        }
    }
    
    private fun startCameraServiceForPreview() {
        // Start the service to enable camera preview, but don't necessarily start the HTTP server
        // The service will run in foreground mode and provide camera frames to the preview
        if (!isServiceBound) {
            Log.d("MainActivity", "Starting camera service for preview only")
            val intent = Intent(this, CameraService::class.java)
            startAndBindService(intent)
        } else {
            Log.d("MainActivity", "Camera service already bound, skipping start")
        }
    }
    
    private fun startAndBindService(intent: Intent) {
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
    
    private fun checkAllPermissions() {
        // Check what permissions we need
        val permissionsToRequest = mutableListOf<String>()
        
        // Camera permission is always required
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        } else {
            hasCameraPermission = true
        }
        
        // Notification permission is only required on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                hasNotificationPermission = true
            }
        } else {
            hasNotificationPermission = true // Not required on older versions
        }
        
        // Update allPermissionsGranted flag
        allPermissionsGranted = hasCameraPermission && hasNotificationPermission
        
        // Request permissions if needed
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All permissions already granted
            updateUI()
        }
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
        // API 30+ always supports battery optimization APIs (introduced in API 23)
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
    
    private fun checkNotificationPermission() {
        // POST_NOTIFICATIONS permission is only required on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    hasNotificationPermission = true
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show rationale before requesting permission
                    showNotificationPermissionRationale()
                }
                else -> {
                    // Request permission directly
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Not required on Android 12 and below
            hasNotificationPermission = true
        }
    }
    
    private fun showNotificationPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_permission_rationale_title)
            .setMessage(R.string.notification_permission_rationale_message)
            .setPositiveButton("OK") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            .setNegativeButton("Skip") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, R.string.notification_permission_optional, Toast.LENGTH_LONG).show()
            }
            .create()
            .show()
    }
    
    private fun setupResolutionSpinner() {
        resolutionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Skip if we're programmatically updating the spinner
                if (isUpdatingSpinners) return
                
                val selectedItem = parent?.getItemAtPosition(position) as? String
                // Allow resolution changes when camera service is bound (server doesn't need to be running)
                if (selectedItem != null && cameraService != null) {
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
                // Skip if we're programmatically updating the spinner
                if (isUpdatingSpinners) return
                
                val selectedItem = parent?.getItemAtPosition(position) as? String
                // Allow rotation changes when camera service is bound (server doesn't need to be running)
                if (selectedItem != null && cameraService != null) {
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
                // Skip if we're programmatically updating the spinner
                if (isUpdatingSpinners) return
                
                val selectedItem = parent?.getItemAtPosition(position) as? String
                // Allow orientation changes when camera service is bound (server doesn't need to be running)
                if (selectedItem != null && cameraService != null) {
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
    

    
    private fun loadResolutions() {
        // Get resolutions from service ONLY - single source of truth
        val resolutions = cameraService?.getSupportedResolutions() ?: emptyList()
        
        val items = mutableListOf(getString(R.string.resolution_auto))
        items.addAll(resolutions.map { "${it.width}x${it.height}" })
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
        
        // Set current selection if service is available
        val currentResolution = cameraService?.getSelectedResolution()
        val selectionString = if (currentResolution != null) {
            "${currentResolution.width}x${currentResolution.height}"
        } else {
            getString(R.string.resolution_auto)
        }
        
        // Update lastAppliedResolution to match current state to prevent false triggers
        lastAppliedResolution = selectionString
        
        val index = items.indexOf(selectionString)
        if (index >= 0) {
            resolutionSpinner.setSelection(index)
        }
    }
    
    private fun applyResolution(selection: String) {
        // Prevent infinite loop: only apply if resolution actually changed
        // onItemSelected fires every time setSelection() is called, even with same value
        // This causes: callback → loadResolutions → setSelection → onItemSelected → applyResolution → requestBindCamera → infinite loop
        if (selection == lastAppliedResolution) {
            Log.d("MainActivity", "applyResolution: same value '$selection', skipping")
            return
        }
        
        Log.d("MainActivity", "applyResolution: changing from '$lastAppliedResolution' to '$selection'")
        lastAppliedResolution = selection
        
        val service = cameraService ?: return
        
        if (selection == getString(R.string.resolution_auto)) {
            service.setResolutionAndRebind(null) // Set resolution and trigger rebind atomically
        } else {
            val parts = selection.split("x")
            if (parts.size == 2) {
                val width = parts[0].toIntOrNull()
                val height = parts[1].toIntOrNull()
                if (width != null && height != null) {
                    service.setResolutionAndRebind(Size(width, height)) // Set resolution and trigger rebind atomically
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
        // Use device-protected storage for Direct Boot compatibility (Android N+)
        // This ensures BootReceiver can access the setting even before device unlock
        val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        
        // Load saved autostart preference
        val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        autoStartCheckBox.isChecked = autoStart
        
        // Save preference when changed
        autoStartCheckBox.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_AUTO_START, isChecked).apply()
            Log.d("MainActivity", "Auto-start preference changed to: $isChecked (using device-protected storage)")
            
            // Show Android 15 info when enabling auto-start
            if (isChecked && Build.VERSION.SDK_INT >= 35) {
                AlertDialog.Builder(this)
                    .setTitle("Android 15 Auto-Start")
                    .setMessage("On Android 15, the app uses on-demand camera activation:\n\n• Server starts automatically at boot\n• Camera activates when first client connects\n• Fully automatic, no manual intervention needed\n\nThis complies with Android 15 restrictions while maintaining full functionality.")
                    .setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show()
            }
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
    
    private fun setupOsdCheckBoxes() {
        // Set listeners for OSD overlay checkboxes
        showDateTimeCheckBox.setOnCheckedChangeListener { _, isChecked ->
            cameraService?.setShowDateTimeOverlay(isChecked)
        }
        
        showBatteryCheckBox.setOnCheckedChangeListener { _, isChecked ->
            cameraService?.setShowBatteryOverlay(isChecked)
        }
        
        showResolutionCheckBox.setOnCheckedChangeListener { _, isChecked ->
            cameraService?.setShowResolutionOverlay(isChecked)
        }
        
        showFpsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            cameraService?.setShowFpsOverlay(isChecked)
        }
    }
    
    private fun setupMjpegFpsSpinner() {
        // Create FPS options: 1, 5, 10, 15, 20, 24, 30, 60
        val fpsOptions = listOf(1, 5, 10, 15, 20, 24, 30, 60)
        val items = fpsOptions.map { "$it fps" }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mjpegFpsSpinner.adapter = adapter
        
        mjpegFpsSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Skip if we're programmatically updating the spinner
                if (isUpdatingSpinners) return
                
                val fps = fpsOptions[position]
                cameraService?.setTargetMjpegFps(fps)
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }
    
    private fun loadOsdSettings() {
        val service = cameraService ?: return
        
        // Load current OSD overlay settings
        showDateTimeCheckBox.isChecked = service.getShowDateTimeOverlay()
        showBatteryCheckBox.isChecked = service.getShowBatteryOverlay()
        showResolutionCheckBox.isChecked = service.getShowResolutionOverlay()
        showFpsCheckBox.isChecked = service.getShowFpsOverlay()
    }
    
    private fun loadFpsSettings() {
        val service = cameraService ?: return
        
        // Update current FPS display
        val currentFps = service.getCurrentFps()
        currentFpsText.text = "%.1f fps".format(currentFps)
        
        // Load target MJPEG FPS setting
        val targetFps = service.getTargetMjpegFps()
        val fpsOptions = listOf(1, 5, 10, 15, 20, 24, 30, 60)
        val index = fpsOptions.indexOf(targetFps)
        if (index >= 0) {
            mjpegFpsSpinner.setSelection(index)
        }
    }
    
    private fun setupDeviceNameControls() {
        // Load current device name when service connects
        saveDeviceNameButton.setOnClickListener {
            val newName = deviceNameEditText.text.toString().trim()
            if (newName.isNotEmpty()) {
                cameraService?.setDeviceName(newName)
                Toast.makeText(this, "Device name saved: $newName", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a device name", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun loadDeviceName() {
        val service = cameraService ?: return
        
        // Load current device name
        val currentName = service.getDeviceName()
        deviceNameEditText.setText(currentName)
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
    
    private fun updateFpsDisplay() {
        val service = cameraService ?: return
        val currentFps = service.getCurrentFps()
        currentFpsText.text = "%.1f fps".format(currentFps)
    }
    
    private fun checkAutoStart() {
        // Use device-protected storage for Direct Boot compatibility (Android N+)
        val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            createDeviceProtectedStorageContext()
        } else {
            this
        }
        
        val prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        
        // Bind if not already bound
        if (!isServiceBound) {
            startAndBindService(intent)
        } else {
            // Service already bound, just send command to start server
            ContextCompat.startForegroundService(this, intent)
            updateUI()
        }
    }
    
    private fun stopServer() {
        // Stop only the HTTP server, not the entire service
        // This keeps the camera running so the preview continues to work
        cameraService?.stopServer() ?: run {
            Log.w("MainActivity", "Cannot stop server: service not bound")
            Toast.makeText(this, "Cannot stop server: service not connected", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }
    
    private fun updateUI() {
        val isRunning = cameraService?.isServerRunning() == true
        val isCameraAvailable = cameraService != null
        
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
        
        // Server controls: require permission and either running or can start
        startStopButton.isEnabled = isRunning || hasCameraPermission
        
        // Camera controls: enabled when camera service is available (bound)
        switchCameraButton.isEnabled = isCameraAvailable
        resolutionSpinner.isEnabled = isCameraAvailable
        cameraOrientationSpinner.isEnabled = isCameraAvailable
        rotationSpinner.isEnabled = isCameraAvailable
        
        // Max connections: only enabled when server is running
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
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                // Service not running, which is fine
                Log.d("MainActivity", "Service not available in onResume: ${e.message}")
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
            isServiceBound = false
        }
    }
}
