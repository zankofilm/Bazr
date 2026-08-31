package ir.javanrood.bazr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
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
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            ?: error("فایل انتخاب‌شده قابل خواندن نیست")
        return EvidenceItem(target.absolutePath, displayName, mime, kind)
    }

    fun delete(item: EvidenceItem) {
        runCatching { File(item.path).delete() }
    }
}
