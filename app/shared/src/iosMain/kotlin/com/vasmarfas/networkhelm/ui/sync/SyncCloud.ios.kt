@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.WayBack
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASPresentationAnchor
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal actual val signInWay: SignInWay? = SignInWay.Ios

internal actual fun wayBack(open: (String) -> Unit, page: String): WayBack = WayBack(SignInWay.Ios, open, page, ::authenticated)

internal actual fun backToApp(label: String): String = ""

// the provider of the sign-in in progress, kept here as the session holds it weakly
private var shown: KeyWindow? = null

// the system shows the sign-in over the app and closes it as soon as the browser is sent to the scheme of the app
private suspend fun authenticated(url: String, scheme: String): String = suspendCancellableCoroutine { going ->
    val closed = DeviceException(ErrorCategory.Interrupted, "the sign-in was closed before it ended")
    val address = NSURL.URLWithString(url) ?: throw DeviceException(ErrorCategory.Api, "the address of the sign-in is not one iOS opens")
    val window = KeyWindow()
    val session = ASWebAuthenticationSession(address, scheme) { back, _ ->
        shown = null
        val answer = back?.absoluteString
        if (answer != null) going.resume(answer) else going.resumeWithException(closed)
    }
    shown = window
    session.presentationContextProvider = window
    going.invokeOnCancellation { session.cancel() }
    if (!session.start()) {
        shown = null
        going.resumeWithException(closed)
    }
}

private class KeyWindow : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): ASPresentationAnchor =
        UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().flatMap { it.windows }.filterIsInstance<UIWindow>()
            .firstOrNull { it.keyWindow } ?: UIWindow()
}
