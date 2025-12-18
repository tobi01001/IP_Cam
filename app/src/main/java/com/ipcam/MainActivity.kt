package com.ipcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var previewImageView: ImageView
    private lateinit var serverStatusText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var cameraSelectionText: TextView
    private lateinit var endpointsText: TextView
    private lateinit var switchCameraButton: Button
    private lateinit var startStopButton: Button
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Camera permission granted, service will handle camera
            Toast.makeText(this, "Camera permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
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
                }
            }
            
            cameraService?.setOnFrameAvailableCallback { bitmap ->
                runOnUiThread {
                    previewImageView.setImageBitmap(bitmap)
                }
            }
            
            updateUI()
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
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startStopButton = findViewById(R.id.startStopButton)
        
        setupEndpointsText()
        
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
                // Permission already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
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
    }
    
    private fun toggleServer() {
        if (cameraService?.isServerRunning() == true) {
            stopServer()
        } else {
            startServer()
        }
    }
    
    private fun startServer() {
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
        
        switchCameraButton.isEnabled = isRunning
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
