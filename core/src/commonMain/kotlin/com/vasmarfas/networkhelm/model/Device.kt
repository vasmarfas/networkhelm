package com.vasmarfas.networkhelm.model

import kotlinx.serialization.Serializable

enum class Platform { RouterOs, OpenWrt, Ssh, Opnsense, Snmp, Upnp, Pfsense, Keenetic, Vyos }

@Serializable
class DeviceInfo(
    val platform: Platform,
    val identity: String,
    val version: String,
    val architecture: String,
    val board: String,
    val packages: Set<String>,
    val policies: Set<String>,
    val deviceMode: Map<String, String> = emptyMap(),
    val id: String? = null,
) {
    val canWrite: Boolean get() = "write" in policies

    // RouterOS lets SFTP in only with the ftp policy, while the API changes files without it
    val canTransfer: Boolean get() = platform != Platform.RouterOs || "ftp" in policies
}

@Serializable
enum class LinkState { Up, Reconnecting, Closed }

enum class SafeModeKind {
    None,

    // /safe-mode take in the API session itself, RouterOS 7.18 and later
    ApiSession,
    SshConsole,
    ApplyRollback,
    CommitConfirm,
}

@Serializable
data class Capabilities(
    val subscriptions: Boolean,
    val streamingCommands: Boolean,
    val cancel: Boolean,
    val safeMode: SafeModeKind,
    val terminal: Boolean,
    val files: Boolean,
    val export: Boolean,
    val encrypted: Boolean = true,
    // RouterOS 6 has no /console/inspect, so each menu is asked about on its own
    val menuContents: Boolean = true,
    val nativeMenu: Boolean = false,
    val offline: Boolean = false,
)

const val LINK_LOST = "the connection to the router is lost"

enum class ErrorCategory {
    MissingItem,
    ArgumentValue,
    Interrupted,
    Scripting,
    General,
    Api,
    Tty,
    Return,
    PermissionDenied,
    ConnectionLost,
    Unsupported,
    ;

    companion object {
        fun ofTrap(category: Int?) = when (category) {
            0 -> MissingItem
            1 -> ArgumentValue
            2 -> Interrupted
            3 -> Scripting
            5 -> Api
            6 -> Tty
            7 -> Return
            else -> General
        }
    }
}

open class DeviceException(val category: ErrorCategory, message: String, cause: Throwable? = null) : Exception(message, cause)
