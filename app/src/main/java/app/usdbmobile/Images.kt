package app.usdbmobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Cover/Hintergrund wie im USDB Syncer (resource_dl._process_image):
 *  - Cover:       rotate → crop → resize → contrast
 *  - Hintergrund: resize → crop
 *  - danach ggf. Verkleinern auf die maximale Breite; Ausgabe immer JPEG.
 */
object Images {

    /** Liefert JPEG-Bytes oder null, wenn die Daten kein Bild sind. */
    fun toJpeg(bytes: ByteArray, tag: TxtTools.ImageTag?, isCover: Boolean, process: Boolean, maxWidth: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val isJpeg = bounds.outMimeType == "image/jpeg"

        val wantsProcessing = process && ((tag?.hasProcessing() == true) || (maxWidth > 0 && maxWidth < bounds.outWidth))
        if (!wantsProcessing && isJpeg) return bytes

        return try {
            var bmp = flatten(BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null)
            if (process && tag != null && tag.hasProcessing()) {
                bmp = if (isCover) {
                    contrast(resize(crop(rotate(bmp, tag), tag), tag), tag)
                } else {
                    crop(resize(bmp, tag), tag)
                }
            }
            if (process && maxWidth > 0 && maxWidth < bmp.width) {
                val h = (bmp.height.toDouble() * maxWidth / bmp.width).roundToInt().coerceAtLeast(1)
                bmp = Bitmap.createScaledBitmap(bmp, maxWidth, h, true)
            }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        } catch (t: Throwable) {
            if (isJpeg) bytes else null
        }
    }

    /** Transparenz verwerfen (wie PIL .convert("RGB")): auf Schwarz zeichnen. */
    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF000000.toInt())
        c.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun rotate(b: Bitmap, t: TxtTools.ImageTag): Bitmap {
        val deg = t.rotate ?: return b
        if (deg == 0f) return b
        val m = Matrix().apply { postRotate(-deg) } // PIL dreht gegen den Uhrzeigersinn
        return flatten(Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true))
    }

    private fun crop(b: Bitmap, t: TxtTools.ImageTag): Bitmap {
        val c = t.crop ?: return b
        val l = c[0].coerceIn(0, b.width - 1)
        val u = c[1].coerceIn(0, b.height - 1)
        val r = c[2].coerceIn(l + 1, b.width)
        val lo = c[3].coerceIn(u + 1, b.height)
        return Bitmap.createBitmap(b, l, u, r - l, lo - u)
    }

    private fun resize(b: Bitmap, t: TxtTools.ImageTag): Bitmap {
        val (w, h) = t.resize ?: return b
        if (w <= 0 || h <= 0) return b
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    private fun contrast(b: Bitmap, t: TxtTools.ImageTag): Bitmap {
        val mode = t.contrast ?: return b
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        if (mode == "auto") {
            // ImageOps.autocontrast(cutoff=5), pro Farbkanal
            val luts = Array(3) { ch ->
                val hist = IntArray(256)
                for (p in px) hist[(p shr (16 - 8 * ch)) and 0xFF]++
                val cut = px.size * 5 / 100
                var lo = 0; var c = cut
                while (lo < 255) { c -= hist[lo]; if (c < 0) break; lo++ }
                var hi = 255; c = cut
                while (hi > 0) { c -= hist[hi]; if (c < 0) break; hi-- }
                IntArray(256) { i ->
                    if (hi <= lo) i else ((i - lo) * 255.0 / (hi - lo)).roundToInt().coerceIn(0, 255)
                }
            }
            for (i in px.indices) {
                val p = px[i]
                px[i] = (0xFF shl 24) or (luts[0][(p shr 16) and 0xFF] shl 16) or
                    (luts[1][(p shr 8) and 0xFF] shl 8) or luts[2][p and 0xFF]
            }
        } else {
            // ImageEnhance.Contrast: Mischung mit dem mittleren Grauwert
            val f = mode.toFloatOrNull() ?: return b
            var sum = 0L
            for (p in px) sum += (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            val mean = sum.toFloat() / px.size
            fun adj(v: Int) = (mean + f * (v - mean)).roundToInt().coerceIn(0, 255)
            for (i in px.indices) {
                val p = px[i]
                px[i] = (0xFF shl 24) or (adj((p shr 16) and 0xFF) shl 16) or
                    (adj((p shr 8) and 0xFF) shl 8) or adj(p and 0xFF)
            }
        }
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return out
    }
}
