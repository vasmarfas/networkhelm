package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.transport.sha256
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

// the check of the system, name of the server included, for every certificate but the pinned one, which is taken as it is: a server
// of the user's own is often reached by its address, which its certificate does not name
internal class PinnedTrust(private val pinning: Pinning) : X509ExtendedTrustManager() {
    private val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    val context: SSLContext get() = SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@PinnedTrust), null) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        checked(chain) { system.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        checked(chain) { system.checkServerTrusted(chain, authType, engine) }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = checked(chain) { system.checkServerTrusted(chain, authType) }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?): Unit = refused()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?): Unit = refused()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit = refused()

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    private fun checked(chain: Array<out X509Certificate>, check: () -> Unit) {
        val seen = sha256(chain.first())
        if (seen == pinning.pin) return
        try {
            check()
        } catch (e: CertificateException) {
            pinning.rejected = seen
            throw e
        }
    }

    private fun refused(): Nothing = throw CertificateException("client certificates are not accepted")
}
