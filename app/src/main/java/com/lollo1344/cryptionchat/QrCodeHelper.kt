package com.lollo1344.cryptionchat

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException

object QrCodeHelper {

    /**
     * Genera una Bitmap contenente il QR Code per il testo fornito.
     * @param content Il testo o URL da codificare.
     * @param width Larghezza in pixel.
     * @param height Altezza in pixel.
     * @return La Bitmap generata o null in caso di errore.
     */
    fun generateQrBitmap(content: String, width: Int, height: Int): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, width, height)
            val matrixWidth = bitMatrix.width
            val matrixHeight = bitMatrix.height
            val pixels = IntArray(matrixWidth * matrixHeight)

            for (y in 0 until matrixHeight) {
                val offset = y * matrixWidth
                for (x in 0 until matrixWidth) {
                    // Moduli neri, sfondo bianco
                    pixels[offset + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight)
            bitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            null
        }
    }
}