package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val LINES = 500
private const val SECTION = "--rd-section--"
private val THROUGH_SUDO = 30.seconds
private val FIELDS = listOf("time", "topics", "message")
private val ERRORS = setOf("emerg", "alert", "crit", "err")
private val AUTH = setOf("auth", "authpriv")
private val LEVEL = Regex("[a-z0-9]+\\.(emerg|alert|crit|err|warning|warn|notice|info|debug)")

// the logs of a host by kind: what systemd-journald keeps, read with journalctl, or where there is no journal the buffer of
// logread, the files of syslog and dmesg. A login that may not read them all, as one outside adm and systemd-journal on
// Debian, reads them as root; a read through sudo is itself a line in the log, so such a log is read again only every 30 s
internal class LinuxLogs(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    // a kind keeps the lines of a shared file by their facility and level, and all of a file of its own
    private class Kind(
        val journal: String,
        val files: List<String> = emptyList(),
        val own: Set<String> = emptySet(),
        val kernel: Boolean = false,
        val keeps: ((String) -> Boolean)? = null,
    )

    private class Tail(var rows: List<Item> = emptyList(), var cursor: String? = null, var read: TimeMark? = null, var asRoot: Boolean? = null)

    private val system = Kind("", listOf("/var/log/messages", "/var/log/syslog"))
    private val kernel = Kind("-k", kernel = true)
    private val auth = Kind("SYSLOG_FACILITY=4 SYSLOG_FACILITY=10", listOf("/var/log/auth.log", "/var/log/secure", "/var/log/messages"), setOf("/var/log/auth.log", "/var/log/secure")) {
        it.substringBefore('.') in AUTH
    }
    private val errors = Kind("-p err", listOf("/var/log/messages")) { it.substringAfter('.', "") in ERRORS }
    private val boot = Kind("-b -1")

    private val tails = HashMap<Kind, Tail>()
    private val lock = Mutex()
    private var whole: Boolean? = null

    val tables: Map<String, HostTable> = mapOf(
        "log" to HostTable(FIELDS, { read(system) }),
        "log/kernel" to HostTable(FIELDS, { read(kernel) }),
        "log/auth" to HostTable(FIELDS, { read(auth) }),
        "log/errors" to HostTable(FIELDS, { read(errors) }),
        "log/boot" to HostTable(FIELDS, { read(boot) }, present = { "journalctl" in tools() }),
    )

    private suspend fun read(kind: Kind): List<Item> = lock.withLock {
        val tail = tails.getOrPut(kind) { Tail() }
        val journal = "journalctl" in tools()
        val asRoot = tail.asRoot ?: (if (journal) !wholeJournal() else kind.kernel || !readable(kind)).also { tail.asRoot = it }
        if (asRoot && !root.login() && tail.read?.let { it.elapsedNow() < THROUGH_SUDO } == true) return@withLock tail.rows
        val rows = when {
            journal -> journal(kind, tail, asRoot)
            kind.kernel -> dmesg()
            else -> syslog(kind, asRoot)
        }
        tail.rows = rows
        tail.read = TimeSource.Monotonic.markNow()
        rows
    }

    // a login outside the groups of the journal sees its own lines alone, and the manager of the system is not among them
    private suspend fun wholeJournal(): Boolean = whole ?: ssh.exec("journalctl -q -n 1 -o cat --no-pager _PID=1").let { result ->
        result.exitStatus != 0 || result.output.isNotBlank()
    }.also { whole = it }

    private suspend fun run(command: String, asRoot: Boolean): String = if (asRoot) root.output(command) else ssh.exec(command).output

    // the first read takes the last lines, the next ones what came after the last line kept
    private suspend fun journal(kind: Kind, tail: Tail, asRoot: Boolean): List<Item> {
        val filter = if (kind.journal.isEmpty()) "" else " ${kind.journal}"
        val after = tail.cursor
        val read = if (after == null) "-n $LINES" else "--after-cursor=${quote(after)}"
        val parts = run("date +%z; echo $SECTION; journalctl -o json $read --no-pager$filter", asRoot).split("$SECTION\n")
        val offset = parts[0].trim()
        val rows = parts.getOrElse(1) { "" }.lines().filter { it.isNotBlank() }.mapNotNull { journalEntry(it, offset) }
        rows.lastOrNull()?.let { tail.cursor = it.id }
        return (if (after == null) rows else tail.rows + rows).takeLast(LINES)
    }

    private suspend fun dmesg(): List<Item> = root.output("dmesg").lines().filter { it.isNotBlank() }.takeLast(LINES).mapIndexed { index, line ->
        val time = line.substringBefore(']', "").removePrefix("[").trim()
        Item("$index", mapOf("time" to time, "topics" to "kern", "message" to if (time.isEmpty()) line else line.substringAfter("] ")))
    }

    // logread of busybox and OpenWrt, else the first file of syslog the host has
    private suspend fun syslog(kind: Kind, asRoot: Boolean): List<Item> {
        val logread = ssh.exec("logread -l $LINES")
        val file = if (logread.exitStatus == 0 && logread.output.isNotBlank()) null else kind.files.firstOrNull { exists(it) } ?: return emptyList()
        val text = if (file == null) logread.output else run("tail -n $LINES ${quote(file)}", asRoot)
        val keeps = kind.keeps.takeIf { file !in kind.own }
        val seen = HashMap<String, Int>()
        return text.lines().filter { it.length > 15 }.mapNotNull { line ->
            val parsed = syslogLine(line)
            if (keeps != null && !keeps(parsed["topics"].orEmpty())) return@mapNotNull null
            val count = (seen[line] ?: 0) + 1
            seen[line] = count
            Item("$line#$count", parsed)
        }
    }

    private suspend fun readable(kind: Kind): Boolean {
        if (ssh.exec("logread -l 1").exitStatus == 0) return true
        val file = kind.files.firstOrNull { exists(it) } ?: return true
        return ssh.exec("test -r ${quote(file)}").exitStatus == 0
    }

    private suspend fun exists(file: String) = ssh.exec("test -e ${quote(file)}").exitStatus == 0
}

// a line of syslog: the time as busybox, logread and rsyslog write it, in the old form or in RFC 3339, the facility and level
// where busybox writes them, and the message
internal fun syslogLine(line: String): Map<String, String> {
    val words = line.split(' ').filter { it.isNotEmpty() }
    val stamped = words.firstOrNull()?.firstOrNull()?.isDigit() == true
    // the old form is Nov 17 11:41:29 host, logread adds the weekday and the year and names no host: Fri Oct 23 17:41:46 2026
    val logread = !stamped && words.getOrNull(3)?.contains(':') == true && words.getOrNull(4)?.toIntOrNull() != null
    val timeWords = if (stamped) 1 else if (logread) 5 else 3
    val time = if (stamped) words[0].replace('T', ' ').substringBefore('.').substringBefore('+') else words.take(timeWords).joinToString(" ")
    val rest = words.drop(timeWords).let { if (logread || it.size < 2) it else it.drop(1) }
    val level = rest.firstOrNull()?.takeIf { LEVEL.matches(it) }
    return mapOf("time" to time, "topics" to level.orEmpty(), "message" to (if (level == null) rest else rest.drop(1)).joinToString(" "))
}
