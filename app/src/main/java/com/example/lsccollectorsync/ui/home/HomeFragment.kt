package com.example.lsccollectorsync.ui.home

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.lsccollectorsync.R

class HomeFragment : Fragment(R.layout.fragment_home) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val logoImage = view.findViewById<ImageView>(R.id.logoImage)
        val btnTextToSign = view.findViewById<Button>(R.id.btnTextToSign)
        val btnCapture = view.findViewById<Button>(R.id.btnCapture)
        val btnSignToText = view.findViewById<Button>(R.id.btnSignToText)

        // Carga el logo desde assets/app_logo.png
        try {
            requireContext().assets.open("app_logo.png").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                logoImage.setImageBitmap(bitmap)
            }
        } catch (e: Exception) {
            // Si no se encuentra el logo, no rompe la app
        }

        btnTextToSign.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_textToSignFragment)
        }

        btnCapture.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_captureFragment)
        }

        btnSignToText.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_signToTextFragment)
        }
    }
}
