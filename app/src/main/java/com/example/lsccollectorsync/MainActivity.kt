package com.example.lsccollectorsync

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import android.widget.TextView
import android.widget.Toast
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.lifecycle.lifecycleScope
import com.example.lsccollectorsync.data.repository.FrasesRepository
import com.example.lsccollectorsync.data.repository.ModelImporter
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        configurarBarra()

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        procesarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    // Barra inferior: Traducir / Historial / Frecuentes. Solo se ve en las pantallas de traducción.
    private fun configurarBarra() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val nav = navHost.navController
        val barra = findViewById<View>(R.id.barraInferior)
        val tabs = mapOf(
            R.id.tabTraducir to R.id.signToTextFragment,
            R.id.tabHistorial to R.id.historialFragment,
            R.id.tabFrecuentes to R.id.frecuentesFragment
        )
        for ((tabId, destino) in tabs) {
            findViewById<TextView>(tabId).setOnClickListener {
                // «Traducir» vuelve a la última dirección que usaste
                val objetivo = if (tabId == R.id.tabTraducir &&
                    FrasesRepository.direccionActual == FrasesRepository.TEXTO_A_SENAS
                ) R.id.textToSignFragment else destino
                if (nav.currentDestination?.id != objetivo) {
                    nav.navigate(objetivo, null, navOptions {
                        launchSingleTop = true
                        popUpTo(R.id.homeFragment)
                    })
                }
            }
        }
        nav.addOnDestinationChangedListener { _, d, _ ->
            // El Historial muestra la lista de la última pantalla de traducción visitada
            if (d.id == R.id.signToTextFragment) {
                com.example.lsccollectorsync.data.repository.FrasesRepository.direccionActual =
                    com.example.lsccollectorsync.data.repository.FrasesRepository.SENAS_A_TEXTO
            } else if (d.id == R.id.textToSignFragment) {
                com.example.lsccollectorsync.data.repository.FrasesRepository.direccionActual =
                    com.example.lsccollectorsync.data.repository.FrasesRepository.TEXTO_A_SENAS
            }
            val visible = d.id in tabs.values || d.id == R.id.textToSignFragment
            barra.visibility = if (visible) View.VISIBLE else View.GONE
            // Texto a señas cuenta como parte de «Traducir»
            val activo = if (d.id == R.id.textToSignFragment) R.id.signToTextFragment else d.id
            for ((tabId, destino) in tabs) {
                findViewById<TextView>(tabId).alpha = if (activo == destino) 1f else 0.55f
            }
        }
    }

    // Un .zip abierto desde otra app (WhatsApp, Telegram, Gmail...) se importa como modelo.
    private fun procesarIntent(entrante: Intent?) {
        val i = entrante ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return
        i.action = null   // para no reimportar al girar la pantalla
        Toast.makeText(this, "⏳ Importando modelo...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val mensaje = when (val r = ModelImporter(applicationContext).importarAsync(uri)) {
                is ModelImporter.Resultado.Ok -> "✅ Modelo actualizado a la versión ${r.version}"
                is ModelImporter.Resultado.Error -> "🔴 ${r.mensaje}"
            }
            Toast.makeText(this@MainActivity, mensaje, Toast.LENGTH_LONG).show()
        }
    }
}
