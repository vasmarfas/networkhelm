package com.vasmarfas.networkhelm.session

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.model.Item
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeRef

// what the router tells about itself and the conditions of its interface descriptions ask for
data class RouterFacts(
    val board: String? = null,
    val family: String? = null,
    val arch: String? = null,
    val capabilities: List<Long> = emptyList(),
    val quickSet: Long? = null,
    val addons: List<String> = emptyList(),
)

interface NativeObjects {
    suspend fun read(spec: PackNative, limit: Int? = null): List<Item>?

    // the table as the router sends it to a subscription, a page at a time as the pages come; null where the router does not send it that way
    suspend fun dump(spec: PackNative, expected: Int, page: suspend (List<Item>) -> Unit): List<Item>? = null

    suspend fun facts(): RouterFacts? = null

    // whether the window of the handler is on the router: its read answers, or is refused in a way that keeps the window
    suspend fun answers(spec: PackNative): Boolean = try {
        read(spec, 1)
        true
    } catch (e: DeviceException) {
        e.keepsWindow()
    }

    // the fields are named as read() names them; a value of null takes the field back, and the id of an element is the one read() gave it
    suspend fun change(spec: PackNative, id: String?, values: Map<String, String?>) {
        throw readOnly()
    }

    suspend fun create(spec: PackNative, values: Map<String, String>, before: String? = null): String = throw readOnly()

    suspend fun delete(spec: PackNative, id: String) {
        throw readOnly()
    }

    suspend fun move(spec: PackNative, id: String, before: String?) {
        throw readOnly()
    }

    // a button of the window: the number of its command, and the element it is pressed for
    suspend fun act(spec: PackNative, command: Int, id: String?) {
        throw readOnly()
    }

    // the rows of the table a field refers to, by their names: the choices of a dropdown
    suspend fun names(ref: PackNativeRef): Map<Long, String> = emptyMap()

    // starts the query or the action of a window with the fields the user touched
    suspend fun start(spec: PackNative, values: Map<String, String>): NativeRun = throw readOnly()

    // the first read of a log gives the lines the router holds, each next one those added since; a read that finds the router has dropped the line it went on from gives all again as a snapshot
    suspend fun lines(spec: PackNative): NativeRun = throw DeviceException(ErrorCategory.Unsupported, "this connection cannot follow a log")

    // a file of the web interface the router serves on the channel, by its name; null where the router has no such file
    suspend fun resource(name: String): ByteArray? = null

    fun close()
}

// what a started query or action gives back until it ends: a table pass replaces what the window shows (snapshot), the rows of a log are added to it, an action is one row
class NativeRead(val rows: List<Item>, val snapshot: Boolean, val finished: Boolean)

interface NativeRun {
    suspend fun read(): NativeRead

    suspend fun cancel()
}

// run() of a session tells the passes over a table apart from the rows of a log by these: a row of a pass has the number of the pass, and the item that ends the pass has none of its own values
const val QUERY_PASS = ".pass"

const val QUERY_END = ".end"

// a window stays unless the router refuses its handler outright
fun DeviceException.keepsWindow() = category != ErrorCategory.General && category != ErrorCategory.Unsupported

private fun readOnly() = DeviceException(ErrorCategory.Unsupported, "this connection cannot write")

fun interface NativeSide {
    suspend fun open(port: Int): NativeObjects
}
