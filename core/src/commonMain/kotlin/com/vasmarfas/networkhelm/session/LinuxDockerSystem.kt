package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

// the engine as a whole: what it is and holds, the room it takes on the disk, what happens in it now, and daemon.json of Docker. A
// change of daemon.json takes effect when dockerd starts again, and that start goes under the guard: a daemon that comes back with
// other rules of the firewall or other networks can cut the host off
internal class DockerSystem(private val ssh: SshSession, private val api: DockerApi, private val root: Root, private val guard: LinuxGuard) {
    fun table(present: suspend () -> Boolean) = HostTable(
        listOf("name", "value"), ::engine, commands = listOf("disk", "daemon", "save-daemon", "group"), run = ::command, streams = mapOf("events" to ::events),
        present = present,
    )

    private suspend fun engine(): List<Item> {
        val version = api.get("/version") as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "/version gave no JSON")
        val info = api.get("/info") as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "/info gave no JSON")
        return engineRows(version, info)
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> = when (command) {
        "disk" -> diskRows(api.get("/system/df") as? JsonObject ?: throw DeviceException(ErrorCategory.Api, "/system/df gave no JSON"))
        "daemon" -> {
            docker()
            listOf(Item(DAEMON, mapOf("file" to DAEMON, "text" to root.exec("cat $DAEMON 2>/dev/null").output)))
        }
        "save-daemon" -> {
            docker()
            saveDaemon(arguments["text"].orEmpty())
            emptyList()
        }
        // a login in the group docker is root on the host through the engine; root itself is told nothing
        "group" -> {
            val (groups, uid) = ssh.exec("id -nG; id -u").output.lines() + listOf("", "")
            listOf(Item("", mapOf("docker" to "${uid.trim() != "0" && "docker" in groups.trim().split(' ')}")))
        }
        else -> throw DeviceException(ErrorCategory.MissingItem, "container/system/$command is not on this host")
    }

    // the file and the service here are those of the system Docker, which a rootless engine is not
    private suspend fun docker() {
        if (api.isPodman()) throw DeviceException(ErrorCategory.Unsupported, "Podman has no daemon.json: it keeps its settings in containers.conf")
        if (api.rootless()) {
            throw DeviceException(
                ErrorCategory.Unsupported,
                "rootless Docker reads ~/.config/docker/daemon.json of the login and restarts with systemctl --user restart docker; the app changes the daemon.json of the system Docker alone",
            )
        }
    }

    // dockerd checks the new file beside the old one; then it takes the place of the old one and Docker starts again, and the guard
    // puts the old one back and starts Docker again if the session or a new login fails after it, or Docker does not come back
    private suspend fun saveDaemon(text: String) {
        val draft = "$DAEMON.networkhelm"
        checked(root.exec("mkdir -p /etc/docker && cat > $draft", text))
        val verdict = root.exec("dockerd --validate --config-file $draft")
        if (verdict.exitStatus != 0) {
            root.exec("rm -f $draft")
            throw DeviceException(ErrorCategory.ArgumentValue, verdict.error.trim().ifEmpty { verdict.output.trim() }.ifEmpty { "exit ${verdict.exitStatus}" })
        }
        guard.guarded(LinuxGuard.Backup("docker", SAVE_DAEMON, RESTORE_DAEMON)) { checked(root.exec("cat $draft > $DAEMON && rm -f $draft && $RESTART && $BACK")) }
    }

    // what happened in the engine over the seconds of period, then what happens from now on, at the time of the host. An empty row parts
    // the two, so a window tells a quiet past from one still read. Docker ends the past at until; Podman reads it without following, as
    // with until it may stop reading its file of events early, and an event both reads give comes once
    private fun events(arguments: Map<String, String>): Flow<Item> = flow {
        val offset = api.offset()
        val now = ssh.exec("date +%s").output.trim().toLongOrNull() ?: throw DeviceException(ErrorCategory.Api, "the host did not tell its time")
        val period = arguments["period"]?.toLongOrNull() ?: 0L
        var last = 0L
        if (period > 0) {
            val end = if (api.isPodman()) "stream=false" else "until=$now"
            api.lines("GET", "/events?since=${now - period}&$end").collect { line ->
                val event = dockerEvent(line)
                last = maxOf(last, event["time-nano"]?.toLongOrNull() ?: 0L)
                emit(timed(event, offset))
            }
        }
        emit(Item("", emptyMap()))
        api.lines("GET", "/events?since=$now").collect { line ->
            val event = dockerEvent(line)
            if ((event["time-nano"]?.toLongOrNull() ?: Long.MAX_VALUE) > last) emit(timed(event, offset))
        }
    }

    private fun timed(event: Map<String, String>, offset: String) = Item("", event + ("time" to event["time"]?.toLongOrNull()?.let { local(it, offset) }.orEmpty()))
}

// what docker version and docker info tell of the engine
internal fun engineRows(version: JsonObject, info: JsonObject): List<Item> = listOf(
    "version" to version.text("Version"),
    "api-version" to version.text("ApiVersion"),
    "os" to info.text("OperatingSystem"),
    "kernel" to info.text("KernelVersion"),
    "architecture" to version.text("Arch"),
    "storage-driver" to info.text("Driver"),
    "cgroup" to info.text("CgroupVersion"),
    "cpus" to info.text("NCPU"),
    "memory" to info.text("MemTotal"),
    "containers" to info.text("Containers"),
    "running" to info.text("ContainersRunning"),
    "paused" to info.text("ContainersPaused"),
    "stopped" to info.text("ContainersStopped"),
    "images" to info.text("Images"),
    "root-dir" to info.text("DockerRootDir"),
    "swarm" to info.inner("Swarm")?.text("LocalNodeState").orEmpty(),
    "rootless" to "${info.texts("SecurityOptions").any { "name=rootless" in it }}",
    "host" to info.text("Name"),
).map { (name, value) -> Item(name, mapOf("name" to name, "value" to value)) }

// docker system df: how much each kind takes and how much of it nothing uses, as prune would take it
internal fun diskRows(df: JsonObject): List<Item> {
    val images = df.objects("Images")
    val containers = df.objects("Containers")
    val volumes = df.objects("Volumes")
    val cache = df.objects("BuildCache")
    fun used(image: JsonObject) = (image.long("Containers") ?: 0L) > 0L
    fun own(image: JsonObject): Long = (image.long("Size") ?: 0L) - (image.long("SharedSize") ?: 0L).coerceAtLeast(0L)
    fun size(volume: JsonObject): Long = volume.inner("UsageData")?.long("Size")?.coerceAtLeast(0L) ?: 0L
    fun mounted(volume: JsonObject) = (volume.inner("UsageData")?.long("RefCount") ?: 0L) > 0L
    fun written(container: JsonObject): Long = container.long("SizeRw") ?: 0L
    fun cached(entry: JsonObject): Long = entry.long("Size") ?: 0L
    fun row(kind: String, count: Int, active: Int, size: Long, free: Long) =
        Item(kind, linkedMapOf("kind" to kind, "count" to "$count", "active" to "$active", "size" to "$size", "reclaimable" to "$free"))
    return listOf(
        row("images", images.size, images.count(::used), df.long("LayersSize") ?: images.sumOf(::own), images.filterNot(::used).sumOf(::own)),
        row(
            "containers", containers.size, containers.count { it.text("State") == "running" }, containers.sumOf(::written),
            containers.filter { it.text("State") != "running" }.sumOf(::written),
        ),
        row("volumes", volumes.size, volumes.count(::mounted), volumes.sumOf(::size), volumes.filterNot(::mounted).sumOf(::size)),
        row("build-cache", cache.size, cache.count { it.flag("InUse") }, cache.sumOf(::cached), cache.filterNot { it.flag("InUse") }.sumOf(::cached)),
    )
}

private const val DAEMON = "/etc/docker/daemon.json"

// systemd, or OpenRC on Alpine
private const val RESTART = "if command -v systemctl >/dev/null 2>&1; then systemctl restart docker; else rc-service docker restart; fi"

// Docker answers again within half a minute, else the change goes back
private const val BACK = "{ i=0; until docker info >/dev/null 2>&1; do i=\$((i+1)); [ \$i -ge 30 ] && exit 1; sleep 1; done; }"

private const val SAVE_DAEMON = "[ ! -f $DAEMON ] || cp -p $DAEMON \"\$B/daemon.json\""
private const val RESTORE_DAEMON = "if [ -f \"\$1/daemon.json\" ]; then cp -p \"\$1/daemon.json\" $DAEMON; else rm -f $DAEMON; fi; $RESTART"
