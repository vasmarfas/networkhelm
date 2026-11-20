package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.journal.EditKind
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession

// the packages of a Linux host through the manager it has: what is installed, what the repositories have newer, and
// installing, removing and upgrading as root; a command answers with what the manager printed
internal class LinuxPackages(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>, private val changed: () -> Unit) {
    val tables: Map<String, HostTable> = mapOf(
        "system/package" to HostTable(
            INSTALLED, ::installed, slow = true, arguments = { listOf(Argument("name", ValueType.Text())) }, add = ::install, remove = ::uninstall,
            commands = listOf("refresh"), run = ::command, present = { manager() != null }, shown = ::shown,
        ),
        "system/package/update" to HostTable(UPDATE, ::updates, slow = true, commands = listOf("upgrade", "upgrade-all", "refresh"), run = ::command, present = { manager() != null }),
    )

    private enum class Manager(val tool: String) { Apt("apt-get"), Dnf("dnf"), Yum("yum"), Zypper("zypper"), Pacman("pacman"), Apk("apk") }

    // the manager found last, for the form that shows the command before it runs
    private var known: Manager? = null

    private suspend fun manager(): Manager? = tools().let { present -> Manager.entries.firstOrNull { it.tool in present } }.also { known = it }

    private suspend fun need() = manager() ?: throw DeviceException(ErrorCategory.Unsupported, "the host has none of apt, dnf, yum, zypper, pacman and apk")

    private suspend fun output(command: String): String {
        val result = ssh.exec(command)
        if (result.exitStatus != 0 && result.output.isBlank()) throw DeviceException(ErrorCategory.Api, result.error.trim().ifEmpty { "exit ${result.exitStatus}" })
        return result.output
    }

    private suspend fun installed(): List<Item> = when (need()) {
        Manager.Apt -> parseDpkg(output("dpkg-query -W -f '\${db:Status-Abbrev}\\t\${binary:Package}\\t\${Version}\\t\${Architecture}\\t\${Installed-Size}\\t\${binary:Summary}\\n'"))
        Manager.Dnf, Manager.Yum, Manager.Zypper -> parseRpm(output("rpm -qa --qf '%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{ARCH}\\t%{SIZE}\\t%{SUMMARY}\\n'"))
        Manager.Pacman -> parsePacman(output("LC_ALL=C pacman -Qi"))
        Manager.Apk -> parseApk(output("apk list -I"))
    }.sortedBy { it.id }

    // the managers that check the repositories as they list keep their last list until refresh
    private suspend fun updates(): List<Item> {
        val manager = need()
        val rows = when (manager) {
            Manager.Apt -> parseAptUpgradable(output("apt list --upgradable 2>/dev/null"))
            Manager.Dnf, Manager.Yum -> parseDnfUpdates(ssh.exec("${manager.tool} -q check-update").output)
            Manager.Zypper -> parseZypperUpdates(output("zypper -q --no-refresh list-updates"))
            Manager.Pacman -> parsePacmanUpdates(ssh.exec("pacman -Qu").output)
            Manager.Apk -> parseApkUpdates(output("apk list -u"))
        }
        if (rows.none { it["version"].isNullOrEmpty() }) return rows
        val current = installed().associate { it.id to it["version"].orEmpty() }
        return rows.map { row -> if (row["version"].isNullOrEmpty()) row.copy(values = row.values + ("version" to current[row.id].orEmpty())) else row }
    }

    private suspend fun install(values: Map<String, String>): String {
        val names = names(values["name"].orEmpty())
        install(names)
        return names.first()
    }

    // what the manager printed as it installed what an overview offers: the engines of containers, the kinds of VPN and of proxies
    suspend fun install(names: List<String>): String = run(installing(need(), names))

    private suspend fun uninstall(ids: List<String>) {
        run(removing(need(), names(ids.joinToString(" "))))
    }

    private fun installing(manager: Manager, names: List<String>) = when (manager) {
        Manager.Apt -> "$APT install ${names.joined()}"
        Manager.Dnf -> "dnf -y install ${names.joined()}"
        Manager.Yum -> "yum -y install ${names.joined()}"
        Manager.Zypper -> "zypper --non-interactive install ${names.joined()}"
        Manager.Pacman -> "pacman --noconfirm -S ${names.joined()}"
        Manager.Apk -> "apk add ${names.joined()}"
    }

    private fun removing(manager: Manager, names: List<String>) = when (manager) {
        Manager.Apt -> "$APT remove ${names.joined()}"
        Manager.Dnf -> "dnf -y remove ${names.joined()}"
        Manager.Yum -> "yum -y remove ${names.joined()}"
        Manager.Zypper -> "zypper --non-interactive remove ${names.joined()}"
        Manager.Pacman -> "pacman --noconfirm -R ${names.joined()}"
        Manager.Apk -> "apk del ${names.joined()}"
    }

    // a name the manager refuses is no command at all, and the form falls back to the line of its own
    private fun shown(kind: EditKind, ids: List<String>, values: Map<String, String?>): String? {
        val manager = known ?: return null
        return try {
            when (kind) {
                EditKind.Add -> installing(manager, names(values["name"].orEmpty()))
                EditKind.Remove -> removing(manager, names(ids.joinToString(" ")))
                else -> null
            }
        } catch (e: DeviceException) {
            null
        }
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val manager = need()
        val line = when (command) {
            "refresh" -> when (manager) {
                Manager.Apt -> "apt-get update"
                Manager.Dnf, Manager.Yum -> "${manager.tool} -q makecache"
                Manager.Zypper -> "zypper --non-interactive refresh"
                Manager.Pacman -> "pacman -Sy"
                Manager.Apk -> "apk update"
            }
            "upgrade" -> {
                val names = names(arguments["numbers"].orEmpty().replace(',', ' ')).joined()
                when (manager) {
                    Manager.Apt -> "$APT install --only-upgrade $names"
                    Manager.Dnf -> "dnf -y upgrade $names"
                    Manager.Yum -> "yum -y update $names"
                    Manager.Zypper -> "zypper --non-interactive update $names"
                    Manager.Pacman -> "pacman --noconfirm -S $names"
                    Manager.Apk -> "apk add -u $names"
                }
            }
            "upgrade-all" -> when (manager) {
                Manager.Apt -> "$APT upgrade"
                Manager.Dnf -> "dnf -y upgrade"
                Manager.Yum -> "yum -y update"
                Manager.Zypper -> "zypper --non-interactive update"
                Manager.Pacman -> "pacman --noconfirm -Syu"
                Manager.Apk -> "apk upgrade"
            }
            else -> throw DeviceException(ErrorCategory.MissingItem, "packages have no command $command")
        }
        return listOf(Item("", mapOf("output" to run(line))))
    }

    // a command as root that changes what the host has installed: its tools are looked up anew after it, and a failure ends with the last
    // lines it printed; the overview of proxies installs the releases of Xray and sing-box through it
    suspend fun run(line: String): String {
        val result = root.exec("$line 2>&1")
        changed()
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, result.output.trim().lines().takeLast(12).joinToString("\n").ifEmpty { result.error.trim() })
        return result.output.trim()
    }

    private fun names(text: String): List<String> = text.split(' ', ',', '\n').filter { it.isNotEmpty() }.also { names ->
        if (names.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "name the package")
        names.firstOrNull { !PACKAGE.matches(it) }?.let { throw DeviceException(ErrorCategory.ArgumentValue, "$it is not the name of a package") }
    }

    private fun List<String>.joined() = joinToString(" ") { quote(it) }

    private companion object {
        const val APT = "DEBIAN_FRONTEND=noninteractive apt-get -y -o DPkg::Lock::Timeout=60"
        val PACKAGE = Regex("[A-Za-z0-9][A-Za-z0-9+._:@-]*")
        val INSTALLED = listOf("name", "version", "architecture", "size", "description")
        val UPDATE = listOf("name", "version", "new-version", "architecture", "repository")
    }
}

private fun row(name: String, vararg values: Pair<String, String>) = Item(name, linkedMapOf("name" to name, *values))

// ii 	bash	5.2.15-2+b7	amd64	7164	GNU Bourne Again SHell
internal fun parseDpkg(text: String): List<Item> = text.lines().map { it.split('\t') }.filter { it.size >= 6 && it[0].trim().endsWith("i") && it[0].trim().length == 2 }.map {
    row(it[1], "version" to it[2], "architecture" to it[3], "size" to (it[4].toLongOrNull()?.times(1024)?.toString().orEmpty()), "description" to it[5])
}

internal fun parseRpm(text: String): List<Item> = text.lines().map { it.split('\t') }.filter { it.size >= 5 && it[0] != "gpg-pubkey" }.map {
    row(it[0], "version" to it[1], "architecture" to it[2], "size" to it[3], "description" to it[4])
}

internal fun parsePacman(text: String): List<Item> = text.split(Regex("\n\\s*\n")).mapNotNull { block ->
    val fields = block.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    val name = fields["Name"] ?: return@mapNotNull null
    row(name, "version" to fields["Version"].orEmpty(), "architecture" to fields["Architecture"].orEmpty(), "size" to bytes(fields["Installed Size"].orEmpty()), "description" to fields["Description"].orEmpty())
}

private fun bytes(size: String): String {
    val (number, unit) = size.split(' ').let { it.getOrElse(0) { "" }.toDoubleOrNull() to it.getOrElse(1) { "" } }
    val scale = when (unit) {
        "B" -> 1L
        "KiB" -> 1024L
        "MiB" -> 1024L * 1024
        "GiB" -> 1024L * 1024 * 1024
        else -> return ""
    }
    return number?.let { "${(it * scale).toLong()}" }.orEmpty()
}

// acl-libs-2.3.2-r1 x86_64 {acl} (LGPL-2.1-or-later AND GPL-2.0-or-later) [installed]
private val APK = Regex("""(.+)-([^-]+-r\d+) (\S+) \{([^}]*)\}.*""")

internal fun parseApk(text: String): List<Item> = text.lines().mapNotNull { APK.matchEntire(it.trim()) }.map {
    row(it.groupValues[1], "version" to it.groupValues[2], "architecture" to it.groupValues[3], "size" to "", "description" to "")
}

// zlib-1.4-r0 x86_64 {zlib} (Zlib) [upgradable from: zlib-1.3.2-r1]
internal fun parseApkUpdates(text: String): List<Item> = text.lines().mapNotNull { APK.matchEntire(it.trim())?.let { match -> match to it } }.map { (match, line) ->
    val name = match.groupValues[1]
    val from = line.substringAfter("[upgradable from: ", "").substringBefore(']').removePrefix("$name-")
    row(name, "version" to from, "new-version" to match.groupValues[2], "architecture" to match.groupValues[3], "repository" to "")
}

// apt/oldstable-security 2.6.2 amd64 [upgradable from: 2.6.1]
internal fun parseAptUpgradable(text: String): List<Item> = text.lines().filter { '/' in it && "[upgradable from:" in it }.map { line ->
    val words = line.split(' ')
    row(
        words[0].substringBefore('/'), "version" to line.substringAfter("[upgradable from: ").substringBefore(']'), "new-version" to words.getOrElse(1) { "" },
        "architecture" to words.getOrElse(2) { "" }, "repository" to words[0].substringAfter('/'),
    )
}

// coreutils.x86_64                   9.10-5.fc44               updates
internal fun parseDnfUpdates(text: String): List<Item> = text.lines().takeWhile { !it.startsWith("Obsoleting") }.map { it.trim().split(Regex("\\s+")) }
    .filter { it.size == 3 && '.' in it[0] }.map { (name, version, repository) ->
        row(name.substringBeforeLast('.'), "version" to "", "new-version" to version, "architecture" to name.substringAfterLast('.'), "repository" to repository)
    }

// v  | repo-oss (16.0) | aaa_base | 84.87+git20250429 | 84.87+git20260610 | x86_64
internal fun parseZypperUpdates(text: String): List<Item> = text.lines().map { it.split('|').map(String::trim) }.filter { it.size >= 6 && it[0] == "v" }.map {
    row(it[2], "version" to it[3], "new-version" to it[4], "architecture" to it[5], "repository" to it[1])
}

// linux 6.17.7.arch1-1 -> 6.17.8.arch1-1
internal fun parsePacmanUpdates(text: String): List<Item> = text.lines().map { it.trim().split(' ') }.filter { it.size >= 4 && it[2] == "->" }.map {
    row(it[0], "version" to it[1], "new-version" to it[3], "architecture" to "", "repository" to "")
}
