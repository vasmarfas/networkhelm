package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.vasmarfas.networkhelm.FakeRouter
import com.vasmarfas.networkhelm.app.HiddenValue
import com.vasmarfas.networkhelm.app.MemorySecrets
import com.vasmarfas.networkhelm.app.MemoryStorage
import com.vasmarfas.networkhelm.journal.EditJournal
import com.vasmarfas.networkhelm.journal.JournalingSession
import com.vasmarfas.networkhelm.model.Argument
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.model.MenuSchema
import com.vasmarfas.networkhelm.model.NodeKind
import com.vasmarfas.networkhelm.model.Platform
import com.vasmarfas.networkhelm.model.ValueType
import com.vasmarfas.networkhelm.packs.MenuEntry
import com.vasmarfas.networkhelm.packs.bundledPacks
import com.vasmarfas.networkhelm.ssh.ExecResult
import com.vasmarfas.networkhelm.ssh.SftpClient
import com.vasmarfas.networkhelm.ssh.ShellChannel
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.ByteStream
import com.vasmarfas.networkhelm.ui.table.TableLayout
import com.vasmarfas.networkhelm.ui.table.TableLayouts
import com.vasmarfas.networkhelm.ui.theme.NetworkHelmTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// pictures of the windows of Docker and Podman in build/screenshots, to look at; the data are those the lab gave
@OptIn(ExperimentalTestApi::class)
class LinuxDockerWindowsTest {

    private fun SkikoComposeUiTest.picture(name: String) {
        val directory = File("build/screenshots").also { it.mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }

    // what the screen and the accessibility tree get; InputText keeps the typed value for the field alone, no platform passes it on
    private fun shows(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.EditableText)?.text?.contains(text) == true ||
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text in it.text }
    }

    private fun SkikoComposeUiTest.shown(text: String) =
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    // a tab of a window under the left menu, whose items may carry the same word
    private fun SkikoComposeUiTest.tab(title: String) {
        onAllNodesWithText(title).onLast().performClick()
        waitForIdle()
    }

    private fun item(id: String, vararg values: Pair<String, String>) = Item(id, mapOf(*values))

    private fun tab(router: FakeRouter): RouterTab {
        val storage = MemoryStorage()
        return RouterTab(router, "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage))
    }

    // the windows a table opens from its menu are offered where the session has an SSH login
    private object Ssh : SshSession {
        override suspend fun exec(command: String) = ExecResult("", "", 0)
        override suspend fun process(command: String): ByteStream = error("no process")
        override suspend fun shell(term: String, columns: Int, rows: Int): ShellChannel = error("no shell")
        override suspend fun sftp(): SftpClient = error("no sftp")
        override suspend fun forward(host: String, port: Int): ByteStream = error("no forward")
        override fun close() = Unit
    }

    private fun schema(path: String, commands: List<String>, arguments: List<Argument> = emptyList()) =
        MenuSchema(MenuPath.parse(path), NodeKind.List, emptyList(), listOf("print") + commands, arguments)

    // the left menu shows a path only where its parent names it, as SshHost names the tables of the engine and the overview beside them
    private fun menus(vararg tables: Pair<String, MenuSchema>) = mapOf(
        "" to MenuSchema(MenuPath.Root, NodeKind.Directory, listOf("container", "container-engine"), emptyList(), emptyList()),
        "container" to MenuSchema(
            MenuPath.parse("container"), NodeKind.List, listOf("image", "network", "pod", "stack", "system", "volume"),
            listOf("print", "add", "set", "remove", "start", "stop", "restart"), FORM,
        ),
    ) + tables

    private fun SkikoComposeUiTest.screen(tab: RouterTab) {
        setContent { NetworkHelmTheme(dark = true) { MainScreen(tab, onDisconnect = {}) } }
        shown("Containers")
    }

    private fun SkikoComposeUiTest.window(tab: RouterTab, call: DockerCall, registries: DockerRegistries? = null) {
        setContent { NetworkHelmTheme(dark = true) { CompositionLocalProvider(LocalDockerRegistries provides registries) { LinuxDockerView(tab, call) } } }
    }

    @Test
    fun theWindowOfAContainerByItsTabs() = runDesktopComposeUiTest(1100, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            output = mapOf(
                "container/log" to (0 until 14).flatMap { second ->
                    val time = "2026-11-17T17:22:${(10 + second).toString().padStart(2, '0')}.418265093Z"
                    listOf(item("", "time" to time, "stream" to "stdout", "message" to "out ${1794936130 + second}"), item("", "time" to time, "stream" to "stderr", "message" to "err"))
                },
                "container/stats" to STATS.mapIndexed { second, cpu ->
                    item(
                        "", "time" to "2026-11-17T17:22:${(10 + second).toString().padStart(2, '0')}.000000000Z", "cpu" to cpu, "memory" to "${741376 + second * 8192}",
                        "memory-limit" to "1073741824", "rx-byte" to "${4516 + second * 1400}", "tx-byte" to "${1180 + second * 640}", "read-byte" to "${second * 8192}",
                        "write-byte" to "${second * 4096}", "pids" to "2",
                    )
                },
                "container/top" to listOf(
                    item("727", "uid" to "root", "pid" to "727", "ppid" to "702", "c" to "0", "stime" to "17:21", "tty" to "?", "time" to "00:00:00", "cmd" to "sh -c while true; do echo out \$(date +%s); echo err >&2; sleep 1; done"),
                    item("901", "uid" to "root", "pid" to "901", "ppid" to "727", "c" to "0", "stime" to "17:22", "tty" to "?", "time" to "00:00:00", "cmd" to "sleep 1"),
                ),
                "container/inspect" to listOf(item(WEB, "json" to INSPECT)),
                "container/ls" to ETC,
            ),
            platform = Platform.Ssh,
        )
        val tab = tab(router)
        setContent { NetworkHelmTheme(dark = true) { LinuxDockerContainerView(tab, 1L, "nh-sample-web", "logs", sudo = "") } }
        shown("out 1794936143")
        picture("docker-container-logs")
        tab("Stats")
        shown("CPU, % of one core")
        picture("docker-container-stats")
        tab("Processes")
        shown("sleep 1")
        picture("docker-container-processes")
        tab("Inspect")
        shown("unless-stopped")
        picture("docker-container-inspect")
        tab("Files")
        shown("alpine-release")
        picture("docker-container-files")
    }

    @Test
    fun theFormOfANewContainerByItsTabs() = runDesktopComposeUiTest(1400, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(schemas = menus(), platform = Platform.Ssh, ssh = Ssh)
        val tab = tab(router)
        screen(tab)
        val given = mapOf(
            "name" to "nh-web", "image" to "nginx:1.27", "env" to "TZ=Europe/Moscow\nNGINX_PORT=80", "ports" to "18082:80", "mounts" to "nh-www:/usr/share/nginx/html:ro",
            "network" to "nh-sample-net", "aliases" to "web", "memory" to "256m", "cpus" to "0.5", "restart" to "unless-stopped", "health-cmd" to "wget -q -O /dev/null http://localhost/",
            "health-interval" to "30s", "log-driver" to "local", "log-opt" to "max-size=10m", "cap-drop" to "NET_RAW", "start" to "true",
        )
        tab.open(MenuEntry("Container", "container", arguments = given, item = true))
        shown("nh-web")
        picture("docker-container-form-general")
        for (title in listOf("Env", "Ports", "Mounts", "Network", "Resources", "Restart", "Healthcheck", "Security", "Logging", "Runtime")) {
            tab(title)
            picture("docker-container-form-${title.lowercase()}")
        }
    }

    @Test
    fun theSecretVariablesStandAsDotsInTheFormAndInTheJournal() = runDesktopComposeUiTest(1400, 900) {
        Locale.setDefault(Locale.ENGLISH)
        val journal = EditJournal()
        val storage = MemoryStorage()
        val router = FakeRouter(schemas = menus(), platform = Platform.Ssh, ssh = Ssh)
        val tab = RouterTab(JournalingSession(router, journal, "192.0.2.1"), "192.0.2.1", bundledPacks().values, storage, TableLayouts(storage), journal)
        screen(tab)
        val given = mapOf("name" to "nh-web", "image" to "nginx:1.27", "secret-env" to "DB_PASSWORD=rd-test-word")
        tab.open(MenuEntry("Container", "container", arguments = given, item = true))
        shown("nh-web")
        tab("Env")
        val line = hasAnyAncestor(hasTestTag("field-secret-env"))
        waitUntil(timeoutMillis = 5_000) { onAllNodes(line and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(onAllNodes(line and hasContentDescription("Show the value")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(shows("rd-test-word")).fetchSemanticsNodes().isEmpty(), "hidden in the form")
        picture("docker-container-form-secret")

        runBlocking { tab.session.add(MenuPath.parse("container"), given) }
        tab.openJournal("Journal")
        shown("secret-env=\"$HiddenValue\"")
        assertTrue(onAllNodes(shows("rd-test-word")).fetchSemanticsNodes().isEmpty(), "hidden in the journal")
    }

    @Test
    fun theSavedLoginsGoWithWhatPullsTheImageOfAContainerAndNeverIntoTheJournal() = runBlocking {
        val registries = DockerRegistries(MemorySecrets())
        registries.save(listOf(DockerRegistry("registry.lab:5000", "ci", "rd-test-ci")))
        val router = FakeRouter(platform = Platform.Ssh)
        val journal = EditJournal()
        val session = JournalingSession(RegistryLogins(router, registries), journal, "192.0.2.1")
        val containers = MenuPath.parse("container")
        session.add(containers, mapOf("image" to "registry.lab:5000/nh/app:1"))
        session.set(containers, "nh-app", mapOf("image" to "registry.lab:5000/nh/app:2", "recreate" to "true"))
        session.run(containers, "recreate", mapOf("numbers" to "nh-app")).collect()
        session.run(containers, "stop", mapOf("numbers" to "nh-app")).collect()
        session.add(MenuPath.parse("container/volume"), mapOf("name" to "nh-data"))
        assertTrue("rd-test-ci" in router.added.first()["registries"].orEmpty())
        assertTrue("rd-test-ci" in router.changed.single().second["registries"].orEmpty())
        assertTrue("rd-test-ci" in router.ran.first().second["registries"].orEmpty())
        assertEquals(null, router.ran.last().second["registries"])
        assertEquals(null, router.added.last()["registries"])
        assertTrue(journal.records.value.none { record -> record.after.values.any { "rd-test-ci" in it.orEmpty() } })
    }

    @Test
    fun aPullShowsItsLayersAsTheyMove() = runDesktopComposeUiTest(1100, 600) {
        Locale.setDefault(Locale.ENGLISH)
        // the pull of busybox as the lab recorded it, caught while a layer comes
        val router = FakeRouter(
            output = mapOf(
                "container/image/pull" to listOf(
                    item("", "id" to "latest", "status" to "Pulling from library/busybox"),
                    item("", "id" to "37bb94b0940b", "status" to "Pulling fs layer"),
                    item("", "id" to "46500c34ad0c", "status" to "Download complete"),
                    item("", "id" to "37bb94b0940b", "status" to "Downloading", "current" to "1048576", "total" to "2226163"),
                ),
            ),
            platform = Platform.Ssh,
            endless = true,
        )
        window(tab(router), DockerCall("pull", "busybox:1.37"))
        onNodeWithTag("docker-pull-start").performClick()
        shown("Downloading")
        picture("docker-image-pull")
    }

    @Test
    fun theHistoryOfAnImage() = runDesktopComposeUiTest(1100, 500) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            output = mapOf(
                "container/image/history" to listOf(
                    item("sha256:f59abe656b69", "created" to "2026-11-05 21:00:28", "created-by" to "CMD [\"/bin/sh\"]", "size" to "0"),
                    item("<missing>", "created" to "2026-11-05 21:00:28", "created-by" to "ADD alpine-minirootfs-3.24.4-x86_64.tar.gz / # buildkit", "size" to "8725601"),
                ),
            ),
            platform = Platform.Ssh,
        )
        window(tab(router), DockerCall("image-history", "alpine:latest"))
        shown("alpine-minirootfs")
        picture("docker-image-history")
    }

    @Test
    fun aVolumeShowsItsFilesThroughAContainerOnceAskedTo() = runDesktopComposeUiTest(1100, 600) {
        Locale.setDefault(Locale.ENGLISH)
        val helper = "e736fc8bafbea47449f6baca64e002291d34ea098d7626d38eb623485bd07380"
        val router = FakeRouter(
            output = mapOf(
                "container/volume/browse" to listOf(item(helper, "container" to helper, "path" to "/volume")),
                "container/ls" to listOf(
                    item("html", "name" to "html", "type" to "directory", "size" to "", "modified" to "Nov 17 20:40"),
                    item("uploads", "name" to "uploads", "type" to "directory", "size" to "", "modified" to "Nov 17 20:41"),
                    item("index.html", "name" to "index.html", "type" to "file", "size" to "615", "modified" to "Nov 17 20:40"),
                    item("site.conf", "name" to "site.conf", "type" to "file", "size" to "1024", "modified" to "Nov 17 20:40"),
                ),
            ),
            platform = Platform.Ssh,
        )
        window(tab(router), DockerCall("volume-files", "nh-www"))
        shown("alpine")
        picture("docker-volume-browse-question")
        onNodeWithTag("docker-volume-browse").performClick()
        shown("index.html")
        picture("docker-volume-files")
    }

    @Test
    fun theNetworksAndTheContainersOnOne() = runDesktopComposeUiTest(1400, 800) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            rows = mapOf(
                "container/network" to listOf(
                    network("bridge", "bridge", "172.17.0.0/16", "172.17.0.1", "1"),
                    network("host", "host", "", "", "0"),
                    network("none", "null", "", "", "0"),
                    network("nh-sample-net", "bridge", "172.30.9.0/24", "172.30.9.1", "1"),
                    network("nh-lan", "macvlan", "192.168.88.0/24", "192.168.88.1", "0", parent = "eth0", mode = "bridge"),
                ),
                "container" to CONTAINERS,
            ),
            schemas = menus("container/network" to schema("container/network", listOf("add", "remove", "containers", "inspect", "connect", "disconnect", "unused"))),
            output = mapOf(
                "container/network/containers" to listOf(
                    item(WEB, "name" to "nh-sample-web", "state" to "running", "ipv4-address" to "172.30.9.2/24", "ipv6-address" to "", "mac-address" to "1e:19:12:be:79:c6", "aliases" to "web"),
                    item("41d10d6ac52a", "name" to "nh-worker", "state" to "exited", "ipv4-address" to "", "ipv6-address" to "", "mac-address" to "", "aliases" to ""),
                ),
            ),
            platform = Platform.Ssh,
            ssh = Ssh,
        )
        val tab = tab(router)
        screen(tab)
        tab.open(MenuEntry("Networks", "container/network"))
        shown("nh-lan")
        picture("docker-networks")
        tab.open(MenuEntry("Containers", "container/network", window = "docker-network-containers", fill = mapOf("network" to "nh-sample-net")))
        shown("172.30.9.2/24")
        picture("docker-network-containers")
    }

    @Test
    fun theStacksANewOneFromATemplateAndAStackByItsTabs() = runDesktopComposeUiTest(1400, 850) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            rows = mapOf(
                "container/stack" to listOf(
                    item("nhstack", "name" to "nhstack", "status" to "running(2)", "services" to "web, worker", "containers" to "2", "dir" to "/home/nhlab/stacks/nhstack"),
                    item("wordpress", "name" to "wordpress", "status" to "exited(1), running(1)", "services" to "db, wordpress", "containers" to "2", "dir" to "/home/nhlab/stacks/wordpress"),
                    item("nh-down", "name" to "nh-down", "status" to "down", "services" to "", "containers" to "0", "dir" to "/home/nhlab/stacks/nh-down"),
                ),
            ),
            schemas = menus("container/stack" to schema("container/stack", listOf("up", "down", "down-volumes", "restart", "pull", "build", "create", "services", "config", "save-config"))),
            platform = Platform.Ssh,
            ssh = Ssh,
        )
        val tab = tab(router)
        screen(tab)
        tab.open(MenuEntry("Stacks", "container/stack"))
        shown("wordpress")
        picture("docker-stacks")
        tab.open(MenuEntry("New Stack", "container/stack", window = "docker-stack-new", fill = mapOf("template" to "WordPress")))
        shown("MARIADB_DATABASE")
        picture("docker-stack-new")
    }

    @Test
    fun aStackByItsTabs() = runDesktopComposeUiTest(1100, 650) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            output = mapOf(
                "container/stack/services" to listOf(
                    item("462c9834ffa7", "service" to "web", "name" to "nhstack_web_1", "state" to "running", "status" to "Up 5 minutes", "image" to "docker.io/library/busybox:1.37", "ports" to "0.0.0.0:18081->8080/tcp"),
                    item("41d10d6ac52a", "service" to "worker", "name" to "nhstack_worker_1", "state" to "running", "status" to "Up 5 minutes", "image" to "docker.io/library/busybox:1.37", "ports" to ""),
                ),
                "container/stack/log" to (0 until 6).flatMap { second ->
                    val time = "2026-11-17T20:40:${(14 + 2 * second).toString().padStart(2, '0')}.971550000Z"
                    listOf(
                        item("", "service" to "worker", "time" to time, "stream" to "stdout", "message" to "tick from-env"),
                        item("", "service" to "web", "time" to time, "stream" to "stderr", "message" to "[::ffff:10.89.0.1]:50050: response:404"),
                    )
                },
                "container/stack/config" to listOf(
                    item("compose", "file" to "/home/nhlab/stacks/nhstack/compose.yaml", "text" to STACK),
                    item("env", "file" to "/home/nhlab/stacks/nhstack/.env", "text" to "WEB_PORT=18081\nGREETING=from-env\n"),
                ),
            ),
            platform = Platform.Ssh,
        )
        window(tab(router), DockerCall("stack-services", "nhstack"))
        shown("nhstack_worker_1")
        picture("docker-stack-services")
        tab("Log")
        shown("tick from-env")
        picture("docker-stack-log")
        tab("compose.yaml")
        shown("httpd")
        picture("docker-stack-compose")
        tab(".env")
        shown("WEB_PORT")
        picture("docker-stack-env")
    }

    @Test
    fun theSystemByItsTabsInEnglishAndInRussian() {
        for (language in listOf("en", "ru")) {
            runDesktopComposeUiTest(1100, 650) {
                Locale.setDefault(Locale.forLanguageTag(language))
                try {
                    val router = FakeRouter(
                        rows = mapOf("container/system" to ENGINE.map { (name, value) -> item(name, "name" to name, "value" to value) }, "container" to CONTAINERS),
                        output = mapOf(
                            "container/system/events" to EVENTS + PAST_END,
                            "container/system/disk" to listOf(
                                disk("images", "3", "2", "13076875", "25874"),
                                disk("containers", "4", "4", "12288", "0"),
                                disk("volumes", "2", "1", "1048576", "524288"),
                                disk("build-cache", "0", "0", "0", "0"),
                            ),
                            "container/system/daemon" to listOf(item("/etc/docker/daemon.json", "file" to "/etc/docker/daemon.json", "text" to DAEMON)),
                            "container/unused" to listOf(item("5b5a4e47a53a", "name" to "nh-old", "image" to "alpine:latest", "status" to "Exited (0) 2 days ago")),
                        ),
                        platform = Platform.Ssh,
                    )
                    window(tab(router), DockerCall("system-overview", ""))
                    shown("nh-sample-web")
                    picture("docker-system-overview-$language")
                    tab(if (language == "ru") "События" else "Events")
                    shown("nh-sample-tty")
                    picture("docker-system-events-$language")
                    tab(if (language == "ru") "Диск" else "Disk")
                    shown("12")
                    picture("docker-system-disk-$language")
                    tab("daemon.json")
                    shown("max-size")
                    picture("docker-system-daemon-$language")
                    tab(if (language == "ru") "Очистка" else "Prune")
                    shown("nh-old")
                    picture("docker-system-prune-$language")
                } finally {
                    Locale.setDefault(Locale.ENGLISH)
                }
            }
        }
    }

    @Test
    fun theEventsStartWithTheLastHourAndAQuietOneSaysSo() = runDesktopComposeUiTest(1100, 400) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(output = mapOf("container/system/events" to listOf(PAST_END)), platform = Platform.Ssh)
        window(tab(router), DockerCall("system-events", ""))
        shown("no events of the last hour")
        picture("docker-system-events-quiet")
        onNodeWithText("Last 24 hours").performClick()
        shown("no events of the last 24 hours")
        assertEquals(listOf(mapOf("period" to "3600"), mapOf("period" to "86400")), router.ran.filter { it.first == "container/system/events" }.map { it.second })
    }

    @Test
    fun theEventsKeepWhatTheFilterNames() = runDesktopComposeUiTest(1100, 400) {
        Locale.setDefault(Locale.ENGLISH)
        window(tab(FakeRouter(output = mapOf("container/system/events" to EVENTS + PAST_END), platform = Platform.Ssh)), DockerCall("system-events", ""))
        shown("nh-sample-tty")
        onNodeWithTag("docker-events-filter").performTextInput("bridge")
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("nh-sample-tty").fetchSemanticsNodes().isEmpty() }
        shown("disconnect")
        picture("docker-system-events-filtered")
        onNodeWithTag("docker-events-filter").performTextInput("-nothing")
        shown("No event matches the filter")
    }

    @Test
    fun aNewStackFromATemplateInRussian() = runDesktopComposeUiTest(1100, 750) {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        try {
            window(tab(FakeRouter(platform = Platform.Ssh)), DockerCall("stack-new", "PostgreSQL"))
            shown("POSTGRES_PASSWORD")
            picture("docker-stack-new-ru")
        } finally {
            Locale.setDefault(Locale.ENGLISH)
        }
    }

    @Test
    fun theRegistriesKeptByTheApp() = runDesktopComposeUiTest(1000, 500) {
        Locale.setDefault(Locale.ENGLISH)
        val registries = DockerRegistries(MemorySecrets())
        runBlocking { registries.save(listOf(DockerRegistry("ghcr.io", "nh", "rd-test-token"), DockerRegistry("", "nhlab", "rd-test-password"), DockerRegistry("registry.lab:5000", "ci", "rd-test-ci"))) }
        window(tab(FakeRouter(platform = Platform.Ssh)), DockerCall("registries", ""), registries)
        shown("registry.lab:5000")
        onNodeWithText("ghcr.io").performClick()
        shown("Log In on the Host")
        picture("docker-registries")
        // the chosen login clicked again lets the fields take a new one
        onAllNodesWithText("ghcr.io").onLast().performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Log In on the Host").fetchSemanticsNodes().isEmpty() }
        shown("Add")
    }

    @Test
    fun theGroupDockerIsToldOnceForEachHost() = runDesktopComposeUiTest(900, 120) {
        Locale.setDefault(Locale.ENGLISH)
        val templates = DockerTemplates(MemoryStorage())
        val router = FakeRouter(output = mapOf("container/system/group" to listOf(item("", "docker" to "true"))), platform = Platform.Ssh)
        val again = tab(router)
        var current by mutableStateOf(tab(router))
        setContent { NetworkHelmTheme(dark = true) { CompositionLocalProvider(LocalDockerTemplates provides templates) { DockerGroupNotice(current) } } }
        shown("group docker")
        picture("docker-group")
        current = again
        waitForIdle()
        assertTrue(onAllNodesWithTag("docker-group").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theGroupOfContainersIsOneWindowWithATabAnEntry() = runDesktopComposeUiTest(1400, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            rows = mapOf(
                "container-engine" to listOf(
                    item("docker", "name" to "Docker", "installed" to "true", "version" to "Docker version 26.1.3, build 26.1.3-0ubuntu1~22.04.1", "packages" to "docker.io"),
                    item("podman", "name" to "Podman", "installed" to "false", "version" to "", "packages" to "podman"),
                    item("compose", "name" to "Compose", "installed" to "false", "version" to "", "packages" to "docker-compose-v2"),
                    item("compose-v1", "name" to "Compose v1", "installed" to "true", "version" to "docker-compose version 1.29.2, build unknown", "packages" to ""),
                ),
                "container" to CONTAINERS,
            ),
            schemas = menus("container-engine" to schema("container-engine", listOf("install"))),
            platform = Platform.Ssh,
            ssh = Ssh,
        )
        val tab = tab(router)
        screen(tab)
        tab.open(MenuEntry("Overview", "container-engine"))
        shown("docker-compose-v2")
        shown("Install Now")
        picture("docker-containers-overview")
        // the other entries of the group turn the tab of the same window, the windows of the app among them
        tab.open(MenuEntry("Registries", "container/image", window = "docker-registries"))
        shown("A login added here")
        tab("Monitor")
        shown("Events")
        picture("docker-containers-monitor")
        assertEquals(listOf("Containers"), tab.workspace.windows.map { it.title })
        // an entry that fills a field of its window opens that window as before
        tab.open(MenuEntry("New Stack", "container/stack", window = "docker-stack-new", fill = mapOf("template" to "Redis")))
        assertEquals(2, tab.workspace.windows.size)
    }

    // a layout of /container of a router with RouterOS under the bare path, as a copy of the app that knows such routers keeps it
    @Test
    fun theContainersOfAHostKeepTheirOwnColumnsAndShowTheirStateByLetters() = runDesktopComposeUiTest(1400, 600) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        tables["container"] = TableLayout(listOf("auto-restart-interval", "check-certificate", "name"))
        val rows = listOf(
            Item(WEB, mapOf("name" to "nh-sample-web", "state" to "running", "running" to "true", "healthy" to "true", "project" to "nhstack", "image" to "alpine:latest")),
            Item("5b5a4e47a53a", mapOf("name" to "nh-sample-old", "state" to "exited", "failed" to "true", "status" to "Exited (137) 2 hours ago", "image" to "alpine:latest")),
        )
        val tab = RouterTab(FakeRouter(rows = mapOf("container" to rows), schemas = menus(), platform = Platform.Ssh, ssh = Ssh), "192.0.2.1", bundledPacks().values, storage, tables)
        screen(tab)
        tab.open(MenuEntry("Containers", "container"))
        shown("nh-sample-old")
        assertTrue(onAllNodesWithText("Auto Restart Interval").fetchSemanticsNodes().isEmpty(), "the layout of RouterOS stays its own")
        listOf("Stack", "Image", "Status", "IP Address", "Ports").forEach { shown(it) }
        assertTrue(listOf("RH", "E").all { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }, "the letters of running and healthy, and of an error")
        picture("docker-containers-table")
    }

    @Test
    fun theWindowOfContainersComesBackWhereItWasOnItsTab() = runDesktopComposeUiTest(1400, 700) {
        Locale.setDefault(Locale.ENGLISH)
        val storage = MemoryStorage()
        val tables = TableLayouts(storage)
        fun host() = RouterTab(FakeRouter(schemas = menus(), platform = Platform.Ssh, ssh = Ssh), "192.0.2.1", bundledPacks().values, storage, tables)
        val first = host()
        var current by mutableStateOf(first)
        setContent { NetworkHelmTheme(dark = true) { MainScreen(current, onDisconnect = {}) } }
        shown("Containers")
        first.open(MenuEntry("Images", "container/image"))
        first.workspace.windows.single().position = DpOffset(120.dp, 60.dp)
        waitUntil(timeoutMillis = 5_000) { runBlocking { storage.read("workspace-rd-test-id.json") }?.contains("\"x\":120.0") == true }

        val second = host()
        current = second
        waitUntil(timeoutMillis = 5_000) { second.workspace.windows.size == 1 }
        val restored = second.workspace.windows.single()
        assertEquals(listOf("Containers", "Images"), listOf(restored.title, second.dockers[restored.id]?.target))
        assertEquals(DpOffset(120.dp, 60.dp), restored.position)
    }

    @Test
    fun thePodsOfPodman() = runDesktopComposeUiTest(1400, 600) {
        Locale.setDefault(Locale.ENGLISH)
        val router = FakeRouter(
            rows = mapOf(
                "container/pod" to listOf(
                    item(
                        "88edab2681f29aff292bc4f8a091ca38d55897bfa524238868e65089cbc71694", "name" to "nh-sample-pod", "status" to "Degraded", "containers" to "nh-sample-podded",
                        "running" to "1/2", "networks" to "podman", "created" to "2026-11-17 17:31:05", "pod-id" to "88edab2681f2",
                    ),
                    item(
                        "2c1a4f7710a708bb1141218e34bd66ca7a74b0508cd677f38f7150387ab5eb6e", "name" to "nhpod", "status" to "Running", "containers" to "nhpod-sleep",
                        "running" to "2/2", "networks" to "podman", "created" to "2026-11-17 20:40:43", "pod-id" to "2c1a4f7710a7",
                    ),
                ),
            ),
            schemas = menus("container/pod" to schema("container/pod", listOf("remove", "start", "stop", "restart", "pause", "unpause", "kill"))),
            platform = Platform.Ssh,
            ssh = Ssh,
        )
        val tab = tab(router)
        screen(tab)
        tab.open(MenuEntry("Pods", "container/pod"))
        shown("nhpod-sleep")
        picture("docker-pods")
    }

    private fun network(name: String, driver: String, subnet: String, gateway: String, containers: String, parent: String = "", mode: String = "") = item(
        name, "name" to name, "driver" to driver, "subnet" to subnet, "gateway" to gateway, "ipv6-subnet" to "", "parent" to parent, "mode" to mode, "internal" to "false",
        "containers" to containers, "scope" to "local", "created" to "2026-11-17 20:20:32",
    )

    private fun disk(kind: String, count: String, active: String, size: String, free: String) =
        item(kind, "kind" to kind, "count" to count, "active" to active, "size" to size, "reclaimable" to free)

    private companion object {
        const val WEB = "87deced11966e779b7967cbd3cb91973f526454a3aeeb5914eb59e069bd6c59d"

        val STATS = listOf("0.12", "0.16", "0.35", "0.21", "0.18", "1.42", "0.95", "0.31", "0.22", "0.19", "0.16", "0.40", "0.25", "0.17", "0.15", "0.21", "0.18", "0.33")

        val CONTAINERS = listOf(
            Item(WEB, mapOf("name" to "nh-sample-web", "state" to "running", "cpu" to "0.16", "memory" to "741376", "image" to "alpine:latest")),
            Item("5b5a4e47a53a", mapOf("name" to "nh-sample-tty", "state" to "running", "cpu" to "0.05", "memory" to "520192", "image" to "alpine:latest")),
            Item("462c9834ffa7", mapOf("name" to "nhstack_web_1", "state" to "running", "cpu" to "1.92", "memory" to "3407872", "image" to "docker.io/library/busybox:1.37")),
            Item("41d10d6ac52a", mapOf("name" to "nhstack_worker_1", "state" to "running", "cpu" to "0.48", "memory" to "1236992", "image" to "docker.io/library/busybox:1.37")),
        )

        val ETC = listOf(
            Item("alpine-release", mapOf("name" to "alpine-release", "type" to "file", "size" to "7", "modified" to "Nov 5 09:17")),
            Item("apk", mapOf("name" to "apk", "type" to "directory", "size" to "", "modified" to "Nov 5 09:19")),
            Item("busybox-paths.d", mapOf("name" to "busybox-paths.d", "type" to "directory", "size" to "", "modified" to "Nov 5 09:19")),
            Item("crontabs", mapOf("name" to "crontabs", "type" to "directory", "size" to "", "modified" to "Nov 5 09:19")),
            Item("fstab", mapOf("name" to "fstab", "type" to "file", "size" to "89", "modified" to "Mar 24 2026")),
            Item("group", mapOf("name" to "group", "type" to "file", "size" to "510", "modified" to "Mar 24 2026")),
            Item("hostname", mapOf("name" to "hostname", "type" to "file", "size" to "13", "modified" to "Nov 17 17:21")),
            Item("hosts", mapOf("name" to "hosts", "type" to "file", "size" to "172", "modified" to "Nov 17 17:21")),
            Item("mtab", mapOf("name" to "mtab", "type" to "link", "size" to "", "modified" to "Nov 17 17:21", "target" to "/proc/mounts")),
            Item("network", mapOf("name" to "network", "type" to "directory", "size" to "", "modified" to "Nov 5 09:19")),
            Item("os-release", mapOf("name" to "os-release", "type" to "link", "size" to "", "modified" to "Nov 5 09:19", "target" to "../usr/lib/os-release")),
            Item("passwd", mapOf("name" to "passwd", "type" to "file", "size" to "702", "modified" to "Mar 24 2026")),
        )

        val INSPECT = """
            {
              "Id": "$WEB",
              "Name": "/nh-sample-web",
              "State": {
                "Status": "running",
                "Running": true,
                "StartedAt": "2026-11-17T17:21:04.730585129Z"
              },
              "Config": {
                "Image": "alpine:latest",
                "Env": [
                  "GREETING=hello",
                  "DB_PASSWORD=********"
                ]
              },
              "HostConfig": {
                "PortBindings": {
                  "80/tcp": [{ "HostIp": "", "HostPort": "18080" }]
                },
                "RestartPolicy": { "Name": "unless-stopped", "MaximumRetryCount": 0 }
              }
            }
        """.trimIndent()

        val STACK = $$"""
            services:
              web:
                image: docker.io/library/busybox:1.37
                command: ["httpd", "-f", "-p", "8080", "-h", "/www"]
                ports:
                  - "${WEB_PORT}:8080"
                volumes:
                  - www:/www
              worker:
                image: docker.io/library/busybox:1.37
                command: ["sh", "-c", "while true; do echo tick $GREETING; sleep 2; done"]
                environment:
                  GREETING: ${GREETING}
            volumes:
              www:
        """.trimIndent() + "\n"

        val DAEMON = """
            {
              "log-driver": "local",
              "log-opts": { "max-size": "10m" },
              "live-restore": true
            }
        """.trimIndent() + "\n"

        val ENGINE = listOf(
            "version" to "29.9.3", "api-version" to "1.56", "os" to "Arch Linux", "kernel" to "6.8.4-2-pve", "architecture" to "amd64", "storage-driver" to "overlayfs",
            "cgroup" to "2", "cpus" to "2", "memory" to "1073741824", "containers" to "4", "running" to "4", "paused" to "0", "stopped" to "0", "images" to "3",
            "root-dir" to "/var/lib/docker", "swarm" to "inactive", "rootless" to "false", "host" to "nh-arch",
        )

        val EVENTS = listOf(
            Item("", mapOf("time" to "2026-11-17 20:22:19", "type" to "container", "action" to "kill", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "signal=15")),
            Item("", mapOf("time" to "2026-11-17 20:22:20", "type" to "container", "action" to "kill", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "signal=9")),
            Item("", mapOf("time" to "2026-11-17 20:22:20", "type" to "container", "action" to "die", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "exitCode=137")),
            Item("", mapOf("time" to "2026-11-17 20:22:20", "type" to "network", "action" to "disconnect", "id" to "64d3a304e077", "name" to "bridge", "attributes" to "container=5b5a4e47a53a, type=bridge")),
            Item("", mapOf("time" to "2026-11-17 20:22:20", "type" to "container", "action" to "stop", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "")),
            Item("", mapOf("time" to "2026-11-17 20:22:21", "type" to "network", "action" to "connect", "id" to "64d3a304e077", "name" to "bridge", "attributes" to "container=5b5a4e47a53a, type=bridge")),
            Item("", mapOf("time" to "2026-11-17 20:22:21", "type" to "container", "action" to "start", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "")),
            Item("", mapOf("time" to "2026-11-17 20:22:21", "type" to "container", "action" to "restart", "id" to "5b5a4e47a53a", "name" to "nh-sample-tty", "attributes" to "")),
        )

        // the empty row the host puts between the events of the period and the live ones
        val PAST_END = Item("", emptyMap())

        private val LINES = ValueType.Many(ValueType.Text(), "\n")

        private fun field(name: String, type: ValueType = ValueType.Text(), choices: List<String> = emptyList()) = Argument(name, type, choices, optional = true)

        // the fields of a container as the host gives them, by the form of the pack
        val FORM = listOf(
            field("name"), field("image", ValueType.Enumeration(), listOf("alpine:latest", "busybox:1.37", "nginx:1.27")), field("command"), field("entrypoint"),
            field("workdir"), field("user"), field("hostname"), field("labels", LINES), field("tty", ValueType.YesNo), field("interactive", ValueType.YesNo),
            field("env", LINES), field("secret-env", ValueType.Many(ValueType.Secret, "\n")), field("ports", LINES), field("publish-all", ValueType.YesNo),
            field("mounts", LINES), field("network", ValueType.Enumeration(), listOf("bridge", "host", "none", "nh-sample-net")), field("aliases", LINES),
            field("ipv4-address", ValueType.Ip4), field("ipv6-address", ValueType.Ip6), field("mac-address", ValueType.Mac), field("dns", LINES),
            field("dns-search", LINES), field("extra-hosts", LINES), field("cpus"), field("cpu-shares", ValueType.Integer()), field("cpuset-cpus"), field("memory"),
            field("memory-swap"), field("memory-reservation"), field("pids-limit", ValueType.Integer()), field("shm-size"), field("ulimits", LINES),
            field("oom-kill-disable", ValueType.YesNo), field("restart", ValueType.Enumeration(closed = true), listOf("no", "always", "unless-stopped", "on-failure")),
            field("restart-retries", ValueType.Integer(min = 0)), field("health-cmd"), field("health-interval"), field("health-timeout"),
            field("health-start-period"), field("health-retries", ValueType.Integer(min = 0)), field("privileged", ValueType.YesNo), field("cap-add", LINES),
            field("cap-drop", LINES), field("security-opt", LINES), field("read-only", ValueType.YesNo), field("userns"), field("devices", LINES), field("gpus"),
            field("log-driver", ValueType.Enumeration(), listOf("json-file", "local", "journald", "syslog", "none")), field("log-opt", LINES), field("runtime"),
            field("init", ValueType.YesNo), field("stop-signal"), field("stop-timeout", ValueType.Integer(min = 0, unit = "s")), field("auto-remove", ValueType.YesNo),
            field("start", ValueType.YesNo), field("recreate", ValueType.YesNo),
        ) + listOf("state", "health", "started", "created", "ip-address", "restart-count", "exit-code", "id").map { Argument(it, ValueType.Text(), readOnly = true) }
    }
}
