package com.ipcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var serverStatusText: TextView
    private lateinit var serverUrlText: TextView
    private lateinit var cameraSelectionText: TextView
    private lateinit var endpointsText: TextView
    private lateinit var switchCameraButton: Button
    private lateinit var startStopButton: Button
    
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    
    private var cameraService: CameraService? = null
    private var isServiceBound = false
    private var currentCamera = CameraSelector.DEFAULT_BACK_CAMERA
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCameraPreview()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_SHORT).show()
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            isServiceBound = true
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            isServiceBound = false
            updateUI()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        previewView = findViewById(R.id.previewView)
        serverStatusText = findViewById(R.id.serverStatusText)
        serverUrlText = findViewById(R.id.serverUrlText)
        cameraSelectionText = findViewById(R.id.cameraSelectionText)
        endpointsText = findViewById(R.id.endpointsText)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        startStopButton = findViewById(R.id.startStopButton)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
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
        """.trimIndent()
        endpointsText.text = endpoints
    }
    
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCameraPreview()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    private fun startCameraPreview() {
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, currentCamera, preview)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun switchCamera() {
        currentCamera = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        
        cameraService?.switchCamera(currentCamera)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
        
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
            unbindService(serviceConnection)
            isServiceBound = false
        }
        val intent = Intent(this, CameraService::class.java)
        stopService(intent)
        cameraService = null
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
        
        val cameraName = if (currentCamera == CameraSelector.DEFAULT_BACK_CAMERA) {
            getString(R.string.camera_back)
        } else {
            getString(R.string.camera_front)
        }
        cameraSelectionText.text = getString(R.string.camera_selection, cameraName)
        
        startStopButton.text = if (isRunning) {
            getString(R.string.stop_server)
        } else {
            getString(R.string.start_server)
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}
