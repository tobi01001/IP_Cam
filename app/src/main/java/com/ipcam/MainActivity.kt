package com.ipcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
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
    private lateinit var switchCameraButton: Button
    private lateinit var startStopButton: Button
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    private var hasCameraPermission = false
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            hasCameraPermission = true
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
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
                }
            }
            
            cameraService?.setOnFrameAvailableCallback { bitmap ->
                runOnUiThread {
                    previewImageView.setImageBitmap(bitmap)
                }
            }
            
            updateUI()
            loadResolutions()
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
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startStopButton = findViewById(R.id.startStopButton)
        
        setupEndpointsText()
        setupResolutionSpinner()
        
        switchCameraButton.setOnClickListener {
            switchCamera()
        }
        
        startStopButton.setOnClickListener {
            toggleServer()
        }
        
        checkCameraPermission()
    }
    
    private fun setupEndpointsText() {
        val endpoints = """
            ${getString(R.string.endpoint_snapshot)}
            ${getString(R.string.endpoint_stream)}
            ${getString(R.string.endpoint_switch)}
            ${getString(R.string.endpoint_status)}
            ${getString(R.string.endpoint_formats)}
            ${getString(R.string.endpoint_set_format)}
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
    
    private fun loadResolutions() {
        val service = cameraService ?: return
        if (!service.isServerRunning()) {
            // Set default when server is not running
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, 
                listOf(getString(R.string.resolution_auto)))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            resolutionSpinner.adapter = adapter
            return
        }
        
        val resolutions = service.getSupportedResolutions()
        val items = mutableListOf(getString(R.string.resolution_auto))
        items.addAll(resolutions.map { "${it.width}x${it.height}" })
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        resolutionSpinner.adapter = adapter
        
        // Set current selection
        val currentResolution = service.getSelectedResolution()
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
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun stopServer() {
        if (isServiceBound) {
            cameraService?.clearCallbacks()
            unbindService(serviceConnection)
            isServiceBound = false
        }
        val intent = Intent(this, CameraService::class.java)
        stopService(intent)
        cameraService = null
        previewImageView.setImageBitmap(null)
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
    
    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            cameraService?.clearCallbacks()
            unbindService(serviceConnection)
        }
    }
}
