package com.sales.phonescanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var isAutoMode by mutableStateOf(false)
    private var isScanning by mutableStateOf(true)
    private var detectedNumber by mutableStateOf<String?>(null)

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true) {
                // Camera granted
            } else {
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.CALL_PHONE))

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    @Composable
    fun MainScreen() {
        Box(modifier = Modifier.fillMaxSize()) {
            CameraPreviewView()

            // Targeting Box overlay
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.8f)
                    .height(150.dp)
                    .border(BorderStroke(3.dp, if (isScanning) Color.Green else Color.Red), RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.1f))
            )

            // Bottom Panel
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isScanning) "Scanning..." else "Paused",
                        color = if (isScanning) Color(0xFF007700) else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto Call")
                        Switch(
                            checked = isAutoMode,
                            onCheckedChange = { isAutoMode = it }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (detectedNumber != null) {
                    Text(
                        text = "Found: $detectedNumber",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { resumeScanning() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                        ) {
                            Text("Cancel / Rescan")
                        }
                        Button(
                            onClick = { initiateCall(detectedNumber!!) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF))
                        ) {
                            Text("Call Now")
                        }
                    }
                } else {
                    Button(
                        onClick = { isScanning = !isScanning },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isScanning) Color.Red else Color(0xFF00AA00))
                    ) {
                        Text(if (isScanning) "Stop Scanning" else "Start Scanning")
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreviewView() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, TextAnalyzer { number ->
                                if (isScanning) {
                                    handleNumberDetected(number)
                                }
                            })
                        }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, cameraSelector, preview, imageAnalyzer
                        )
                    } catch (exc: Exception) {
                        Log.e("CameraX", "Use case binding failed", exc)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    private var consecutiveDetections = 0
    private var lastDetected = ""

    private fun handleNumberDetected(number: String) {
        runOnUiThread {
            if (number == lastDetected) {
                consecutiveDetections++
            } else {
                lastDetected = number
                consecutiveDetections = 1
            }

            // Stability check: Must see the same number 3 frames in a row
            if (consecutiveDetections >= 3) {
                isScanning = false
                detectedNumber = number
                
                if (isAutoMode) {
                    initiateCall(number)
                }
            }
        }
    }

    private fun resumeScanning() {
        detectedNumber = null
        lastDetected = ""
        consecutiveDetections = 0
        isScanning = true
    }

    private fun initiateCall(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$number")
            startActivity(callIntent)
        } else {
            // Fallback to dialer if permission not granted
            val dialIntent = Intent(Intent.ACTION_DIAL)
            dialIntent.data = Uri.parse("tel:$number")
            startActivity(dialIntent)
        }
        // Keep scanning paused after call to avoid accidental double calls
    }
}

// --- OCR AND REGEX LOGIC ---

class TextAnalyzer(private val onNumberDetected: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = visionText.text
                    val foundNumber = extractIndianPhoneNumber(text)
                    if (foundNumber != null) {
                        onNumberDetected(foundNumber)
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun extractIndianPhoneNumber(rawText: String): String? {
        // Split by lines and spaces to isolate potential number blocks
        val tokens = rawText.split('\n', ' ', '\r', '\t')
        
        for (token in tokens) {
            // Strip out non-numeric characters for length/format checking
            val digits = token.replace(Regex("[^0-9]"), "")
            
            // Check Indian Mobile formats (Starts with 6,7,8,9, strictly 10 digits of core)
            val coreNumberRegex = Regex("^[6-9]\\d{9}$")
            
            if (digits.length == 10 && digits.matches(coreNumberRegex)) {
                return digits
            }
            if (digits.length == 11 && digits.startsWith("0") && digits.substring(1).matches(coreNumberRegex)) {
                return digits.substring(1)
            }
            if (digits.length == 12 && digits.startsWith("91") && digits.substring(2).matches(coreNumberRegex)) {
                return digits.substring(2)
            }
        }
        
        // Also do a pass for combined tokens (e.g. "98765 43210")
        val allDigits = rawText.replace(Regex("[^0-9]"), "")
        if (allDigits.length in 10..12) {
             if (allDigits.length == 10 && allDigits.matches(Regex("^[6-9]\\d{9}$"))) return allDigits
        }

        return null
    }
}
