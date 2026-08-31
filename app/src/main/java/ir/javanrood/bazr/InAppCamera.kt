package ir.javanrood.bazr

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/**
 * CameraX based camera kept inside our own app. This avoids OEM camera/file-provider
 * crashes seen when launching external camera apps from Compose.
 */
@Composable
fun InAppCameraScreen(
    missionKey: String,
    onCaptured: (CameraEvidence.PendingPhoto) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { runCatching { cameraProvider?.unbindAll() } }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            runCatching {
                                val provider = future.get()
                                cameraProvider = provider
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .setJpegQuality(92)
                                    .build()
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                                imageCapture = capture
                            }.onFailure { onError("راه‌اندازی دوربین انجام نشد: ${it.message ?: "خطای دوربین"}") }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                }
            )

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = Color(0xCC071D33)
            ) {
                Row(
                    Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("انصراف", color = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("ثبت عکس بازرسی", color = Color.White, fontSize = 16.sp)
                        Text("تاریخ و ساعت پس از ثبت روی عکس درج می‌شود", color = Color(0xFFD5B46A), fontSize = 11.sp)
                    }
                }
            }

            Button(
                onClick = {
                    val capture = imageCapture
                    if (capture == null) {
                        onError("دوربین هنوز آماده نشده است.")
                        return@Button
                    }
                    busy = true
                    val pending = runCatching { CameraEvidence.createPendingPhoto(context, missionKey) }
                        .getOrElse {
                            busy = false
                            onError("ساخت فایل عکس انجام نشد: ${it.message}")
                            return@Button
                        }
                    val options = ImageCapture.OutputFileOptions.Builder(pending.file).build()
                    capture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                busy = false
                                onCaptured(pending)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                CameraEvidence.discard(pending)
                                onError("ثبت عکس انجام نشد: ${exception.message ?: "خطای دوربین"}")
                            }
                        }
                    )
                },
                enabled = !busy && imageCapture != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 26.dp).height(58.dp).widthIn(min = 210.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC79B4A), contentColor = Color(0xFF071D33))
            ) {
                Text(if (busy) "در حال ثبت..." else "گرفتن عکس", fontSize = 16.sp)
            }
        }
    }
}
