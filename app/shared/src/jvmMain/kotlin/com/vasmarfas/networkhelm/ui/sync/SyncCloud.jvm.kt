package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.WayBack

internal actual val signInWay: SignInWay? = SignInWay.Loopback

internal actual fun wayBack(open: (String) -> Unit, page: String): WayBack = WayBack(SignInWay.Loopback, open, page)

internal actual fun backToApp(label: String): String = ""
