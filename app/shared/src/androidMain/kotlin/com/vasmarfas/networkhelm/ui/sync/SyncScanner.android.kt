package com.vasmarfas.networkhelm.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composeunstyled.Text
import com.google.zxing.BinaryBitmap
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_no_camera
import networkhelm.app.shared.generated.resources.sync_scanning

internal actual val scansCodes = true

@Composable
internal actual fun CodeScanner(onCode: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) true else null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (allowed == null) ask.launch(Manifest.permission.CAMERA) }
    when (allowed) {
        true -> Camera(onCode)
        false -> Text(stringResource(Res.string.sync_no_camera), color = Theme.palette.warning)
        null -> Unit
    }
}

// the back camera with its picture on the screen; each frame goes to zxing until one holds a code
@Composable
private fun Camera(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val read by rememberUpdatedState(onCode)
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var missing by remember { mutableStateOf(false) }
    val found = remember { MutableStateFlow<String?>(null) }
    LaunchedEffect(found) { read(found.filterNotNull().first()) }
    LaunchedEffect(lifecycle) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { request = it } }
        val reader = QRCodeReader()
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(Dispatchers.Default.asExecutor()) { image -> image.use { decoded(reader, it) }?.let { found.value = it } }
        try {
            provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        } catch (e: IllegalArgumentException) {
            // a device without a camera at the back, as a headset
            missing = true
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            provider.unbind(preview, analysis)
        }
    }
    if (missing) {
        Text(stringResource(Res.string.sync_no_camera), color = Theme.palette.warning)
        return
    }
    Text(stringResource(Res.string.sync_scanning), color = Theme.palette.muted)
    request?.let { CameraXViewfinder(it, Modifier.size(280.dp).testTag("sync-camera")) }
}

// the plane of brightness is enough for a code of black and white modules
private fun decoded(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
    val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: ReaderException) {
        // most frames hold no code, or only a part of it
        null
    } finally {
        reader.reset()
    }
}
