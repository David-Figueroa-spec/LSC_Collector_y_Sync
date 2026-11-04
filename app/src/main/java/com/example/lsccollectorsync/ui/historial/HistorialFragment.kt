package com.example.lsccollectorsync.ui.historial

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.lsccollectorsync.R
import com.example.lsccollectorsync.data.repository.FrasesRepository

class HistorialFragment : Fragment(R.layout.fragment_historial) {

    private val frases by lazy { FrasesRepository(requireContext()) }
    private lateinit var lista: LinearLayout
    private lateinit var vacio: TextView
    private lateinit var titulo: TextView
    private val direccion get() = FrasesRepository.direccionActual

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lista = view.findViewById(R.id.listaHistorial)
        vacio = view.findViewById(R.id.tvVacioHistorial)
        titulo = view.findViewById(R.id.tvTituloHistorial)
        view.findViewById<Button>(R.id.btnVaciarHistorial).setOnClickListener { confirmarVaciar() }
    }

    override fun onResume() {
        super.onResume()
        mostrar()
    }

    private fun mostrar() {
        lista.removeAllViews()
        titulo.text = if (direccion == FrasesRepository.SENAS_A_TEXTO)
            "Historial · Señas a texto" else "Historial · Texto a señas"
        val items = frases.historial(direccion)
        vacio.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (frase in items) {
            val pildora = TextView(requireContext()).apply {
                text = frase
                textSize = 15f
                setTextColor(Color.parseColor("#222222"))
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.parseColor("#D9D9D9"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                // Tocar: abre Texto a señas con esa frase. Mantener pulsado: borrarla.
                setOnClickListener {
                    findNavController().navigate(R.id.textToSignFragment, bundleOf("texto" to frase))
                }
                setOnLongClickListener {
                    confirmarBorrar(frase)
                    true
                }
            }
            lista.addView(pildora)
        }
    }

    private fun confirmarBorrar(frase: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Borrar frase")
            .setMessage("¿Quitar «$frase» del historial?")
            .setPositiveButton("Borrar") { _, _ ->
                frases.eliminarDeHistorial(direccion, frase)
                mostrar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmarVaciar() {
        if (frases.historial(direccion).isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Vaciar historial")
            .setMessage("Se borrarán todas las frases guardadas.")
            .setPositiveButton("Vaciar") { _, _ ->
                frases.borrarHistorial(direccion)
                mostrar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
