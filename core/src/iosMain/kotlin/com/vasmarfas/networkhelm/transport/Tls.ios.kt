@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.networkhelm.transport

import cnames.structs.__SecCertificate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFRelease
import platform.Foundation.CFBridgingRelease
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_cancelled
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_connection_t
import platform.Network.nw_content_context_create
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_copy_cf_error
import platform.Network.nw_error_t
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_tls_copy_sec_protocol_options
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import platform.Security.sec_protocol_options_set_verify_block
import platform.Security.sec_trust_copy_ref
import platform.Security.sec_trust_t
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual suspend fun openTls(host: String, port: Int, pinnedSha256: String?, timeoutMillis: Int): TlsStream {
    val queue = dispatch_queue_create("com.vasmarfas.networkhelm.tls", null)
    var seen: String? = null
    val parameters = nw_parameters_create_secure_tcp({ tls ->
        sec_protocol_options_set_verify_block(nw_tls_copy_sec_protocol_options(tls), { _, trust, complete ->
            val sha = trust?.let(::leafSha256)
            seen = sha
            complete?.invoke(sha != null && (pinnedSha256 == null || sha == pinnedSha256))
        }, queue)
    }, { })
    val connection = nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)
    nw_connection_set_queue(connection, queue)
    val ready = CompletableDeferred<Unit>()
    // a refused or unreachable port leaves the connection waiting for a better network; here that is a failure
    nw_connection_set_state_changed_handler(connection) { state, error ->
        when (state) {
            nw_connection_state_ready -> ready.complete(Unit)
            nw_connection_state_failed, nw_connection_state_waiting -> ready.completeExceptionally(IOException("$host:$port: ${describe(error)}"))
            nw_connection_state_cancelled -> ready.completeExceptionally(IOException("$host:$port: closed"))
        }
    }
    nw_connection_start(connection)
    try {
        withTimeoutOrNull(timeoutMillis.toLong()) { ready.await() } ?: throw IOException("$host:$port did not answer in $timeoutMillis ms")
    } catch (e: Throwable) {
        nw_connection_cancel(connection)
        val actual = seen
        if (pinnedSha256 != null && actual != null && actual != pinnedSha256) throw CertificateMismatchException(pinnedSha256, actual)
        throw e
    }
    return NetworkTlsStream(connection, seen ?: throw IOException("$host:$port sent no certificate"))
}

private class NetworkTlsStream(private val connection: nw_connection_t, override val certificateSha256: String) : TlsStream {
    private var ended = false

    // since iOS 27 the NW_CONNECTION_*_CONTEXT constants are blocks, and Kotlin/Native aborts on reading a block as an object
    private val context = nw_content_context_create("write")

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (ended) return -1
        return suspendCancellableCoroutine { continuation ->
            nw_connection_receive(connection, 1u, length.convert()) { content, _, complete, error ->
                when {
                    error != null -> continuation.resumeWithException(IOException(describe(error)))
                    content != null -> {
                        val count = content.copyInto(buffer, offset)
                        if (complete) ended = true
                        continuation.resume(if (count == 0 && complete) -1 else count)
                    }
                    else -> {
                        ended = true
                        continuation.resume(-1)
                    }
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // without a destructor dispatch_data_create copies the bytes, so they need not stay pinned
        val data = bytes.usePinned { dispatch_data_create(it.addressOf(0), bytes.size.convert(), null, null) }
        suspendCancellableCoroutine { continuation ->
            nw_connection_send(connection, data, context, false) { error ->
                if (error == null) continuation.resume(Unit) else continuation.resumeWithException(IOException(describe(error)))
            }
        }
    }

    override fun close() = nw_connection_cancel(connection)
}

private fun dispatch_data_t.copyInto(target: ByteArray, offset: Int): Int {
    var copied = 0
    target.usePinned { pinned ->
        dispatch_data_apply(this) { _, _, region, size ->
            memcpy(pinned.addressOf(offset + copied), region, size)
            copied += size.toInt()
            true
        }
    }
    return copied
}

private fun leafSha256(trust: sec_trust_t): String? {
    val ref = sec_trust_copy_ref(trust) ?: return null
    try {
        return sha256(ref)
    } finally {
        CFRelease(ref)
    }
}

internal fun sha256(trust: SecTrustRef): String? {
    val chain = SecTrustCopyCertificateChain(trust) ?: return null
    try {
        val leaf = CFArrayGetValueAtIndex(chain, 0)?.reinterpret<__SecCertificate>() ?: return null
        val der = SecCertificateCopyData(leaf) ?: return null
        try {
            val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
            digest.usePinned { CC_SHA256(CFDataGetBytePtr(der), CFDataGetLength(der).convert(), it.addressOf(0)) }
            return digest.joinToString("") { it.toString(16).padStart(2, '0') }
        } finally {
            CFRelease(der)
        }
    } finally {
        CFRelease(chain)
    }
}

private fun describe(error: nw_error_t): String {
    val failure = error?.let(::nw_error_copy_cf_error) ?: return "failed"
    try {
        return CFBridgingRelease(CFErrorCopyDescription(failure)) as? String ?: "failed"
    } finally {
        CFRelease(failure)
    }
}
