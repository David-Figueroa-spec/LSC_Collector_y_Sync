package com.example.lsccollectorsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private val linePaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 12f
        style = Paint.Style.FILL
    }

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    fun clear() {
        results = null
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.let { handLandmarkerResult ->
            for (landmark in handLandmarkerResult.landmarks()) {
                // Puntos
                for (point in landmark) {
                    canvas.drawPoint(
                        point.x() * imageWidth * scaleFactor,
                        point.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }
                // Líneas conectando los puntos (esqueleto)
                HandLandmarker.HAND_CONNECTIONS.forEach { connection ->
                    val start = landmark[connection!!.start()]
                    val end = landmark[connection.end()]
                    canvas.drawLine(
                        start.x() * imageWidth * scaleFactor,
                        start.y() * imageHeight * scaleFactor,
                        end.x() * imageWidth * scaleFactor,
                        end.y() * imageHeight * scaleFactor,
                        linePaint
                    )
                }
            }
        }
    }

    fun setResults(
        handLandmarkerResults: HandLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int
    ) {
        results = handLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        // Escala la imagen analizada al tamaño real de la vista (como CENTER_CROP)
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)

        invalidate()
    }
}