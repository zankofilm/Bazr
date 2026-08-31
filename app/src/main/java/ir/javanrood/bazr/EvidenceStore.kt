package ir.javanrood.bazr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class EvidenceItem(
    val path: String,
    val name: String,
    val mime: String,
    val kind: String
)

object EvidenceStore {
    fun importUri(context: Context, missionKey: String, uri: Uri, kind: String): EvidenceItem {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty().ifBlank { if (kind == "image") "image/jpeg" else "application/pdf" }
        var displayName = "evidence_${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
        }
        val extFromName = displayName.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }
        val ext = extFromName ?: when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "application/pdf" -> "pdf"
            else -> "jpg"
        }
        val safeMission = missionKey.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(context.filesDir, "evidence/$safeMission").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        val temp = File(dir, ".${target.name}.part")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp, false).use { output -> input.copyTo(output) }
            } ?: error("فایل انتخاب‌شده قابل خواندن نیست")
            require(temp.length() > 0L) { "فایل انتخاب‌شده خالی است" }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            require(target.exists() && target.length() > 0L) { "ذخیره مستند روی گوشی کامل نشد" }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            runCatching { target.delete() }
            throw t
        }
        return EvidenceItem(target.absolutePath, displayName, mime, kind)
    }

    fun exists(item: EvidenceItem): Boolean {
        val file = File(item.path)
        return file.exists() && file.isFile && file.length() > 0L
    }

    fun capturedAt(item: EvidenceItem): String {
        val file = File(item.path)
        val millis = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        return java.time.Instant.ofEpochMilli(millis).toString()
    }

    fun delete(item: EvidenceItem) {
        runCatching { File(item.path).delete() }
    }
}
