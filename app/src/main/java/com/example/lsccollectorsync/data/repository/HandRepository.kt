package com.example.lsccollectorsync.data.repository

import android.content.Context
import com.example.lsccollectorsync.HandLandmarkerHelper
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class HandRepository(context: Context) : HandLandmarkerHelper.LandmarkerListener {

    private val landmarkerHelper = HandLandmarkerHelper(context, this)

    private val _handResults = MutableSharedFlow<HandLandmarksResultData>(extraBufferCapacity = 1)
    val handResults: SharedFlow<HandLandmarksResultData> = _handResults.asSharedFlow()

    data class HandLandmarksResultData(
        val result: HandLandmarkerResult,
        val imageHeight: Int,
        val imageWidth: Int,
        val flatLandmarks: List<Float>?
    )

    fun processFrame(imageProxy: androidx.camera.core.ImageProxy, isFrontCamera: Boolean) {
        landmarkerHelper.detectLiveStream(imageProxy, isFrontCamera)
    }

    override fun onResults(result: HandLandmarkerResult, imageHeight: Int, imageWidth: Int) {
        val hands = result.landmarks()
        val flatLandmarks = if (hands.isNotEmpty()) flattenLandmarks(hands[0]) else null
        _handResults.tryEmit(
            HandLandmarksResultData(
                result = result,
                imageHeight = imageHeight,
                imageWidth = imageWidth,
                flatLandmarks = flatLandmarks
            )
        )
    }

    override fun onError(error: String) {
        // Manejo de error de MediaPipe
    }

    fun close() {
        landmarkerHelper.close()
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
}
