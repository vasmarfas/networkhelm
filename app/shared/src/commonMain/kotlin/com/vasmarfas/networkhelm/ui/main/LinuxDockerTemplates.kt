package com.vasmarfas.networkhelm.ui.main

import androidx.compose.runtime.staticCompositionLocalOf
import com.vasmarfas.networkhelm.app.Secrets
import com.vasmarfas.networkhelm.app.Storage
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.model.MenuPath
import com.vasmarfas.networkhelm.session.DeviceSession
import com.vasmarfas.networkhelm.session.hub
import com.vasmarfas.networkhelm.session.registryOf
import com.vasmarfas.networkhelm.sync.FileRecords
import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.Record
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.sync.recordJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

// a stack ready to start: its compose.yaml and .env
@Serializable
internal data class StackTemplate(val name: String, val compose: String, val env: String = "")

// the .env of a template names the variables and leaves their values empty: they are passwords and keys as often as not, and a template
// goes to the other devices with synchronisation, where it lies in a plain file
internal fun envNames(env: String): String =
    env.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.removePrefix("export ").substringBefore('=').trim() + "=" }.distinct().joinToString("") { "$it\n" }

// the templates of the user, kept as the snippets are, which the sync carries to his other devices
internal class DockerTemplates(private val storage: Storage, clock: HlcClock = HlcClock()) {
    private val list = MutableStateFlow<List<StackTemplate>>(emptyList())
    val all: StateFlow<List<StackTemplate>> = list.asStateFlow()
    val records = FileRecords(storage, FILE, "docker-template", clock)

    // an edit of the user and the templates of other devices go into the file one after the other
    private val lock = Mutex()

    private var failure: Exception? = null

    suspend fun load() = lock.withLock { read() }

    // the hosts whose login was told it is root through the group docker; the list stays on this device
    suspend fun firstNotice(host: String): Boolean = lock.withLock {
        val told = storage.read(NOTICES)?.lines().orEmpty()
        if (host in told) return@withLock false
        storage.write(NOTICES, (told + host).joinToString("\n"))
        true
    }

    // a template saved under a name already there takes its place
    suspend fun put(template: StackTemplate) = change { kept -> kept.filter { it.name != template.name } + template }

    suspend fun remove(name: String) = change { kept -> kept.filter { it.name != name } }

    // the templates merged with those of the other devices: those here keep their places, those from elsewhere come after them
    suspend fun apply(merged: List<Record>) = lock.withLock {
        read()
        failure?.let { throw IllegalStateException("the templates could not be read, not overwriting them", it) }
        records.put(merged, StackTemplate.serializer()) { templates ->
            val stored = list.value
            write(stored.mapNotNull { template -> templates.firstOrNull { it.name == template.name } } + templates.filter { template -> stored.none { it.name == template.name } })
        }
        read()
    }

    private suspend fun read() = records.match {
        storage.read(FILE)?.let { text ->
            try {
                list.value = JSON.decodeFromString(SERIALIZER, text)
                failure = null
            } catch (e: IllegalArgumentException) {
                failure = e
                return@match null
            }
        }
        list.value.associate { recordId(it.name) to recordJson.encodeToJsonElement(StackTemplate.serializer(), it).jsonObject }
    }

    private suspend fun change(edit: (List<StackTemplate>) -> List<StackTemplate>) = lock.withLock {
        read()
        failure?.let { throw IllegalStateException("the templates could not be read, not overwriting them", it) }
        write(edit(list.value))
        read()
    }

    private suspend fun write(templates: List<StackTemplate>) {
        storage.write(FILE, JSON.encodeToString(SERIALIZER, templates))
        list.value = templates
    }

    private companion object {
        const val FILE = "docker-templates.json"
        const val NOTICES = "docker-notices.txt"
        val SERIALIZER = ListSerializer(StackTemplate.serializer())
        val JSON = Json { prettyPrint = true }
    }
}

internal val LocalDockerTemplates = staticCompositionLocalOf<DockerTemplates?> { null }

// the login of a registry, as the protected store of the app keeps it
@Serializable
internal data class DockerRegistry(val server: String, val username: String, val password: String)

// the logins of registries in the protected store of the app: one goes in the header of a pull or a push of an image of its registry,
// and none stays on the host unless the user logs the host in himself
internal class DockerRegistries(private val secrets: Secrets) {
    suspend fun all(): List<DockerRegistry> = saved()?.let { JSON.decodeFromString(SERIALIZER, it) }.orEmpty()

    // all of them as the store keeps them, for the host to pick the one of the image it pulls
    suspend fun saved(): String? = secrets.get(KEY)

    suspend fun save(registries: List<DockerRegistry>) = secrets.put(KEY, JSON.encodeToString(SERIALIZER, registries))

    suspend fun loginFor(image: String): DockerRegistry? {
        val registry = hub(registryOf(image))
        return all().firstOrNull { hub(it.server) == registry }
    }

    private companion object {
        const val KEY = "docker-registries"
        val SERIALIZER = ListSerializer(DockerRegistry.serializer())
        val JSON = Json
    }
}

internal val LocalDockerRegistries = staticCompositionLocalOf<DockerRegistries?> { null }

// a host that pulls the image of a new container or of one made anew gets the logins with the request, as docker run takes the login
// of the registry; the session of the journal stands above this one, so the logins never reach the journal
internal class RegistryLogins(private val session: DeviceSession, private val registries: DockerRegistries) : DeviceSession by session {
    override suspend fun add(path: MenuPath, values: Map<String, String>): String = session.add(path, values + logins(path))

    override suspend fun set(path: MenuPath, id: String?, changes: Map<String, String?>) = session.set(path, id, changes + logins(path))

    override fun run(path: MenuPath, command: String, arguments: Map<String, String>): Flow<Item> =
        if (command == "recreate") flow { emitAll(session.run(path, command, arguments + logins(path))) } else session.run(path, command, arguments)

    private suspend fun logins(path: MenuPath): Map<String, String> =
        if (path == CONTAINER) registries.saved()?.let { mapOf("registries" to it) }.orEmpty() else emptyMap()
}

private val CONTAINER = MenuPath.parse("container")

// the stacks the app brings, as Portainer does: a service each, a database beside an application where it needs one. Passwords are
// placeholders in .env, to be changed before the stack starts
internal val TEMPLATES = listOf(
    StackTemplate(
        "nginx",
        """
        services:
          web:
            image: nginx:1.27
            ports:
              - "8080:80"
            volumes:
              - ./html:/usr/share/nginx/html:ro
            restart: unless-stopped
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "PostgreSQL",
        $$"""
        services:
          db:
            image: postgres:17
            environment:
              POSTGRES_USER: ${POSTGRES_USER}
              POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
              POSTGRES_DB: ${POSTGRES_DB}
            ports:
              - "5432:5432"
            volumes:
              - data:/var/lib/postgresql/data
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
        "POSTGRES_USER=app\nPOSTGRES_PASSWORD=change-me\nPOSTGRES_DB=app\n",
    ),
    StackTemplate(
        "Redis",
        """
        services:
          cache:
            image: redis:7
            command: ["redis-server", "--appendonly", "yes"]
            ports:
              - "6379:6379"
            volumes:
              - data:/data
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "Home Assistant",
        """
        services:
          homeassistant:
            image: ghcr.io/home-assistant/home-assistant:stable
            network_mode: host
            volumes:
              - ./config:/config
              - /etc/localtime:/etc/localtime:ro
            restart: unless-stopped
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "AdGuard Home",
        """
        services:
          adguard:
            image: adguard/adguardhome:latest
            ports:
              - "53:53/tcp"
              - "53:53/udp"
              - "3000:3000/tcp"
              - "8081:80/tcp"
            volumes:
              - ./work:/opt/adguardhome/work
              - ./conf:/opt/adguardhome/conf
            restart: unless-stopped
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "Uptime Kuma",
        """
        services:
          kuma:
            image: louislam/uptime-kuma:1
            ports:
              - "3001:3001"
            volumes:
              - data:/app/data
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "WordPress",
        $$"""
        services:
          db:
            image: mariadb:11
            environment:
              MARIADB_DATABASE: wordpress
              MARIADB_USER: wordpress
              MARIADB_PASSWORD: ${DB_PASSWORD}
              MARIADB_RANDOM_ROOT_PASSWORD: "1"
            volumes:
              - db:/var/lib/mysql
            restart: unless-stopped
          wordpress:
            image: wordpress:6
            depends_on:
              - db
            environment:
              WORDPRESS_DB_HOST: db
              WORDPRESS_DB_USER: wordpress
              WORDPRESS_DB_PASSWORD: ${DB_PASSWORD}
              WORDPRESS_DB_NAME: wordpress
            ports:
              - "8082:80"
            volumes:
              - site:/var/www/html
            restart: unless-stopped
        volumes:
          db:
          site:
        """.trimIndent() + "\n",
        "DB_PASSWORD=change-me\n",
    ),
    StackTemplate(
        "Gitea",
        """
        services:
          gitea:
            image: gitea/gitea:1
            ports:
              - "3002:3000"
              - "2222:22"
            volumes:
              - data:/data
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "Grafana",
        """
        services:
          grafana:
            image: grafana/grafana-oss:latest
            ports:
              - "3003:3000"
            volumes:
              - data:/var/lib/grafana
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
    ),
    StackTemplate(
        "Prometheus",
        """
        services:
          prometheus:
            image: prom/prometheus:latest
            ports:
              - "9090:9090"
            volumes:
              - data:/prometheus
            restart: unless-stopped
        volumes:
          data:
        """.trimIndent() + "\n",
    ),
)
