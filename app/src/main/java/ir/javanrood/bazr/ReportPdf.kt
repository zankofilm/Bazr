package ir.javanrood.bazr

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object OfficialReportPdf {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 38f

    fun create(
        context: Context,
        mission: MissionEntity,
        answers: Map<String, Int>,
        notes: Map<String, String>,
        receipt: String,
        inspectorName: String
    ): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "BazrReports")
        if (!dir.exists()) dir.mkdirs()
        val preview = receipt == "PREVIEW"
        val safeReceipt = if (preview) "PREVIEW-${mission.key.takeLast(18).replace(Regex("[^A-Za-z0-9_-]"), "_")}" else receipt.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "BAZR-REPORT-$safeReceipt.pdf")
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: android.graphics.Canvas? = null
        var y = 0f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11,39,66); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11,39,66); textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35,45,55); textSize = 10.5f; textAlign = Paint.Align.RIGHT }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100,108,118); textSize = 9f; textAlign = Paint.Align.RIGHT }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(177,133,59); textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.RIGHT }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220,225,230); strokeWidth = 1f }

        fun startPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            canvas!!.drawColor(Color.WHITE)
            canvas!!.drawRect(0f, 0f, PAGE_W.toFloat(), 72f, Paint().apply { color = Color.rgb(11,39,66) })
            canvas!!.drawRect(0f, 72f, PAGE_W.toFloat(), 75f, Paint().apply { color = Color.rgb(199,155,74) })
            canvas!!.drawText("بازرسی ادارات", PAGE_W - MARGIN, 31f, Paint(titlePaint).apply { color = Color.WHITE; textSize = 18f })
            canvas!!.drawText("فرمانداری شهرستان جوانرود", PAGE_W - MARGIN, 52f, Paint(bodyPaint).apply { color = Color.WHITE; textSize = 10f })
            canvas!!.drawText("گزارش رسمی بازرس - صفحه $pageNo", MARGIN, 52f, Paint(mutedPaint).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT })
            y = 98f
        }

        fun need(space: Float) { if (page == null || y + space > PAGE_H - 42f) startPage() }

        fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            if (text.isBlank()) return listOf("-")
            val words = text.trim().split(Regex("\\s+"))
            val lines = mutableListOf<String>()
            var current = ""
            for (word in words) {
                val next = if (current.isBlank()) word else "$current $word"
                if (paint.measureText(next) <= maxWidth || current.isBlank()) current = next
                else { lines += current; current = word }
            }
            if (current.isNotBlank()) lines += current
            return lines
        }

        fun paragraph(text: String, paint: Paint = bodyPaint, gap: Float = 4f, indent: Float = 0f) {
            val lines = wrap(text, paint, PAGE_W - 2*MARGIN - indent)
            need(lines.size * 15f + gap)
            for (line in lines) { canvas!!.drawText(line, PAGE_W - MARGIN - indent, y, paint); y += 15f }
            y += gap
        }

        startPage()
        paragraph(if (preview) "پیش‌نمایش گزارش قبل از ارسال نهایی" else "گزارش نهایی و قفل‌شده بازرسی", titlePaint, 6f)
        paragraph("دستگاه: ${mission.title}", headPaint)
        paragraph("تاریخ مأموریت: ${mission.date.ifBlank { "-" }}     نوع: ${mission.type.ifBlank { "-" }}", bodyPaint)
        paragraph("بازرس: ${inspectorName.ifBlank { "بازرس" }}", bodyPaint)
        if (preview) paragraph("پیش‌نمایش - فاقد کد رسید و اعتبار نهایی", goldPaint, 8f) else paragraph("کد رسید سرور: $receipt", goldPaint, 8f)
        canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint); y += 16f

        val avg = if (answers.isEmpty()) 0 else answers.values.average().roundToInt()
        paragraph("امتیاز کل: $avg از 100", headPaint, 8f)

        val root = runCatching { JSONObject(mission.payload) }.getOrNull()
        val snap = root?.optJSONObject("checklistSnapshot")
        val questions = snap?.optJSONArray("questions")
        if (questions == null || questions.length() == 0) {
            paragraph("جزئیات چک‌لیست در Snapshot مأموریت موجود نیست.", bodyPaint)
        } else {
            for (i in 0 until questions.length()) {
                val q = questions.optJSONObject(i) ?: continue
                val id = q.optString("id", i.toString())
                val text = q.optString("text", "سؤال ${i+1}")
                val score = answers[id] ?: 80
                need(70f)
                paragraph("${i+1}. $text", headPaint, 2f)
                paragraph("امتیاز: $score از 100", if (score < 60) Paint(goldPaint).apply { color = Color.rgb(170,55,55) } else goldPaint, 2f, 10f)
                val note = notes[id].orEmpty().trim()
                if (note.isNotBlank()) paragraph("توضیح بازرس: $note", bodyPaint, 5f, 10f)
                canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, linePaint); y += 10f
            }
        }
        val general = notes["general"].orEmpty().trim()
        if (general.isNotBlank()) { paragraph("یادداشت نهایی بازرس", headPaint); paragraph(general, bodyPaint, 8f) }
        need(45f)
        paragraph(if (preview) "این فایل فقط برای بازبینی پیش از ارسال نهایی است و اعتبار گزارش نهایی را ندارد." else "این نسخه پس از ارسال نهایی تولید شده و با کد رسید فوق قابل رهگیری است.", mutedPaint)
        page?.let { doc.finishPage(it) }
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}

fun openReportPdf(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "مشاهده گزارش PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun shareReportPdf(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "اشتراک گزارش PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
