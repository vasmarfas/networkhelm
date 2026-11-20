package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.model.duration
import com.vasmarfas.networkhelm.openwrt.quote
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

// a node of Proxmox VE from its own shell, as root, through pvesh and pvesm, no token of the API: the node and its storages, the
// virtual machines and containers of the cluster with what they can be told, their snapshots and backups, the tasks of the node and
// the log of the cluster. A command that makes a task follows it for a while, so the table read after it shows how it ended; pvesh
// runs as root alone, so the tables are read seldom, as everything that goes through sudo
internal class LinuxProxmox(private val root: Root, private val tools: suspend () -> Set<String>) {
    private val present: suspend () -> Boolean = { "pvesh" in tools() }
    private var here: String? = null
    private var offset: String? = null

    val tables: Map<String, HostTable> = mapOf(
        "proxmox/node" to HostTable(NODE_FIELDS, { listOf(nodeRow(here(), pveObject("get ${quote("/nodes/${here()}/status")}"))) }, settings = true, slow = true, present = present),
        "proxmox/storage" to HostTable(STORAGE_FIELDS, { storageRows(objects(pvesh("get ${quote("/nodes/${here()}/storage")}"), "/nodes/${here()}/storage")) }, slow = true, present = present),
        "proxmox/vm" to guestTable(QEMU),
        "proxmox/ct" to guestTable(LXC),
        "proxmox/snapshot" to HostTable(
            SNAPSHOT_FIELDS, ::snapshots, slow = true, arguments = ::snapshotArguments, add = ::snapshot, remove = ::removeSnapshots,
            commands = listOf("rollback"), run = { _, arguments -> rollback(arguments["number"].orEmpty()) }, present = present,
        ),
        "proxmox/backup" to HostTable(BACKUP_FIELDS, ::backups, slow = true, arguments = ::backupArguments, add = ::backup, remove = ::removeBackups, present = present),
        "proxmox/task" to HostTable(TASK_FIELDS, ::tasks, slow = true, commands = listOf("log", "stop"), run = ::task, present = present),
        "proxmox/log" to HostTable(LOG_FIELDS, { logRows(objects(pvesh("get /cluster/log --max 100"), "/cluster/log"), offset()) }, slow = true, present = present),
    )

    private fun guestTable(kind: Kind) = HostTable(
        GUEST_FIELDS, { guests().filter { it["type"] == kind.type } }, slow = true, arguments = { kind.arguments }, set = { id, changes -> configure(kind, id, changes) },
        commands = GUEST_COMMANDS, run = ::act, item = { id -> config(kind, id) }, present = present,
    )

    // pvesh in JSON; what it says on stderr when it fails is the text of the failure, as the host gave it
    private suspend fun pvesh(command: String): JsonElement {
        val result = root.exec("pvesh $command --output-format json")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, result.error.trim().ifEmpty { result.output.trim() }.ifEmpty { "exit ${result.exitStatus}" })
        return try {
            Json.parseToJsonElement(result.output.ifBlank { "null" })
        } catch (e: SerializationException) {
            throw DeviceException(ErrorCategory.Api, "pvesh $command gave no JSON", e)
        }
    }

    private suspend fun pveObject(command: String) = pvesh(command) as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "pvesh $command gave no JSON")

    // the name of this node in its cluster, which the paths of pvesh take
    private suspend fun here(): String = here ?: objects(pvesh("get /cluster/status"), "/cluster/status")
        .firstOrNull { it.text("type") == "node" && it.text("local") == "1" }?.text("name")?.also { here = it }
        ?: throw DeviceException(ErrorCategory.Api, "the cluster names no node of this host")

    private suspend fun offset(): String = offset ?: root.exec("date +%z").output.trim().also { offset = it }

    // the guests of the cluster as /cluster/resources has them, each with the node it runs on and the line that opens its console
    private suspend fun guests(): List<Item> {
        val sudo = if (root.login()) "" else "sudo "
        return objects(pvesh("get /cluster/resources --type vm"), "/cluster/resources").map { guestRow(it, sudo) }.sortedBy { it.id.toLongOrNull() }
    }

    private suspend fun guest(id: String?): Item {
        val vmid = id?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "choose the guest")
        return guests().firstOrNull { it.id == vmid } ?: throw DeviceException(ErrorCategory.MissingItem, "the cluster has no guest $vmid")
    }

    private fun path(guest: Item, rest: String) = quote("/nodes/${guest["node"]}/${guest["type"]}/${guest.id}/$rest")

    // start, shutdown, stop, reboot, suspend and resume of the guests chosen, each a task of the node of its guest
    private suspend fun act(command: String, arguments: Map<String, String>): List<Item> {
        val known = guests().associateBy { it.id }
        for (vmid in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) {
            val guest = known[vmid] ?: throw DeviceException(ErrorCategory.MissingItem, "the cluster has no guest $vmid")
            finished(upid(pvesh("create ${path(guest, "status/$command")}")))
        }
        return emptyList()
    }

    // the settings the form of a guest changes, from its config, beside its row of the list
    private suspend fun config(kind: Kind, id: String): Item? {
        val guest = guests().firstOrNull { it.id == id && it["type"] == kind.type } ?: return null
        return Item(id, guest.values + configValues(pveObject("get ${path(guest, "config")}")))
    }

    // what the user changed of the cores, sockets, memory, swap, the start at boot and the description; an empty description or start
    // at boot goes back to the default of Proxmox, a number has to stay a number
    private suspend fun configure(kind: Kind, id: String?, changes: Map<String, String?>) {
        val guest = guests().firstOrNull { it.id == id && it["type"] == kind.type } ?: throw DeviceException(ErrorCategory.MissingItem, "the cluster has no guest $id")
        val given = kind.arguments.map { it.name }.filter { it in changes }
        if (given.isEmpty()) return
        val options = given.mapNotNull { field ->
            val value = changes[field].orEmpty()
            when {
                field == "onboot" -> value.ifEmpty { null }?.let { "--onboot ${if (it in ValueType.Flag.on) 1 else 0}" }
                field == "description" -> value.ifEmpty { null }?.let { "--description ${quote(it)}" }
                else -> "--${CONFIG_KEYS.getValue(field)} " + (value.toLongOrNull() ?: throw DeviceException(ErrorCategory.ArgumentValue, "$field: $value is no number"))
            }
        }
        val deleted = given.filter { it in CLEARED && changes[it].isNullOrEmpty() }.map { CONFIG_KEYS.getValue(it) }
        pvesh("set ${path(guest, "config")} " + (options + listOfNotNull(deleted.ifEmpty { null }?.let { "--delete ${quote(it.joinToString(","))}" })).joinToString(" "))
    }

    // the snapshots of the guests whose configs name a parent, which only a snapshot makes, as pvesh lists them
    private suspend fun snapshots(): List<Item> {
        val result = root.exec(SNAPSHOTS)
        val rows = parseSnapshots(result.output, offset())
        if (rows.isEmpty() && result.error.isNotBlank()) throw DeviceException(ErrorCategory.Api, result.error.trim())
        return rows
    }

    private suspend fun snapshotArguments(): List<Argument> = listOf(chosenGuest(guests())) + SNAPSHOT_ARGUMENTS

    // a snapshot of a guest by the name typed, with the memory of a running virtual machine where asked
    private suspend fun snapshot(values: Map<String, String>): String {
        val guest = guest(values["guest"])
        val name = values["name"].orEmpty()
        if (!SNAPSHOT_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "the name of a snapshot is a letter and then letters, digits, - and _, 2 to 40 of them: $name")
        val description = values["description"]?.ifEmpty { null }?.let { " --description ${quote(it)}" }.orEmpty()
        val memory = if (guest["type"] == QEMU.type && values["ram"] in ValueType.Flag.on) " --vmstate 1" else ""
        finished(upid(pvesh("create ${path(guest, "snapshot")} --snapname ${quote(name)}$description$memory")))
        return "${guest.id}/$name"
    }

    private suspend fun removeSnapshots(ids: List<String>) {
        for (id in ids) {
            val guest = guest(id.substringBefore('/'))
            finished(upid(pvesh("delete ${path(guest, "snapshot/${id.substringAfter('/')}")}")))
        }
    }

    // the guest goes back to the snapshot, and what it was since is lost
    private suspend fun rollback(id: String): List<Item> {
        val guest = guest(id.substringBefore('/'))
        finished(upid(pvesh("create ${path(guest, "snapshot/${id.substringAfter('/')}/rollback")}")))
        return emptyList()
    }

    // the storages of this node that keep backups and are active: one that is down would fail the whole list
    private suspend fun backupStorages(): List<String> =
        objects(pvesh("get ${quote("/nodes/${here()}/storage")} --content backup"), "/nodes/${here()}/storage").filter { it.text("active") == "1" }.map { it.text("storage") }

    // the backups of every storage of the node that keeps them, newest first
    private suspend fun backups(): List<Item> {
        val node = here()
        val offset = offset()
        return backupStorages().flatMap { storage ->
            objects(pvesh("get ${quote("/nodes/$node/storage/$storage/content")} --content backup"), "/nodes/$node/storage/$storage/content").map { backupRow(it, storage, offset) }
        }.sortedByDescending { it["created"] }
    }

    // no storage is chosen for the user: local of a node often lies on its root disk, which a large guest fills
    private suspend fun backupArguments(): List<Argument> = listOf(chosenGuest(guests()), Argument("storage", ValueType.Enumeration(closed = true), backupStorages())) + BACKUP_ARGUMENTS

    // vzdump of a guest to the storage chosen, a task of the node of the guest: a large one takes minutes, and the table of tasks
    // follows it; the answer is the task
    private suspend fun backup(values: Map<String, String>): String {
        val guest = guest(values["guest"])
        val storage = values["storage"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "choose the storage")
        val options = listOfNotNull(
            "--vmid ${guest.id}", "--storage ${quote(storage)}", values["mode"]?.ifEmpty { null }?.let { "--mode ${quote(it)}" },
            values["compress"]?.ifEmpty { null }?.let { "--compress ${quote(it)}" }, values["notes"]?.ifEmpty { null }?.let { "--notes-template ${quote(it)}" },
        )
        return upid(pvesh("create ${quote("/nodes/${guest["node"]}/vzdump")} ${options.joinToString(" ")}")).orEmpty()
    }

    private suspend fun removeBackups(ids: List<String>) {
        for (volume in ids) root.output("pvesm free ${quote(volume)}")
    }

    private suspend fun tasks(): List<Item> = objects(pvesh("get ${quote("/nodes/${here()}/tasks")} --source all --limit 50"), "/nodes/${here()}/tasks").map { taskRow(it, offset()) }

    // the log of a task to read, or tasks still running stopped
    private suspend fun task(command: String, arguments: Map<String, String>): List<Item> {
        if (command == "log") {
            val upid = arguments["number"].orEmpty()
            return listOf(Item(upid, mapOf("output" to taskLog(upid).joinToString("\n"))))
        }
        for (upid in arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }) pvesh("delete ${quote("/nodes/${upidNode(upid)}/tasks/$upid")}")
        return emptyList()
    }

    private suspend fun taskLog(upid: String): List<String> =
        objects(pvesh("get ${quote("/nodes/${upidNode(upid)}/tasks/$upid/log")} --limit 1000"), "the log of $upid").map { it.text("t") }

    // a task followed to its end for TASK_POLLS seconds at most: one that failed says why at the end of its log, one still going goes on
    // in the table of tasks; warnings are no failure
    private suspend fun finished(upid: String?) {
        if (upid == null) return
        repeat(TASK_POLLS) {
            val status = pveObject("get ${quote("/nodes/${upidNode(upid)}/tasks/$upid/status")}")
            if (status.text("status") == "stopped") {
                val exit = status.text("exitstatus")
                if (exit != "OK" && !exit.startsWith("WARNINGS")) throw DeviceException(ErrorCategory.Api, taskLog(upid).takeLast(5).joinToString("\n").ifEmpty { exit })
                return
            }
            delay(1.seconds)
        }
    }

    // a kind of guest: its name in the paths of pvesh, and what its form changes
    private class Kind(val type: String, val arguments: List<Argument>)

    private companion object {
        val QEMU = Kind(
            "qemu",
            listOf(
                Argument("cores", ValueType.Integer(min = 1), optional = true), Argument("sockets", ValueType.Integer(min = 1), optional = true),
                Argument("memory-size", ValueType.Integer(min = 16, unit = "MiB"), optional = true), Argument("onboot", ValueType.YesNo, optional = true), many("description"),
            ),
        )
        val LXC = Kind(
            "lxc",
            listOf(
                Argument("cores", ValueType.Integer(min = 1), optional = true), Argument("memory-size", ValueType.Integer(min = 16, unit = "MiB"), optional = true),
                Argument("swap-size", ValueType.Integer(min = 0, unit = "MiB"), optional = true), Argument("onboot", ValueType.YesNo, optional = true), many("description"),
            ),
        )
    }
}

// the guests of the cluster, the nodes and storages of /cluster/resources left out: a share of the CPU in percent, the memory and the
// disk in bytes, the line that opens the console of the guest in a terminal
internal fun guestRow(guest: JsonObject, sudo: String = ""): Item {
    val vmid = guest.text("vmid")
    val type = guest.text("type")
    return Item(
        vmid,
        linkedMapOf(
            "vmid" to vmid, "name" to guest.text("name"), "status" to guest.text("status"), "node" to guest.text("node"), "type" to type,
            "cpu" to percent(guest.text("cpu")), "cpus" to guest.text("maxcpu"), "memory" to guest.text("mem"), "max-memory" to guest.text("maxmem"),
            "disk" to guest.text("disk"), "max-disk" to guest.text("maxdisk"), "uptime" to uptime(guest.text("uptime")), "tags" to guest.text("tags"),
            "template" to flag(guest.text("template")), "lock" to guest.text("lock"),
            "console" to sudo + (if (type == "qemu") "qm terminal " else "pct enter ") + vmid,
        ),
    )
}

// what the form of a guest changes, as its config has it; memory and swap in MiB
internal fun configValues(config: JsonObject): Map<String, String> = linkedMapOf(
    "cores" to config.text("cores"), "sockets" to config.text("sockets"), "memory-size" to config.text("memory"), "swap-size" to config.text("swap"),
    "onboot" to flag(config.text("onboot")), "description" to config.text("description"),
)

// the node as /nodes/<node>/status tells of it
internal fun nodeRow(name: String, status: JsonObject): Item {
    val cpu = status.inner("cpuinfo")
    val memory = status.inner("memory")
    val swap = status.inner("swap")
    val disk = status.inner("rootfs")
    return Item(
        "",
        linkedMapOf(
            "node" to name, "version" to status.text("pveversion").removePrefix("pve-manager/").substringBefore('/'),
            "kernel" to status.inner("current-kernel")?.text("release").orEmpty().ifEmpty { status.text("kversion") }, "boot-mode" to status.inner("boot-info")?.text("mode").orEmpty(),
            "uptime" to uptime(status.text("uptime")), "cpu-model" to cpu?.text("model").orEmpty(), "cpu-count" to cpu?.text("cpus").orEmpty(),
            "sockets" to cpu?.text("sockets").orEmpty(), "cores" to cpu?.text("cores").orEmpty(), "cpu-load" to percent(status.text("cpu")), "io-delay" to percent(status.text("wait")),
            "load" to status.texts("loadavg").joinToString(" "), "total-memory" to memory?.text("total").orEmpty(), "used-memory" to memory?.text("used").orEmpty(),
            "total-swap" to swap?.text("total").orEmpty(), "used-swap" to swap?.text("used").orEmpty(), "root-total" to disk?.text("total").orEmpty(),
            "root-used" to disk?.text("used").orEmpty(), "ksm-shared" to status.inner("ksm")?.text("shared").orEmpty(),
        ),
    )
}

internal fun storageRows(storages: List<JsonObject>): List<Item> = storages.map { storage ->
    Item(
        storage.text("storage"),
        linkedMapOf(
            "name" to storage.text("storage"), "type" to storage.text("type"), "content" to storage.text("content"), "active" to flag(storage.text("active")),
            "enabled" to flag(storage.text("enabled")), "shared" to flag(storage.text("shared")), "used" to storage.text("used"), "total" to storage.text("total"),
            "available" to storage.text("avail"), "usage" to percent(storage.text("used_fraction")),
        ),
    )
}.sortedBy { it.id }

// what the script of snapshots printed: a mark with the node, the kind and the ID of a guest, then the list pvesh gave for it; current
// is where the guest stands now, no snapshot
internal fun parseSnapshots(text: String, offset: String): List<Item> {
    var guest: List<String>? = null
    return text.lines().flatMap { line ->
        if (line.startsWith(GUEST_MARK)) guest = line.removePrefix(GUEST_MARK).trim().split(' ')
        val (node, type, vmid) = guest?.takeIf { it.size == 3 && line.startsWith("[") } ?: return@flatMap emptyList()
        val list = try {
            Json.parseToJsonElement(line)
        } catch (e: SerializationException) {
            throw DeviceException(ErrorCategory.Api, "pvesh get /nodes/$node/$type/$vmid/snapshot gave no JSON", e)
        }
        objects(list, "/nodes/$node/$type/$vmid/snapshot").filter { it.text("name") != "current" }.map { snapshot ->
            Item(
                "$vmid/${snapshot.text("name")}",
                linkedMapOf(
                    "guest" to vmid, "name" to snapshot.text("name"), "time" to moment(snapshot.text("snaptime"), offset), "ram" to flag(snapshot.text("vmstate").ifEmpty { "0" }),
                    "description" to snapshot.text("description"), "parent" to snapshot.text("parent"), "node" to node, "type" to type,
                ),
            )
        }
    }
}

internal fun backupRow(backup: JsonObject, storage: String, offset: String) = Item(
    backup.text("volid"),
    linkedMapOf(
        "guest" to backup.text("vmid"), "type" to backup.text("subtype"), "created" to moment(backup.text("ctime"), offset), "size" to backup.text("size"),
        "storage" to storage, "format" to backup.text("format"), "notes" to backup.text("notes"), "protected" to flag(backup.text("protected").ifEmpty { "0" }),
        "verified" to backup.inner("verification")?.text("state").orEmpty(), "volid" to backup.text("volid"),
    ),
)

internal fun taskRow(task: JsonObject, offset: String) = Item(
    task.text("upid"),
    linkedMapOf(
        "start" to moment(task.text("starttime"), offset), "end" to moment(task.text("endtime"), offset), "type" to task.text("type"), "guest" to task.text("id"),
        "user" to task.text("user"), "status" to task.text("status"), "node" to task.text("node"),
    ),
)

// the log of the cluster from the oldest, its service and level as the topics of the log of the host are
internal fun logRows(entries: List<JsonObject>, offset: String): List<Item> = entries.reversed().map { entry ->
    val level = entry.text("pri").toIntOrNull()?.let { LEVELS.getOrNull(it) } ?: entry.text("pri")
    Item(
        entry.text("id"),
        linkedMapOf(
            "time" to moment(entry.text("time"), offset), "topics" to listOf(entry.text("tag"), level).filter { it.isNotEmpty() }.joinToString("."),
            "message" to entry.text("msg"), "node" to entry.text("node"), "user" to entry.text("user"),
        ),
    )
}

// the guest to choose in a form, by its ID, which the form names with its name
private fun chosenGuest(guests: List<Item>) = Argument("guest", ValueType.Enumeration(closed = true), guests.map { it.id }, labels = guests.associate { it.id to "${it.id} ${it["name"]}" })

private fun upid(answer: JsonElement): String? = (answer as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("UPID:") }

// UPID:<node>:<pid>:...: names the node that runs the task
private fun upidNode(upid: String) = upid.split(':').getOrElse(1) { "" }

// a share of 0 to 1 in percent to a tenth
private fun percent(share: String): String = share.toDoubleOrNull()?.let { (it * 1000).roundToLong() }?.let { "${it / 10}.${it % 10}" } ?: share

private fun uptime(seconds: String): String = seconds.toLongOrNull()?.let { if (it > 0) duration(it) else "" } ?: seconds

private fun flag(value: String) = when (value) {
    "1" -> "true"
    "0" -> "false"
    else -> value
}

internal val PROXMOX_TOOLS = listOf("pvesh")

private const val GUEST_MARK = "--nh-guest--"

// how many seconds a command follows the task it made
private const val TASK_POLLS = 20

// the guests whose configs name a parent, each after a mark with its node, kind and ID, with what pvesh lists of its snapshots
private val SNAPSHOTS = $$"""for f in /etc/pve/nodes/*/qemu-server/*.conf /etc/pve/nodes/*/lxc/*.conf; do
[ -f "$f" ] && grep -q '^parent:' "$f" || continue
set -- $(echo "$f" | tr / ' ')
echo "$${GUEST_MARK} $4 ${5%-server} ${6%.conf}"
pvesh get "/nodes/$4/${5%-server}/${6%.conf}/snapshot" --output-format json
done
true"""

private val SNAPSHOT_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{1,39}")

private val LEVELS = listOf("emerg", "alert", "crit", "err", "warning", "notice", "info", "debug")

private val GUEST_COMMANDS = listOf("start", "shutdown", "stop", "reboot", "suspend", "resume")

private val CONFIG_KEYS = mapOf("cores" to "cores", "sockets" to "sockets", "memory-size" to "memory", "swap-size" to "swap", "onboot" to "onboot", "description" to "description")

// an empty field that goes back to the default of Proxmox rather than being refused
private val CLEARED = setOf("onboot", "description")

private val NODE_FIELDS = listOf(
    "node", "version", "kernel", "boot-mode", "uptime", "cpu-model", "cpu-count", "sockets", "cores", "cpu-load", "io-delay", "load", "total-memory", "used-memory",
    "total-swap", "used-swap", "root-total", "root-used", "ksm-shared",
)
private val STORAGE_FIELDS = listOf("name", "type", "content", "active", "enabled", "shared", "used", "total", "available", "usage")
private val GUEST_FIELDS = listOf(
    "vmid", "name", "status", "node", "type", "cpu", "cpus", "memory", "max-memory", "disk", "max-disk", "uptime", "tags", "template", "lock", "console",
)
private val SNAPSHOT_FIELDS = listOf("guest", "name", "time", "ram", "description", "parent", "node", "type")
private val BACKUP_FIELDS = listOf("guest", "type", "created", "size", "storage", "format", "notes", "protected", "verified", "volid")
private val TASK_FIELDS = listOf("start", "end", "type", "guest", "user", "status", "node")
private val LOG_FIELDS = listOf("time", "topics", "message", "node", "user")

private val SNAPSHOT_ARGUMENTS = listOf(
    Argument("name", ValueType.Text(maxLength = 40)), Argument("description", ValueType.Text(), optional = true), Argument("ram", ValueType.YesNo, optional = true),
)
private val BACKUP_ARGUMENTS = listOf(
    Argument("mode", ValueType.Enumeration(closed = true), listOf("snapshot", "suspend", "stop"), default = "snapshot"),
    Argument("compress", ValueType.Enumeration(closed = true), listOf("zstd", "lzo", "gzip", "0"), default = "zstd", labels = mapOf("0" to "none")),
    Argument("notes", ValueType.Text(), optional = true, default = "{{guestname}}"),
)
