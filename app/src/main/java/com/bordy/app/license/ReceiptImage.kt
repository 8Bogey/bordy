package com.bordy.app.license

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Turns a picked receipt into a JPEG the license server accepts (≤2 MB, max 1600 px).
 * Screenshots are flat colour, so a mild downscale stays perfectly readable.
 */
object ReceiptImage {

    const val MAX_UPLOAD_BYTES = 2 * 1024 * 1024
    private const val TARGET_BYTES = 1_200_000
    private const val MAX_DIM = 1600

    fun prepare(context: Context, uri: Uri): ByteArray? {
        // ALLOCATOR_SOFTWARE keeps a compressible bitmap; hardware bitmaps cannot be re-encoded.
        val bitmap = try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DIM) {
                    val scale = MAX_DIM.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } catch (_: Exception) {
            null
        } ?: return null

        try {
            for (quality in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val bytes = out.toByteArray()
                if (bytes.size <= TARGET_BYTES) return bytes
            }
            // Still oversized: shrink the bitmap itself and re-encode once.
            val half = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width / 2).coerceAtLeast(1),
                (bitmap.height / 2).coerceAtLeast(1),
                true
            )
            val out = ByteArrayOutputStream()
            half.compress(Bitmap.CompressFormat.JPEG, 60, out)
            if (half !== bitmap) half.recycle()
            return out.toByteArray().takeIf { it.size in 1..MAX_UPLOAD_BYTES }
        } catch (_: Exception) {
            return null
        } finally {
            bitmap.recycle()
        }
    }

    fun toBase64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun humanSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f م.ب", bytes / 1048576f)
        bytes >= 1024 -> "${bytes / 1024} ك.ب"
        else -> "$bytes بايت"
    }
}
