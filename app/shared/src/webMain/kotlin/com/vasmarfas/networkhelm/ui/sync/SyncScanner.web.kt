@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.theme.Theme
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.js.Promise
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_no_code
import networkhelm.app.shared.generated.resources.sync_photo
import networkhelm.app.shared.generated.resources.sync_photo_hint

// Chromium on Android, ChromeOS and macOS recognises QR codes itself; elsewhere the page takes the text under the code
internal actual val scansCodes: Boolean get() = recognises()

// a phone takes a photo of the code with its own camera app, and the browser finds the code in it; the page sees no camera
@Composable
internal actual fun CodeScanner(onCode: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var missed by remember { mutableStateOf(false) }
    Text(stringResource(Res.string.sync_photo_hint), color = Theme.palette.muted)
    Button(stringResource(Res.string.sync_photo), {
        missed = false
        scope.launch {
            val code = photographed().await<JsAny?>()?.let(::text)
            if (code == null) missed = true else onCode(code)
        }
    }, Modifier.testTag("sync-photo"), primary = true)
    if (missed) Text(stringResource(Res.string.sync_no_code), color = Theme.palette.warning)
}

private fun recognises(): Boolean = js("typeof BarcodeDetector !== 'undefined'")

private fun text(value: JsAny): String = js("value")

// null where the user took no photo, or the photo holds no code the browser could read
private fun photographed(): Promise<JsAny?> = js(
    """new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.setAttribute('capture', 'environment');
        input.addEventListener('cancel', () => resolve(null));
        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            if (!file) { resolve(null); return; }
            createImageBitmap(file)
                .then((image) => new BarcodeDetector({ formats: ['qr_code'] }).detect(image))
                .then((codes) => resolve(codes.length > 0 ? codes[0].rawValue : null), () => resolve(null));
        });
        input.click();
    })""",
)
