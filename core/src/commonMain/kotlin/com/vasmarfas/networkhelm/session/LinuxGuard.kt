package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

// a change that can cut the host off: what it touches is kept first and comes back by itself a minute later unless the session
// still answers after the change, and at once when a new login no longer gets in; the firewall, VPN and containers share it
internal class LinuxGuard(private val ssh: SshSession, private val root: Root, private val login: (suspend () -> Unit)?) {
    // whether a new login got in before the first change: a host that refuses the user a second session, or asks it for more than
    // the session gave, is checked through the session alone
    private var admitted: Boolean? = null

    // save writes what the change touches to $B, restore puts it back from $1; the tag names the folder it is kept in
    class Backup(val tag: String, val save: String, val restore: String)

    // a change of several steps that failed in the middle is taken back at once
    suspend fun guarded(backup: Backup, change: suspend () -> Unit) {
        val checked = admitted ?: admits().also { admitted = it }
        val folder = "/tmp/networkhelm-${backup.tag}."
        val armed = root.output(
            "B=\$(mktemp -d ${folder}XXXXXX) || exit 1\n${backup.save} || exit 1\n" +
                "nohup sh -c ${quote("sleep $GUARD_SECONDS; ${backup.restore}; rm -rf \"\$1\"")} guard \"\$B\" >/dev/null 2>&1 </dev/null &\necho \"\$! \$B\"",
        ).trim().split(' ')
        val pid = armed.getOrNull(0)?.takeIf { it.all(Char::isDigit) } ?: throw DeviceException(ErrorCategory.Api, "the host did not keep what it had before the change: ${armed.joinToString(" ")}")
        val kept = armed.getOrNull(1)?.takeIf { it.startsWith(folder) } ?: throw DeviceException(ErrorCategory.Api, "the host did not keep what it had before the change")
        try {
            change()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            restore(pid, backup, kept, e)
            throw e
        }
        if (!answers()) throw DeviceException(ErrorCategory.ConnectionLost, LOST)
        // a change that keeps the sessions that are open and drops new ones would lock the user out once this session ends
        if (checked && !admits()) {
            restore(pid, backup, kept, null)
            throw DeviceException(ErrorCategory.ArgumentValue, "after the change the host let no new SSH session in: what it had is back")
        }
        root.exec("kill $pid; rm -rf ${quote(kept)}")
    }

    // a change that cut the session leaves the putting back to the host, which does it by itself in a minute
    private suspend fun restore(pid: String, backup: Backup, kept: String, cause: Exception?) {
        try {
            root.exec("kill $pid; sh -c ${quote(backup.restore)} restore ${quote(kept)}; rm -rf ${quote(kept)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeviceException(ErrorCategory.ConnectionLost, LOST, cause ?: e)
        }
    }

    // as the probe of the session, the login has a job of its own: a connection a firewall drops does not give up when its caller does
    suspend fun admits(): Boolean {
        val login = login ?: return false
        val attempt = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { login() }
        return try {
            withTimeoutOrNull(GUARD_LOGIN) { attempt.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    // the probe has a job of its own: a command over a connection that went silent does not give up when its caller does
    private suspend fun answers(): Boolean {
        val probe = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { ssh.exec("true") }
        return try {
            withTimeoutOrNull(GUARD_CHECK) { probe.await() } != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val GUARD_SECONDS = 60
        const val LOST = "the host stopped answering after the change: what it had comes back in a minute"
        val GUARD_CHECK = 10.seconds
        val GUARD_LOGIN = 20.seconds
    }
}

// what ufw and firewalld had before a change, kept by the guard; the firewall and the NAT of the VPN take them both
internal val UFW_BACKUP = LinuxGuard.Backup(
    "firewall",
    "cp -a /etc/ufw \"\$B/ufw\"",
    "rm -rf /etc/ufw && cp -a \"\$1/ufw\" /etc/ufw && if grep -q '^ENABLED=yes' /etc/ufw/ufw.conf; then ufw --force enable; else ufw disable; fi",
)
internal val FIREWALLD_BACKUP = LinuxGuard.Backup("firewall", "cp -a /etc/firewalld \"\$B/firewalld\"", "rm -rf /etc/firewalld && cp -a \"\$1/firewalld\" /etc/firewalld && firewall-cmd --reload")
