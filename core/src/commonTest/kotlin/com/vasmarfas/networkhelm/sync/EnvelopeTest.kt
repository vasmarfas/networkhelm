package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeTest {
    private val path = statePath("a1")
    private val kindKey = ByteArray(32) { 7 }
    private val content = "{\"schema\":{\"major\":1,\"minor\":0},\"records\":[]}".encodeToByteArray()
    private val header = Header("state", "v1", 1, "a1", 3, SCHEMA)

    private suspend fun file() = seal(header, path, content, kindKey, ByteArray(32) { 1 }, ByteArray(24) { 2 })

    // the text, or null where the key of the file is not this one; a changed file throws
    private suspend fun opened(bytes: ByteArray, at: String = path, key: ByteArray = kindKey) = sealed(bytes, at).open(at, key)

    private suspend fun assertRefused(bytes: ByteArray, at: String = path) =
        assertEquals("$at was changed after it was written", assertFailsWith<DeviceException> { opened(bytes, at) }.message)

    private fun assertNotAFile(bytes: ByteArray) = assertEquals("$path is not a file of a NetworkHelm vault", assertFailsWith<DeviceException> { sealed(bytes, path) }.message)

    private fun JsonElement?.text() = (this as JsonPrimitive).content

    @Test
    fun aFileOpensWithItsKeyAndFillsWholeBlocksOf4Kib() = runTest {
        val bytes = file()
        val written = headerOf(bytes)
        assertEquals(0, bytes.size % 4096)
        assertEquals(header.copy(salt = written["salt"].text(), nonce = written["nonce"].text(), commitment = written["commitment"].text()), sealed(bytes, path).header)
        assertEquals(content.decodeToString(), opened(bytes)?.decodeToString()?.trimEnd())
        assertEquals(8192, seal(header, path, ByteArray(5000) { 'x'.code.toByte() }, kindKey).size)
    }

    @Test
    fun everyFieldOfTheHeaderChangedOrAddedIsRefused() = runTest {
        val bytes = file()
        val original = headerOf(bytes)
        val changes = mapOf(
            "kind" to JsonPrimitive("device"),
            "vault" to JsonPrimitive("v2"),
            "epoch" to JsonPrimitive(2),
            "author" to JsonPrimitive("a2"),
            "version" to JsonPrimitive(4),
            "schema" to fileJson.parseToJsonElement("{\"major\":1,\"minor\":9}"),
            "salt" to JsonPrimitive(Base64.encode(ByteArray(32) { 9 })),
            "nonce" to JsonPrimitive(Base64.encode(ByteArray(24) { 9 })),
            "added" to JsonPrimitive("by the service"),
        )
        for ((field, value) in changes) {
            val result = runCatching { opened(withHeader(bytes, JsonObject(original + (field to value)))) }
            assertTrue(result.isFailure || result.getOrNull() == null, field)
        }
    }

    @Test
    fun aCommitmentOfAnotherKeyIsRefusedBeforeAnythingIsDecrypted() = runTest {
        val bytes = file()
        val other = ByteArray(32) { 8 }
        assertNull(opened(bytes, key = other))
        // the commitment of the other key in place of the right one: the right key fails the commitment, the other one opens nothing
        val foreign = headerOf(seal(header, path, content, other, ByteArray(32) { 1 }, ByteArray(24) { 2 }))["commitment"]
        val swapped = withHeader(bytes, JsonObject(headerOf(bytes) + ("commitment" to checkNotNull(foreign))))
        assertNull(opened(swapped))
        assertEquals("$path was changed after it was written", assertFailsWith<DeviceException> { opened(swapped, key = other) }.message)
    }

    @Test
    fun aFileMovedToAnotherPlaceChangedOrCutShortIsRefused() = runTest {
        val bytes = file()
        assertRefused(bytes, statePath("a2"))
        assertRefused(bytes.copyOf(bytes.size - 1))
        assertRefused(bytes.copyOf().also { it[it.size - 100] = (it[it.size - 100].toInt() xor 1).toByte() })
        for (size in listOf(0, 3, 7, 200)) assertNotAFile(bytes.copyOf(size))
    }

    @Test
    fun aFileThatIsNotAnEnvelopeIsToldAsSuch() = runTest {
        val bytes = file()
        assertNotAFile("NHX".encodeToByteArray() + bytes.copyOfRange(3, bytes.size))
        assertNotAFile(withHeader(bytes, JsonObject(headerOf(bytes) - "salt")))
        assertNotAFile(withHeader(bytes, JsonObject(headerOf(bytes) + ("nonce" to JsonPrimitive("AAAA")))))
        assertNotAFile(withHeader(bytes, JsonObject(headerOf(bytes) + ("commitment" to JsonPrimitive("not base64")))))
        assertNotAFile(bytes.copyOf(5) + byteArrayOf(0, 2) + "[]".encodeToByteArray() + bytes.copyOfRange(7, bytes.size))
    }

    @Test
    fun anEnvelopeOfANewerVersionAsksForAnUpdate() = runTest {
        val error = assertFailsWith<DeviceException> { sealed(file().also { it[4] = 2 }, path) }
        assertEquals(ErrorCategory.Unsupported, error.category)
        assertEquals("$path was written by a newer version of the app: update it to synchronise", error.message)
    }

    @Test
    fun anUnknownFieldOfANewerVersionIsReadAndProtectedWithTheRest() = runTest {
        val key = Sodium.blake2b(ByteArray(32) { 1 }, kindKey, Sodium.KEY)
        val text = JsonObject(headerOf(file()) + ("future" to JsonPrimitive("field"))).toString().encodeToByteArray()
        val prefix = "NHS".encodeToByteArray() + byteArrayOf(0, 1, (text.size shr 8).toByte(), text.size.toByte()) + text
        val bytes = prefix + Sodium.encrypt(content, prefix + path.encodeToByteArray(), ByteArray(24) { 2 }, key)
        assertContentEquals(content, opened(bytes))
        assertRefused(withHeader(bytes, JsonObject(headerOf(bytes) + ("future" to JsonPrimitive("other")))))
    }
}
