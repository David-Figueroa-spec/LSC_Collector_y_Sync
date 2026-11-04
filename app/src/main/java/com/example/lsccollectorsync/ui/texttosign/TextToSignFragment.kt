package com.example.lsccollectorsync.ui.texttosign

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.lsccollectorsync.R
import com.example.lsccollectorsync.data.repository.FrasesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class TextToSignFragment : Fragment(R.layout.fragment_text_to_sign) {

    private val viewModel: TextToSignViewModel by viewModels()

    private lateinit var inputEditText: EditText
    private lateinit var signsContainer: LinearLayout
    private lateinit var hand3DView: Hand3DView
    private var reproduccion: Job? = null
    private var claveReproduccion: String? = null
    private val frases by lazy { FrasesRepository(requireContext()) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputEditText = view.findViewById(R.id.inputEditText)
        signsContainer = view.findViewById(R.id.signsContainer)
        hand3DView = view.findViewById(R.id.hand3DView)

        inputEditText.doAfterTextChanged { text ->
            viewModel.onInputTextChanged(text?.toString() ?: "")
        }

        // Texto recibido desde Historial o Frecuentes (solo al crear la pantalla, no al girarla)
        if (savedInstanceState == null) {
            arguments?.getString("texto")?.let { inputEditText.setText(it) }
        }

        observeUiState()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.uiState, viewModel.poses) { state, poses -> state to poses }
                    .collect { (state, poses) ->
                        renderSignItems(state.signItems)
                        reproducir(state.signItems, poses)
                    }
            }
        }
    }

    private fun reproducir(items: List<SignImageItem>, poses: Map<String, List<FloatArray>>) {
        val letras = items.map { it.character }.filter { it.isLetter() }
        val clave = letras.joinToString("") + "|" + poses.size
        if (clave == claveReproduccion) return // misma secuencia: no reiniciar
        claveReproduccion = clave
        reproduccion?.cancel()
        if (letras.isEmpty()) {
            hand3DView.mostrarPose(null)
            return
        }
        reproduccion = viewLifecycleOwner.lifecycleScope.launch {
            for (c in letras) {
                val frames = poses[c.toString()]
                if (frames == null) {
                    hand3DView.mostrarPose(null)
                } else {
                    val dur = if (frames.size > 1) 150L else 350L
                    for (f in frames) {
                        hand3DView.mostrarPose(f, dur, false)
                        if (frames.size > 1) delay(160)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun renderSignItems(items: List<SignImageItem>) {
        signsContainer.removeAllViews()

        val itemWidthPx = dp(110)
        val itemHeightPx = dp(140)
        val imageSizePx = dp(90)
        val marginPx = dp(8)

        for (item in items) {
            val cardView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#222222"))
                setPadding(marginPx, marginPx, marginPx, marginPx)
                layoutParams = LinearLayout.LayoutParams(itemWidthPx, itemHeightPx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }
            }

            val imageView = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(imageSizePx, imageSizePx)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (item.bitmap != null) {
                    setImageBitmap(item.bitmap)
                } else {
                    setBackgroundColor(Color.parseColor("#333333"))
                }
            }

            val labelView = TextView(requireContext()).apply {
                text = item.character.toString()
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }

            cardView.addView(imageView)
            cardView.addView(labelView)
            signsContainer.addView(cardView)
        }
    }

    override fun onStop() {
        super.onStop()
        val texto = inputEditText.text.toString().trim()
        if (texto.length >= 2) frases.agregarHistorial(FrasesRepository.TEXTO_A_SENAS, texto)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
