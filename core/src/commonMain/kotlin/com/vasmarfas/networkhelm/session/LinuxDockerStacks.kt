package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.openwrt.quote
import com.vasmarfas.networkhelm.ssh.SshSession
import com.vasmarfas.networkhelm.transport.urlEncoded
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// the stacks of Compose: the projects the labels of the containers name, and the folders the app keeps in stacks in the home of the
// login, whose stack may have no container now. Compose runs on the host as the engine is reached, as the plugin of the CLI or as a
// program of its own; the services and the log of a stack come through the API
internal class DockerStacks(private val ssh: SshSession, private val api: DockerApi) {
    // the folder of a stack and its files of Compose; a stack the app made has the file Compose finds by itself
    private class Stack(val name: String, val dir: String, val files: List<String>)

    private var tool: String? = null

    fun table(present: suspend () -> Boolean) = HostTable(
        STACK_COLUMNS, ::list, commands = STACK_COMMANDS, run = ::command, streams = mapOf("log" to ::log), present = present,
    )

    private suspend fun list(): List<Item> {
        val containers = objects(api.get("/containers/json?all=1"), "/containers/json")
        val kept = lines(ssh.exec(KEPT).output)
        return stackRows(containers, kept)
    }

    private suspend fun command(command: String, arguments: Map<String, String>): List<Item> {
        val ids = arguments["numbers"].orEmpty().split(',').filter { it.isNotEmpty() }
        val id by lazy { arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a stack") }
        return when (command) {
            in VERBS -> ids.flatMap { compose(stack(it), VERBS.getValue(command)) }
            "create" -> create(arguments)
            "services" -> {
                val offset = api.offset()
                members(id).map { containerRow(it, offset, emptyMap()) }
            }
            "config" -> config(stack(id))
            "save-config" -> {
                save(stack(id), arguments["compose"], arguments["env"])
                emptyList()
            }
            else -> throw DeviceException(ErrorCategory.MissingItem, "container/stack/$command is not on this host")
        }
    }

    private suspend fun stack(name: String): Stack {
        val row = list().firstOrNull { it.id == name } ?: throw DeviceException(ErrorCategory.MissingItem, "the host has no stack $name")
        return Stack(name, row["dir"].orEmpty(), row["files"].orEmpty().split(',').filter { it.isNotEmpty() })
    }

    private suspend fun members(name: String) = objects(api.get("/containers/json?all=1&filters=" + urlEncoded("""{"label":["$COMPOSE_PROJECT=$name"]}""")), "/containers/json")
        .filter { it.inner("Labels")?.text(COMPOSE_PROJECT) == name }

    // docker compose itself, or the plugin of Podman, which hands the work over to a provider and says so in a banner on every command
    // unless told not to; else docker-compose or podman-compose
    private suspend fun tool(): String = tool ?: run {
        val cli = api.bareCli()
        val found = listOf("$cli compose", "$cli-compose").firstOrNull { api.shell("$it version").exitStatus == 0 }
            ?: throw DeviceException(ErrorCategory.Unsupported, "the host has no Compose: docker compose, docker-compose or podman-compose")
        if (found == "$cli compose" && api.isPodman()) "PODMAN_COMPOSE_WARNING_LOGS=false $found" else found
    }.also { tool = it }

    // a command of Compose in the folder of the stack, with its name and files: what it says of its progress comes with its output
    private suspend fun compose(stack: Stack, verb: String): List<Item> {
        val files = stack.files.joinToString("") { "-f ${quote(it)} " }
        return lines(composed("cd ${quote(stack.dir)} && ${tool()} -p ${quote(stack.name)} $files$verb")).map { Item("", mapOf("line" to it)) }
    }

    // a line with Compose in it, its output and what it said: Compose colours its words for a terminal, and the colours do not come along
    private suspend fun composed(line: String): String {
        val result = api.shell(line)
        val output = ANSI.replace(result.output, "")
        val said = ANSI.replace(result.error, "")
        if (result.exitStatus != 0) throw DeviceException(ErrorCategory.Api, said.trim().ifEmpty { output.trim() }.ifEmpty { "exit ${result.exitStatus}" })
        return output + "\n" + said
    }

    // a new stack has a folder of its own in stacks: a copy of a repository of Git, or the files as typed. Compose reads it before it
    // starts, and a stack it cannot read leaves no folder behind
    private suspend fun create(values: Map<String, String>): List<Item> {
        val name = values["name"].orEmpty()
        if (!PROJECT_NAME.matches(name)) throw DeviceException(ErrorCategory.ArgumentValue, "the name of a stack is small letters, digits, - and _, from a letter or a digit: $name")
        val dir = "$STACKS/$name"
        if (api.shell("[ ! -e ${quote(dir)} ]").exitStatus != 0) throw DeviceException(ErrorCategory.ArgumentValue, "the folder $dir is there already")
        val git = values["git"].orEmpty()
        try {
            if (git.isNotEmpty()) {
                checked(api.shell("mkdir -p $STACKS && git clone --depth 1 -- ${quote(git)} ${quote(dir)}"))
            } else {
                checked(api.shell("mkdir -p ${quote(dir)} && cat > ${quote("$dir/$FILE")}", values["compose"].orEmpty()))
                values["env"]?.ifEmpty { null }?.let { checked(api.shell("cat > ${quote("$dir/.env")}", it)) }
            }
            checked(api.shell("[ -z \"\$SUDO_UID\" ] || { chown \"\$SUDO_UID:\$SUDO_GID\" $STACKS && chown -R \"\$SUDO_UID:\$SUDO_GID\" ${quote(dir)}; }"))
            composed("cd ${quote(dir)} && ${tool()} -p ${quote(name)} config >/dev/null")
        } catch (e: DeviceException) {
            undo(e) { checked(api.shell("rm -rf ${quote(dir)}")) }
            throw e
        }
        return if (values["start"] in setOf("false", "no")) emptyList() else compose(Stack(name, dir, emptyList()), "up -d")
    }

    // compose.yaml of the stack, the first file where it has more, and .env of its folder, empty where there is none
    private suspend fun config(stack: Stack): List<Item> {
        val file = stack.files.firstOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "the stack ${stack.name} names no file of Compose")
        val env = "${stack.dir}/.env"
        return listOf(
            Item("compose", mapOf("file" to file, "text" to checked(api.shell("cat ${quote(file)}")))),
            Item("env", mapOf("file" to env, "text" to api.shell("cat ${quote(env)} 2>/dev/null").output)),
        )
    }

    // a change goes to a file beside the old one, and Compose reads the stack with it before it takes the place of the old one; the
    // old file keeps its owner and mode
    private suspend fun save(stack: Stack, compose: String?, env: String?) {
        val file = stack.files.firstOrNull() ?: throw DeviceException(ErrorCategory.MissingItem, "the stack ${stack.name} names no file of Compose")
        val envFile = "${stack.dir}/.env"
        val drafts = listOfNotNull(compose?.let { "$file.$DRAFT" to it }, env?.let { "$envFile.$DRAFT" to it })
        try {
            for ((draft, text) in drafts) checked(api.shell("cat > ${quote(draft)}", text))
            val files = (listOf(compose?.let { "$file.$DRAFT" } ?: file) + stack.files.drop(1)).joinToString("") { "-f ${quote(it)} " }
            val envOption = env?.let { "--env-file ${quote("$envFile.$DRAFT")} " }.orEmpty()
            composed("cd ${quote(stack.dir)} && ${tool()} -p ${quote(stack.name)} $files${envOption}config >/dev/null")
            compose?.let { checked(api.shell("cat ${quote("$file.$DRAFT")} > ${quote(file)}")) }
            env?.let { checked(api.shell("cat ${quote("$envFile.$DRAFT")} > ${quote(envFile)}")) }
        } finally {
            if (drafts.isNotEmpty()) api.shell("rm -f " + drafts.joinToString(" ") { quote(it.first) })
        }
    }

    // the logs of all the containers of the stack in one, each line with its service, as docker compose logs gives them
    private fun log(arguments: Map<String, String>): Flow<Item> = channelFlow {
        val name = arguments["id"]?.ifEmpty { null } ?: throw DeviceException(ErrorCategory.ArgumentValue, "the command needs a stack")
        val tail = urlEncoded(arguments["tail"]?.ifEmpty { null } ?: "200")
        val offset = api.offset()
        for (member in members(name)) {
            val service = member.inner("Labels")?.text(COMPOSE_SERVICE).orEmpty()
            val path = "/containers/${urlEncoded(member.text("Id"))}/logs?stdout=1&stderr=1&follow=1&timestamps=1&tail=$tail"
            launch {
                api.log(path).collect { (stream, line) ->
                    send(Item("", mapOf("service" to service, "time" to logTime(line.substringBefore(' '), offset), "stream" to stream, "message" to line.substringAfter(' ', ""))))
                }
            }
        }
    }
}

// a stack a project: its containers by the labels Compose gives them, its state as docker compose ls counts it; a folder of the app
// with a file of Compose and no container is a stack that is down
internal fun stackRows(containers: List<JsonObject>, kept: List<String>): List<Item> {
    val projects = containers.groupBy { it.inner("Labels")?.text(COMPOSE_PROJECT).orEmpty() }.filterKeys { it.isNotEmpty() }
    val up = projects.map { (name, members) ->
        val labels = members.first().inner("Labels").orEmpty()
        val dir = labels.text(WORKING_DIR)
        // podman-compose names the files as given, without the folder of the stack
        val files = labels.text(CONFIG_FILES).split(',').filter { it.isNotEmpty() }.map { if (it.startsWith('/')) it else "$dir/$it" }
        Item(name, linkedMapOf(
            "name" to name,
            "status" to members.groupingBy { it.text("State") }.eachCount().entries.joinToString(", ") { "${it.key}(${it.value})" },
            "services" to members.map { it.inner("Labels")?.text(COMPOSE_SERVICE).orEmpty() }.distinct().sorted().joinToString(", "),
            "containers" to "${members.size}",
            "dir" to dir,
            "files" to files.joinToString(","),
        ))
    }
    val down = kept.distinctBy { it.substringBeforeLast('/') }.filter { it.substringBeforeLast('/').substringAfterLast('/') !in projects }.map { file ->
        val dir = file.substringBeforeLast('/')
        val name = dir.substringAfterLast('/')
        Item(name, linkedMapOf("name" to name, "status" to "down", "services" to "", "containers" to "0", "dir" to dir, "files" to file))
    }
    return up + down
}

private val STACK_COLUMNS = listOf("name", "status", "services", "containers", "dir")

// the commands of Compose by the commands of the table
private val VERBS = mapOf("up" to "up -d", "down" to "down", "down-volumes" to "down -v", "restart" to "restart", "pull" to "pull", "build" to "build")

private val STACK_COMMANDS = VERBS.keys.toList() + listOf("create", "services", "config", "save-config")

private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

// the name Compose takes for a project
private val PROJECT_NAME = Regex("[a-z0-9][a-z0-9_-]*")

private const val STACKS = "stacks"
private const val FILE = "compose.yaml"
private const val DRAFT = "networkhelm"
private const val WORKING_DIR = "com.docker.compose.project.working_dir"
private const val CONFIG_FILES = "com.docker.compose.project.config_files"

// the files of Compose of the folders in stacks, by the names Compose looks for
private const val KEPT = "ls -1d \"\$PWD\"/$STACKS/*/compose.yaml \"\$PWD\"/$STACKS/*/compose.yml \"\$PWD\"/$STACKS/*/docker-compose.yaml " +
    "\"\$PWD\"/$STACKS/*/docker-compose.yml 2>/dev/null"
