package com.example.lsccollectorsync.ui.frecuentes

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.lsccollectorsync.R
import com.example.lsccollectorsync.data.repository.FrasesRepository

class FrecuentesFragment : Fragment(R.layout.fragment_frecuentes) {

    private val frases by lazy { FrasesRepository(requireContext()) }
    private lateinit var lista: LinearLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lista = view.findViewById(R.id.listaFrecuentes)
        view.findViewById<Button>(R.id.btnAgregarFrecuente).setOnClickListener { pedirPalabra() }
        mostrar()
    }

    private fun mostrar() {
        lista.removeAllViews()
        for (par in frases.frecuentes().chunked(2)) {
            val fila = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            }
            for (palabra in par) fila.addView(crearChip(palabra))
            if (par.size == 1) fila.addView(View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            lista.addView(fila)
        }
    }

    private fun crearChip(palabra: String): TextView = TextView(requireContext()).apply {
        text = palabra
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#222222"))
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(2), Color.parseColor("#222222"))
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
        }
        // Tocar: abre Texto a señas con la palabra. Mantener pulsado: borrar (solo las propias).
        setOnClickListener {
            findNavController().navigate(R.id.textToSignFragment, bundleOf("texto" to palabra))
        }
        setOnLongClickListener {
            if (frases.esPropia(palabra)) {
                confirmarBorrar(palabra)
            } else {
                Toast.makeText(requireContext(), "Solo se pueden borrar las palabras que añadiste", Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun pedirPalabra() {
        val campo = EditText(requireContext()).apply {
            hint = "Palabra o frase corta"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Añadir palabra")
            .setView(campo)
            .setPositiveButton("Añadir") { _, _ ->
                if (frases.agregarFrecuente(campo.text.toString())) {
                    mostrar()
                } else {
                    Toast.makeText(requireContext(), "Está vacía, es muy larga o ya existe", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarBorrar(palabra: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar palabra")
            .setMessage("¿Quitar «$palabra» de tus palabras frecuentes?")
            .setPositiveButton("Borrar") { _, _ ->
                frases.eliminarFrecuente(palabra)
                mostrar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
