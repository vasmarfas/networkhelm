package com.vasmarfas.networkhelm.ui.files

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.ui.main.deviceText
import com.vasmarfas.networkhelm.ui.terminal.sshFailure
import org.jetbrains.compose.resources.getString
import networkhelm.app.shared.generated.resources.Res
import networkhelm.app.shared.generated.resources.files_closed_ssh
import networkhelm.app.shared.generated.resources.files_failed
import networkhelm.app.shared.generated.resources.files_key_refused
import networkhelm.app.shared.generated.resources.files_no_ftp
import networkhelm.app.shared.generated.resources.files_no_ssh
import networkhelm.app.shared.generated.resources.files_off_ssh
import networkhelm.app.shared.generated.resources.files_too_big_rpcd

internal suspend fun bulkText(why: Why, port: Int?): String = when (why) {
    Why.Rights -> getString(Res.string.files_no_ftp)
    Why.SshOff -> getString(Res.string.files_off_ssh)
    Why.SshClosed -> getString(Res.string.files_closed_ssh, port ?: 22)
    Why.KeyRefused -> getString(Res.string.files_key_refused)
    Why.NoSsh -> getString(Res.string.files_no_ssh)
    Why.TooBig -> getString(Res.string.files_too_big_rpcd)
}

internal suspend fun fileFailure(e: Throwable): String? = when (e) {
    is NoChannelException -> bulkText(e.why, e.port)
    is DeviceException -> e.message?.let { deviceText(it) }
    else -> getString(Res.string.files_failed, sshFailure(e)?.let { getString(it) } ?: e.message ?: e.toString())
}
