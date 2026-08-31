package ir.javanrood.bazr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileOutputStream

data class SignatureStroke(val points: List<Offset>)

@Composable
fun SignatureScreen(
    context: Context,
    mission: MissionEntity,
    onCancel: () -> Unit,
    onSaveAndSend: (File) -> Unit
) {
    var strokes by remember { mutableStateOf<List<SignatureStroke>>(emptyList()) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var error by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F6F8)) {
        Column(Modifier.fillMaxSize()) {
            OfficialHeader()
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("امضای نهایی بازرس", color = Color(0xFF0B2742), fontSize = 24.sp, style = MaterialTheme.typography.titleLarge)
                Text("اداره بازرسی‌شده: ${missionOrganizationName(mission)}", color = Color(0xFF0B2742), fontSize = 14.sp)
                Text("برای ارسال نهایی، داخل کادر زیر با انگشت امضا کنید. بدون امضا گزارش ارسال نمی‌شود.", color = Color(0xFF6F7782), fontSize = 12.sp)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFFD9DEE5), RoundedCornerShape(14.dp))
                        .onSizeChanged { padSize = it }
                ) {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { p -> current = listOf(p) },
                                    onDrag = { change, _ -> current = current + change.position },
                                    onDragEnd = {
                                        if (current.size > 1) strokes = strokes + SignatureStroke(current)
                                        current = emptyList()
                                    },
                                    onDragCancel = { current = emptyList() }
                                )
                            }
                    ) {
                        (strokes.map { it.points } + listOf(current)).forEach { pts ->
                            if (pts.size > 1) {
                                for (i in 1 until pts.size) {
                                    drawLine(
                                        color = Color(0xFF13263A),
                                        start = pts[i - 1],
                                        end = pts[i],
                                        strokeWidth = 5f,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }
                    if (strokes.isEmpty() && current.isEmpty()) {
                        Text("محل امضا", modifier = Modifier.align(Alignment.Center), color = Color(0xFFADB4BC), fontSize = 18.sp)
                    }
                }

                if (error.isNotBlank()) Text(error, color = Color(0xFFB54238), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { strokes = emptyList(); current = emptyList(); error = "" },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("پاک کردن", color = Color(0xFF0B2742)) }
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("انصراف", color = Color(0xFF0B2742)) }
                    Button(
                        onClick = {
                            if (strokes.isEmpty()) {
                                error = "ابتدا امضای بازرس را ثبت کنید."
                            } else {
                                runCatching { saveSignature(context, mission.key, strokes, padSize) }
                                    .onSuccess(onSaveAndSend)
                                    .onFailure { error = "ذخیره امضا انجام نشد: ${it.message.orEmpty()}" }
                            }
                        },
                        modifier = Modifier.weight(1.35f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0B2742)),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("ذخیره و ارسال", fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun saveSignature(context: Context, missionKey: String, strokes: List<SignatureStroke>, sourceSize: IntSize): File {
    val w = 1400
    val h = 560
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    canvas.drawColor(AndroidColor.WHITE)
    val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(19, 38, 58)
        strokeWidth = 8f
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    val sx = w.toFloat() / sourceSize.width.coerceAtLeast(1)
    val sy = h.toFloat() / sourceSize.height.coerceAtLeast(1)
    strokes.forEach { stroke ->
        val pts = stroke.points
        for (i in 1 until pts.size) {
            canvas.drawLine(pts[i - 1].x * sx, pts[i - 1].y * sy, pts[i].x * sx, pts[i].y * sy, paint)
        }
    }
    val dir = File(context.filesDir, "signatures").apply { mkdirs() }
    val safe = missionKey.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val file = File(dir, "signature_${safe}_${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}
