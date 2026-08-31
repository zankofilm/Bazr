package ir.javanrood.bazr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Camera helper used by the inspector form.
 * Photos are created directly in private app storage, then permanently stamped
 * with the device date/time before they are attached to a question.
 */
object CameraEvidence {
    data class PendingPhoto(
        val file: File,
        val displayName: String
    )

    fun createPendingPhoto(context: Context, missionKey: String): PendingPhoto {
        val safeMission = missionKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.filesDir, "evidence/$safeMission").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "CAM_${stamp}_${UUID.randomUUID().toString().take(8)}.jpg")
        if (!file.exists()) file.createNewFile()
        require(file.parentFile?.exists() == true && file.canWrite()) { "مسیر ذخیره عکس قابل نوشتن نیست" }
        // CameraX writes straight to this private file. No FileProvider URI is needed here.
        return PendingPhoto(file, file.name)
    }

    fun finalizeCapturedPhoto(context: Context, pending: PendingPhoto): EvidenceItem {
        require(pending.file.exists() && pending.file.length() > 0L) { "عکس دوربین ذخیره نشده است" }
        val oriented = decodeOrientedBitmap(pending.file)
        val resized = resizeForEvidence(oriented, 1920)
        val stamped = addTimestamp(resized)
        val temp = File(pending.file.parentFile, ".${pending.file.name}.processing")
        FileOutputStream(temp, false).use { out ->
            // 86 keeps text/evidence visually sharp while reducing typical camera files dramatically.
            require(stamped.compress(Bitmap.CompressFormat.JPEG, 86, out)) { "فشرده‌سازی تصویر انجام نشد" }
            out.fd.sync()
        }
        require(temp.length() > 0L) { "پردازش تصویر خروجی معتبری تولید نکرد" }
        if (pending.file.exists() && !pending.file.delete()) { temp.delete(); error("جایگزینی تصویر پردازش‌شده انجام نشد") }
        if (!temp.renameTo(pending.file)) {
            temp.copyTo(pending.file, overwrite = true)
            temp.delete()
        }
        require(pending.file.exists() && pending.file.length() > 0L) { "ذخیره نهایی عکس انجام نشد" }
        if (resized !== oriented) oriented.recycle()
        if (stamped !== resized) resized.recycle()
        stamped.recycle()
        return EvidenceItem(
            path = pending.file.absolutePath,
            name = pending.displayName,
            mime = "image/jpeg",
            kind = "image"
        )
    }

    fun discard(pending: PendingPhoto?) {
        pending ?: return
        runCatching { pending.file.delete() }
    }

    private fun decodeOrientedBitmap(file: File): Bitmap {
        // Keep evidence sharp but avoid loading very large camera images at full sensor size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        // Decode close to our final evidence size instead of loading the full sensor image.
        val maxDimension = 2560
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val raw = BitmapFactory.decodeFile(file.absolutePath, options) ?: error("خواندن تصویر دوربین ممکن نشد")
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(270f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.preScale(-1f, 1f); matrix.postRotate(90f) }
        }
        if (matrix.isIdentity) return raw
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }


    private fun resizeForEvidence(source: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= maxLongEdge) return source
        val scale = maxLongEdge.toFloat() / longEdge.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun addTimestamp(source: Bitmap): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val densityScale = (bitmap.width / 1080f).coerceIn(0.65f, 2.2f)
        val padding = 24f * densityScale
        val textSize = 36f * densityScale
        val lineGap = 12f * densityScale
        val label = "بازرسی ادارات"
        val time = SimpleDateFormat("yyyy/MM/dd  HH:mm:ss", Locale.US).format(Date())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setShadowLayer(4f * densityScale, 0f, 2f * densityScale, Color.BLACK)
        }
        val lineHeight = textSize * 1.25f
        val maxWidth = maxOf(paint.measureText(label), paint.measureText(time))
        val boxHeight = lineHeight * 2 + lineGap + padding * 1.2f
        val left = padding
        val bottom = bitmap.height - padding
        val top = (bottom - boxHeight).coerceAtLeast(padding)
        val right = (left + maxWidth + padding * 1.5f).coerceAtMost(bitmap.width - padding)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(165, 5, 25, 45) }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 18f * densityScale, 18f * densityScale, bg)

        val textX = left + padding * 0.65f
        var y = top + padding * 0.65f + textSize
        canvas.drawText(label, textX, y, paint)
        y += lineHeight + lineGap
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        canvas.drawText(time, textX, y, paint)
        return bitmap
    }
}
