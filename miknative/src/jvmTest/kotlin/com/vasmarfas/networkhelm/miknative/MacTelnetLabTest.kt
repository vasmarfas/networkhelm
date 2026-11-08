package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.ssh.ShellChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MacTelnetLabTest {

    private suspend fun ShellChannel.readUntil(marker: String): String {
        val text = StringBuilder()
        val buffer = ByteArray(4096)
        while (marker !in text) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            text.append(buffer.decodeToString(0, count))
        }
        return text.toString()
    }

    @Test
    fun routerOs7ConsoleAnswersACommand() = runBlocking {
        val lab = Lab.require("chr.user", "chr.password")
        val target = System.getProperty("networkhelm.macTarget").orEmpty()
        val from = System.getProperty("networkhelm.macFrom").orEmpty()
        assumeTrue("-PmacTarget and -PmacFrom do not name the link", target.isNotEmpty() && from.isNotEmpty())
        val links = listBroadcastInterfaces()
        val link = checkNotNull(links.firstOrNull { it.address == from }) { "no interface has the address $from, there are ${links.map { "${it.name} ${it.address} ${it.broadcast} ${it.mac}" }}" }
        withTimeout(60.seconds) {
            val ssh = connectMacTelnet(MacAddress.parse(target), lab.getValue("chr.user"), lab.getValue("chr.password"), this, link.mac, destinationAddress = link.broadcast)
            try {
                val shell = ssh.shell("xterm-256color", 100, 30)
                shell.readUntil("] > ")
                shell.write("/system identity print\r".encodeToByteArray())
                assertTrue("name:" in shell.readUntil("name:"))
            } finally {
                ssh.close()
            }
        }
    }
}
