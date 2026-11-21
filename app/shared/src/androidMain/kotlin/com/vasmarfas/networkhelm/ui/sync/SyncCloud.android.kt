package com.vasmarfas.networkhelm.ui.sync

import com.vasmarfas.networkhelm.sync.APP_SCHEME
import com.vasmarfas.networkhelm.sync.SignInWay
import com.vasmarfas.networkhelm.sync.WayBack
import kotlinx.coroutines.CompletableDeferred

internal actual val signInWay: SignInWay? = SignInWay.Android

internal actual fun wayBack(open: (String) -> Unit, page: String): WayBack = WayBack(SignInWay.Android, open, page) { url, _ -> SignInReturn.await(url, open) }

// Chrome opens the app by a link of an intent only on a tap, so the page tries once by itself and keeps the link
internal actual fun backToApp(label: String): String {
    val link = "intent://signin#Intent;scheme=$APP_SCHEME;end"
    return "<a href=\"$link\">$label</a><script>location.replace('$link')</script>"
}

// the address the browser of the system came back to after a sign-in to a cloud, which the activity of the scheme of the app hands over
object SignInReturn {
    @Volatile
    private var waiting: CompletableDeferred<String>? = null

    fun cameBack(address: String) {
        waiting?.complete(address)
    }

    internal suspend fun await(url: String, open: (String) -> Unit): String {
        val answer = CompletableDeferred<String>()
        waiting = answer
        try {
            open(url)
            return answer.await()
        } finally {
            if (waiting === answer) waiting = null
        }
    }
}
