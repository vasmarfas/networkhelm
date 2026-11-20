package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.ssh.SshSession

// the containers of a Linux host: Docker and Podman through the API of the engine
internal class LinuxDocker(
    private val ssh: SshSession,
    private val root: Root,
    private val tools: suspend () -> Set<String>,
    private val guard: LinuxGuard,
    private val packages: LinuxPackages,
) {
    private val api = DockerApi(ssh, root, tools)
    private val containers = DockerContainers(api)
    private val present: suspend () -> Boolean = { tools().any { it == "docker" || it == "podman" } }

    val tables: Map<String, HostTable> = mapOf(
        "container-engine" to HostTable(ENGINE_FIELDS, ::engines, commands = listOf("install"), run = { _, arguments -> install(arguments) }),
        "container" to containers.table(present),
        "container/image" to DockerImages(api).table(present),
        "container/volume" to DockerVolumes(api, containers::create).table(present),
        "container/network" to DockerNetworks(ssh, api, guard).table(present),
        "container/stack" to DockerStacks(ssh, api).table(present),
        "container/system" to DockerSystem(ssh, api, root, guard).table(present),
        "container/pod" to DockerPods(api).table { "podman" in tools() && api.isPodman() },
    )

    // what runs containers on the host, and what the window of packages installs for the distribution; it stands outside container, so
    // a host without an engine shows it alone in the group
    private suspend fun engines(): List<Item> {
        val present = tools()
        val text = ssh.exec(ENGINES).output
        fun mark(name: String) = text.lines().firstOrNull { it.startsWith("--nh-$name-- ") }?.substringAfter(' ')?.trim().orEmpty()
        // podman compose prints the version of Podman before the one of its provider
        val composed = text.lines().filter { it.startsWith("--nh-compose-- ") }.map { it.substringAfter(' ').trim() }
        val composeVersion = composed.firstOrNull { "compose" in it.lowercase() } ?: composed.firstOrNull().orEmpty()
        val system = mark("os").split(' ')
        val distribution = DISTRIBUTIONS.firstOrNull { it.first in system }?.second ?: OTHER_DISTRIBUTION
        // docker of podman-docker answers as podman, and the package of Docker conflicts with it: the row names it and offers nothing
        val wrapped = "podman" in mark("docker").lowercase()
        val compose = if ("podman" in present && ("docker" !in present || wrapped)) "podman-compose" else distribution.second
        // Compose v1, docker-compose 1.29.2 of Ubuntu and Debian 12: the row of Compose offers v2, and v1 keeps a row of its own, as the
        // stacks still go through it
        val v1 = composeVersion.startsWith("docker-compose version 1.")
        return listOfNotNull(
            Item(
                "docker",
                linkedMapOf(
                    "name" to if (wrapped) "Docker (podman-docker)" else "Docker", "installed" to "${"docker" in present && !wrapped}", "version" to mark("docker"),
                    "packages" to if (wrapped) "" else distribution.first,
                ),
            ),
            Item("podman", linkedMapOf("name" to "Podman", "installed" to "${"podman" in present}", "version" to mark("podman"), "packages" to "podman")),
            Item(
                "compose",
                linkedMapOf("name" to "Compose", "installed" to "${composeVersion.isNotEmpty() && !v1}", "version" to if (v1) "" else composeVersion, "packages" to compose),
            ),
            if (v1) Item("compose-v1", linkedMapOf("name" to "Compose v1", "installed" to "true", "version" to composeVersion, "packages" to "")) else null,
        )
    }

    // the packages of the rows chosen, by the manager the window of packages uses. Docker put in here starts at once and with the host:
    // Debian and Ubuntu start it as they install it, the other distributions leave that to the admin
    private suspend fun install(arguments: Map<String, String>): List<Item> {
        val chosen = arguments["numbers"].orEmpty().split(',')
        val rows = engines().filter { it.id in chosen && it["packages"].orEmpty().isNotEmpty() }
        if (rows.isEmpty()) throw DeviceException(ErrorCategory.ArgumentValue, "the rows chosen have nothing to install")
        val said = packages.install(rows.flatMap { it["packages"].orEmpty().split(' ') })
        val started = if (rows.any { it.id == "docker" && it["installed"] == "false" }) checked(root.exec("$START 2>&1")).trim() else ""
        return listOf(Item("", mapOf("output" to listOf(said, started).filter { it.isNotEmpty() }.joinToString("\n"))))
    }
}

internal val DOCKER_TOOLS = listOf("docker", "dockerd", "podman")

private val ENGINE_FIELDS = listOf("name", "installed", "version", "packages")

// Docker and its Compose by the names of each distribution, found by ID and ID_LIKE of os-release: Rocky names fedora among its likes and
// goes before it, Ubuntu before Debian; docker-ce and docker-compose-plugin come from the repository of Docker alone
private val DISTRIBUTIONS = listOf(
    "ubuntu" to ("docker.io" to "docker-compose-v2"),
    "debian" to ("docker.io" to "docker-compose"),
    "rhel" to ("docker-ce" to "docker-compose-plugin"),
    "fedora" to ("moby-engine" to "docker-compose"),
    "suse" to ("docker" to "docker-compose"),
    "arch" to ("docker" to "docker-compose"),
    "alpine" to ("docker" to "docker-cli-compose"),
)
private val OTHER_DISTRIBUTION = "docker" to "docker-compose"

// systemd, or OpenRC on Alpine
private const val START = "if command -v systemctl >/dev/null 2>&1; then systemctl enable --now docker; else rc-update add docker default && rc-service docker start; fi"

// the login reads what is installed: the version of each engine and what the first Compose that answers says of its version, the plugin
// before the program
private val ENGINES = $$"""[ ! -f /etc/os-release ] || . /etc/os-release; echo "--nh-os-- $ID $ID_LIKE"
for c in docker podman; do v=$($c --version 2>/dev/null | head -n 1) && [ -n "$v" ] && echo "--nh-$c-- $v"; done
for c in "docker compose" docker-compose "podman compose" podman-compose; do v=$($c version 2>/dev/null) && [ -n "$v" ] && { printf '%s\n' "$v" | sed 's/^/--nh-compose-- /'; break; }; done
true"""
