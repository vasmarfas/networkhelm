package com.vasmarfas.networkhelm.ui.main

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

// pictures of the window of Proxmox VE in build/screenshots, to look at; the rows are those the lab node gave, made neutral
@OptIn(ExperimentalTestApi::class)
class LinuxProxmoxWindowsTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    private fun SkikoComposeUiTest.shown(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    // a tab of a window under the left menu, whose items may carry the same word
    private fun SkikoComposeUiTest.tab(title: String) {
        onAllNodesWithText(title).onLast().performClick()
        waitForIdle()
    }

    // the console of a row is offered where the session has an SSH login
    private object Ssh : SshSession {
        override suspend fun exec(command: String) = ExecResult("", "", 0)
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private fun list(path: String, vararg commands: String) = path to MenuSchema(MenuPath.parse(path), NodeKind.List, emptyList(), listOf("print") + commands, emptyList())

    private fun guest(vmid: String, name: String, status: String, type: String, vararg values: Pair<String, String>) = Item(
        vmid,
        mapOf(
            "vmid" to vmid, "name" to name, "status" to status, "node" to "pve1", "type" to type, "template" to "false", "lock" to "", "tags" to "",
            "console" to (if (type == "qemu") "qm terminal " else "pct enter ") + vmid, *values,
        ),
    )

    @Test
    fun theWindowOfProxmoxByItsTabs() = runDesktopComposeUiTest(1400, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val commands = arrayOf("set", "start", "shutdown", "stop", "reboot", "suspend", "resume")
        val router = FakeRouter(
            rows = mapOf(
                "proxmox/vm" to listOf(
                    guest("100", "vm-main", "running", "qemu", "cpu" to "7.5", "cpus" to "48", "memory" to "138198470656", "max-memory" to "137434759168", "max-disk" to "0", "uptime" to "20h44m15s"),
                    guest("200", "vm-desktop", "stopped", "qemu", "cpu" to "0.0", "cpus" to "16", "memory" to "0", "max-memory" to "34358689792", "max-disk" to "1610612736000", "uptime" to ""),
                    guest("300", "vm-home", "running", "qemu", "cpu" to "1.2", "cpus" to "6", "memory" to "7852785664", "max-memory" to "8594128896", "max-disk" to "68719476736", "uptime" to "20h43m16s", "tags" to "community-script"),
                    guest("9000", "tpl-desktop", "stopped", "qemu", "cpu" to "0.0", "cpus" to "8", "memory" to "0", "max-memory" to "17179869184", "max-disk" to "107374182400", "uptime" to "", "template" to "true"),
                ),
                "proxmox/ct" to listOf(
                    guest("101", "ct-debian", "running", "lxc", "cpu" to "0.1", "cpus" to "2", "memory" to "74981376", "max-memory" to "536870912", "disk" to "842235904", "max-disk" to "4143677440", "uptime" to "35m12s", "tags" to "lab"),
                    guest("102", "ct-fedora", "stopped", "lxc", "cpu" to "0.0", "cpus" to "2", "memory" to "0", "max-memory" to "1073741824", "disk" to "0", "max-disk" to "4294967296", "uptime" to "", "tags" to "lab"),
                ),
                "proxmox/task" to listOf(
                    Item("UPID:pve1:001575C9:0072AC48:6AFD6EEE:vzstart:102:root@pam:", mapOf("start" to "2026-11-18 12:25:02", "end" to "", "type" to "vzstart", "guest" to "102", "user" to "root@pam", "status" to "", "node" to "pve1")),
                    Item("UPID:pve1:000BF93C:003FBF4F:6AFCEC90:aptupdate::root@pam:", mapOf("start" to "2026-11-18 03:08:48", "end" to "2026-11-18 03:08:53", "type" to "aptupdate", "guest" to "", "user" to "root@pam", "status" to "OK", "node" to "pve1")),
                ),
            ),
            schemas = mapOf(list("proxmox/vm", *commands), list("proxmox/ct", *commands), list("proxmox/task", "log", "stop")),
            platform = Platform.Ssh,
            ssh = Ssh,
        )
        val storage = MemoryStorage()
        val tab = RouterTab(router, "192.0.2.10", bundledPacks().values, storage, TableLayouts(storage))
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        waitForIdle()
        tab.open(MenuEntry("Proxmox", "proxmox/vm"))
        shown("vm-main")
        picture("proxmox-vms")
        tab("LXC Containers")
        shown("ct-debian")
        picture("proxmox-cts")
        tab("Tasks")
        shown("aptupdate")
        picture("proxmox-tasks")
        // every menu entry of the pack opens its tab in the one window
        assertEquals(listOf("Proxmox"), tab.workspace.windows.map { it.title })
    }
}
