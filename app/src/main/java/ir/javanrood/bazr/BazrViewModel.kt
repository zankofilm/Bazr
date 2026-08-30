package ir.javanrood.bazr

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val phase: String = "boot",
    val message: String = "",
    val name: String = "",
    val missions: List<MissionEntity> = emptyList(),
    val reports: List<ReportEntity> = emptyList(),
    val busy: Boolean = false,
    val lastPdfPath: String = "",
    val lastReceipt: String = "",
    val submitProgress: Float = 0f,
    val submitStage: String = "",
    val submitStatus: String = "idle",
    val role: String = "inspector",
    val governorPayload: String = "{}"
)

class BazrViewModel(app: Application) : AndroidViewModel(app) {
    private val security = DeviceSecurity(app)
    private val api = ApiClient(security)
    private val db = BazrDb.get(app)
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            db.missions().observe().collect { missions ->
                _ui.value = _ui.value.copy(missions = missions)
            }
        }
        viewModelScope.launch {
            db.reports().observe().collect { reports ->
                _ui.value = _ui.value.copy(reports = reports)
            }
        }
        boot()
    }

    private fun boot() {
        val phase = when {
            security.loadDeviceToken() != null -> "biometric"
            security.activationRequest() != null -> "pending"
            else -> "activate"
        }
        _ui.value = _ui.value.copy(phase = phase, name = security.profileName(), role = security.profileRole())
    }

    fun activate(nationalId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = "ارسال درخواست فعال‌سازی…")
            runCatching {
                api.requestActivation(nationalId, "${Build.MANUFACTURER} ${Build.MODEL}")
            }.onSuccess { result ->
                security.saveActivationRequest(result.getString("request_id"))
                _ui.value = _ui.value.copy(
                    phase = "pending",
                    busy = false,
                    message = "درخواست برای تأیید ادمین ارسال شد."
                )
            }.onFailure {
                _ui.value = _ui.value.copy(busy = false, message = "فعال‌سازی انجام نشد: ${it.message}")
            }
        }
    }

    fun checkActivation() {
        val requestId = security.activationRequest() ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            runCatching { api.activationStatus(requestId) }
                .onSuccess { result ->
                    if (result.optString("status") == "approved") {
                        result.optString("device_token").takeIf { it.isNotBlank() }?.let(security::saveDeviceToken)
                        val profile = result.optJSONObject("profile")
                        security.saveProfile(
                            profile?.optString("name") ?: "کاربر",
                            profile?.optString("inspector_ref") ?: "",
                            profile?.optString("role") ?: "inspector"
                        )
                        _ui.value = _ui.value.copy(
                            phase = "biometric",
                            name = security.profileName(),
                            role = security.profileRole(),
                            busy = false,
                            message = "این گوشی تأیید شد."
                        )
                    } else {
                        _ui.value = _ui.value.copy(busy = false, message = "هنوز در انتظار تأیید ادمین است.")
                    }
                }
                .onFailure {
                    _ui.value = _ui.value.copy(busy = false, message = "بررسی وضعیت ناموفق بود: ${it.message}")
                }
        }
    }

    fun biometricUnlocked() {
        val token = security.loadDeviceToken() ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true, message = "اتصال به سرور و دریافت مأموریت‌ها…")
            runCatching {
                api.openSession(token)
                api.sync()
            }.onSuccess { sync ->
                val arr = sync.optJSONArray("missions")
                val submittedKeys = db.missions().submittedKeys().toSet()
                val list = mutableListOf<MissionEntity>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i)
                        val key = m.optString("_server_key", m.optString("id"))
                        list += MissionEntity(
                            key = key,
                            title = m.optString("orgName", m.optString("title", "مأموریت بازرسی")),
                            date = m.optString("date"),
                            type = m.optString("type"),
                            payload = m.toString(),
                            updatedAt = m.optString("_updated_at"),
                            submitted = key in submittedKeys
                        )
                    }
                }
                db.missions().putAll(list)
                val role = sync.optJSONObject("profile")?.optString("role")?.ifBlank { security.profileRole() } ?: security.profileRole()
                _ui.value = _ui.value.copy(
                    phase = if (role == "governor") "governor" else "home",
                    role = role,
                    governorPayload = if (role == "governor") sync.toString() else "{}",
                    busy = false,
                    message = "اطلاعات با سرور همگام شد."
                )
            }.onFailure {
                val role = security.profileRole()
                _ui.value = _ui.value.copy(
                    phase = if (role == "governor") "governor" else "home",
                    role = role,
                    busy = false,
                    message = if (role == "governor") "اتصال به سرور برقرار نشد؛ برای داشبورد فرماندار اینترنت لازم است." else "آفلاین: مأموریت‌های ذخیره‌شده گوشی در دسترس است."
                )
            }
        }
    }

    suspend fun loadDraft(missionKey: String): DraftEntity? = db.drafts().get(missionKey)

    fun saveDraft(mission: MissionEntity, answers: Map<String, Int>, notes: Map<String, String>, evidence: Map<String, List<EvidenceItem>> = emptyMap()) {
        viewModelScope.launch {
            val a = JSONObject()
            answers.forEach { (k, v) -> a.put(k, v) }
            val n = JSONObject()
            notes.forEach { (k, v) -> n.put(k, v) }
            val ev = JSONObject()
            evidence.forEach { (qid, list) ->
                val arr = JSONArray()
                list.forEach { item ->
                    arr.put(JSONObject().put("path", item.path).put("name", item.name).put("mime", item.mime).put("kind", item.kind))
                }
                ev.put(qid, arr)
            }
            db.drafts().save(DraftEntity(mission.key, JSONObject().put("answers", a).put("notes", n).put("evidence", ev).toString()))
            _ui.value = _ui.value.copy(message = "پیش‌نویس روی گوشی ذخیره شد.")
        }
    }

    fun finalSubmit(mission: MissionEntity, answers: Map<String, Int>, notes: Map<String, String>, evidence: Map<String, List<EvidenceItem>> = emptyMap()) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                busy = true,
                submitProgress = 0.04f,
                submitStage = "آماده‌سازی اطلاعات گزارش",
                message = "در حال آماده‌سازی ارسال…",
                lastPdfPath = "",
                lastReceipt = "",
                submitStatus = "sending"
            )
            val a = JSONObject(); answers.forEach { (k, v) -> a.put(k, v) }
            val n = JSONObject(); notes.forEach { (k, v) -> n.put(k, v) }
            val ev = JSONObject()
            evidence.forEach { (qid, list) ->
                val arr = JSONArray()
                list.forEach { item -> arr.put(JSONObject().put("path", item.path).put("name", item.name).put("mime", item.mime).put("kind", item.kind)) }
                ev.put(qid, arr)
            }
            val local = JSONObject().put("answers", a).put("notes", n).put("evidence", ev)
            val submitResult = runCatching {
                val token = security.loadDeviceToken() ?: error("device token missing")
                _ui.value = _ui.value.copy(submitProgress = 0.10f, submitStage = "برقراری اتصال امن با سرور")
                api.openSession(token)

                val allEvidence = evidence.flatMap { (qid, list) -> list.map { qid to it } }
                val uploaded = JSONArray()
                if (allEvidence.isEmpty()) {
                    _ui.value = _ui.value.copy(submitProgress = 0.58f, submitStage = "مستندی برای بارگذاری وجود ندارد")
                } else {
                    allEvidence.forEachIndexed { index, (qid, item) ->
                        val progress = 0.14f + (0.44f * index.toFloat() / allEvidence.size.toFloat())
                        _ui.value = _ui.value.copy(
                            submitProgress = progress,
                            submitStage = "بارگذاری فایل ${index + 1} از ${allEvidence.size}: ${item.name}"
                        )
                        val file = java.io.File(item.path)
                        if (!file.exists()) error("مستند ${item.name} روی گوشی پیدا نشد")
                        val up = try {
                            api.uploadEvidence(mission.key, file, item.mime, qid, item.kind, "")
                        } catch (e: Exception) {
                            error("آپلود ${item.name} انجام نشد: ${e.message ?: "خطای نامشخص"}")
                        }
                        uploaded.put(JSONObject()
                            .put("question_id", qid)
                            .put("file_id", up.getInt("file_id"))
                            .put("name", item.name)
                            .put("mime", item.mime)
                            .put("kind", item.kind))
                    }
                }

                _ui.value = _ui.value.copy(submitProgress = 0.64f, submitStage = "ارسال پاسخ‌های فرم به سرور")
                api.submit(
                    mission.key,
                    JSONObject()
                        .put("answers", a)
                        .put("notes", n)
                        .put("findings", JSONArray())
                        .put("uploads", uploaded)
                )
            }
            if (submitResult.isFailure) {
                db.drafts().save(DraftEntity(mission.key, local.toString(), finalPending = true))
                _ui.value = _ui.value.copy(
                    busy = false,
                    submitProgress = 0f,
                    submitStage = "",
                    submitStatus = "error",
                    message = "ارسال انجام نشد؛ پاسخ‌ها و مستندات روی گوشی محفوظ ماندند. ${submitResult.exceptionOrNull()?.message.orEmpty()}"
                )
                return@launch
            }

            val result = submitResult.getOrThrow()
            val receipt = result.optString("receipt_code").ifBlank { "JAV-MOB" }
            db.missions().markSubmitted(mission.key)
            db.drafts().remove(mission.key)

            _ui.value = _ui.value.copy(submitProgress = 0.78f, submitStage = "ساخت PDF رسمی گزارش")
            val pdfResult = runCatching {
                val pdf = OfficialReportPdf.create(getApplication(), mission, answers, notes, receipt, security.profileName())
                _ui.value = _ui.value.copy(submitProgress = 0.86f, submitStage = "بارگذاری PDF رسمی روی سرور")
                val upload = api.uploadReportPdf(mission.key, pdf)
                val fileId = upload.getInt("file_id")
                _ui.value = _ui.value.copy(submitProgress = 0.95f, submitStage = "ثبت نهایی و اتصال PDF به گزارش")
                api.attachReportPdf(mission.key, fileId)
                pdf
            }
            pdfResult.onSuccess { pdf ->
                db.reports().save(ReportEntity(mission.key, mission.title, mission.date, mission.type, receipt, pdf.absolutePath, status = "complete"))
                _ui.value = _ui.value.copy(
                    busy = false,
                    submitProgress = 1f,
                    submitStage = "ارسال با موفقیت تکمیل شد",
                    lastPdfPath = pdf.absolutePath,
                    lastReceipt = receipt,
                    submitStatus = "success",
                    message = "گزارش نهایی با تمام مستندات ارسال شد و PDF رسمی ساخته شد. کد رسید: $receipt"
                )
            }.onFailure {
                db.reports().save(ReportEntity(mission.key, mission.title, mission.date, mission.type, receipt, "", status = "pdf_pending"))
                _ui.value = _ui.value.copy(
                    busy = false,
                    submitProgress = 1f,
                    submitStage = "فرم ارسال شد؛ PDF نیازمند همگام‌سازی مجدد است",
                    lastReceipt = receipt,
                    submitStatus = "partial",
                    message = "گزارش و مستندات با موفقیت ارسال شد (رسید: $receipt)، اما همگام‌سازی PDF کامل نشد."
                )
            }
        }
    }

    fun clearSubmitStatus() {
        _ui.value = _ui.value.copy(submitStatus = "idle")
    }

    fun createPreviewPdf(mission: MissionEntity, answers: Map<String, Int>, notes: Map<String, String>): java.io.File {
        return OfficialReportPdf.create(getApplication(), mission, answers, notes, "PREVIEW", security.profileName())
    }

}
