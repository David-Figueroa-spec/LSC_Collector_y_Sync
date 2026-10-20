package com.example.lsccollectorsync

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        val logoImage = findViewById<ImageView>(R.id.logoImage)
        val captureButton = findViewById<Button>(R.id.captureButton)

        // Carga el logo desde assets/app_logo.png
        try {
            assets.open("app_logo.png").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                logoImage.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            // Si no se encuentra el logo, simplemente no se muestra (no rompe la app)
        }

        captureButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}