package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// the storage of a Linux host: what is mounted, the block devices, the lines of /etc/fstab and the SMART state of the
// drives. findmnt, lsblk and smartctl give JSON where the host has them, /proc, /sys and df stand in on busybox;
// mounting, unmounting and writing fstab go as root
internal class LinuxStorage(private val ssh: SshSession, private val root: Root, private val tools: suspend () -> Set<String>) {
    val tables: Map<String, HostTable> = mapOf(
        "system/storage" to HostTable(MOUNTED, ::mounts, arguments = { MOUNT_ARGUMENTS }, add = ::mount, set = ::remount, remove = ::unmount),
        "system/storage/disk" to HostTable(DISK, ::disks, slow = true),
        "system/storage/fstab" to HostTable(
            FSTAB + "mounted", ::fstab, slow = true, arguments = { FSTAB_ARGUMENTS }, add = ::addFstab, set = ::setFstab, remove = ::removeFstab,
            commands = listOf("mount-all"), run = { _, _ -> mountAll() },
        ),
        "system/storage/smart" to HostTable(
            SMART, ::smart, slow = true, commands = listOf("short-test"), run = { _, arguments -> shortTest(arguments["number"].orEmpty()) },
            present = { "smartctl" in tools() },
        ),
    )

    private suspend fun output(command: String): String {
        val result = ssh.exec(command)
        if (result.exitStatus != 0 && result.output.isBlank()) throw DeviceException(ErrorCategory.Api, "$command: ${result.error.trim().ifEmpty { "exit ${result.exitStatus}" }}")
        return result.output
    }

    // findmnt of util-linux before 2.27 has no JSON
    private suspend fun mounts(): List<Item> {
        if ("findmnt" in tools()) ssh.exec(FINDMNT).output.takeIf { it.trimStart().startsWith("{") }?.let { return parseFindmnt(it) }
        val (mounts, sizes) = output("cat /proc/mounts; echo $SECTION; df -P -k").split("$SECTION\n") + List(2) { "" }
        return parseMounts(mounts, sizes)
    }

    private suspend fun mount(values: Map<String, String>): String {
        val device = values["device"].orEmpty().also { require(it.isNotEmpty() && '\n' !in it, "device", it) }
        val point = values["mount-point"].orEmpty().also { require(it.startsWith("/") && '\n' !in it, "mount-point", it) }
        val type = values["type"].orEmpty().also { require(it.isEmpty() || TYPE.matches(it), "type", it) }
        val options = values["options"].orEmpty().also { require(it.none(Char::isWhitespace), "options", it) }
        val line = buildList {
            if (values["create"].orEmpty().ifEmpty { "yes" } in ValueType.Flag.on) add("mkdir -p ${quote(point)} &&")
            add("mount")
            if (type.isNotEmpty()) add("-t ${quote(type)}")
            if (options.isNotEmpty()) add("-o ${quote(options)}")
            add("${quote(device)} ${quote(point)}")
        }
        root.output(line.joinToString(" "))
        if (values["fstab"] in ValueType.Flag.on) addFstab(values)
        return point
    }

    private suspend fun remount(id: String?, changes: Map<String, String?>) {
        val options = changes["options"] ?: return
        val point = id?.takeIf { it.startsWith("/") } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no mount $id")
        require(options.none(Char::isWhitespace), "options", options)
        root.output("mount -o ${quote(if (options.isEmpty()) "remount" else "remount,$options")} ${quote(point)}")
    }

    // a mount inside another goes first
    private suspend fun unmount(ids: List<String>) {
        val home = home()
        val steps = ids.sortedByDescending { it.length }.map { point ->
            if (!point.startsWith("/")) throw DeviceException(ErrorCategory.MissingItem, "the host has no mount $point")
            if (point == "/") throw DeviceException(ErrorCategory.ArgumentValue, "/ is the root of the host and stays mounted")
            if (point == home) throw DeviceException(ErrorCategory.ArgumentValue, "the home of this login is on $point, and it stays mounted")
            "umount ${quote(point)}"
        }
        root.output(steps.joinToString(" && "))
    }

    private suspend fun home(): String? = ssh.exec("df -P -k \"\$HOME\"").output.lines().drop(1).firstOrNull { it.isNotBlank() }
        ?.trim()?.split(BLANKS)?.takeIf { it.size >= 6 }?.drop(5)?.joinToString(" ")

    // lsblk of util-linux before 2.33 has no PATH, and busybox has no lsblk
    private suspend fun disks(): List<Item> {
        if ("lsblk" in tools()) ssh.exec(LSBLK).output.takeIf { it.trimStart().startsWith("{") }?.let { return parseLsblk(it) }
        val (partitions, block, mounts) = output("cat /proc/partitions; echo $SECTION; $BLOCK; echo $SECTION; cat /proc/mounts").split("$SECTION\n") + List(3) { "" }
        return parsePartitions(partitions, block, mounts)
    }

    private suspend fun fstab(): List<Item> {
        val (mounts, text) = output("cat /proc/mounts; echo $SECTION; cat $FSTAB_FILE").split("$SECTION\n") + List(2) { "" }
        return parseFstab(text, mounts.lines().mapNotNull { it.split(' ').getOrNull(1)?.let(::unescape) }.toSet())
    }

    private suspend fun addFstab(values: Map<String, String>): String {
        val line = fstabLine(values)
        val written = writeFstab { lines ->
            taken(lines, values["mount-point"].orEmpty(), null)
            (if (lines.lastOrNull() == "") lines.dropLast(1) else lines) + line + ""
        }
        return entries(written).last().id
    }

    private suspend fun setFstab(id: String?, changes: Map<String, String?>) {
        writeFstab { lines ->
            val entry = entries(lines).firstOrNull { it.id == id } ?: throw DeviceException(ErrorCategory.MissingItem, "fstab has no line $id")
            val values = entry.values() + changes.mapValues { it.value.orEmpty() }
            taken(lines, values["mount-point"].orEmpty(), id)
            lines.toMutableList().apply { set(entry.line, fstabLine(values)) }
        }
    }

    private suspend fun removeFstab(ids: List<String>) {
        writeFstab { lines ->
            val byId = entries(lines).associateBy { it.id }
            val gone = ids.map { id -> byId[id]?.line ?: throw DeviceException(ErrorCategory.MissingItem, "fstab has no line $id") }.toSet()
            lines.filterIndexed { index, _ -> index !in gone }
        }
    }

    // the old file stays beside as fstab.networkhelm and comes back when findmnt finds errors in the new one the old one
    // did not have: findmnt counts a disk that is not plugged in as an error as well
    private suspend fun writeFstab(edit: (List<String>) -> List<String>): List<String> {
        val lines = edit(output("cat $FSTAB_FILE").lines())
        val check = "findmnt" in tools()
        val before = if (check) parseVerify(root.exec(VERIFY).output) else emptySet()
        // the new file starts as a copy, so it keeps the owner and the mode of the old one
        root.output("cp -p $FSTAB_FILE $BACKUP && cp -p $FSTAB_FILE $NEW && printf '%s' ${quote(lines.joinToString("\n"))} > $NEW && mv -f $NEW $FSTAB_FILE")
        val found = if (check) parseVerify(root.exec(VERIFY).output) - before else emptySet()
        if (found.isNotEmpty()) {
            root.output("cp -p $BACKUP $FSTAB_FILE")
            throw DeviceException(ErrorCategory.ArgumentValue, "findmnt --verify: ${found.joinToString("; ")}. $FSTAB_FILE is back as it was")
        }
        return lines
    }

    private fun taken(lines: List<String>, point: String, id: String?) {
        if (point !in SWAP && entries(lines).any { it.point == point && it.id != id }) throw DeviceException(ErrorCategory.ArgumentValue, "fstab has a line for $point already")
    }

    private suspend fun mountAll(): List<Item> {
        root.output("mount -a")
        return emptyList()
    }

    // --scan-open opens each device and so tells its type, sat for a SATA disk that --scan calls scsi; smartctl before 7
    // has no JSON. -n standby leaves a sleeping disk asleep
    private suspend fun smart(): List<Item> {
        val scan = root.exec("smartctl --scan-open -j").output
        val json = scan.trimStart().startsWith("{")
        val drives = parseSmartScan(if (json) scan else root.exec("smartctl --scan-open").output)
        if (drives.isEmpty()) return emptyList()
        val outputs = root.exec(drives.joinToString("; echo $SECTION; ") { (name, type) -> "smartctl${if (json) " -j" else ""} -n standby -i -H -A${drive(name, type)}" })
            .output.split("$SECTION\n")
        return drives.mapIndexed { index, (name, type) -> parseSmart(name, type, outputs.getOrElse(index) { "" }) }
    }

    private suspend fun shortTest(id: String): List<Item> {
        val name = id.substringBefore(" -d ")
        if (!name.startsWith("/dev/")) throw DeviceException(ErrorCategory.MissingItem, "smartctl has no device $id")
        val result = root.exec("smartctl -t short${drive(name, id.substringAfter(" -d ", ""))}")
        val text = result.output.substringAfter("\n\n").trim()
        // the low three bits of the status are a wrong command line, a device that did not open and a command the drive refused
        if ((result.exitStatus and 7) != 0) throw DeviceException(ErrorCategory.Api, text.ifEmpty { result.error.trim() })
        return listOf(Item("", mapOf("output" to text)))
    }

    private fun drive(name: String, type: String) = (if (type.isEmpty()) "" else " -d ${quote(type)}") + " ${quote(name)}"

    private companion object {
        const val FINDMNT = "findmnt -J -b -l -o TARGET,SOURCE,FSTYPE,OPTIONS,SIZE,USED,AVAIL,USE%"
        const val LSBLK = "lsblk -J -b -o NAME,PATH,TYPE,SIZE,MODEL,SERIAL,TRAN,ROTA,RM,RO,FSTYPE,LABEL,UUID,MOUNTPOINT"
        const val BLOCK = $$"""for d in /sys/block/*; do echo "${d##*/}|$(cat $d/device/model 2>/dev/null)|$(cat $d/queue/rotational 2>/dev/null)|""" +
            $$"""$(cat $d/removable 2>/dev/null)|$(cat $d/ro 2>/dev/null)"; done"""
        const val FSTAB_FILE = "/etc/fstab"
        const val BACKUP = "$FSTAB_FILE.networkhelm"
        const val NEW = "$FSTAB_FILE.networkhelm-new"
        const val VERIFY = "findmnt --verify --tab-file $FSTAB_FILE"

        val MOUNTED = listOf("mount-point", "device", "type", "options", "size", "used", "free", "use", "read-only")
        val TYPES = listOf("ext4", "xfs", "btrfs", "vfat", "exfat", "ntfs3", "nfs", "nfs4", "cifs", "tmpfs")
        val MOUNT_ARGUMENTS = listOf(
            Argument("device", ValueType.Text(), description = "/dev/sdb1, UUID=..., LABEL=... or server:/export", fixed = true),
            Argument("mount-point", ValueType.Text(), description = "/mnt/data", fixed = true),
            Argument("type", ValueType.Text(), TYPES, optional = true, fixed = true),
            Argument("options", ValueType.Text(), optional = true, description = "noatime,ro"),
            Argument("create", ValueType.YesNo, optional = true, default = "yes", fixed = true),
            Argument("fstab", ValueType.YesNo, optional = true, default = "no", fixed = true),
        )
        val DISK = listOf(
            "name", "path", "type", "size", "model", "serial", "transport", "rotational", "removable", "read-only", "file-system", "label", "uuid", "mount-point", "parent",
        )
        val FSTAB_ARGUMENTS = listOf(
            Argument("device", ValueType.Text(), description = "UUID=..., LABEL=..., /dev/sdb1 or server:/export"),
            Argument("mount-point", ValueType.Text(), description = "/mnt/data, or none for swap"),
            Argument("type", ValueType.Text(), TYPES + "swap", optional = true, default = "auto"),
            Argument("options", ValueType.Text(), optional = true, default = "defaults"),
            Argument("dump", ValueType.Integer(min = 0), optional = true, default = "0"),
            Argument("pass", ValueType.Integer(min = 0), optional = true, default = "0"),
        )
        val SMART = listOf(
            "device", "type", "model", "serial", "firmware", "capacity", "smart-enabled", "health", "temperature", "power-on-hours", "power-cycles",
            "reallocated", "pending", "uncorrectable", "wear", "life-left", "message",
        )
    }
}

private const val SECTION = "--nh-section--"

private val BLANKS = Regex("\\s+")

private val OCTAL = Regex("""\\([0-7]{3})""")

private val TYPE = Regex("[A-Za-z0-9_.+-]+")

// what the kernel mounts for itself, which holds no files of the host
private val PSEUDO = setOf(
    "proc", "sysfs", "devtmpfs", "devpts", "cgroup", "cgroup2", "cpuset", "mqueue", "securityfs", "debugfs", "tracefs", "pstore", "bpf", "hugetlbfs",
    "autofs", "binfmt_misc", "rpc_pipefs", "nfsd", "fusectl", "configfs", "efivarfs", "nsfs", "selinuxfs",
)

private val FSTAB = listOf("device", "mount-point", "type", "options", "dump", "pass")

// the mount points of swap, which may come in fstab more than once
private val SWAP = setOf("none", "swap")

// the attributes whose normalised value is the life an SSD has left: SandForce and Kingston, Intel, Samsung, Crucial
private val LIFE = listOf("231", "233", "177", "202")

// /proc/mounts and fstab write a space, a tab, a newline and a backslash in octal
private fun unescape(text: String) = OCTAL.replace(text) { it.groupValues[1].toInt(8).toChar().toString() }

private fun escape(text: String) = text.replace("\\", "\\134").replace(" ", "\\040").replace("\t", "\\011").replace("\n", "\\012")

private fun require(valid: Boolean, field: String, value: String) {
    if (!valid) throw DeviceException(ErrorCategory.ArgumentValue, if (value.isEmpty()) "$field: a value is needed" else "$field: $value is not a value it takes")
}

// lsblk before util-linux 2.33 and the files of sysfs say 1 and 0
private fun flag(value: String) = when (value) {
    "1" -> "true"
    "0" -> "false"
    else -> value
}

private fun parsed(text: String): JsonObject {
    val element = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw DeviceException(ErrorCategory.Api, "the host gave no JSON: ${text.trim().take(80)}", e)
    }
    return element as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "the host gave no JSON object: ${text.trim().take(80)}")
}

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.inner(key: String) = this[key] as? JsonObject

private fun JsonObject.array(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun mounted(device: String, point: String, type: String, options: String, size: String, used: String, free: String, use: String) = Item(
    point,
    linkedMapOf(
        "mount-point" to point, "device" to device, "type" to type, "options" to options, "size" to size, "used" to used, "free" to free,
        "use" to use.takeIf { it != "-" }.orEmpty(), "read-only" to "${"ro" in options.split(',')}",
    ),
)

// FUSE without a size shows something else, as lxcfs its files of /proc; of two mounts on one point the one on top counts
private fun List<Item>.shown() = filter { it["type"] !in PSEUDO && !(it["type"].orEmpty().startsWith("fuse.") && it["size"].orEmpty().ifEmpty { "0" } == "0") }
    .associateBy { it.id }.values.toList()

internal fun parseFindmnt(text: String): List<Item> = parsed(text).array("filesystems").map { fs ->
    mounted(fs.text("source"), fs.text("target"), fs.text("fstype"), fs.text("options"), fs.text("size"), fs.text("used"), fs.text("avail"), fs.text("use%"))
}.shown()

// /proc/mounts has the mounts with their options, df -P -k their sizes in KiB by the mount point
internal fun parseMounts(mounts: String, df: String): List<Item> {
    val sizes = df.lines().drop(1).map { it.trim().split(BLANKS) }.filter { it.size >= 6 }.associateBy { it.drop(5).joinToString(" ") }
    return mounts.lines().map { it.split(' ') }.filter { it.size >= 4 }.map { field ->
        val point = unescape(field[1])
        val size = sizes[point].orEmpty()
        fun kib(index: Int) = size.getOrNull(index)?.toLongOrNull()?.times(1024)?.toString().orEmpty()
        mounted(unescape(field[0]), point, field[2], field[3], kib(1), kib(2), kib(3), size.getOrNull(4).orEmpty())
    }.shown()
}

// a device under several parents, as a thin pool under its data and its metadata or a RAID under its members, comes once
// under each of them
internal fun parseLsblk(text: String): List<Item> {
    val rows = linkedMapOf<String, Item>()
    fun walk(device: JsonObject, parent: String) {
        val name = device.text("name")
        val path = device.text("path").ifEmpty { "/dev/$name" }
        val known = rows[path]
        if (known != null) {
            val parents = (known["parent"].orEmpty().split(", ") + parent).filter { it.isNotEmpty() }.distinct()
            rows[path] = Item(path, known.values + ("parent" to parents.joinToString(", ")))
            return
        }
        rows[path] = Item(
            path,
            linkedMapOf(
                "name" to name, "path" to path, "type" to device.text("type"), "size" to device.text("size"), "model" to device.text("model"),
                "serial" to device.text("serial"), "transport" to device.text("tran"), "rotational" to flag(device.text("rota")), "removable" to flag(device.text("rm")),
                "read-only" to flag(device.text("ro")), "file-system" to device.text("fstype"), "label" to device.text("label"), "uuid" to device.text("uuid"),
                "mount-point" to device.text("mountpoint"), "parent" to parent,
            ),
        )
        device.array("children").forEach { walk(it, name) }
    }
    parsed(text).array("blockdevices").forEach { walk(it, "") }
    return rows.values.toList()
}

// major minor #blocks name: the disks are what /sys/block has, a partition is of the disk its name starts with; major 1
// is the RAM disks, which lsblk leaves out as well
internal fun parsePartitions(partitions: String, block: String, mounts: String): List<Item> {
    val disks = block.lines().map { it.split('|') }.filter { it.size >= 5 }.associateBy { it[0] }
    val points = mounts.lines().map { it.split(' ') }.filter { it.size >= 2 }.associate { unescape(it[0]) to unescape(it[1]) }
    return partitions.lines().map { it.trim().split(BLANKS) }.filter { it.size >= 4 && it[0] != "1" && it[2].toLongOrNull() != null }.map { row ->
        val name = row[3]
        val disk = disks[name]
        val path = "/dev/$name"
        Item(
            path,
            linkedMapOf(
                "name" to name, "path" to path,
                "type" to when {
                    disk == null -> "part"
                    name.startsWith("loop") -> "loop"
                    name.startsWith("dm-") -> "dm"
                    name.startsWith("md") -> "raid"
                    else -> "disk"
                },
                "size" to "${row[2].toLong() * 1024}", "model" to disk?.get(1)?.trim().orEmpty(), "rotational" to flag(disk?.get(2).orEmpty()),
                "removable" to flag(disk?.get(3).orEmpty()), "read-only" to flag(disk?.get(4).orEmpty()), "mount-point" to points[path].orEmpty(),
                "parent" to if (disk != null) "" else disks.keys.filter { name.startsWith(it) }.maxByOrNull { it.length }.orEmpty(),
            ),
        )
    }
}

private class FstabEntry(val id: String, val point: String, val line: Int, val fields: List<String>) {
    fun values() = FSTAB.mapIndexed { index, name -> name to unescape(fields.getOrElse(index) { "" }) }.toMap()
}

// the lines that are entries, by the mount point, with #2 and on for one that comes again, as none of swap does
private fun entries(lines: List<String>): List<FstabEntry> {
    val seen = mutableMapOf<String, Int>()
    return lines.mapIndexedNotNull { index, line ->
        val fields = line.trim().split(BLANKS)
        if (fields[0].isEmpty() || fields[0].startsWith("#")) return@mapIndexedNotNull null
        val point = unescape(fields.getOrElse(1) { "" })
        val count = (seen[point] ?: 0) + 1
        seen[point] = count
        FstabEntry(if (count == 1) point else "$point#$count", point, index, fields)
    }
}

internal fun parseFstab(text: String, mounted: Set<String>): List<Item> = entries(text.lines()).map { entry ->
    val values = entry.values()
    Item(entry.id, if (values["type"] == "swap") values else values + ("mounted" to "${entry.point in mounted}"))
}

private fun fstabLine(values: Map<String, String>): String {
    val device = values["device"].orEmpty().also { require(it.isNotEmpty() && !it.startsWith("#"), "device", it) }
    val point = values["mount-point"].orEmpty().also { require(it.startsWith("/") || it in SWAP, "mount-point", it) }
    val type = values["type"].orEmpty().ifEmpty { "auto" }.also { require(TYPE.matches(it), "type", it) }
    val options = values["options"].orEmpty().ifEmpty { "defaults" }.also { require(it.none(Char::isWhitespace), "options", it) }
    val dump = values["dump"].orEmpty().ifEmpty { "0" }.also { require(it.all { c -> c in '0'..'9' }, "dump", it) }
    val pass = values["pass"].orEmpty().ifEmpty { "0" }.also { require(it.all { c -> c in '0'..'9' }, "pass", it) }
    return listOf(escape(device), escape(point), type, options, dump, pass).joinToString("\t")
}

// /mnt/data
//    [E] unreachable on boot required source: UUID=...
internal fun parseVerify(text: String): Set<String> = buildSet {
    var target = ""
    for (line in text.lines()) {
        val body = line.trim()
        if (!line.startsWith(" ")) target = body else if (body.startsWith("[E] ")) add("$target: ${body.removePrefix("[E] ")}")
    }
}

// {"devices": [{"name": "/dev/sda", "type": "sat"}]}, or /dev/sda -d sat # /dev/sda [SAT], ATA device
internal fun parseSmartScan(text: String): List<Pair<String, String>> = if (text.trimStart().startsWith("{")) {
    parsed(text).array("devices").map { it.text("name") to it.text("type") }
} else {
    text.lines().filter { it.startsWith("/") }.map { it.substringBefore(' ') to it.substringAfter(" -d ", "").substringBefore(' ') }
}

internal fun parseSmart(name: String, type: String, text: String): Item = Item(
    if (type.isEmpty()) name else "$name -d $type",
    linkedMapOf("device" to name, "type" to type) + if (text.trimStart().startsWith("{")) smartJson(parsed(text)) else smartText(text),
)

private fun smartJson(drive: JsonObject): Map<String, String> {
    val attributes = drive.inner("ata_smart_attributes")?.array("table").orEmpty().associateBy { it.text("id") }
    fun raw(id: String) = attributes[id]?.inner("raw")?.text("value").orEmpty()
    val nvme = drive.inner("nvme_smart_health_information_log")
    return linkedMapOf(
        "model" to drive.text("model_name").ifEmpty { drive.text("scsi_model_name") },
        "serial" to drive.text("serial_number"),
        "firmware" to drive.text("firmware_version").ifEmpty { drive.text("scsi_revision") },
        "capacity" to drive.inner("user_capacity")?.text("bytes").orEmpty().ifEmpty { drive.text("nvme_total_capacity") },
        "smart-enabled" to drive.inner("smart_support")?.text("enabled").orEmpty(),
        "health" to when (drive.inner("smart_status")?.text("passed")) {
            "true" -> "PASSED"
            "false" -> "FAILED"
            else -> ""
        },
        "temperature" to drive.inner("temperature")?.text("current").orEmpty(),
        "power-on-hours" to drive.inner("power_on_time")?.text("hours").orEmpty(),
        "power-cycles" to drive.text("power_cycle_count"),
        "reallocated" to raw("5"),
        "pending" to raw("197"),
        "uncorrectable" to raw("198").ifEmpty { nvme?.text("media_errors").orEmpty() },
        "wear" to nvme?.text("percentage_used").orEmpty(),
        "life-left" to if (drive.text("rotation_rate") == "0") LIFE.firstNotNullOfOrNull { attributes[it]?.text("value") }.orEmpty() else "",
        "message" to drive.inner("smartctl")?.array("messages").orEmpty().joinToString("; ") { it.text("string") },
    )
}

// smartctl before 7: lines of a key and its value, and the temperature as the raw value of attribute 194 or a line of
// its own for NVMe and SCSI
private fun smartText(text: String): Map<String, String> {
    val fields = text.lines().filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    val attribute = text.lines().map { it.trim().split(BLANKS) }.firstOrNull { it.size >= 10 && it[0] == "194" }?.get(9)
    return linkedMapOf(
        "model" to (fields["Device Model"] ?: fields["Model Number"] ?: fields["Product"]).orEmpty(),
        "serial" to (fields["Serial Number"] ?: fields["Serial number"]).orEmpty(),
        "firmware" to (fields["Firmware Version"] ?: fields["Revision"]).orEmpty(),
        "health" to (fields["SMART overall-health self-assessment test result"] ?: fields["SMART Health Status"]).orEmpty(),
        "temperature" to (attribute ?: fields["Temperature"] ?: fields["Current Drive Temperature"])?.substringBefore(' ').orEmpty(),
    )
}
