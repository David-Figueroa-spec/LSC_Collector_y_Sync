package com.example.lsccollectorsync.ui.signtotext

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import android.widget.Button
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.lsccollectorsync.OverlayView
import com.example.lsccollectorsync.R
import com.example.lsccollectorsync.data.repository.FrasesRepository
import com.example.lsccollectorsync.data.repository.ModelImporter
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SignToTextFragment : Fragment(R.layout.fragment_sign_to_text) {

    private val viewModel: SignToTextViewModel by viewModels()

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvPredictedLetter: TextView
    private lateinit var tvDetectedText: TextView
    private lateinit var btnSpace: Button
    private lateinit var btnClear: Button
    private lateinit var btnBackspace: Button
    private lateinit var btnSpeak: Button
    private lateinit var pbProgreso: ProgressBar
    private lateinit var llSugerencias: LinearLayout
    private var tts: TextToSpeech? = null
    private var textoSugerido: String? = null
    private val frases by lazy { FrasesRepository(requireContext()) }
    private lateinit var btnImportar: Button
    private lateinit var btnModeloBase: Button
    private val importer by lazy { ModelImporter(requireContext().applicationContext) }

    private val selectorModelo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importarModelo(uri)
        }

    private lateinit var cameraExecutor: ExecutorService
    private var frameCounter = 0

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else tvStatus.text = "🔴 Permiso de cámara denegado"
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        overlayView = view.findViewById(R.id.overlayView)
        tvStatus = view.findViewById(R.id.tvStatus)
        tvPredictedLetter = view.findViewById(R.id.tvPredictedLetter)
        tvDetectedText = view.findViewById(R.id.tvDetectedText)
        btnSpace = view.findViewById(R.id.btnSpace)
        btnClear = view.findViewById(R.id.btnClear)
        btnBackspace = view.findViewById(R.id.btnBackspace)
        btnSpeak = view.findViewById(R.id.btnSpeak)
        pbProgreso = view.findViewById(R.id.pbProgreso)
        llSugerencias = view.findViewById(R.id.llSugerencias)
        btnImportar = view.findViewById(R.id.btnImportarModelo)
        btnModeloBase = view.findViewById(R.id.btnModeloBase)

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnSpace.setOnClickListener { viewModel.addSpace() }
        btnClear.setOnClickListener {
            guardarEnHistorial()
            viewModel.clearText()
        }
        btnBackspace.setOnClickListener { viewModel.borrarUltima() }
        btnSpeak.setOnClickListener { hablar() }

        tts = TextToSpeech(requireContext().applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("es", "CO"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("es"))
                }
            }
        }
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                tts?.shutdown()
                tts = null
            }
        })

        btnImportar.setOnClickListener { selectorModelo.launch(arrayOf("*/*")) }
        btnModeloBase.setOnClickListener { confirmarModeloBase() }

        tvPredictedLetter.setOnClickListener {
            val letter = viewModel.uiState.value.currentPredictedLetter
            if (letter != null) {
                viewModel.appendLetter(letter)
            }
        }

        observeUiState()
        observeEsqueleto()
        checkCameraPermission()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.letraEscrita.collect {
                        view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                viewModel.uiState.collect { state ->
                    tvStatus.text = state.statusMessage
                    tvPredictedLetter.text = state.currentPredictedLetter ?: "--"
                    tvDetectedText.text = state.detectedText
                    pbProgreso.progress = (state.progreso * 100).toInt()
                    mostrarSugerencias(state.detectedText)
                }
            }
        }
    }

    // Dibuja el esqueleto de MediaPipe con cada resultado de la mano (igual que CaptureFragment).
    private fun observeEsqueleto() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.handRepository.handResults.collect { datos ->
                    overlayView.setResults(datos.result, datos.imageHeight, datos.imageWidth)
                }
            }
        }
    }

    private fun importarModelo(uri: Uri) {
        Toast.makeText(requireContext(), "⏳ Importando modelo...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val mensaje = when (val r = importer.importarAsync(uri)) {
                is ModelImporter.Resultado.Ok -> "✅ Modelo actualizado a la versión ${r.version}"
                is ModelImporter.Resultado.Error -> "🔴 ${r.mensaje}"
            }
            Toast.makeText(requireContext(), mensaje, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmarModeloBase() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Volver al modelo base")
            .setMessage("Se descartan las actualizaciones importadas y se usa el modelo incluido en la app.")
            .setPositiveButton("Volver") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    importer.volverAlBaseAsync()
                    Toast.makeText(requireContext(), "Modelo base activo", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
                        viewModel.handRepository.processFrame(imageProxy, isFrontCamera = true)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("SignToTextFragment", "Fallo al vincular la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    // Hasta 3 chips con palabras de Frecuentes/historial que empiezan por lo que llevas escrito
    private fun mostrarSugerencias(texto: String) {
        if (texto == textoSugerido) return        // solo se recalcula si el texto cambió
        textoSugerido = texto
        llSugerencias.removeAllViews()
        val prefijo = texto.substringAfterLast(' ')
        if (prefijo.isEmpty()) {
            llSugerencias.visibility = View.GONE
            return
        }
        val candidatas = (frases.frecuentes() + frases.historial(FrasesRepository.SENAS_A_TEXTO))
            .filter { it.startsWith(prefijo, ignoreCase = true) && !it.equals(prefijo, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .take(3)
        if (candidatas.isEmpty()) {
            llSugerencias.visibility = View.GONE
            return
        }
        candidatas.forEach { s ->
            val chip = Button(requireContext()).apply {
                text = s
                textSize = 12f
                isAllCaps = false
                setOnClickListener { viewModel.reemplazarPalabra(s) }
            }
            llSugerencias.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        llSugerencias.visibility = View.VISIBLE
    }

    private fun hablar() {
        val texto = viewModel.uiState.value.detectedText.trim()
        if (texto.isEmpty()) return
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "senas")
    }

    private fun guardarEnHistorial() {
        val texto = viewModel.uiState.value.detectedText.trim()
        if (texto.length >= 2) frases.agregarHistorial(FrasesRepository.SENAS_A_TEXTO, texto)
    }

    override fun onStop() {
        super.onStop()
        guardarEnHistorial()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
