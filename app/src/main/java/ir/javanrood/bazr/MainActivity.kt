package ir.javanrood.bazr

import android.os.Bundle
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel

private val Navy = Color(0xFF0B2742)
private val Navy2 = Color(0xFF102E4D)
private val Gold = Color(0xFFC79B4A)
private val Page = Color(0xFFF5F6F8)
private val Ink = Color(0xFF172536)
private val Muted = Color(0xFF6F7782)
private val Line = Color(0xFFE4E7EB)
private val SoftGold = Color(0xFFF7F0E2)
private val Success = Color(0xFF287A58)

class MainActivity : FragmentActivity() {
    private var cameraPermissionCallback: ((Boolean) -> Unit)? = null
    private var documentResultCallback: ((Uri?) -> Unit)? = null

    companion object {
        private const val REQ_CAMERA_PERMISSION = 1201
        private const val REQ_DOCUMENT = 1202
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Navy.toArgbCompat()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Navy,
                    onPrimary = Color.White,
                    secondary = Gold,
                    background = Page,
                    surface = Color.White,
                    onSurface = Ink,
                    outline = Line
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    BazrApp(openBio = { onOk -> showBiometric(onOk) })
                }
            }
        }
    }

    fun requestCameraPermissionCompat(callback: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            callback(true)
            return
        }
        cameraPermissionCallback = callback
        // Use an explicit request code in the lower 16-bit range. This intentionally avoids
        // ActivityResultRegistry request codes that can crash FragmentActivity on some devices.
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
    }

    @Suppress("DEPRECATION")
    fun openEvidenceDocumentCompat(callback: (Uri?) -> Unit) {
        documentResultCallback = callback
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png", "image/webp"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQ_DOCUMENT) }
            .onFailure {
                documentResultCallback = null
                callback(null)
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            cameraPermissionCallback?.invoke(granted)
            cameraPermissionCallback = null
        }
    }

    @Deprecated("Legacy low-16-bit result path used deliberately for device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DOCUMENT) {
            val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            documentResultCallback?.invoke(uri)
            documentResultCallback = null
        }
    }

    private fun showBiometric(onOk: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onOk()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("ورود به سامانه بازرسی")
                .setSubtitle("اثر انگشت یا قفل امن گوشی را تأیید کنید")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        )
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt()
)

@Composable
fun BazrApp(openBio: ((() -> Unit)) -> Unit, vm: BazrViewModel = viewModel()) {
    val state by vm.ui.collectAsState()
    var selectedMission by remember { mutableStateOf<MissionEntity?>(null) }
    var tab by remember { mutableStateOf("home") }

    selectedMission?.let { mission ->
        MissionFormScreen(
            mission = mission,
            vm = vm,
            onBack = { selectedMission = null },
            onCompleted = { selectedMission = null; tab = "reports" }
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Page) {
        Column(Modifier.fillMaxSize()) {
            OfficialHeader()
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold, trackColor = Navy2)

            when (state.phase) {
                "activate" -> ActivationPage(
                    message = state.message,
                    onActivate = vm::activate
                )
                "pending" -> PendingPage(
                    message = state.message,
                    onCheck = vm::checkActivation
                )
                "biometric" -> BiometricPage(
                    name = state.name,
                    message = state.message,
                    onOpen = { openBio { vm.biometricUnlocked() } }
                )
                "governor" -> {
                    Box(Modifier.weight(1f)) { GovernorPanel(state = state, tab = tab) }
                    GovernorBottomNav(tab = tab, onSelect = { tab = it })
                }
                else -> {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            "home" -> InspectorHome(state, onMission = { selectedMission = it })
                            "missions" -> MissionListPage(state.missions.filter { !it.submitted }, onMission = { selectedMission = it })
                            "inspections" -> PlaceholderPage("بازرسی‌ها", "پیش‌نویس‌ها و بازرسی‌های در حال انجام در این بخش نمایش داده می‌شود.")
                            "reports" -> ReportsArchivePage(state.reports)
                        }
                    }
                    InspectorBottomNav(tab = tab, onSelect = { tab = it })
                }
            }
        }
    }
}

@Composable
fun OfficialHeader() {
    Surface(color = Navy, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.selected_header),
                contentDescription = "سربرگ رسمی بازرسی ادارات",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(106.dp),
                contentScale = ContentScale.FillBounds
            )
            Box(Modifier.fillMaxWidth().height(2.dp).background(Gold))
        }
    }
}

@Composable
private fun ActivationPage(message: String, onActivate: (String) -> Unit) {
    var nationalId by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PageIntro("فعال‌سازی اولیه", "اتصال امن کاربر به سامانه", "این مرحله فقط یک‌بار برای همین دستگاه انجام می‌شود.")
        }
        if (message.isNotBlank()) item { StatusBanner(message) }
        item {
            FormalCard {
                Text("احراز هویت کاربر", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Navy)
                Spacer(Modifier.height(6.dp))
                Text("کد ملی ثبت‌شده در پنل مدیریت را وارد کنید. سامانه نقش شما را به‌صورت خودکار تشخیص می‌دهد؛ بازرس وارد پنل بازرسی و فرماندار وارد داشبورد مدیریتی می‌شود.", color = Muted, lineHeight = 23.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = nationalId,
                    onValueChange = { nationalId = it.filter(Char::isDigit).take(10) },
                    label = { Text("کد ملی") },
                    placeholder = { Text("مثال: 1234567890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Navy, focusedLabelColor = Navy)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onActivate(nationalId) },
                    enabled = nationalId.length == 10,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Navy)
                ) { Text("ارسال درخواست فعال‌سازی", fontWeight = FontWeight.Bold) }
            }
        }
        item { SecurityNote() }
    }
}

@Composable
private fun PendingPage(message: String, onCheck: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("در انتظار تأیید دستگاه", "درخواست فعال‌سازی ارسال شد", "مدیر سامانه باید این گوشی را از پنل ادمین تأیید کند.") }
        if (message.isNotBlank()) item { StatusBanner(message) }
        item {
            FormalCard {
                AccentLabel("وضعیت فعال‌سازی")
                Spacer(Modifier.height(10.dp))
                Text("درخواست این دستگاه با شناسه امن ثبت شده است.", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text("پس از تأیید در پنل مدیریت، روی دکمه زیر بزنید. نیازی به ثبت مجدد کد ملی نیست.", color = Muted)
                Spacer(Modifier.height(18.dp))
                Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                    Text("بررسی وضعیت فعال‌سازی", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BiometricPage(name: String, message: String, onOpen: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("ورود امن", if (name.isBlank()) "سامانه بازرسی ادارات" else "خوش آمدید، $name", "برای دریافت اطلاعات اختصاصی حساب خود، هویت را تأیید کنید.") }
        if (message.isNotBlank()) item { StatusBanner(message) }
        item {
            FormalCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(SoftGold), contentAlignment = Alignment.Center) {
                        Text("✓", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("دستگاه تأیید شده", fontWeight = FontWeight.Bold, color = Navy)
                        Text("ورود امن با اثر انگشت یا قفل گوشی", color = Muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                    Text("ورود به سامانه", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InspectorHome(state: UiState, onMission: (MissionEntity) -> Unit) {
    val next = state.missions.firstOrNull { !it.submitted }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("داشبورد بازرس", fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
            Text(if (state.name.isBlank()) "فرمانداری شهرستان جوانرود" else state.name, color = Muted, fontSize = 13.sp)
        }
        if (state.message.isNotBlank()) item { StatusBanner(state.message) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("ماموریت امروز", state.missions.count { !it.submitted }.toString(), "مأموریت فعال", Modifier.weight(1f))
                SummaryCard("نیازمند پیگیری", "۰", "مورد باز", Modifier.weight(1f))
            }
        }
        item {
            SectionTitle("ماموریت بعدی", "برنامه نزدیک شما")
            Spacer(Modifier.height(8.dp))
            if (next == null) EmptyState("ماموریت فعالی برای شما ثبت نشده است.")
            else MissionHighlight(next, onMission)
        }
        item {
            SectionTitle("یادآوری مهم", "ثبت دقیق مستندات")
            Spacer(Modifier.height(8.dp))
            FormalCard {
                AccentLabel("راهنمای بازرسی")
                Spacer(Modifier.height(8.dp))
                Text("برای امتیازهای کمتر از ۶۰، توضیح الزامی است. پیش از ارسال نهایی، پاسخ‌ها را مرور و مستندات را کنترل کنید.", color = Muted, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun MissionListPage(missions: List<MissionEntity>, onMission: (MissionEntity) -> Unit) {
    val activeMissions = missions.filter { !it.submitted }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("ماموریت‌های من", "فهرست مأموریت‌های بازرسی", "هر مأموریت به‌صورت مستقل ثبت و ارسال می‌شود.") }
        if (activeMissions.isEmpty()) item { EmptyState("مأموریت فعالی برای شما وجود ندارد. مأموریت‌های تکمیل‌شده در بخش گزارش‌ها بایگانی می‌شوند.") }
        items(activeMissions, key = { it.key }) { mission -> MissionListCard(mission, onMission) }
    }
}

@Composable
private fun ReportsArchivePage(reports: List<ReportEntity>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("گزارش‌ها و بایگانی", "گزارش‌های ارسال نهایی‌شده", "گزارش نهایی پس از ارسال از مأموریت‌های فعال خارج می‌شود و در این بخش ماندگار خواهد بود.") }
        if (reports.isEmpty()) item { EmptyState("هنوز گزارش نهایی بایگانی نشده است.") }
        items(reports, key = { it.missionKey }) { r ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
                Column(Modifier.padding(15.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                        Surface(color = if (r.status == "complete") Color(0xFFE9F4EF) else SoftGold, shape = RoundedCornerShape(9.dp)) {
                            Text(if (r.status == "complete") "بایگانی شد" else "PDF در انتظار", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = if (r.status == "complete") Success else Color(0xFF8A641F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text("${r.date}  •  ${r.type}", color = Muted, fontSize = 12.sp)
                    Text("کد رسید: ${r.receipt}", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (r.pdfPath.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openReportPdf(context, r.pdfPath) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Navy), shape = RoundedCornerShape(9.dp)) { Text("مشاهده PDF", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { shareReportPdf(context, r.pdfPath) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(9.dp)) { Text("دانلود / اشتراک", color = Navy, fontWeight = FontWeight.Bold) }
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        Text("فرم روی سرور ثبت شده اما PDF محلی کامل نشده است.", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionHighlight(m: MissionEntity, onMission: (MissionEntity) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onMission(m) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, fontWeight = FontWeight.ExtraBold, color = Navy, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Surface(color = SoftGold, shape = RoundedCornerShape(10.dp)) { Text(m.type.ifBlank { "سرزده" }, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = Color(0xFF8A641F), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(10.dp))
            Text("تاریخ بازدید: ${m.date.ifBlank { "طبق برنامه مأموریت" }}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onMission(m) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                Text(if (m.submitted) "مشاهده مأموریت" else "شروع بازرسی", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MissionListCard(m: MissionEntity, onMission: (MissionEntity) -> Unit) {
    Card(onClick = { onMission(m) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(m.title, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                Surface(color = if (m.submitted) Color(0xFFE9F4EF) else SoftGold, shape = RoundedCornerShape(9.dp)) {
                    Text(if (m.submitted) "ارسال شده" else "فعال", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = if (m.submitted) Success else Color(0xFF8A641F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("${m.date}  •  ${m.type}", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InspectorBottomNav(tab: String, onSelect: (String) -> Unit) {
    val items = listOf("home" to "خانه", "missions" to "ماموریت‌ها", "inspections" to "بازرسی‌ها", "reports" to "گزارش‌ها")
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            items.forEach { (key, label) ->
                val active = tab == key
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(key) }.padding(top = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(Modifier.width(34.dp).height(3.dp).background(if (active) Navy else Color.Transparent, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(7.dp))
                    Text(label, color = if (active) Navy else Muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, number: String, caption: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Text(number, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
            Text(caption, color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PageIntro(title: String, subtitle: String, helper: String) {
    Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Navy)
    Spacer(Modifier.height(2.dp))
    Text(subtitle, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Ink)
    Spacer(Modifier.height(4.dp))
    Text(helper, color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
}

@Composable
private fun StatusBanner(message: String) {
    Surface(color = Color(0xFFF0F4F7), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Text(message, Modifier.fillMaxWidth().padding(12.dp), color = Navy, fontSize = 12.sp, textAlign = TextAlign.Right)
    }
}

@Composable
private fun FormalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun SecurityNote() {
    Surface(color = SoftGold, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text("امنیت دستگاه", fontWeight = FontWeight.Bold, color = Color(0xFF76571F))
            Spacer(Modifier.height(4.dp))
            Text("حساب بازرس پس از تأیید به همین نصب برنامه متصل می‌شود و کلید خصوصی دستگاه از گوشی خارج نمی‌شود.", color = Color(0xFF76571F), fontSize = 12.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun AccentLabel(text: String) {
    Text(text, color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column {
            Text(title, fontWeight = FontWeight.ExtraBold, color = Navy, fontSize = 19.sp)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Box(Modifier.width(46.dp).height(2.dp).background(Gold, RoundedCornerShape(3.dp)))
    }
}

@Composable
private fun EmptyState(text: String) {
    FormalCard { Text(text, color = Muted, lineHeight = 22.sp) }
}


@Composable
private fun GovernorPanel(state: UiState, tab: String) {
    val root = remember(state.governorPayload) { runCatching { org.json.JSONObject(state.governorPayload) }.getOrElse { org.json.JSONObject() } }
    val orgs = root.optJSONArray("organizations") ?: org.json.JSONArray()
    val reports = root.optJSONArray("reports") ?: org.json.JSONArray()
    val alerts = root.optJSONArray("alerts") ?: org.json.JSONArray()
    when (tab) {
        "reports" -> GovernorReports(reports)
        "orgs" -> GovernorOrganizations(orgs, reports)
        "alerts" -> GovernorAlerts(alerts)
        else -> GovernorHome(state, orgs, reports, alerts)
    }
}

@Composable
private fun GovernorHome(state: UiState, orgs: org.json.JSONArray, reports: org.json.JSONArray, alerts: org.json.JSONArray) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("داشبورد فرماندار", state.name.ifBlank { "فرمانداری شهرستان جوانرود" }, "نمای فقط‌خواندنی وضعیت بازرسی دستگاه‌های شهرستان") }
        if (state.message.isNotBlank()) item { StatusBanner(state.message) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("دستگاه‌ها", orgs.length().toString(), "تحت پایش", Modifier.weight(1f))
                SummaryCard("گزارش‌ها", reports.length().toString(), "ثبت نهایی", Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryCard("هشدارها", alerts.length().toString(), "نیازمند توجه", Modifier.weight(1f))
                SummaryCard("ماموریت‌ها", state.missions.size.toString(), "کل مأموریت", Modifier.weight(1f))
            }
        }
        item { SectionTitle("آخرین گزارش‌های بازرسی", "نمای مدیریتی") }
        if (reports.length()==0) item { EmptyState("هنوز گزارش نهایی در سرور ثبت نشده است.") }
        else items((0 until minOf(reports.length(),5)).toList()) { i -> GovernorReportCard(reports.optJSONObject(i)) }
        item {
            FormalCard {
                AccentLabel("دسترسی فقط‌خواندنی")
                Spacer(Modifier.height(8.dp))
                Text("فرماندار امکان مشاهده وضعیت دستگاه‌ها، گزارش‌ها و هشدارها را دارد؛ تغییر امتیاز یا ویرایش فرم بازرسان از اپ مجاز نیست.", color = Muted, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun GovernorReports(reports: org.json.JSONArray) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("گزارش‌های نهایی", "گزارش‌های ارسالی بازرسان", "نسخه فقط‌خواندنی گزارش‌های ثبت‌شده روی سرور") }
        if (reports.length()==0) item { EmptyState("گزارشی ثبت نشده است.") }
        else items((0 until reports.length()).toList()) { i -> GovernorReportCard(reports.optJSONObject(i)) }
    }
}

@Composable
private fun GovernorReportCard(o: org.json.JSONObject?) {
    val x=o?:org.json.JSONObject()
    val payload=x.optJSONObject("payload")?:org.json.JSONObject()
    val score = payload.optDouble("score", Double.NaN)
    FormalCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(payload.optString("orgName", "گزارش بازرسی"), fontWeight = FontWeight.ExtraBold, color = Navy, modifier = Modifier.weight(1f))
            Surface(color=SoftGold, shape=RoundedCornerShape(9.dp)) { Text(if(score.isNaN()) "نهایی" else score.toInt().toString(), Modifier.padding(horizontal=9.dp,vertical=4.dp), color=Color(0xFF8A641F), fontWeight=FontWeight.Bold) }
        }
        Spacer(Modifier.height(7.dp))
        Text("رسید: ${x.optString("receipt_code","—")}", color=Muted, fontSize=12.sp)
        Text("تاریخ ارسال: ${x.optString("submitted_at","—")}", color=Muted, fontSize=12.sp)
    }
}

@Composable
private fun GovernorOrganizations(orgs: org.json.JSONArray, reports: org.json.JSONArray) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("دستگاه‌ها", "پرونده مدیریتی ادارات", "نمای وضعیت و تعداد گزارش‌های ثبت‌شده هر دستگاه") }
        if(orgs.length()==0) item { EmptyState("اطلاعات دستگاه‌ها هنوز دریافت نشده است.") }
        else items((0 until orgs.length()).toList()) { i ->
            val o=orgs.optJSONObject(i)?:org.json.JSONObject(); val id=o.optString("id")
            var count=0
            for(j in 0 until reports.length()) { val p=reports.optJSONObject(j)?.optJSONObject("payload"); if(p?.optString("orgId")==id || p?.optString("org_id")==id) count++ }
            FormalCard {
                Text(o.optString("name","دستگاه"), fontWeight=FontWeight.ExtraBold, color=Navy, fontSize=17.sp)
                Spacer(Modifier.height(5.dp)); Text("${count} گزارش ثبت‌شده", color=Muted, fontSize=12.sp)
            }
        }
    }
}

@Composable
private fun GovernorAlerts(alerts: org.json.JSONArray) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { PageIntro("هشدارهای مدیریتی", "موارد نیازمند توجه", "هشدارها و نواقص باز مهم") }
        if(alerts.length()==0) item { EmptyState("هشدار فعالی وجود ندارد.") }
        else items((0 until alerts.length()).toList()) { i ->
            val a=alerts.optJSONObject(i)?:org.json.JSONObject()
            FormalCard { AccentLabel(a.optString("severity","مهم")); Spacer(Modifier.height(5.dp)); Text(a.optString("title",a.optString("description","مورد نیازمند پیگیری")), fontWeight=FontWeight.Bold, color=Navy); Spacer(Modifier.height(4.dp)); Text(a.optString("orgName",""), color=Muted, fontSize=12.sp) }
        }
    }
}

@Composable
private fun GovernorBottomNav(tab: String, onSelect: (String) -> Unit) {
    val nav = listOf("home" to "داشبورد", "orgs" to "دستگاه‌ها", "reports" to "گزارش‌ها", "alerts" to "هشدارها")
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(68.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            nav.forEach { (key,label) -> val active=tab==key
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(key) }.padding(top=6.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                    Box(Modifier.width(34.dp).height(3.dp).background(if(active) Navy else Color.Transparent, RoundedCornerShape(4.dp))); Spacer(Modifier.height(7.dp)); Text(label, color=if(active) Navy else Muted, fontWeight=if(active) FontWeight.Bold else FontWeight.Medium, fontSize=12.sp)
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPage(title: String, text: String) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            PageIntro(title, "پنل بازرس", text)
            Spacer(Modifier.height(14.dp))
            EmptyState("اطلاعات این بخش پس از همگام‌سازی با سرور نمایش داده می‌شود.")
        }
    }
}
