@file:OptIn(ExperimentalWasmJsInterop::class)

package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.sync.SIGN_IN_PAGE
import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.WayBack
import kotlinx.coroutines.await
import kotlin.js.Promise

// the page signs in only on the address the consoles of the clouds list: the page of a router or a gateway, or a copy elsewhere, does not
internal actual val signInWay: SignInWay? = SignInWay.Page.takeIf { address() == SIGN_IN_PAGE }

internal actual fun wayBack(open: (String) -> Unit, page: String): WayBack = WayBack(SignInWay.Page, open, page) { url, _ ->
    returned(url).await<JsAny?>()?.let(::text)
        ?: throw DeviceException(ErrorCategory.General, "the browser did not open the window of the sign-in: allow pop-up windows for this page")
}

internal actual fun backToApp(label: String): String = ""

private fun address(): String = js("location.origin + location.pathname")

private fun text(value: JsAny): String = js("value")

// the window of the sign-in comes back to this page, which there hands the address over through the storage of the origin (index.html):
// a cloud may cut the window off from the page that opened it. Null where the browser did not open the window
private fun returned(url: String): Promise<JsAny?> = js(
    """new Promise((resolve) => {
        const key = 'networkhelm-sign-in';
        const heard = (event) => {
            if (event.key !== key || !event.newValue || event.newValue === 'waiting') return;
            window.removeEventListener('storage', heard);
            localStorage.removeItem(key);
            resolve(event.newValue);
        };
        localStorage.setItem(key, 'waiting');
        window.addEventListener('storage', heard);
        if (!window.open(url, key, 'popup,width=520,height=720')) {
            window.removeEventListener('storage', heard);
            localStorage.removeItem(key);
            resolve(null);
        }
    })""",
)
