package ir.javanrood.bazr

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val FormNavy = Color(0xFF0B2742)
private val FormGold = Color(0xFFC79B4A)
private val FormPage = Color(0xFFF5F6F8)
private val FormMuted = Color(0xFF6F7782)
private val FormLine = Color(0xFFE4E7EB)
private val DangerSoft = Color(0xFFFFF0EF)
private val Danger = Color(0xFFB54238)

data class FormQuestion(val id: String, val text: String, val section: String)

private fun decodeEvidenceThumbnail(path: String, targetPx: Int = 240): android.graphics.Bitmap? {
    val file = File(path)
    if (!file.exists() || file.length() <= 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > targetPx * 2 || bounds.outHeight / sample > targetPx * 2) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) })
}


fun questionsFromMission(mission: MissionEntity): List<FormQuestion> {
    return try {
        val root = JSONObject(mission.payload)
        val snap = root.optJSONObject("checklistSnapshot")
        val sections = snap?.optJSONArray("sections")
        val names = mutableMapOf<String, String>()
        if (sections != null) for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            names[s.optString("id")] = s.optString("title", "محور")
        }
        val q = snap?.optJSONArray("questions") ?: return emptyList()
        buildList {
            for (i in 0 until q.length()) {
                val x = q.getJSONObject(i)
                add(FormQuestion(x.optString("id", i.toString()), x.optString("text", "سؤال"), names[x.optString("sectionId")] ?: "محور ارزیابی"))
            }
        }
    } catch (_: Throwable) { emptyList() }
}

private fun evidenceFromJson(o: JSONObject?): Map<String, List<EvidenceItem>> {
    if (o == null) return emptyMap()
    val out = mutableMapOf<String, List<EvidenceItem>>()
    val keys = o.keys()
    while (keys.hasNext()) {
        val qid = keys.next()
        val arr = o.optJSONArray(qid) ?: continue
        val items = mutableListOf<EvidenceItem>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i)
            if (x != null) {
                val path = x.optString("path")
                if (path.isNotBlank()) items.add(EvidenceItem(path, x.optString("name", "مستند"), x.optString("mime", "application/octet-stream"), x.optString("kind", "document")))
            }
        }
        out[qid] = items
    }
    return out
}

@Composable
fun MissionFormScreen(mission: MissionEntity, vm: BazrViewModel, onBack: () -> Unit, onCompleted: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val activity = context as? MainActivity
    val questions = remember(mission.key) { questionsFromMission(mission) }
    val sections = remember(questions) { questions.groupBy { it.section }.entries.toList() }
    val answers = remember { mutableStateMapOf<String, Int>() }
    val notes = remember { mutableStateMapOf<String, String>() }
    val evidence = remember { mutableStateMapOf<String, List<EvidenceItem>>() }
    var currentPage by rememberSaveable(mission.key) { mutableIntStateOf(0) }
    var generalNote by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf("") }
    var targetQuestionId by rememberSaveable(mission.key, "targetQuestion") { mutableStateOf<String?>(null) }
    var showCamera by rememberSaveable(mission.key, "showCamera") { mutableStateOf(false) }
    var previewPdfPath by rememberSaveable(mission.key, "previewPdf") { mutableStateOf("") }
    var previewImagePath by rememberSaveable(mission.key, "previewImage") { mutableStateOf("") }
    var showSignature by rememberSaveable(mission.key, "showSignature") { mutableStateOf(false) }
    var startLocationJson by rememberSaveable(mission.key, "startLocation") { mutableStateOf("") }

    if (previewImagePath.isNotBlank()) {
        val previewBitmap = remember(previewImagePath) { decodeEvidenceThumbnail(previewImagePath, 1600) }
        Dialog(onDismissRequest = { previewImagePath = "" }) {
            Surface(color = Color(0xFF071B2D), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "پیش‌نمایش عکس مستند",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("نمایش عکس ممکن نشد.", color = Color.White, modifier = Modifier.padding(24.dp))
                    }
                    TextButton(onClick = { previewImagePath = "" }) { Text("بستن", color = FormGold) }
                }
            }
        }
    }

    LaunchedEffect(mission.key) {
        questions.forEach { q -> if (!answers.containsKey(q.id)) answers[q.id] = 80 }
        vm.loadDraft(mission.key)?.let { draft ->
            runCatching {
                val root = JSONObject(draft.payload)
                val a = root.optJSONObject("answers")
                a?.keys()?.forEach { id -> answers[id] = a.optInt(id, 80) }
                val n = root.optJSONObject("notes")
                n?.keys()?.forEach { id -> notes[id] = n.optString(id) }
                generalNote = n?.optString("general").orEmpty()
                evidenceFromJson(root.optJSONObject("evidence")).forEach { (id, list) -> evidence[id] = list }
            }
        }
    }

    fun saveCurrentDraft(message: String = "پیش‌نویس روی همین گوشی ذخیره شد.") {
        if (questions.isEmpty()) notes["general"] = generalNote
        vm.saveDraft(mission, answers, notes, evidence)
        validationMessage = message
    }

    fun currentPageValid(): Boolean {
        if (sections.isEmpty()) return true
        val currentIds = sections[currentPage].value.map { it.id }.toSet()
        val lowWithoutNote = currentIds.any { id -> (answers[id] ?: 80) < 60 && notes[id].isNullOrBlank() }
        val criticalWithoutEvidence = currentIds.any { id -> (answers[id] ?: 80) < 30 && evidence[id].orEmpty().isEmpty() }
        validationMessage = when {
            lowWithoutNote -> "برای امتیازهای کمتر از ۶۰ در این محور، توضیح الزامی را تکمیل کنید."
            criticalWithoutEvidence -> "برای امتیازهای بحرانی کمتر از ۳۰، حداقل یک عکس یا مستند الزامی است."
            else -> ""
        }
        return !lowWithoutNote && !criticalWithoutEvidence
    }

    fun fullReportValid(): Boolean {
        val lowWithoutNote = answers.any { (id, score) -> score < 60 && notes[id].isNullOrBlank() }
        val criticalWithoutEvidence = answers.any { (id, score) -> score < 30 && evidence[id].orEmpty().isEmpty() }
        validationMessage = when {
            lowWithoutNote -> "برای تمام امتیازهای کمتر از ۶۰، توضیح الزامی را تکمیل کنید."
            criticalWithoutEvidence -> "برای تمام امتیازهای بحرانی کمتر از ۳۰، حداقل یک عکس یا مستند ثبت کنید."
            else -> ""
        }
        return !lowWithoutNote && !criticalWithoutEvidence
    }


    if (showSignature) {
        SignatureScreen(
            context = context,
            mission = mission,
            onCancel = { showSignature = false },
            onSaveAndSend = { signatureFile ->
                showSignature = false
                val endLocation = LocationCapture.snapshot(context)?.toString().orEmpty()
                vm.finalSubmit(mission, answers, notes, evidence, signatureFile, startLocationJson, endLocation)
            }
        )
        return
    }

    if (showCamera) {
        InAppCameraScreen(
            missionKey = mission.key,
            onCaptured = { pending ->
                val qid = targetQuestionId
                runCatching { CameraEvidence.finalizeCapturedPhoto(context, pending) }
                    .onSuccess { item ->
                        if (qid != null) {
                            evidence[qid] = evidence[qid].orEmpty() + item
                            vm.saveDraft(mission, answers, notes, evidence)
                            validationMessage = "عکس با تاریخ و ساعت ثبت شد."
                        } else {
                            EvidenceStore.delete(item)
                        }
                    }
                    .onFailure {
                        CameraEvidence.discard(pending)
                        validationMessage = "پردازش عکس انجام نشد: ${it.message ?: "خطای تصویر"}"
                    }
                showCamera = false
                targetQuestionId = null
            },
            onCancel = {
                showCamera = false
                targetQuestionId = null
                validationMessage = "عکس‌برداری لغو شد."
            },
            onError = { message ->
                showCamera = false
                targetQuestionId = null
                validationMessage = message
            }
        )
        return
    }

    if (ui.submitStatus == "success" || ui.submitStatus == "partial" || ui.submitStatus == "error") {
        val success = ui.submitStatus == "success"
        val partial = ui.submitStatus == "partial"
        AlertDialog(
            onDismissRequest = { if (!ui.busy) vm.clearSubmitStatus() },
            title = { Text(if (success) "ارسال نهایی با موفقیت انجام شد" else if (partial) "فرم ارسال شد؛ PDF کامل نشد" else "ارسال نهایی انجام نشد", color = FormNavy, fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.message, color = FormMuted)
                    if (ui.lastReceipt.isNotBlank()) Text("کد رسید: ${ui.lastReceipt}", color = FormGold, fontWeight = FontWeight.Bold)
                    if (success) Text("این مأموریت از فهرست مأموریت‌های فعال خارج و در گزارش‌ها بایگانی شد.", color = FormNavy, fontSize = 12.sp)
                }
            },
            confirmButton = {
                if (success || partial) {
                    TextButton(onClick = { vm.clearSubmitStatus(); onCompleted() }) { Text("رفتن به گزارش‌ها", fontWeight = FontWeight.Bold, color = FormNavy) }
                } else {
                    TextButton(onClick = { vm.clearSubmitStatus() }) { Text("باشه", fontWeight = FontWeight.Bold, color = FormNavy) }
                }
            },
            dismissButton = {
                if (success && ui.lastPdfPath.isNotBlank()) {
                    TextButton(onClick = { openReportPdf(context, ui.lastPdfPath) }) { Text("مشاهده PDF", color = FormGold, fontWeight = FontWeight.Bold) }
                }
            }
        )
    }

    Surface(Modifier.fillMaxSize(), color = FormPage) {
        Column(Modifier.fillMaxSize()) {
            OfficialHeader(onLogout = { saveCurrentDraft(); vm.logout(); onBack() }, onRefresh = vm::refresh)
            Surface(color = Color.White, shadowElevation = 2.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { saveCurrentDraft(); onBack() }) { Text("خروج از فرم", color = FormNavy, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(mission.title, fontWeight = FontWeight.ExtraBold, color = FormNavy, fontSize = 17.sp)
                        Text("${mission.date}  •  ${mission.type}", color = FormMuted, fontSize = 11.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 0.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        if (activity == null) validationMessage = "ثبت موقعیت در این دستگاه در دسترس نیست."
                        else activity.requestLocationPermissionCompat { granted ->
                            if (!granted) validationMessage = "ثبت موقعیت اختیاری است؛ دسترسی موقعیت داده نشد."
                            else {
                                val loc = LocationCapture.snapshot(context)
                                if (loc != null) { startLocationJson = loc.toString(); validationMessage = "موقعیت شروع بازرسی ثبت شد." }
                                else validationMessage = "موقعیت فعلی هنوز توسط گوشی قابل دریافت نیست."
                            }
                        }
                    }) { Text(if(startLocationJson.isBlank()) "ثبت موقعیت شروع (اختیاری)" else "✓ موقعیت شروع ثبت شد", color=FormGold, fontSize=11.sp, fontWeight=FontWeight.Bold) }
                }
            }

            if (validationMessage.isNotBlank()) {
                Surface(color = if (validationMessage.contains("الزامی") || validationMessage.contains("نشد")) DangerSoft else Color(0xFFEEF3F7), modifier = Modifier.fillMaxWidth()) {
                    Text(validationMessage, Modifier.padding(horizontal = 16.dp, vertical = 9.dp), color = if (validationMessage.contains("الزامی") || validationMessage.contains("نشد")) Danger else FormNavy, fontSize = 12.sp)
                }
            }

            if (questions.isEmpty()) {
                Column(Modifier.weight(1f).padding(16.dp)) {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("فرم مأموریت", fontWeight = FontWeight.Bold, color = FormNavy)
                            Spacer(Modifier.height(6.dp))
                            Text("چک‌لیست این مأموریت در داده دریافتی موجود نیست. بعد از همگام‌سازی مجدد، فرم کامل را دریافت کنید.", color = FormMuted, lineHeight = 21.sp)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(generalNote, { generalNote = it }, label = { Text("یادداشت بازرس") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), shape = RoundedCornerShape(12.dp))
                        }
                    }
                }
            } else {
                val section = sections[currentPage]
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = Color(0xFFF7F0E2), shape = RoundedCornerShape(9.dp)) {
                                        Text("محور ${currentPage + 1} از ${sections.size}", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFF8A641F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                    Text(section.key, fontWeight = FontWeight.ExtraBold, color = FormNavy, fontSize = 20.sp)
                                }
                                Spacer(Modifier.height(9.dp))
                                LinearProgressIndicator(progress = { (currentPage + 1).toFloat() / sections.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp), color = FormGold, trackColor = FormLine)
                                Spacer(Modifier.height(7.dp))
                                Text("${section.value.size} سؤال در این محور • هر محور در یک صفحه مستقل", color = FormMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    itemsIndexed(section.value, key = { _, q -> q.id }) { index, q ->
                        val score = answers[q.id] ?: 80
                        val files = evidence[q.id].orEmpty()
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
                            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("سؤال ${index + 1} از ${section.value.size}", color = FormGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Surface(color = if (score < 60) DangerSoft else FormPage, shape = RoundedCornerShape(8.dp)) {
                                        Text("$score / 100", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = if (score < 60) Danger else FormNavy, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                                Text(q.text, color = Color(0xFF1A2634), fontWeight = FontWeight.SemiBold, lineHeight = 22.sp)
                                Slider(value = score.toFloat(), onValueChange = { answers[q.id] = it.toInt() }, valueRange = 0f..100f, steps = 19, colors = SliderDefaults.colors(thumbColor = FormNavy, activeTrackColor = FormNavy, inactiveTrackColor = FormLine))
                                if (score < 60) {
                                    OutlinedTextField(value = notes[q.id].orEmpty(), onValueChange = { notes[q.id] = it }, label = { Text("علت امتیاز کمتر از ۶۰ *") }, supportingText = { Text("ثبت توضیح برای این امتیاز الزامی است.") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Danger, focusedLabelColor = Danger))
                                } else {
                                    OutlinedTextField(value = notes[q.id].orEmpty(), onValueChange = { notes[q.id] = it }, label = { Text("توضیحات بازرس (اختیاری)") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp), minLines = 2)
                                }

                                HorizontalDivider(color = FormLine)
                                Text("عکس و مستندات", color = FormNavy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        targetQuestionId = q.id
                                        if (activity == null) {
                                            targetQuestionId = null
                                            validationMessage = "دسترسی به محیط دوربین امکان‌پذیر نیست."
                                        } else {
                                            runCatching {
                                                activity.requestCameraPermissionCompat { granted ->
                                                    if (granted) {
                                                        showCamera = targetQuestionId != null
                                                    } else {
                                                        targetQuestionId = null
                                                        validationMessage = "برای ثبت عکس، دسترسی دوربین را فعال کنید."
                                                    }
                                                }
                                            }.onFailure {
                                                targetQuestionId = null
                                                validationMessage = "درخواست دسترسی دوربین انجام نشد: ${it.message ?: "خطای سیستم"}"
                                            }
                                        }
                                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ عکس", color = FormNavy, fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = {
                                        targetQuestionId = q.id
                                        if (activity == null) {
                                            targetQuestionId = null
                                            validationMessage = "انتخاب مستند در این دستگاه در دسترس نیست."
                                        } else {
                                            runCatching {
                                                activity.openEvidenceDocumentCompat { uri ->
                                                    val qid = targetQuestionId
                                                    if (uri != null && qid != null) {
                                                        runCatching { EvidenceStore.importUri(context, mission.key, uri, "document") }
                                                            .onSuccess {
                                                                evidence[qid] = evidence[qid].orEmpty() + it
                                                                vm.saveDraft(mission, answers, notes, evidence)
                                                                validationMessage = "مستند به این سؤال اضافه شد."
                                                            }
                                                            .onFailure { validationMessage = "افزودن مستند انجام نشد: ${it.message}" }
                                                    } else if (qid != null) {
                                                        validationMessage = "انتخاب مستند لغو شد."
                                                    }
                                                    targetQuestionId = null
                                                }
                                            }.onFailure {
                                                targetQuestionId = null
                                                validationMessage = "باز کردن انتخاب فایل انجام نشد: ${it.message ?: "خطای سیستم"}"
                                            }
                                        }
                                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("+ مستند", color = FormNavy, fontWeight = FontWeight.Bold) }
                                }
                                if (files.isNotEmpty()) {
                                    val imageCount = files.count { it.kind == "image" }
                                    val documentCount = files.size - imageCount
                                    Text(
                                        "ثبت‌شده: $imageCount عکس${if (documentCount > 0) " • $documentCount مستند" else ""}",
                                        color = FormMuted,
                                        fontSize = 11.sp
                                    )
                                    files.forEach { item ->
                                        Surface(color = FormPage, shape = RoundedCornerShape(9.dp), modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (item.kind == "image") {
                                                    val thumb = remember(item.path) { decodeEvidenceThumbnail(item.path) }
                                                    if (thumb != null) {
                                                        Image(
                                                            bitmap = thumb.asImageBitmap(),
                                                            contentDescription = "عکس ثبت‌شده",
                                                            modifier = Modifier
                                                                .size(64.dp)
                                                                .clickable { previewImagePath = item.path },
                                                            contentScale = ContentScale.Crop
                                                        )
                                                    } else {
                                                        Surface(color = FormLine, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(64.dp)) {
                                                            Box(contentAlignment = Alignment.Center) { Text("عکس", color = FormMuted, fontSize = 10.sp) }
                                                        }
                                                    }
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text("عکس بازرسی", color = FormGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                        Text("برای مشاهده بزرگ لمس کنید", color = FormMuted, fontSize = 10.sp)
                                                    }
                                                } else {
                                                    Surface(color = Color.White, shape = RoundedCornerShape(8.dp), modifier = Modifier.size(46.dp)) {
                                                        Box(contentAlignment = Alignment.Center) { Text("PDF", color = FormGold, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
                                                    }
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text("مستند", color = FormGold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                        Text(item.name, color = FormMuted, fontSize = 10.sp, maxLines = 1)
                                                    }
                                                }
                                                TextButton(onClick = {
                                                    EvidenceStore.delete(item)
                                                    evidence[q.id] = files - item
                                                    vm.saveDraft(mission, answers, notes, evidence)
                                                }) { Text("حذف", color = Danger, fontSize = 11.sp) }
                                            }
                                        }
                                    }
                                } else {
                                    Text("برای این سؤال هنوز عکس یا مستندی ثبت نشده است.", color = FormMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            Surface(color = Color.White, shadowElevation = 8.dp) {
                Column(Modifier.navigationBarsPadding().padding(10.dp)) {
                    if (ui.busy) {
                        Surface(color = Color(0xFFF7F0E2), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("در حال ارسال گزارش…", color = FormNavy, fontWeight = FontWeight.ExtraBold)
                                    Text("${(ui.submitProgress * 100).toInt()}٪", color = FormGold, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { ui.submitProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(7.dp),
                                    color = FormGold,
                                    trackColor = FormLine
                                )
                                Text(ui.submitStage.ifBlank { "در حال آماده‌سازی اطلاعات…" }, color = FormMuted, fontSize = 11.sp)
                                Text("لطفاً تا پایان ارسال، برنامه را نبندید.", color = FormNavy, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (questions.isNotEmpty() && currentPage == sections.lastIndex && !ui.busy) {
                        OutlinedButton(
                            onClick = {
                                if (!currentPageValid()) return@OutlinedButton
                                if (!fullReportValid()) {
                                    // پیام اعتبارسنجی در بالای فرم نمایش داده می‌شود.
                                } else {
                                    runCatching { vm.createPreviewPdf(mission, answers, notes, evidence) }
                                        .onSuccess { pdf -> previewPdfPath = pdf.absolutePath; openReportPdf(context, pdf.absolutePath); validationMessage = "پیش‌نمایش PDF ساخته شد. پس از بررسی، ارسال نهایی را بزنید." }
                                        .onFailure { validationMessage = "ساخت پیش‌نمایش PDF انجام نشد: ${it.message.orEmpty()}" }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("مشاهده پیش‌نمایش PDF قبل از ارسال", color = FormNavy, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(
                            onClick = {
                                saveCurrentDraft()
                                if (currentPage > 0) currentPage-- else onBack()
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("برگشت", color = FormNavy, fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            onClick = { saveCurrentDraft() },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("ذخیره", color = FormNavy, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = {
                                if (!currentPageValid()) return@Button
                                saveCurrentDraft("این محور ذخیره شد.")
                                if (currentPage < sections.lastIndex) {
                                    currentPage++
                                } else {
                                    if (fullReportValid()) showSignature = true
                                }
                            },
                            modifier = Modifier.weight(1.15f).height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FormNavy),
                            enabled = !ui.busy
                        ) { Text(if (questions.isNotEmpty() && currentPage == sections.lastIndex) "ارسال نهایی" else "صفحه بعد", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                    if (ui.lastPdfPath.isNotBlank()) {
                        Spacer(Modifier.height(9.dp))
                        Surface(color = Color(0xFFF7F0E2), shape = RoundedCornerShape(11.dp)) {
                            Column(Modifier.fillMaxWidth().padding(11.dp)) {
                                Text("PDF رسمی گزارش آماده است", color = FormNavy, fontWeight = FontWeight.Bold)
                                Text("کد رسید: ${ui.lastReceipt}", color = FormMuted, fontSize = 11.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { openReportPdf(context, ui.lastPdfPath) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = FormNavy), shape = RoundedCornerShape(9.dp)) { Text("مشاهده PDF", fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = { shareReportPdf(context, ui.lastPdfPath) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp)) { Text("دانلود / اشتراک", fontWeight = FontWeight.Bold, color = FormNavy) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
