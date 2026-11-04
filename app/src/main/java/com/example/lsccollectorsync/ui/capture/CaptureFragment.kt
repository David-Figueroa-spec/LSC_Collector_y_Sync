package com.example.lsccollectorsync.ui.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.lsccollectorsync.HandLandmarkerHelper
import com.example.lsccollectorsync.OverlayView
import com.example.lsccollectorsync.R
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureFragment : Fragment(R.layout.fragment_capture), HandLandmarkerHelper.LandmarkerListener {

    private val viewModel: CaptureViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var footerText: TextView
    private lateinit var recordButton: Button
    private lateinit var letterContainer: LinearLayout
    private lateinit var switchCameraButton: TextView
    private lateinit var flashButton: TextView
    private lateinit var exportButton: TextView
    private lateinit var referenceImage: ImageView

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService
    private val letterButtons = mutableMapOf<String, TextView>()

    private var camera: Camera? = null
    private var frameCounter = 0
    private var latestLandmarks: List<Float>? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onCameraPermissionGranted() else onCameraPermissionDenied()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        overlayView = view.findViewById(R.id.overlayView)
        statusText = view.findViewById(R.id.statusText)
        footerText = view.findViewById(R.id.footerText)
        recordButton = view.findViewById(R.id.recordButton)
        letterContainer = view.findViewById(R.id.letterContainer)
        switchCameraButton = view.findViewById(R.id.switchCameraButton)
        flashButton = view.findViewById(R.id.flashButton)
        exportButton = view.findViewById(R.id.exportButton)
        referenceImage = view.findViewById(R.id.referenceImage)

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(context = requireContext(), listener = this)
        }

        buildLetterSelector()
        setupListeners()
        observeUiState()
        checkCameraPermission()
    }

    private fun setupListeners() {
        recordButton.setOnClickListener {
            val state = viewModel.uiState.value.recordingState
            if (state == RecordingState.IDLE) {
                viewModel.startRecordingFlow()
            } else {
                viewModel.cancelRecording()
            }
        }

        switchCameraButton.setOnClickListener {
            viewModel.toggleCameraLens()
            startCamera()
        }

        flashButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (cam.cameraInfo.hasFlashUnit()) {
                val isOn = viewModel.toggleTorch()
                cam.cameraControl.enableTorch(isOn)
                flashButton.text = if (isOn) "🔦" else "⚡"
            }
        }

        statusText.setOnClickListener {
            viewModel.syncWithPc()
        }

        exportButton.setOnClickListener {
            exportButton.isEnabled = false
            Toast.makeText(requireContext(), "Preparando exportación...", Toast.LENGTH_SHORT).show()
            viewModel.exportSamples { file ->
                exportButton.isEnabled = true
                if (file != null) {
                    shareFile(file)
                } else {
                    Toast.makeText(requireContext(), "No hay muestras guardadas para exportar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun buildLetterSelector() {
        val paddingPx = dp(14)
        val marginPx = dp(6)

        for (letter in viewModel.getAlphabet()) {
            val chip = TextView(requireContext()).apply {
                text = letter
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(marginPx, 0, marginPx, 0) }
                setOnClickListener { viewModel.selectLetter(letter) }
            }
            letterButtons[letter] = chip
            letterContainer.addView(chip)
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    statusText.text = state.syncStatus
                    footerText.text = "Letra: ${state.selectedLetter} · Muestras guardadas: ${state.savedCountForSelectedLetter}"

                    if (state.referenceBitmap != null) {
                        referenceImage.setImageBitmap(state.referenceBitmap)
                        referenceImage.visibility = View.VISIBLE
                    } else {
                        referenceImage.visibility = View.GONE
                    }

                    refreshLetterChipStyles(state.selectedLetter, state.recordingState == RecordingState.IDLE)

                    when (state.recordingState) {
                        RecordingState.IDLE -> recordButton.text = "Grabar"
                        RecordingState.WAITING_FOR_HAND -> recordButton.text = "Esperando mano..."
                        RecordingState.COUNTDOWN -> recordButton.text = state.countdownValue.toString()
                        RecordingState.RECORDING -> recordButton.text = "Grabando ${state.capturedCount}/${CaptureViewModel.TARGET_SAMPLES}"
                    }
                }
            }
        }
    }

    private fun refreshLetterChipStyles(selectedLetter: String, enabled: Boolean) {
        for ((letter, chip) in letterButtons) {
            val isSelected = letter == selectedLetter
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.5f
            val bg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (isSelected) Color.parseColor("#E74C3C") else Color.parseColor("#333333"))
            }
            chip.background = bg
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> onCameraPermissionGranted()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onCameraPermissionGranted() {
        startCamera()
    }

    private fun onCameraPermissionDenied() {
        statusText.text = "🔴 Permiso de cámara denegado"
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        frameCounter++
                        if (frameCounter % 2 != 0) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val isFront = viewModel.uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT
                        if (::handLandmarkerHelper.isInitialized) {
                            handLandmarkerHelper.detectLiveStream(
                                imageProxy = imageProxy,
                                isFrontCamera = isFront
                            )
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(viewModel.uiState.value.lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageAnalyzer
                )

                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                flashButton.isEnabled = hasFlash
                flashButton.alpha = if (hasFlash) 1f else 0.4f
                flashButton.text = "⚡"

            } catch (exc: Exception) {
                Log.e("CaptureFragment", "Fallo al vincular la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    override fun onResults(result: HandLandmarkerResult, imageHeight: Int, imageWidth: Int) {
        activity?.runOnUiThread {
            overlayView.setResults(result, imageHeight, imageWidth)

            val hands = result.landmarks()
            val handPresent = hands.isNotEmpty()
            latestLandmarks = if (handPresent) flattenLandmarks(hands[0]) else null

            viewModel.onHandPresenceChanged(handPresent, latestLandmarks)
        }
    }

    override fun onError(error: String) {
        activity?.runOnUiThread {
            Log.e("CaptureFragment", "MediaPipe error: $error")
        }
    }

    private fun flattenLandmarks(landmarks: List<NormalizedLandmark>): List<Float> {
        val out = ArrayList<Float>(landmarks.size * 3)
        for (point in landmarks) {
            out.add(point.x())
            out.add(point.y())
            out.add(point.z())
        }
        return out
    }

    private fun shareFile(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir muestras LSC"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        if (::handLandmarkerHelper.isInitialized) {
            handLandmarkerHelper.close()
        }
    }
}
