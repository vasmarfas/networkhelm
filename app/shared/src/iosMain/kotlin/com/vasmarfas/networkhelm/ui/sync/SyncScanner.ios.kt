@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.stringResource
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIView
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_no_camera
import networkhelm.app.shared.generated.resources.sync_scanning

internal actual val scansCodes = true

// the system asks for the camera the first time, with the words of NSCameraUsageDescription
@Composable
internal actual fun CodeScanner(onCode: (String) -> Unit) {
    var allowed by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        allowed = when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> true
            AVAuthorizationStatusNotDetermined -> suspendCoroutine { asked -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted: Boolean -> asked.resume(granted) } }
            else -> false
        }
    }
    when (allowed) {
        true -> Camera(onCode)
        false -> Text(stringResource(Res.string.sync_no_camera), color = Theme.palette.warning)
        null -> Unit
    }
}

// the camera of AVFoundation with its picture on the screen; the system itself finds the QR code in the frames
@Composable
private fun Camera(onCode: (String) -> Unit) {
    val read by rememberUpdatedState(onCode)
    val found = remember { MutableStateFlow<String?>(null) }
    LaunchedEffect(found) { read(found.filterNotNull().first()) }
    val session = remember { AVCaptureSession() }
    val reader = remember { Reader { found.value = it } }
    var missing by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        val input = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, null) }
        if (input == null || !session.canAddInput(input)) {
            missing = true
        } else {
            session.addInput(input)
            val output = AVCaptureMetadataOutput()
            session.addOutput(output)
            output.setMetadataObjectsDelegate(reader, dispatch_get_main_queue())
            output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            // the session starts slowly and blocks the thread it starts on
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { session.startRunning() }
        }
        onDispose { session.stopRunning() }
    }
    if (missing) {
        Text(stringResource(Res.string.sync_no_camera), color = Theme.palette.warning)
        return
    }
    Text(stringResource(Res.string.sync_scanning), color = Theme.palette.muted)
    UIKitView(
        factory = { PreviewView(AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }) },
        modifier = Modifier.size(280.dp).testTag("sync-camera"),
    )
}

private class Reader(private val found: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        (didOutputMetadataObjects.firstOrNull() as? AVMetadataMachineReadableCodeObject)?.stringValue?.let(found)
    }
}

// the layer of the picture follows the size Compose gives the view
private class PreviewView(private val preview: AVCaptureVideoPreviewLayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }
}
