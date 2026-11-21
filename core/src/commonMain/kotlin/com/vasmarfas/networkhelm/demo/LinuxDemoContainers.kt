package com.vasmarfas.networkhelm.demo

import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.session.local
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.io.encoding.Base64
import kotlin.time.Clock

private const val CONTAINERS_OFFSET = "+0300"

// what the containers wrote to their logs; the log of a stack shows that of its database
private val containersLines = mapOf(
    "proxy-caddy-1" to listOf(
        "stderr" to "{\"level\":\"info\",\"logger\":\"http\",\"msg\":\"enabling HTTP/3 listener\",\"addr\":\":443\"}",
        "stderr" to "{\"level\":\"info\",\"logger\":\"tls\",\"msg\":\"certificate is valid\",\"subjects\":[\"cloud.example.com\"],\"remaining\":5011200}",
        "stdout" to "{\"level\":\"info\",\"logger\":\"http.log.access\",\"msg\":\"handled request\",\"request\":{\"remote_ip\":\"198.51.100.23\",\"host\":\"cloud.example.com\",\"uri\":\"/status.php\"},\"status\":200}",
        "stdout" to "{\"level\":\"info\",\"logger\":\"http.log.access\",\"msg\":\"handled request\",\"request\":{\"remote_ip\":\"192.168.10.101\",\"host\":\"media.example.com\",\"uri\":\"/web/index.html\"},\"status\":200}",
        "stderr" to "{\"level\":\"warn\",\"logger\":\"http.log.access\",\"msg\":\"handled request\",\"request\":{\"remote_ip\":\"203.0.113.66\",\"host\":\"192.168.10.20\",\"uri\":\"/.env\"},\"status\":404}",
    ),
    "media-jellyfin-1" to listOf(
        "stdout" to "[INF] [1] Main: Jellyfin version: 10.10.3",
        "stdout" to "[INF] [12] Emby.Server.Implementations.ScheduledTasks.TaskManager: Scan Media Library Completed after 0 minute(s) and 41 seconds",
        "stdout" to "[INF] [33] Jellyfin.Api.Helpers.MediaInfoHelper: User policy for anna. EnablePlaybackRemuxing: true EnableVideoPlaybackTranscoding: true",
        "stderr" to "[WRN] [33] MediaBrowser.MediaEncoding.Transcoding.TranscodeManager: FFmpeg exited with code 0 after the client stopped",
    ),
    "cloud-db-1" to listOf(
        "stderr" to "LOG:  database system is ready to accept connections",
        "stderr" to "LOG:  checkpoint starting: time",
        "stderr" to "LOG:  checkpoint complete: wrote 412 buffers (2.5%); 0 WAL file(s) added, 0 removed, 1 recycled",
    ),
)

private val containersGeneric = listOf(
    "stdout" to "Starting up",
    "stdout" to "Listening on 0.0.0.0",
    "stdout" to "Health check passed",
)

private val containersLoad: Live = { row, tick ->
    if (row["state"] != "running") {
        emptyMap()
    } else {
        val cpu = wander(row["cpu"]?.toDoubleOrNull()?.times(100)?.toLong() ?: 0L, tick, row.id.hashCode())
        val memory = row["memory"]?.toLongOrNull() ?: 0L
        linuxGrowing("rx-byte" to 2_400L, "tx-byte" to 9_800L, "read-byte" to 1_200L, "write-byte" to 3_100L)(row, tick) + mapOf(
            "cpu" to "${cpu / 100}.${(cpu % 100).toString().padStart(2, '0')}", "memory" to "${memory + wander(memory / 50, tick, row.id.hashCode())}",
        )
    }
}

internal fun DemoBuilder.linuxContainersDemo() {
    list("container-engine", writable = false) {
        row(".id=docker", "name=Docker", "installed=true", "version=Docker version 26.1.5+dfsg1, build a72d7cd", "packages=docker.io")
        row(".id=compose", "name=Docker Compose", "installed=true", "version=Docker Compose version 2.26.1-4", "packages=docker-compose")
        row(".id=podman", "name=Podman", "installed=true", "version=podman version 4.3.1", "packages=podman")
    }
    command("container-engine/install", run = { linuxAnswer("output=Reading package lists...\nThe chosen engines are already installed: nothing to do.") })
    containersListDemo()
    containersImagesDemo()
    containersNetworksDemo()
    containersStacksDemo()
    containersSystemDemo()
}

private fun DemoBuilder.containersListDemo() {
    list("container", live = containersLoad) {
        row(
            ".id=proxy-caddy-1", "name=proxy-caddy-1", "project=proxy", "service=caddy", "image=caddy:2.8", "state=running", "health=", "status=Up 3 weeks",
            "created=2026-10-27 05:24:02", "ip-address=172.20.0.2", "networks=proxy_default,cloud_default,media_default", "ports=0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp, 0.0.0.0:443->443/udp",
            "cpu=0.31", "memory=41943040", "memory-limit=16694808576", "rx-byte=88120433211", "tx-byte=251004331987", "read-byte=41204736", "write-byte=12582912", "pids=11",
            "command=caddy run --config /etc/caddy/Caddyfile --adapter caddyfile", "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false",
            "healthy=false", "unhealthy=false", "id=7c1e0a9f3b42d6e8a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3", "restart=unless-stopped", "hostname=caddy",
            "env=TZ=Europe/Moscow", "mounts=/home/admin/compose/proxy/Caddyfile:/etc/caddy/Caddyfile:ro, proxy_data:/data", "network=proxy_default", "started=2026-10-27 05:24:03",
            "restart-count=0", "exit-code=0",
        )
        row(
            ".id=media-jellyfin-1", "name=media-jellyfin-1", "project=media", "service=jellyfin", "image=jellyfin/jellyfin:10.10.3", "state=running", "health=healthy",
            "status=Up 3 weeks (healthy)", "created=2026-10-27 05:24:05", "ip-address=172.19.0.2", "networks=media_default", "ports=0.0.0.0:8096->8096/tcp",
            "cpu=4.20", "memory=612401152", "memory-limit=4294967296", "rx-byte=9120334871", "tx-byte=210448871022", "read-byte=880412033211", "write-byte=4120334871", "pids=38",
            "command=/jellyfin/jellyfin", "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false", "healthy=true", "unhealthy=false",
            "id=1f3a5c7e9b0d2f4a6c8e0a1b3c5d7e9f1a3b5c7d9e1f3a5b7c9d1e3f5a7b9c1d", "restart=unless-stopped", "hostname=jellyfin", "env=TZ=Europe/Moscow, JELLYFIN_PublishedServerUrl=https://media.example.com",
            "mounts=media_config:/config, media_cache:/cache, /srv/data/media:/media:ro", "network=media_default", "started=2026-10-27 05:24:06",
            "restart-count=0", "exit-code=0",
        )
        row(
            ".id=cloud-app-1", "name=cloud-app-1", "project=cloud", "service=app", "image=nextcloud:30-apache", "state=running", "health=", "status=Up 6 days",
            "created=2026-11-13 21:40:11", "ip-address=172.18.0.3", "networks=cloud_default", "ports=80/tcp", "cpu=0.85", "memory=331350016", "memory-limit=16694808576",
            "rx-byte=15520117236", "tx-byte=41290033117", "read-byte=2210448871", "write-byte=880412033", "pids=24", "command=/entrypoint.sh apache2-foreground",
            "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false", "healthy=false", "unhealthy=false",
            "id=2b4d6f8a0c2e4a6c8e0b2d4f6a8c0e2a4c6e8b0d2f4a6c8e0a2c4e6b8d0f2a4c", "restart=unless-stopped", "hostname=cloud", "env=POSTGRES_HOST=db, REDIS_HOST=redis, NEXTCLOUD_TRUSTED_DOMAINS=cloud.example.com",
            "secret-env=POSTGRES_PASSWORD", "mounts=cloud_nextcloud:/var/www/html, /srv/data/cloud:/var/www/html/data", "network=cloud_default", "started=2026-11-13 21:40:12",
            "restart-count=0", "exit-code=0",
        )
        row(
            ".id=cloud-db-1", "name=cloud-db-1", "project=cloud", "service=db", "image=postgres:16-alpine", "state=running", "health=healthy", "status=Up 6 days (healthy)",
            "created=2026-11-13 21:40:09", "ip-address=172.18.0.4", "networks=cloud_default", "ports=5432/tcp", "cpu=0.12", "memory=301989888", "memory-limit=16694808576",
            "rx-byte=1204331870", "tx-byte=4120334871", "read-byte=312044871", "write-byte=6120334871", "pids=9", "command=docker-entrypoint.sh postgres",
            "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false", "healthy=true", "unhealthy=false",
            "id=3c5e7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e", "restart=unless-stopped", "hostname=db", "env=POSTGRES_DB=nextcloud, POSTGRES_USER=nextcloud",
            "secret-env=POSTGRES_PASSWORD", "mounts=cloud_db-data:/var/lib/postgresql/data", "network=cloud_default", "started=2026-11-13 21:40:10", "restart-count=0", "exit-code=0",
        )
        row(
            ".id=cloud-redis-1", "name=cloud-redis-1", "project=cloud", "service=redis", "image=redis:7-alpine", "state=running", "health=", "status=Up 6 days",
            "created=2026-11-13 21:40:08", "ip-address=172.18.0.5", "networks=cloud_default", "ports=6379/tcp", "cpu=0.18", "memory=12582912", "memory-limit=16694808576",
            "rx-byte=412339807", "tx-byte=880412033", "read-byte=1048576", "write-byte=20971520", "pids=6", "command=docker-entrypoint.sh redis-server",
            "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false", "healthy=false", "unhealthy=false",
            "id=4d6f8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f", "restart=unless-stopped", "hostname=redis", "network=cloud_default", "restart-count=0", "exit-code=0",
        )
        row(
            ".id=uptime-kuma", "name=uptime-kuma", "project=", "service=", "image=louislam/uptime-kuma:1", "state=running", "health=unhealthy", "status=Up 2 hours (unhealthy)",
            "created=2026-09-30 18:02:44", "ip-address=172.17.0.2", "networks=bridge", "ports=0.0.0.0:3001->3001/tcp", "cpu=1.40", "memory=134217728", "memory-limit=16694808576",
            "rx-byte=301228840", "tx-byte=120334871", "read-byte=88120433", "write-byte=412339807", "pids=14", "command=/usr/bin/dumb-init -- extra/entrypoint.sh node server/server.js",
            "running=true", "paused=false", "restarting=false", "stopped=false", "failed=false", "healthy=false", "unhealthy=true",
            "id=5e7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e7a", "restart=always", "hostname=uptime-kuma", "mounts=uptime-kuma:/app/data", "network=bridge",
            "restart-count=2", "exit-code=0",
        )
        row(
            ".id=watchtower", "name=watchtower", "project=", "service=", "image=containrrr/watchtower:latest", "state=paused", "health=", "status=Up 3 weeks (Paused)",
            "created=2026-08-02 11:15:31", "ip-address=172.17.0.3", "networks=bridge", "ports=8080/tcp", "cpu=0.00", "memory=9437184", "memory-limit=16694808576",
            "rx-byte=88120433", "tx-byte=4120334", "read-byte=0", "write-byte=0", "pids=8", "command=/watchtower --schedule \"0 0 4 * * *\"",
            "running=false", "paused=true", "restarting=false", "stopped=false", "failed=false", "healthy=false", "unhealthy=false",
            "id=6f8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f8b", "restart=unless-stopped", "mounts=/var/run/docker.sock:/var/run/docker.sock", "network=bridge",
        )
        row(
            ".id=backup-test", "name=backup-test", "project=", "service=", "image=alpine:3.20", "state=exited", "health=", "status=Exited (0) 2 days ago",
            "created=2026-11-17 03:00:02", "ip-address=", "networks=bridge", "ports=", "cpu=", "memory=", "memory-limit=", "rx-byte=", "tx-byte=", "read-byte=", "write-byte=", "pids=",
            "command=sh -c 'tar tzf /backup/latest.tar.gz >/dev/null && echo ok'", "running=false", "paused=false", "restarting=false", "stopped=true", "failed=false",
            "healthy=false", "unhealthy=false", "id=7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e7a9c1e3a5c7e9a1c3e5a7c9e1a3c5e7a9c", "restart=no", "mounts=/mnt/backup:/backup:ro",
            "network=bridge", "restart-count=0", "exit-code=0",
        )
        row(
            ".id=old-grafana", "name=old-grafana", "project=", "service=", "image=grafana/grafana:10.4.2", "state=exited", "health=", "status=Exited (1) 5 weeks ago",
            "created=2026-06-11 19:30:12", "ip-address=", "networks=bridge", "ports=", "cpu=", "memory=", "memory-limit=", "rx-byte=", "tx-byte=", "read-byte=", "write-byte=", "pids=",
            "command=/run.sh", "running=false", "paused=false", "restarting=false", "stopped=false", "failed=true", "healthy=false", "unhealthy=false",
            "id=8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f8b0d2f4b6d8f0b2d4f6b8d0f2b4d6f8b0d", "restart=no", "network=bridge", "restart-count=0", "exit-code=1",
        )
    }
    command("container/log", run = { containersLog(it, containersLines[it["id"].orEmpty()] ?: containersGeneric, null) })
    command("container/stats", run = ::containersStats)
    command("container/top", run = {
        flowOf(
            Item("3120", mapOf("uid" to "root", "pid" to "3120", "ppid" to "3101", "c" to "4", "stime" to "Oct27", "tty" to "?", "time" to "21:44:10", "cmd" to "/jellyfin/jellyfin")),
            Item("3388", mapOf("uid" to "root", "pid" to "3388", "ppid" to "3120", "c" to "0", "stime" to "12:01", "tty" to "?", "time" to "00:00:04", "cmd" to "/usr/lib/jellyfin-ffmpeg/ffmpeg -analyzeduration 200M -i file:/media/demo.mkv")),
        )
    })
    command("container/changes", run = {
        flowOf(
            Item("/etc", mapOf("path" to "/etc", "kind" to "changed")),
            Item("/etc/hosts.allow", mapOf("path" to "/etc/hosts.allow", "kind" to "added")),
            Item("/tmp/install.lock", mapOf("path" to "/tmp/install.lock", "kind" to "deleted")),
            Item("/var/log", mapOf("path" to "/var/log", "kind" to "changed")),
        )
    })
    command("container/inspect", run = {
        val id = it["id"].orEmpty()
        linuxAnswer(
            "json={\n  \"Id\": \"${id.hashCode().toUInt().toString(16).padStart(8, '0').repeat(8)}\",\n  \"Name\": \"/$id\",\n  \"State\": {\n    \"Status\": \"running\",\n" +
                "    \"Running\": true,\n    \"Pid\": 3120,\n    \"StartedAt\": \"2026-10-27T02:24:06.412Z\"\n  },\n  \"Config\": {\n    \"Hostname\": \"$id\",\n" +
                "    \"Env\": [\n      \"TZ=Europe/Moscow\",\n      \"POSTGRES_PASSWORD=********\"\n    ]\n  },\n  \"HostConfig\": {\n    \"RestartPolicy\": { \"Name\": \"unless-stopped\" }\n  }\n}",
        )
    })
    command("container/ls", run = { arguments ->
        val folder = arguments["path"].orEmpty().trimEnd('/').ifEmpty { "/" }
        val entries = if (folder == "/") {
            listOf("bin" to "link", "config" to "directory", "etc" to "directory", "media" to "directory", "tmp" to "directory", "usr" to "directory", "var" to "directory")
        } else {
            listOf("cache" to "directory", "data" to "directory", "log" to "directory", "settings.xml" to "file", "system.xml" to "file")
        }
        flowOf(*entries.map { (name, type) ->
            Item(name, mapOf(
                "name" to name, "type" to type, "size" to if (type == "file") "${name.length * 811}" else "", "mode" to if (type == "file") "-rw-r--r--" else "drwxr-xr-x",
                "owner" to "root", "group" to "root", "modified" to "Nov 19 09:12", "target" to if (type == "link") "usr/bin" else "",
            ))
        }.toTypedArray())
    })
    command("container/download", run = {
        val path = it["path"].orEmpty()
        linuxAnswer("name=${path.substringAfterLast('/')}", "content=${Base64.encode("demo file $path of a demo container\n".encodeToByteArray())}")
    })
    command("container/unused", run = {
        flowOf(
            Item("backup-test", mapOf("name" to "backup-test", "image" to "alpine:3.20", "state" to "exited", "status" to "Exited (0) 2 days ago", "created" to "2026-11-17 03:00:02")),
            Item("old-grafana", mapOf("name" to "old-grafana", "image" to "grafana/grafana:10.4.2", "state" to "exited", "status" to "Exited (1) 5 weeks ago", "created" to "2026-06-11 19:30:12")),
        )
    })
    command("container/commit", run = { linuxAnswer("image=${it["numbers"].orEmpty().substringBefore(',')}-snapshot:latest") })
    list("container/pod") {
        row(".id=8e1a4c2f0b3d", "name=monitoring", "status=Running", "containers=3", "running=3", "networks=podman", "created=2026-11-02 14:20:51", "pod-id=8e1a4c2f0b3d")
    }
}

// the lines of a log, the last of them some minutes old, and then a new line now and then while the window follows it
private fun containersLog(arguments: Map<String, String>, lines: List<Pair<String, String>>, service: String?): Flow<Item> = flow {
    val stamped = arguments["timestamps"] !in setOf("false", "no")
    val start = Clock.System.now().epochSeconds - lines.size * 47L
    var index = 0
    while (true) {
        val (stream, message) = lines[index % lines.size]
        val at = if (index < lines.size) start + index * 47L else Clock.System.now().epochSeconds
        val time = "${local(at, CONTAINERS_OFFSET)}.${(index * 137).mod(1000).toString().padStart(3, '0')}"
        emit(Item("", listOfNotNull("time" to time, "stream" to stream, "message" to message, service?.let { "service" to it }).filter { stamped || it.first != "time" }.toMap()))
        index++
        if (index >= lines.size) delay(6000)
    }
}

private fun containersStats(arguments: Map<String, String>): Flow<Item> = flow {
    val salt = arguments["id"].hashCode()
    var tick = 0
    while (true) {
        emit(
            Item(
                "",
                mapOf(
                    "time" to Clock.System.now().toString(), "cpu" to "${wander(420, tick, salt) / 100.0}", "memory" to "${612_401_152 + wander(8_000_000, tick, salt)}",
                    "memory-limit" to "4294967296", "rx-byte" to "${9_120_334_871 + tick * 21_400L}", "tx-byte" to "${210_448_871_022 + tick * 1_480_000L}",
                    "read-byte" to "${880_412_033_211 + tick * 2_100_000L}", "write-byte" to "${4_120_334_871 + tick * 3_100L}", "pids" to "38",
                ),
            ),
        )
        tick++
        delay(1000)
    }
}

private fun DemoBuilder.containersImagesDemo() {
    list("container/image") {
        row(".id=caddy:2.8", "name=caddy:2.8", "repository=caddy", "tag=2.8", "image-id=1b5ae7c6d0f2", "created=2026-10-08 22:13:40", "size=48922112", "containers=1", "digest=sha256:0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9")
        row(".id=jellyfin/jellyfin:10.10.3", "name=jellyfin/jellyfin:10.10.3", "repository=jellyfin/jellyfin", "tag=10.10.3", "image-id=8a2d9c71e4b3", "created=2026-10-12 06:40:11", "size=1295123456", "containers=1", "digest=sha256:1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a")
        row(".id=nextcloud:30-apache", "name=nextcloud:30-apache", "repository=nextcloud", "tag=30-apache", "image-id=c34f0e8b2a19", "created=2026-11-05 02:15:09", "size=1253048320", "containers=1", "digest=sha256:2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b")
        row(".id=postgres:16-alpine", "name=postgres:16-alpine", "repository=postgres", "tag=16-alpine", "image-id=d0e7b1f93c52", "created=2026-11-01 19:02:33", "size=274989056", "containers=1", "digest=sha256:3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c")
        row(".id=redis:7-alpine", "name=redis:7-alpine", "repository=redis", "tag=7-alpine", "image-id=e9a2c0d4f871", "created=2026-10-30 04:41:56", "size=41271296", "containers=1", "digest=sha256:4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d")
        row(".id=louislam/uptime-kuma:1", "name=louislam/uptime-kuma:1", "repository=louislam/uptime-kuma", "tag=1", "image-id=f13b8e2c9d06", "created=2026-07-22 13:08:47", "size=443711488", "containers=1", "digest=sha256:5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e")
        row(".id=containrrr/watchtower:latest", "name=containrrr/watchtower:latest", "repository=containrrr/watchtower", "tag=latest", "image-id=0c9d2e4f6a81", "created=2026-03-14 10:21:09", "size=14732288", "containers=1", "digest=sha256:60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f")
        row(".id=alpine:3.20", "name=alpine:3.20", "repository=alpine", "tag=3.20", "image-id=91ef0af61f39", "created=2026-09-06 21:47:12", "size=7798784", "containers=1", "digest=sha256:718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60")
        row(".id=grafana/grafana:10.4.2", "name=grafana/grafana:10.4.2", "repository=grafana/grafana", "tag=10.4.2", "image-id=2f6c4a8e1b07", "created=2026-04-10 15:33:20", "size=432013312", "containers=1", "digest=sha256:8293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071")
        row(".id=3e7f1a0c5b28", "name=3e7f1a0c5b28", "repository=", "tag=", "image-id=3e7f1a0c5b28", "created=2026-10-12 06:40:11", "size=1290012672", "containers=0", "digest=")
    }
    command("container/image/history", run = {
        flowOf(
            Item("sha256:8a2d9c71e4b3", mapOf("created" to "2026-10-12 06:40:11", "created-by" to "ENTRYPOINT [\"./jellyfin/jellyfin\" \"--datadir\" \"/config\" \"--cachedir\" \"/cache\"]", "size" to "0", "tags" to it["id"].orEmpty(), "comment" to "buildkit.dockerfile.v0")),
            Item("<missing>/2", mapOf("created" to "2026-10-12 06:39:58", "created-by" to "COPY /jellyfin /jellyfin # buildkit", "size" to "231211008", "tags" to "", "comment" to "buildkit.dockerfile.v0")),
            Item("<missing>/1", mapOf("created" to "2026-10-12 06:37:40", "created-by" to "RUN /bin/sh -c apt-get update && apt-get install --no-install-recommends -y jellyfin-ffmpeg7 # buildkit", "size" to "934281216", "tags" to "", "comment" to "buildkit.dockerfile.v0")),
            Item("<missing>/0", mapOf("created" to "2026-10-08 00:00:00", "created-by" to "# debian.sh --arch 'amd64' out/ 'bookworm' '@1728000000'", "size" to "129630232", "tags" to "", "comment" to "debuerreotype 0.15")),
        )
    })
    command("container/image/inspect", run = {
        linuxAnswer("json={\n  \"Id\": \"sha256:8a2d9c71e4b3\",\n  \"RepoTags\": [ \"${it["id"]}\" ],\n  \"Architecture\": \"amd64\",\n  \"Os\": \"linux\",\n  \"Size\": 1295123456,\n  \"Config\": {\n    \"ExposedPorts\": { \"8096/tcp\": {} },\n    \"Volumes\": { \"/cache\": {}, \"/config\": {} }\n  }\n}")
    })
    command("container/image/pull", run = { containersTransfer(it, push = false) })
    command("container/image/push", run = { containersTransfer(it, push = true) })
    command("container/image/save", run = { linuxAnswer("file=${it["file"]}") })
    command("container/image/load", run = { linuxAnswer("line=Loaded image: ${it["file"].orEmpty().substringAfterLast('/').substringBefore(".tar")}:latest") })
    command("container/image/login", run = { linuxAnswer("line=Login Succeeded") })
    command("container/image/unused", run = {
        flowOf(
            *listOfNotNull(
                Item("3e7f1a0c5b28", mapOf("name" to "3e7f1a0c5b28", "repository" to "", "tag" to "", "size" to "1290012672", "created" to "2026-10-12 06:40:11")),
                Item("grafana/grafana:10.4.2", mapOf("name" to "grafana/grafana:10.4.2", "repository" to "grafana/grafana", "tag" to "10.4.2", "size" to "432013312", "created" to "2026-04-10 15:33:20"))
                    .takeIf { _ -> it["all"] == "true" },
            ).toTypedArray(),
        )
    })
}

// the steps of a pull or a push as the engine reports them, layer by layer
private fun containersTransfer(arguments: Map<String, String>, push: Boolean): Flow<Item> = flow {
    val image = arguments["image"].orEmpty().ifEmpty { "nginx:1.27" }
    val layers = listOf("a2318d6c47ec" to 29_126_484L, "e0e3f7c4b4f5" to 41_428_117L, "7e3c1d2b9a64" to 628L)
    fun step(id: String, status: String, current: String = "", total: String = "") = Item("", mapOf("id" to id, "status" to status, "current" to current, "total" to total, "units" to ""))
    emit(step(if (push) "" else image.substringAfter(':', "latest"), if (push) "The push refers to repository [${image.substringBefore(':')}]" else "Pulling from ${image.substringBefore(':')}"))
    for ((id, _) in layers) emit(step(id, if (push) "Preparing" else "Pulling fs layer"))
    for ((id, size) in layers) {
        for (part in 1..5) {
            emit(step(id, if (push) "Pushing" else "Downloading", "${size * part / 5}", "$size"))
            delay(250)
        }
        emit(step(id, if (push) "Pushed" else "Pull complete"))
    }
    val digest = "sha256:9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0"
    if (push) {
        emit(step("", "${image.substringAfter(':', "latest")}: digest: $digest size: 1572"))
    } else {
        emit(step("", "Digest: $digest"))
        emit(step("", "Status: Downloaded newer image for $image"))
    }
}

private fun DemoBuilder.containersNetworksDemo() {
    list("container/volume") {
        row(".id=cloud_db-data", "name=cloud_db-data", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/cloud_db-data/_data", "scope=local", "created=2026-04-02 20:11:09", "options=", "labels=com.docker.compose.project=cloud")
        row(".id=cloud_nextcloud", "name=cloud_nextcloud", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/cloud_nextcloud/_data", "scope=local", "created=2026-04-02 20:11:09", "options=", "labels=com.docker.compose.project=cloud")
        row(".id=media_config", "name=media_config", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/media_config/_data", "scope=local", "created=2026-01-15 17:30:44", "options=", "labels=com.docker.compose.project=media")
        row(".id=media_cache", "name=media_cache", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/media_cache/_data", "scope=local", "created=2026-01-15 17:30:44", "options=", "labels=com.docker.compose.project=media")
        row(".id=proxy_data", "name=proxy_data", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/proxy_data/_data", "scope=local", "created=2025-12-20 13:02:51", "options=", "labels=com.docker.compose.project=proxy")
        row(".id=uptime-kuma", "name=uptime-kuma", "driver=local", "containers=1", "anonymous=false", "mountpoint=/var/lib/docker/volumes/uptime-kuma/_data", "scope=local", "created=2026-09-30 18:02:41", "options=", "labels=")
        row(".id=nfs-music", "name=nfs-music", "driver=local", "containers=0", "anonymous=false", "mountpoint=/var/lib/docker/volumes/nfs-music/_data", "scope=local", "created=2026-05-08 09:44:10", "options=type=nfs, o=addr=192.168.10.5,ro, device=:/volume1/music", "labels=")
        row(".id=4c2a9e7f1b3d5a8c0e6f2b4d9a1c3e5f7b0d2a4c6e8f1a3b5d7c9e0f2a4b6c8d", "name=4c2a9e7f1b3d5a8c0e6f2b4d9a1c3e5f7b0d2a4c6e8f1a3b5d7c9e0f2a4b6c8d", "driver=local", "containers=0", "anonymous=true", "mountpoint=/var/lib/docker/volumes/4c2a9e7f1b3d5a8c0e6f2b4d9a1c3e5f7b0d2a4c6e8f1a3b5d7c9e0f2a4b6c8d/_data", "scope=local", "created=2026-06-11 19:30:12", "options=", "labels=")
    }
    command("container/volume/unused", run = {
        flowOf(
            *listOfNotNull(
                Item("4c2a9e7f1b3d5a8c0e6f2b4d9a1c3e5f7b0d2a4c6e8f1a3b5d7c9e0f2a4b6c8d", mapOf("name" to "4c2a9e7f1b3d5a8c0e6f2b4d9a1c3e5f7b0d2a4c6e8f1a3b5d7c9e0f2a4b6c8d", "driver" to "local", "anonymous" to "true")),
                Item("nfs-music", mapOf("name" to "nfs-music", "driver" to "local", "anonymous" to "false")).takeIf { _ -> it["all"] == "true" },
            ).toTypedArray(),
        )
    })
    command("container/volume/browse", run = { linuxAnswer("container=networkhelm-browse-${it["id"].orEmpty().take(12)}") })
    list("container/network") {
        row(".id=bridge", "name=bridge", "driver=bridge", "subnet=172.17.0.0/16", "gateway=172.17.0.1", "ipv6-subnet=", "parent=", "mode=", "internal=false", "containers=4", "scope=local", "created=2026-10-27 05:23:24", "attachable=false", "id=9a8b7c6d5e4f")
        row(".id=host", "name=host", "driver=host", "subnet=", "gateway=", "ipv6-subnet=", "parent=", "mode=", "internal=false", "containers=0", "scope=local", "created=2025-12-20 12:58:03", "attachable=false", "id=1a2b3c4d5e6f")
        row(".id=none", "name=none", "driver=null", "subnet=", "gateway=", "ipv6-subnet=", "parent=", "mode=", "internal=false", "containers=0", "scope=local", "created=2025-12-20 12:58:03", "attachable=false", "id=2b3c4d5e6f7a")
        row(".id=proxy_default", "name=proxy_default", "driver=bridge", "subnet=172.20.0.0/16", "gateway=172.20.0.1", "ipv6-subnet=", "parent=", "mode=", "internal=false", "containers=1", "scope=local", "created=2025-12-20 13:02:51", "attachable=false", "labels=com.docker.compose.project=proxy", "id=3c4d5e6f7a8b")
        row(".id=media_default", "name=media_default", "driver=bridge", "subnet=172.19.0.0/16", "gateway=172.19.0.1", "ipv6-subnet=", "parent=", "mode=", "internal=false", "containers=2", "scope=local", "created=2026-01-15 17:30:44", "attachable=false", "labels=com.docker.compose.project=media", "id=4d5e6f7a8b9c")
        row(".id=cloud_default", "name=cloud_default", "driver=bridge", "subnet=172.18.0.0/16", "gateway=172.18.0.1", "ipv6-subnet=fd00:18::/64", "parent=", "mode=", "internal=false", "containers=4", "scope=local", "created=2026-04-02 20:11:09", "attachable=false", "ipv6=true", "ipv6-gateway=fd00:18::1", "labels=com.docker.compose.project=cloud", "id=7c1e0a9f3b42")
        row(".id=iot", "name=iot", "driver=macvlan", "subnet=192.168.30.0/24", "gateway=192.168.30.1", "ipv6-subnet=", "parent=vmbr0.30", "mode=bridge", "internal=false", "containers=0", "scope=local", "created=2026-07-04 16:20:37", "attachable=true", "ip-range=192.168.30.192/27", "id=5e6f7a8b9c0d")
    }
    command("container/network/containers", run = {
        flowOf(
            Item("cloud-app-1", mapOf("name" to "cloud-app-1", "state" to "running", "ipv4-address" to "172.18.0.3/16", "ipv6-address" to "fd00:18::3/64", "aliases" to "app")),
            Item("cloud-db-1", mapOf("name" to "cloud-db-1", "state" to "running", "ipv4-address" to "172.18.0.4/16", "ipv6-address" to "fd00:18::4/64", "aliases" to "db")),
            Item("cloud-redis-1", mapOf("name" to "cloud-redis-1", "state" to "running", "ipv4-address" to "172.18.0.5/16", "ipv6-address" to "fd00:18::5/64", "aliases" to "redis")),
            Item("proxy-caddy-1", mapOf("name" to "proxy-caddy-1", "state" to "running", "ipv4-address" to "172.18.0.6/16", "ipv6-address" to "", "aliases" to "")),
        )
    })
    command("container/network/inspect", run = {
        linuxAnswer("json={\n  \"Name\": \"${it["id"]}\",\n  \"Driver\": \"bridge\",\n  \"IPAM\": {\n    \"Config\": [ { \"Subnet\": \"172.18.0.0/16\", \"Gateway\": \"172.18.0.1\" } ]\n  },\n  \"Internal\": false,\n  \"Attachable\": false\n}")
    })
    command("container/network/unused", run = { flowOf(Item("iot", mapOf("name" to "iot", "driver" to "macvlan", "subnet" to "192.168.30.0/24"))) })
}

private fun DemoBuilder.containersStacksDemo() {
    list("container/stack", writable = false) {
        row(".id=proxy", "name=proxy", "status=running(1)", "services=caddy", "containers=1", "dir=/home/admin/compose/proxy", "files=/home/admin/compose/proxy/compose.yaml")
        row(".id=media", "name=media", "status=running(1)", "services=jellyfin", "containers=1", "dir=/home/admin/compose/media", "files=/home/admin/compose/media/compose.yaml")
        row(".id=cloud", "name=cloud", "status=running(3)", "services=app, db, redis", "containers=3", "dir=/home/admin/compose/cloud", "files=/home/admin/compose/cloud/compose.yaml")
    }
    command("container/stack/create", run = {
        flowOf(
            Item("", mapOf("line" to " Network ${it["name"]}_default  Creating")),
            Item("", mapOf("line" to " Network ${it["name"]}_default  Created")),
            Item("", mapOf("line" to " Container ${it["name"]}-app-1  Creating")),
            Item("", mapOf("line" to " Container ${it["name"]}-app-1  Started")),
        )
    })
    command("container/stack/services", run = { arguments ->
        val stack = arguments["id"].orEmpty()
        val services = mapOf(
            "proxy" to listOf(Triple("caddy", "caddy:2.8", "0.0.0.0:80->80/tcp, 0.0.0.0:443->443/tcp")),
            "media" to listOf(Triple("jellyfin", "jellyfin/jellyfin:10.10.3", "0.0.0.0:8096->8096/tcp")),
            "cloud" to listOf(Triple("app", "nextcloud:30-apache", "80/tcp"), Triple("db", "postgres:16-alpine", "5432/tcp"), Triple("redis", "redis:7-alpine", "6379/tcp")),
        )[stack].orEmpty()
        flowOf(*services.map { (service, image, ports) ->
            Item("$stack-$service-1", mapOf("name" to "$stack-$service-1", "service" to service, "image" to image, "state" to "running", "status" to "Up 6 days", "ports" to ports, "project" to stack))
        }.toTypedArray())
    })
    command("container/stack/log", run = { containersLog(it, containersLines.getValue("cloud-db-1"), "db") })
    command("container/stack/config", run = { arguments ->
        val stack = arguments["id"].orEmpty()
        flowOf(
            Item("compose", mapOf(
                "file" to "/home/admin/compose/$stack/compose.yaml",
                "text" to "services:\n  app:\n    image: nextcloud:30-apache\n    restart: unless-stopped\n    environment:\n      POSTGRES_HOST: db\n      REDIS_HOST: redis\n" +
                    "      POSTGRES_PASSWORD: \${DB_PASSWORD}\n    volumes:\n      - nextcloud:/var/www/html\n  db:\n    image: postgres:16-alpine\n    restart: unless-stopped\n" +
                    "    environment:\n      POSTGRES_PASSWORD: \${DB_PASSWORD}\n    volumes:\n      - db-data:/var/lib/postgresql/data\n  redis:\n    image: redis:7-alpine\n" +
                    "volumes:\n  nextcloud:\n  db-data:\n",
            )),
            Item("env", mapOf("file" to "/home/admin/compose/$stack/.env", "text" to "DB_PASSWORD=demo-password\nTZ=Europe/Moscow\n")),
        )
    })
}

private fun DemoBuilder.containersSystemDemo() {
    list("container/system", writable = false) {
        row(".id=version", "name=version", "value=26.1.5+dfsg1")
        row(".id=api-version", "name=api-version", "value=1.45")
        row(".id=os", "name=os", "value=Debian GNU/Linux 12 (bookworm)")
        row(".id=kernel", "name=kernel", "value=6.8.12-4-pve")
        row(".id=architecture", "name=architecture", "value=amd64")
        row(".id=storage-driver", "name=storage-driver", "value=overlay2")
        row(".id=cgroup", "name=cgroup", "value=2")
        row(".id=cpus", "name=cpus", "value=4")
        row(".id=memory", "name=memory", "value=16694808576")
        row(".id=containers", "name=containers", "value=9")
        row(".id=running", "name=running", "value=6")
        row(".id=paused", "name=paused", "value=1")
        row(".id=stopped", "name=stopped", "value=2")
        row(".id=images", "name=images", "value=10")
        row(".id=root-dir", "name=root-dir", "value=/var/lib/docker")
        row(".id=swarm", "name=swarm", "value=inactive")
        row(".id=rootless", "name=rootless", "value=false")
        row(".id=host", "name=host", "value=demo-server")
    }
    command("container/system/disk", run = {
        flowOf(
            Item("images", mapOf("kind" to "images", "count" to "10", "active" to "9", "size" to "5011209216", "reclaimable" to "1722025984")),
            Item("containers", mapOf("kind" to "containers", "count" to "9", "active" to "6", "size" to "88120433", "reclaimable" to "4120334")),
            Item("volumes", mapOf("kind" to "volumes", "count" to "8", "active" to "6", "size" to "21004433112", "reclaimable" to "120334871")),
            Item("build-cache", mapOf("kind" to "build-cache", "count" to "14", "active" to "0", "size" to "412339807", "reclaimable" to "412339807")),
        )
    })
    command("container/system/daemon", run = {
        linuxAnswer("file=/etc/docker/daemon.json", "text={\n  \"log-driver\": \"json-file\",\n  \"log-opts\": { \"max-size\": \"10m\", \"max-file\": \"3\" },\n  \"default-address-pools\": [ { \"base\": \"172.17.0.0/12\", \"size\": 16 } ]\n}\n")
    })
    command("container/system/events", run = { arguments ->
        flow {
            val now = Clock.System.now().epochSeconds
            val period = arguments["period"]?.toLongOrNull() ?: 0L
            val past = listOf(
                Triple(3_400L, "container", "health_status: unhealthy") to ("uptime-kuma" to "louislam/uptime-kuma:1"),
                Triple(2_200L, "image", "pull") to ("nextcloud:30-apache" to ""),
                Triple(1_900L, "container", "exec_start: sh -c pg_isready") to ("cloud-db-1" to "postgres:16-alpine"),
                Triple(600L, "network", "connect") to ("cloud_default" to ""),
                Triple(120L, "container", "exec_start: sh -c pg_isready") to ("cloud-db-1" to "postgres:16-alpine"),
            ).filter { it.first.first <= period }
            for ((what, actor) in past) emit(containersEvent(now - what.first, what.second, what.third, actor.first, actor.second))
            emit(Item("", emptyMap()))
            while (true) {
                delay(9000)
                emit(containersEvent(Clock.System.now().epochSeconds, "container", "exec_start: sh -c pg_isready", "cloud-db-1", "postgres:16-alpine"))
            }
        }
    })
}

private fun containersEvent(at: Long, type: String, action: String, name: String, image: String) = Item(
    "",
    mapOf(
        "time" to local(at, CONTAINERS_OFFSET), "time-nano" to "${at}000000000", "type" to type, "action" to action, "id" to name.hashCode().toUInt().toString(16),
        "name" to name, "image" to image, "attributes" to if (type == "container") "com.docker.compose.project=${name.substringBefore('-')}" else "",
    ),
)
