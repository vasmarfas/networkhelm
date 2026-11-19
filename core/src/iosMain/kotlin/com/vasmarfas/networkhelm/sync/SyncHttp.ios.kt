@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.transport.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecTrustEvaluateWithError

// the evaluation of the system, name of the server included, for every certificate but the pinned one
internal actual fun httpClient(pinning: Pinning): HttpClient = HttpClient(Darwin) {
    limits()
    engine {
        handleChallenge { _, _, challenge, answer ->
            val trust = challenge.protectionSpace.serverTrust
            if (challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null) {
                answer(NSURLSessionAuthChallengePerformDefaultHandling, null)
            } else {
                val seen = sha256(trust)
                when {
                    seen != null && seen == pinning.pin -> answer(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                    SecTrustEvaluateWithError(trust, null) -> answer(NSURLSessionAuthChallengePerformDefaultHandling, null)
                    else -> {
                        pinning.rejected = seen
                        answer(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                    }
                }
            }
        }
    }
}
