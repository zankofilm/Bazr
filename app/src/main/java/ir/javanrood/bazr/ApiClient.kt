package ir.javanrood.bazr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiClient(private val security:DeviceSecurity){
    private val client=OkHttpClient.Builder()
        .connectTimeout(20,TimeUnit.SECONDS)
        .writeTimeout(90,TimeUnit.SECONDS)
        .readTimeout(90,TimeUnit.SECONDS)
        .build()
    private var sessionToken:String?=null
    private fun postJson(endpoint:String,j:JSONObject,auth:Boolean=false):JSONObject{
        val req=Request.Builder().url(BuildConfig.API_BASE_URL+endpoint)
            .post(j.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept","application/json").apply{if(auth&&sessionToken!=null)header("Authorization","Bearer $sessionToken")}.build()
        client.newCall(req).execute().use{r-> val text=r.body?.string().orEmpty();val o=if(text.isBlank())JSONObject() else JSONObject(text);if(!r.isSuccessful)throw ApiException(r.code,o.optString("error","HTTP_${r.code}"));return o}
    }
    suspend fun requestActivation(nationalId:String,deviceName:String)=withContext(Dispatchers.IO){
        postJson("mobile_activate_request.php",JSONObject().put("national_id",nationalId).put("device_id",security.deviceId).put("public_key_pem",security.publicKeyPem()).put("device_name",deviceName).put("app_version",BuildConfig.VERSION_NAME))
    }
    suspend fun activationStatus(requestId:String)=withContext(Dispatchers.IO){
        val ts=Instant.now().toString();val nonce=UUID.randomUUID().toString().replace("-","")+"ab"
        val msg="activation-status|$requestId|${security.deviceId}|$ts|$nonce"
        postJson("mobile_activate_status.php",JSONObject().put("request_id",requestId).put("device_id",security.deviceId).put("timestamp",ts).put("nonce",nonce).put("signature",security.sign(msg)))
    }
    suspend fun openSession(deviceToken:String)=withContext(Dispatchers.IO){
        val ts=Instant.now().toString();val nonce=UUID.randomUUID().toString().replace("-","")+"cd";val msg="session|${security.deviceId}|$ts|$nonce"
        val o=postJson("mobile_session.php",JSONObject().put("device_id",security.deviceId).put("device_token",deviceToken).put("timestamp",ts).put("nonce",nonce).put("signature",security.sign(msg)))
        sessionToken=o.getString("token");o
    }
    suspend fun sync():JSONObject=withContext(Dispatchers.IO){
        val req=Request.Builder().url(BuildConfig.API_BASE_URL+"mobile_sync.php").get().header("Authorization","Bearer $sessionToken").header("Accept","application/json").build()
        client.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();val o=JSONObject(t);if(!r.isSuccessful)throw ApiException(r.code,o.optString("error"));o}
    }
    suspend fun submit(missionKey:String,payload:JSONObject):JSONObject=withContext(Dispatchers.IO){
        payload.put("mission_key",missionKey)
        val idem=UUID.randomUUID().toString()
        val req=Request.Builder().url(BuildConfig.API_BASE_URL+"mobile_submit.php")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization","Bearer $sessionToken").header("X-Idempotency-Key",idem).header("Content-Type","application/json").build()
        client.newCall(req).execute().use{r->val t=r.body?.string().orEmpty();val o=JSONObject(t);if(!r.isSuccessful)throw ApiException(r.code,o.optString("error"));o}
    }
    suspend fun uploadEvidence(missionKey:String,file:File,mime:String,questionKey:String="",fileKind:String="document",capturedAt:String=""):JSONObject=withContext(Dispatchers.IO){
        val media = mime.ifBlank { "application/octet-stream" }.toMediaType()
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("mission_key",missionKey)
            .addFormDataPart("question_key",questionKey)
            .addFormDataPart("file_kind",fileKind)
            .addFormDataPart("captured_at",capturedAt)
            .addFormDataPart("file",file.name,file.asRequestBody(media))
            .build()
        val req=Request.Builder().url(BuildConfig.API_BASE_URL+"mobile_upload.php").post(body)
            .header("Authorization","Bearer $sessionToken").header("Accept","application/json").build()
        client.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            val o = try { if (t.isBlank()) JSONObject() else JSONObject(t) }
                    catch (_: Exception) { JSONObject().put("error", "HTTP_${r.code}").put("raw", t.take(180)) }
            if (!r.isSuccessful) {
                val err = o.optString("error", "HTTP_${r.code}")
                val stage = o.optString("stage")
                val suffix = if (stage.isNotBlank()) " [$stage]" else ""
                throw ApiException(r.code, "$err$suffix")
            }
            o
        }
    }
    suspend fun uploadReportPdf(missionKey:String,file:File):JSONObject=uploadEvidence(missionKey,file,"application/pdf","","report_pdf","")
    suspend fun attachReportPdf(missionKey:String,fileId:Int):JSONObject=withContext(Dispatchers.IO){
        postJson("mobile_report_attach.php",JSONObject().put("mission_key",missionKey).put("file_id",fileId),auth=true)
    }

}
class ApiException(val status:Int,message:String):Exception(message)
