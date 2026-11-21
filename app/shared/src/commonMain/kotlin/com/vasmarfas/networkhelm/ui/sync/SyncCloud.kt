package com.vasmarfas.networkhelm.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composeunstyled.Text
import com.vasmarfas.networkhelm.sync.Cloud
import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.WayBack
import com.vasmarfas.networkhelm.sync.offered
import com.vasmarfas.networkhelm.ui.kit.Button
import com.vasmarfas.networkhelm.ui.kit.Labeled
import com.vasmarfas.networkhelm.ui.theme.Theme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.sync_hint_dropbox
import networkhelm.app.shared.generated.resources.sync_hint_google
import networkhelm.app.shared.generated.resources.sync_hint_icloud
import networkhelm.app.shared.generated.resources.sync_hint_onedrive
import networkhelm.app.shared.generated.resources.sync_hint_yandex
import networkhelm.app.shared.generated.resources.sync_service_dropbox
import networkhelm.app.shared.generated.resources.sync_service_google
import networkhelm.app.shared.generated.resources.sync_service_icloud
import networkhelm.app.shared.generated.resources.sync_service_onedrive
import networkhelm.app.shared.generated.resources.sync_service_yandex
import networkhelm.app.shared.generated.resources.sync_sign_in
import networkhelm.app.shared.generated.resources.sync_sign_in_back
import networkhelm.app.shared.generated.resources.sync_sign_in_elsewhere
import networkhelm.app.shared.generated.resources.sync_sign_in_page
import networkhelm.app.shared.generated.resources.sync_sign_in_page_refused
import networkhelm.app.shared.generated.resources.sync_sign_in_page_refused_text
import networkhelm.app.shared.generated.resources.sync_sign_in_page_text
import networkhelm.app.shared.generated.resources.sync_sign_in_waiting
import networkhelm.app.shared.generated.resources.sync_signed_in

// the desktop signs in to a cloud in its own browser, Android and iOS in the browser of the system, the page only on the address the
// consoles of the clouds list. Null where this copy signs in nowhere
internal expect val signInWay: SignInWay?

// the browser of this device for a sign-in: open and page serve the loopback address the desktop and Android listen on
internal expect fun wayBack(open: (String) -> Unit, page: String): WayBack

// what the page of the loopback address of a phone adds to bring the browser back to the app, under that label
internal expect fun backToApp(label: String): String

// a cloud is offered where the app is registered with it for the way this device signs in
internal fun offered(cloud: Cloud) = signInWay?.let(cloud::offered) == true

internal fun cloudName(cloud: Cloud): StringResource = when (cloud) {
    Cloud.YandexDisk -> Res.string.sync_service_yandex
    Cloud.Dropbox -> Res.string.sync_service_dropbox
    Cloud.GoogleDrive -> Res.string.sync_service_google
    Cloud.OneDrive -> Res.string.sync_service_onedrive
    Cloud.CloudKit -> Res.string.sync_service_icloud
}

internal fun cloudHint(cloud: Cloud): StringResource = when (cloud) {
    Cloud.YandexDisk -> Res.string.sync_hint_yandex
    Cloud.Dropbox -> Res.string.sync_hint_dropbox
    Cloud.GoogleDrive -> Res.string.sync_hint_google
    Cloud.OneDrive -> Res.string.sync_hint_onedrive
    Cloud.CloudKit -> Res.string.sync_hint_icloud
}

// the page the browser shows on the loopback address at the end of a sign-in, in the colours of the app, light or dark as the system is:
// that the answer went to the app, or that the cloud refused, as its address tells, and on a phone the way back to the app. The address
// with the code of the sign-in leaves the bar and the history of the browser
internal fun signInPage(done: String, doneText: String, refused: String, refusedText: String, back: String) = """
    <!doctype html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="color-scheme" content="light dark">
    <title>NetworkHelm</title>
    <style>
    :root { --workspace: #e9ecef; --surface: #ffffff; --line: #d5dae0; --text: #1e2329; --muted: #5b646f; --accent: #1c64c4; --on-accent: #ffffff; --success: #1e754d; --danger: #bf322a }
    @media (prefers-color-scheme: dark) {
        :root { --workspace: #23272d; --surface: #1c2025; --line: #353b43; --text: #e4e7eb; --muted: #abb2bc; --accent: #68a5fd; --on-accent: #1c2025; --success: #4cb782; --danger: #f3837d }
    }
    body { margin: 0; min-height: 100vh; box-sizing: border-box; display: flex; align-items: center; justify-content: center; padding: 24px; background: var(--workspace); color: var(--text); font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif }
    main { max-width: 380px; padding: 32px 28px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); text-align: center }
    svg { width: 48px; height: 48px }
    h1 { margin: 16px 0 8px; font-size: 19px; font-weight: 600 }
    p { margin: 0; color: var(--muted) }
    a { display: inline-block; margin-top: 22px; padding: 9px 18px; border-radius: 6px; background: var(--accent); color: var(--on-accent); font-weight: 600; text-decoration: none }
    .refused, .failed .done { display: none }
    .failed .refused { display: block }
    </style>
    </head>
    <body>
    <main>
    <div class="done">
    <svg viewBox="0 0 48 48" fill="none" stroke="var(--success)" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><circle cx="24" cy="24" r="20"/><path d="M15 24.5l6 6 12-13"/></svg>
    <h1>$done</h1>
    <p>$doneText</p>
    </div>
    <div class="refused">
    <svg viewBox="0 0 48 48" fill="none" stroke="var(--danger)" stroke-width="3" stroke-linecap="round"><circle cx="24" cy="24" r="20"/><path d="M17 17l14 14M31 17L17 31"/></svg>
    <h1>$refused</h1>
    <p>$refusedText</p>
    </div>
    $back
    </main>
    <script>
    if (new URLSearchParams(location.search).has('error')) document.body.classList.add('failed')
    history.replaceState(null, '', location.pathname)
    </script>
    </body>
    </html>
""".trimIndent()

// the sign-in opens in the browser, and the page the browser shows at the end of it sends the user back to the app
@Composable
internal fun CloudSignIn(cloud: Cloud, signedIn: Boolean, busy: Boolean, onSignIn: (open: (String) -> Unit, page: String) -> Unit) {
    val palette = Theme.palette
    val links = LocalUriHandler.current
    val page = signInPage(
        stringResource(Res.string.sync_sign_in_page),
        stringResource(Res.string.sync_sign_in_page_text),
        stringResource(Res.string.sync_sign_in_page_refused),
        stringResource(Res.string.sync_sign_in_page_refused_text),
        backToApp(stringResource(Res.string.sync_sign_in_back)),
    )
    if (!offered(cloud)) {
        Text(stringResource(Res.string.sync_sign_in_elsewhere, stringResource(cloudName(cloud))), color = palette.warning, style = Theme.type.small)
        return
    }
    Labeled("") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(Res.string.sync_sign_in), { onSignIn(links::openUri, page) }, Modifier.testTag("sync-sign-in"), enabled = !busy)
            if (signedIn) Text(stringResource(Res.string.sync_signed_in), Modifier.testTag("sync-signed-in"), color = palette.success)
        }
    }
    if (busy && !signedIn) Text(stringResource(Res.string.sync_sign_in_waiting), color = palette.muted, style = Theme.type.small)
}
