package com.example.lsccollectorsync

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), HandLandmarkerHelper.LandmarkerListener {

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

    // --- Room ---
    private lateinit var db: AppDatabase
    private lateinit var dao: SampleDao
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    // --- Selección de letra ---
    private var selectedLetter: String = LscAlphabet.LETTERS.first()
    private val letterButtons = mutableMapOf<String, TextView>()

    // --- Cámara: lente actual, control de flash ---
    private var currentLensFacing = CameraSelector.LENS_FACING_FRONT
    private var camera: Camera? = null
    private var torchOn = false

    // --- Imagen de referencia de la seña ---
    private val referenceImageCache = mutableMapOf<String, Bitmap>()

    // --- WebSocket / Sync con el PC ---
    private val httpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var isSyncing = false

    // --- Máquina de estados de grabación ---
    private enum class RecordingState { IDLE, WAITING_FOR_HAND, COUNTDOWN, RECORDING }
    private var recordingState = RecordingState.IDLE
    private var handFirstSeenAt = 0L
    private var currentCountdown = 0
    private var capturedThisRun = 0
    private var frameCounter = 0
    private var lastCaptureTime = 0L
    private var latestLandmarks: List<Float>? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val HAND_STABLE_MS = 800L
        private const val CAPTURE_INTERVAL_MS = 150L
        private const val TARGET_SAMPLES = 30
        private const val PC_WS_URL = "ws://127.0.0.1:8765"
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onCameraPermissionGranted() else onCameraPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        footerText = findViewById(R.id.footerText)
        recordButton = findViewById(R.id.recordButton)
        letterContainer = findViewById(R.id.letterContainer)
        switchCameraButton = findViewById(R.id.switchCameraButton)
        flashButton = findViewById(R.id.flashButton)
        exportButton = findViewById(R.id.exportButton)
        referenceImage = findViewById(R.id.referenceImage)

        db = AppDatabase.getInstance(this)
        dao = db.sampleDao()

        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(context = this, listener = this)
        }

        buildLetterSelector()
        updateFooter()
        loadReferenceImage(selectedLetter)

        recordButton.setOnClickListener {
            if (recordingState == RecordingState.IDLE) {
                startRecordingFlow()
            } else {
                cancelRecording()
            }
        }

        switchCameraButton.setOnClickListener {
            if (recordingState != RecordingState.IDLE) return@setOnClickListener
            currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            torchOn = false
            startCamera()
        }

        flashButton.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            if (cam.cameraInfo.hasFlashUnit()) {
                torchOn = !torchOn
                cam.cameraControl.enableTorch(torchOn)
                flashButton.text = if (torchOn) "🔦" else "⚡"
            }
        }

        statusText.setOnClickListener {
            onStatusTextTapped()
        }

        exportButton.setOnClickListener {
            exportSamplesToFile()
        }

        checkCameraPermission()
    }

    // ---------------------------------------------------------------
    // Selector de letras
    // ---------------------------------------------------------------

    private fun buildLetterSelector() {
        val paddingPx = dp(14)
        val marginPx = dp(6)

        for (letter in LscAlphabet.LETTERS) {
            val chip = TextView(this).apply {
                text = letter
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(marginPx, 0, marginPx, 0) }
                setOnClickListener { selectLetter(letter) }
            }
            letterButtons[letter] = chip
            letterContainer.addView(chip)
        }
        refreshLetterChipStyles()
    }

    private fun selectLetter(letter: String) {
        if (recordingState != RecordingState.IDLE) return
        selectedLetter = letter
        refreshLetterChipStyles()
        updateFooter()
        loadReferenceImage(letter)
    }

    private fun refreshLetterChipStyles() {
        for ((letter, chip) in letterButtons) {
            val isSelected = letter == selectedLetter
            val bg = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (isSelected) Color.parseColor("#E74C3C") else Color.parseColor("#333333"))
            }
            chip.background = bg
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        for (chip in letterButtons.values) {
            chip.isEnabled = enabled
            chip.alpha = if (enabled) 1f else 0.5f
        }
        switchCameraButton.isEnabled = enabled
        switchCameraButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    // ---------------------------------------------------------------
    // Imagen de referencia de la seña
    // ---------------------------------------------------------------

    private fun signAssetBaseName(letter: String): String = if (letter == "Ñ") "enye" else letter

    private fun loadReferenceImage(letter: String) {
        referenceImageCache[letter]?.let {
            referenceImage.setImageBitmap(it)
            referenceImage.visibility = View.VISIBLE
            return
        }

        dbExecutor.execute {
            val base = signAssetBaseName(letter)
            val bitmap = tryLoadBitmap("signs/$base.png") ?: tryLoadBitmap("signs/$base.jpg")

            runOnUiThread {
                if (bitmap != null) {
                    referenceImageCache[letter] = bitmap
                    referenceImage.setImageBitmap(bitmap)
                    referenceImage.visibility = View.VISIBLE
                } else {
                    referenceImage.visibility = View.GONE
                }
            }
        }
    }

    private fun tryLoadBitmap(assetPath: String): Bitmap? {
        return try {
            assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            null
        }
    }

    // ---------------------------------------------------------------
    // Permisos + cámara
    // ---------------------------------------------------------------

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> onCameraPermissionGranted()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun onCameraPermissionGranted() {
        statusText.text = "⚪ Cámara lista (sin PC)"
        startCamera()
    }

    private fun onCameraPermissionDenied() {
        statusText.text = "🔴 Permiso de cámara denegado"
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

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
                            // Salta este frame para reducir carga (procesa 1 de cada 2)
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val isFront = currentLensFacing == CameraSelector.LENS_FACING_FRONT
                        handLandmarkerHelper.detectLiveStream(
                            imageProxy = imageProxy,
                            isFrontCamera = isFront
                        )
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(currentLensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

                val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
                flashButton.isEnabled = hasFlash
                flashButton.alpha = if (hasFlash) 1f else 0.4f
                flashButton.text = "⚡"
                torchOn = false

            } catch (exc: Exception) {
                Log.e("MainActivity", "Fallo al vincular la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------
    // Flujo de grabación
    // ---------------------------------------------------------------

    private fun startRecordingFlow() {
        recordingState = RecordingState.WAITING_FOR_HAND
        handFirstSeenAt = 0L
        recordButton.text = "Esperando mano..."
        setControlsEnabled(false)
    }

    private fun cancelRecording() {
        handler.removeCallbacksAndMessages(null)
        recordingState = RecordingState.IDLE
        recordButton.text = "Grabar"
        setControlsEnabled(true)
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            currentCountdown--
            if (currentCountdown > 0) {
                recordButton.text = currentCountdown.toString()
                handler.postDelayed(this, 1000)
            } else {
                beginActualRecording()
            }
        }
    }

    private fun startCountdown() {
        recordingState = RecordingState.COUNTDOWN
        currentCountdown = 3
        recordButton.text = currentCountdown.toString()
        handler.postDelayed(countdownRunnable, 1000)
    }

    private fun beginActualRecording() {
        recordingState = RecordingState.RECORDING
        capturedThisRun = 0
        lastCaptureTime = 0L
        recordButton.text = "Grabando 0/$TARGET_SAMPLES"
    }

    private fun captureSample() {
        val landmarks = latestLandmarks ?: return
        val letter = selectedLetter
        val json = gson.toJson(landmarks)
        val timestamp = System.currentTimeMillis()

        dbExecutor.execute {
            dao.insert(
                SampleEntity(
                    letter = letter,
                    landmarksJson = json,
                    timestamp = timestamp,
                    synced = false
                )
            )
            runOnUiThread {
                capturedThisRun++
                recordButton.text = "Grabando $capturedThisRun/$TARGET_SAMPLES"
                updateFooter()
                if (capturedThisRun >= TARGET_SAMPLES) {
                    finishRecording()
                }
            }
        }
    }

    private fun finishRecording() {
        recordingState = RecordingState.IDLE
        recordButton.text = "Grabar"
        setControlsEnabled(true)
    }

    private fun updateFooter() {
        val letter = selectedLetter
        dbExecutor.execute {
            val count = dao.countForLetter(letter)
            runOnUiThread {
                footerText.text = "Letra: $letter · Muestras guardadas: $count"
            }
        }
    }

    // ---------------------------------------------------------------
    // Exportar y compartir los datos recolectados
    // ---------------------------------------------------------------

    private fun exportSamplesToFile() {
        exportButton.isEnabled = false
        Toast.makeText(this, "Preparando exportación...", Toast.LENGTH_SHORT).show()

        dbExecutor.execute {
            val samples = dao.getAllSamples()

            if (samples.isEmpty()) {
                runOnUiThread {
                    exportButton.isEnabled = true
                    Toast.makeText(this, "No hay muestras guardadas para exportar", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            try {
                val csvFile = buildCsvFile(samples)
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", csvFile)

                runOnUiThread {
                    exportButton.isEnabled = true
                    shareFile(uri)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error exportando muestras", e)
                runOnUiThread {
                    exportButton.isEnabled = true
                    Toast.makeText(this, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildCsvFile(samples: List<SampleEntity>): File {
        val floatListType = object : TypeToken<List<Float>>() {}.type

        // Determina cuántos puntos tiene cada muestra a partir de la primera fila válida
        val numPoints = samples.asSequence()
            .mapNotNull { runCatching { gson.fromJson<List<Float>>(it.landmarksJson, floatListType) }.getOrNull() }
            .firstOrNull()
            ?.let { it.size / 3 } ?: 21

        val sb = StringBuilder()
        sb.append("letra,timestamp,sincronizado")
        for (i in 0 until numPoints) {
            sb.append(",p${i}_x,p${i}_y,p${i}_z")
        }
        sb.append('\n')

        for (sample in samples) {
            val landmarks: List<Float> = runCatching {
                gson.fromJson<List<Float>>(sample.landmarksJson, floatListType)
            }.getOrDefault(emptyList())

            sb.append(sample.letter)
            sb.append(',')
            sb.append(sample.timestamp)
            sb.append(',')
            sb.append(if (sample.synced) "1" else "0")
            for (value in landmarks) {
                sb.append(',')
                sb.append(value)
            }
            sb.append('\n')
        }

        val exportDir = File(cacheDir, "exports").apply { mkdirs() }
        val fileName = "lsc_muestras_${System.currentTimeMillis()}.csv"
        val file = File(exportDir, fileName)
        file.writeText(sb.toString())
        return file
    }

    private fun shareFile(uri: android.net.Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir muestras LSC"))
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

    // ---------------------------------------------------------------
    // Sincronización con el PC (WebSocket sobre USB vía adb reverse)
    // ---------------------------------------------------------------

    private fun onStatusTextTapped() {
        if (isSyncing) return
        syncWithPc()
    }

    private fun syncWithPc() {
        isSyncing = true
        statusText.text = "⏳ Conectando con el PC..."

        val request = Request.Builder().url(PC_WS_URL).build()
        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { statusText.text = "🟢 PC conectado, enviando..." }
                sendPendingSamples(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    statusText.text = "🔴 Sin PC (offline)"
                    isSyncing = false
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { isSyncing = false }
            }
        })
    }

    private fun sendPendingSamples(ws: WebSocket) {
        dbExecutor.execute {
            val pending = dao.getPendingSamples()

            if (pending.isEmpty()) {
                runOnUiThread {
                    statusText.text = "✅ PC conectado (nada pendiente)"
                    isSyncing = false
                }
                ws.close(1000, "Nada que enviar")
                return@execute
            }

            val floatListType = object : TypeToken<List<Float>>() {}.type
            val items = pending.map { sample ->
                val landmarks: List<Float> = gson.fromJson(sample.landmarksJson, floatListType)
                SamplePayload(
                    id = sample.id,
                    letter = sample.letter,
                    landmarks = landmarks,
                    timestamp = sample.timestamp
                )
            }

            val message = SyncMessage(items = items)
            val json = gson.toJson(message)
            ws.send(json)
        }
    }

    private fun handleServerMessage(text: String) {
        try {
            val ack = gson.fromJson(text, AckMessage::class.java)
            if (ack.type == "ack" && ack.ids.isNotEmpty()) {
                dbExecutor.execute {
                    dao.markSynced(ack.ids)
                    runOnUiThread {
                        statusText.text = "✅ Sincronizado (${ack.ids.size} enviadas)"
                        isSyncing = false
                        updateFooter()
                    }
                    webSocket?.close(1000, "Listo")
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error procesando ack: ${e.message}")
        }
    }

    // ---------------------------------------------------------------
    // Callbacks de HandLandmarkerHelper.LandmarkerListener
    // ---------------------------------------------------------------

    override fun onResults(result: HandLandmarkerResult, imageHeight: Int, imageWidth: Int) {
        runOnUiThread {
            overlayView.setResults(result, imageHeight, imageWidth)

            val hands = result.landmarks()
            val handPresent = hands.isNotEmpty()
            latestLandmarks = if (handPresent) flattenLandmarks(hands[0]) else null

            handleRecordingStateMachine(handPresent)
        }
    }

    private fun handleRecordingStateMachine(handPresent: Boolean) {
        when (recordingState) {
            RecordingState.WAITING_FOR_HAND -> {
                val now = SystemClock.uptimeMillis()
                if (handPresent) {
                    if (handFirstSeenAt == 0L) handFirstSeenAt = now
                    if (now - handFirstSeenAt >= HAND_STABLE_MS) {
                        startCountdown()
                    }
                } else {
                    handFirstSeenAt = 0L
                }
            }
            RecordingState.RECORDING -> {
                if (handPresent) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastCaptureTime >= CAPTURE_INTERVAL_MS) {
                        lastCaptureTime = now
                        captureSample()
                    }
                } else {
                    recordButton.text = "Grabando $capturedThisRun/$TARGET_SAMPLES (sin mano...)"
                }
            }
            else -> { /* IDLE / COUNTDOWN: sin acción aquí */ }
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            Log.e("MainActivity", "MediaPipe error: $error")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        dbExecutor.shutdown()
        if (::handLandmarkerHelper.isInitialized) {
            handLandmarkerHelper.close()
        }
    }
}