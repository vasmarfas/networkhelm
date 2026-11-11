package com.vasmarfas.networkhelm.openwrt

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private const val MAX_FORM_MEMORY_BYTES = 256L * 1024 * 1024
private const val OUT_OF_MEMORY = "out of memory"
private const val MAX_FORM_MILLIS = 10_000L
private const val MAX_FORM_SOURCE_BYTES = 8 * 1024 * 1024
private const val MAX_FORM_RESULT_CHARS = 4 * 1024 * 1024
private const val MAX_FORM_MODULE_NAME_CHARS = 512
private const val MAX_FORM_MODULES = 1_024

class QuickJsForms(
    private val ubus: Ubus,
    private val ssh: SshSession?,
    private val memoryLimit: Long = MAX_FORM_MEMORY_BYTES,
    private val sourceLimit: Int = MAX_FORM_SOURCE_BYTES,
    private val resultLimit: Int = MAX_FORM_RESULT_CHARS,
    private val timeLimit: Long = MAX_FORM_MILLIS,
) : LuciForms {
    private val lock = Mutex()
    private var objects: Set<String>? = null
    private var menu: List<LuciNode>? = null
    private val modules = mutableMapOf<String, String?>()
    private var moduleBytes = 0L

    override suspend fun views(): Map<String, List<String>> =
        menu().mapNotNull { it.view }.distinct().flatMap { view ->
            val source = file("$RESOURCES/view/$view.js") ?: return@flatMap emptyList()
            configsOfView(source).ifEmpty { if ("form.Map(" in source) maps(view).map { it.config }.toSet() else emptySet() }.map { it to view }
        }.groupBy({ it.first }, { it.second })

    override suspend fun menu(): List<LuciNode> {
        lock.withLock { menu?.let { return it } }
        val files = listFiles(ubus, objects(), ssh, MENUS).filter { it.endsWith(".json") }
        val nodes = files.flatMap { name -> file("$MENUS/$name")?.let { menuNodes(name.removeSuffix(".json"), it) }.orEmpty() }
        lock.withLock { menu = nodes }
        return nodes
    }

    override suspend fun maps(view: String): List<LuciMap> = run("runSchema", "$RESOURCES/view/$view.js")

    override suspend fun protocol(name: String): List<LuciOption> = run("runProtocol", "$RESOURCES/protocol/$name.js").flatMap { it.sections }.flatMap { it.options }

    private suspend fun run(entry: String, path: String): List<LuciMap> = withContext(Dispatchers.IO) {
        val source = file(path) ?: return@withContext emptyList()
        val engine = QuickJs.create(Dispatchers.IO)
        try {
            engine.evaluationTimeoutMillis = timeLimit
            engine.memoryLimit = memoryLimit
            // QuickJS for iOS hands the exception of a host function to the script as an error that the page may catch,
            // so the first one is kept and thrown after the run, where the JVM build has thrown it at once
            var refused: Exception? = null
            fun <T> host(work: suspend () -> T): T {
                refused?.let { throw it }
                return try {
                    runBlocking { work() }
                } catch (e: Exception) {
                    refused = e
                    throw e
                }
            }
            engine.function<String?>("hostUbus") { args -> host { read(args[0] as String, args[1] as String, args[2] as String) }?.let(::escaped) }
            engine.function<String?>("hostModule") { args -> host { module(args[0] as String) }?.let(::escaped) }
            engine.evaluate<Any?>(luciStub, "luci-stub.js")
            val thrown = try {
                engine.evaluate<Any?>(escaped("$entry(${Json.encodeToString(source)})"), path.substringAfterLast('/'))
                null
            } catch (e: QuickJsException) {
                e
            }
            refused?.let { throw it }
            // a page that cannot be evaluated, or does not end in its time, is left without a form; only the memory limit stops the work
            // on the rest. It is what the engine throws past the page, and QuickJS for iOS words it as a TypeError about null
            if (thrown is QuickJsInterruptedException) return@withContext emptyList()
            if (thrown != null) throw DeviceException(ErrorCategory.Api, thrown.message?.takeIf { OUT_OF_MEMORY in it } ?: OUT_OF_MEMORY)
            engine.evaluate<String?>("schemaError")?.takeIf { OUT_OF_MEMORY in it }?.let { throw DeviceException(ErrorCategory.Api, it) }
            if (engine.evaluate<Boolean>("typeof schemaResult === 'string' && schemaResult.length > $resultLimit")) {
                throw DeviceException(ErrorCategory.Api, "the form result exceeds $resultLimit characters")
            }
            engine.evaluate<String?>("schemaResult")?.let { result ->
                try {
                    luciJson.decodeFromString<Schema>(result).maps
                } catch (e: SerializationException) {
                    null
                }
            }.orEmpty()
        } finally {
            engine.close()
        }
    }

    private suspend fun objects() = lock.withLock { objects ?: ubus.objects().also { objects = it } }

    // with the ACL of LuCI alone a login over /ubus lists the menus and the views and may not read them: it has no forms
    private suspend fun file(path: String): String? = try {
        readFile(ubus, objects(), ssh, path, sourceLimit)
    } catch (e: UbusException) {
        if (e.status != UbusStatus.PERMISSION_DENIED) throw e
        null
    }?.also {
        if (it.encodeToByteArray().size > sourceLimit) throw DeviceException(ErrorCategory.Api, "a form source exceeds $sourceLimit bytes")
    }

    private suspend fun module(name: String): String? {
        if (name.length > MAX_FORM_MODULE_NAME_CHARS) throw DeviceException(ErrorCategory.Api, "a form module name exceeds $MAX_FORM_MODULE_NAME_CHARS characters")
        lock.withLock {
            if (name in modules) return modules[name]
            if (modules.size >= MAX_FORM_MODULES) throw DeviceException(ErrorCategory.Api, "the form has more than $MAX_FORM_MODULES modules")
        }
        val source = file("$RESOURCES/${name.replace('.', '/')}.js")
        lock.withLock {
            if (name in modules) return modules[name]
            if (modules.size >= MAX_FORM_MODULES) throw DeviceException(ErrorCategory.Api, "the form has more than $MAX_FORM_MODULES modules")
            val size = source?.encodeToByteArray()?.size ?: 0
            if (moduleBytes + size > sourceLimit) throw DeviceException(ErrorCategory.Api, "the form modules exceed $sourceLimit bytes")
            moduleBytes += size
            modules[name] = source
        }
        return source
    }

    private suspend fun read(obj: String, method: String, args: String): String? {
        if (!reads(obj, method)) return null
        return try {
            ubus.call(obj, method, Json.parseToJsonElement(args).jsonObject).toString()
        } catch (e: UbusException) {
            null
        }
    }

    private fun reads(obj: String, method: String) = when (obj) {
        "file" -> method in setOf("read", "list", "stat")
        "uci" -> method in setOf("get", "state", "configs")
        "session", "rc", "service" -> method == "list"
        else -> method.startsWith("get") || method in setOf("board", "info", "status", "dump", "devices", "assoclist", "freqlist", "txpowerlist", "countrylist")
    }

    // QuickJS breaks a character outside the basic plane, so it goes in as the escapes of its two halves
    private fun escaped(text: String): String =
        if (text.none { it.isSurrogate() }) text else buildString { for (c in text) if (c.isSurrogate()) append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c) }

    @Serializable
    private class Schema(val maps: List<LuciMap> = emptyList())

    private companion object {
        const val RESOURCES = "/www/luci-static/resources"
    }
}
