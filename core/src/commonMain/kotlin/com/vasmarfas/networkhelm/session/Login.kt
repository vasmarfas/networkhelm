package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.keenetic.KeeneticLogin
import com.vasmarfas.networkhelm.keenetic.connectKeenetic
import com.vasmarfas.networkhelm.opnsense.OpnsenseLogin
import com.vasmarfas.networkhelm.opnsense.connectOpnsense
import com.vasmarfas.networkhelm.pfsense.PfsenseLogin
import com.vasmarfas.networkhelm.pfsense.connectPfsense
import com.vasmarfas.networkhelm.snmp.SnmpLogin
import com.vasmarfas.networkhelm.snmp.SnmpUser
import com.vasmarfas.networkhelm.snmp.connectSnmp
import com.vasmarfas.networkhelm.ssh.SshAuth
import com.vasmarfas.networkhelm.transport.Base64Bytes
import com.vasmarfas.networkhelm.transport.TrustPolicy
import com.vasmarfas.networkhelm.upnp.UpnpLogin
import com.vasmarfas.networkhelm.upnp.connectUpnp
import com.vasmarfas.networkhelm.vyos.VyosLogin
import com.vasmarfas.networkhelm.vyos.connectVyos
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Login {
    val host: String

    suspend fun connect(trust: TrustPolicy): DeviceSession

    @Serializable
    @SerialName("opnsense")
    class Opnsense(override val host: String, val key: String, val secret: String, val port: Int) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectOpnsense(OpnsenseLogin(host, key, secret, port), trust)
    }

    @Serializable
    @SerialName("pfsense")
    class Pfsense(override val host: String, val user: String, val secret: String, val port: Int) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectPfsense(PfsenseLogin(host, user, secret, port), trust)
    }

    @Serializable
    @SerialName("vyos")
    class Vyos(override val host: String, val key: String, val port: Int) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectVyos(VyosLogin(host, key, port), trust)
    }

    @Serializable
    @SerialName("keenetic")
    class Keenetic(
        override val host: String,
        val user: String,
        val secret: String,
        val tls: Boolean,
        val port: Int,
        val language: String = "en",
        val sshHost: String = "",
        val sshPort: Int = 22,
        val tunnel: Boolean = false,
    ) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectKeenetic(KeeneticLogin(host, user, secret, tls, port, language, sshHost, sshPort, tunnel), trust)
    }

    @Serializable
    @SerialName("snmp")
    class Snmp(override val host: String, val community: String, val port: Int, val user: SnmpUser? = null) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectSnmp(SnmpLogin(host, community, port, user))
    }

    @Serializable
    @SerialName("upnp")
    class Upnp(override val host: String, val port: Int, val user: String, val password: String) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectUpnp(UpnpLogin(host, port, user, password))
    }

    @Serializable
    @SerialName("ssh")
    class Ssh(
        override val host: String,
        val port: Int,
        val user: String,
        val password: String,
        @Serializable(with = Base64Bytes::class) val key: ByteArray? = null,
        val agent: Boolean = false,
        val passphrase: String? = null,
    ) : Login {
        override suspend fun connect(trust: TrustPolicy) = connectSshHost(host, port, user, SshAuth.of(password, key, agent, passphrase), trust, password.ifEmpty { null })
    }
}
