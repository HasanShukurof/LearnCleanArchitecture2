package com.example.learncleanarchitecture2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * OCR keyfiyyətini artırmaq üçün bitmap preprocessing.
 * Grayscale + yüksək kontrast tətbiq edir.
 * Bu xüsusilə ə, ğ, ş, ç, ö, ü, ı kimi Azərbaycan simvollarının
 * ML Kit tərəfindən daha dəqiq tanınmasına kömək edir.
 */
fun enhanceBitmapForOcr(source: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Grayscale: rəng küyünü sil
    val matrix = ColorMatrix()
    matrix.setSaturation(0f)

    // Kontrast artır: hərflərin kənarlarını kəskinləşdir
    // contrast=1.7 — ə/e, ş/s, ç/c kimi oxşar hərflərin daha aydın seçilməsinə kömək edir
    val contrast = 1.7f
    val offset = (-0.5f * contrast + 0.5f) * 255f
    val contrastMatrix = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    )

    matrix.postConcat(contrastMatrix)
    paint.colorFilter = ColorMatrixColorFilter(matrix)
    canvas.drawBitmap(source, 0f, 0f, paint)

    return result
}
