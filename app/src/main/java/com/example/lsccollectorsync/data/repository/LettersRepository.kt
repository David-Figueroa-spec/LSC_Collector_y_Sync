package com.example.lsccollectorsync.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.lsccollectorsync.LscAlphabet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class LettersRepository(private val context: Context) {

    fun getAlphabet(): List<String> = LscAlphabet.LETTERS

    private fun signAssetBaseName(letter: String): String = if (letter == "Ñ") "enye" else letter

    suspend fun loadReferenceImage(letter: String): Bitmap? = withContext(Dispatchers.IO) {
        val base = signAssetBaseName(letter)
        tryLoadBitmap("signs/$base.png") ?: tryLoadBitmap("signs/$base.jpg")
    }

    private fun tryLoadBitmap(assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (e: IOException) {
            null
        }
    }
}
