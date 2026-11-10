package com.vasmarfas.networkhelm.miknative

import com.vasmarfas.networkhelm.model.DeviceException
import com.vasmarfas.networkhelm.model.ErrorCategory
import com.vasmarfas.networkhelm.packs.PackNative
import com.vasmarfas.networkhelm.packs.PackNativeField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val SET_RECORD = 0xFE0003L
private const val ADD = 0xFE0005L
private const val REMOVE = 0xFE0006L
private const val MOVE = 0xFE0007L
private const val SET_SINGLETON = 0xFE000EL

private class Writer(private val code: Long? = null, private val text: String? = null) {
    val asked = ArrayList<M2Message>()

    suspend fun FakeRouter.handle(message: M2Message) {
        val command = message.number(M2Key.Command)
        if (command !in setOf(SET_RECORD, ADD, REMOVE, MOVE, SET_SINGLETON) && command !in 0L..0xFFL) return
        asked += message
        if (code != null) return reply(message, *listOfNotNull(M2Key.ErrorCode to M2Value.U32(code), text?.let { M2Key.Error to M2Value.Text(it) }).toTypedArray())
        if (command == ADD) reply(message, 0xFE0001 to M2Value.U8(7)) else reply(message)
    }
}

class NativeObjectWriteTest {

    private val scripts = PackNative(
        listOf(48, 101),
        listOf(
            PackNativeField("name", 0x66, "string", letter = "s", writable = true),
            PackNativeField("dont-require-permissions", 0x74, "bool", letter = "b", writable = true),
            PackNativeField("run-count", 0x6D, "number", letter = "u"),
        ),
        edit = true,
        add = true,
        remove = true,
        move = true,
    )

    private val clock = PackNative(listOf(24, 0), listOf(PackNativeField("time-zone-name", 0x1A, "string", letter = "s", writable = true)), single = true, edit = true)

    private fun session(writer: Writer = Writer(), block: suspend CoroutineScope.(NativeObjectModel, Writer) -> Unit) = runTest {
        val (client, server) = pipe()
        val fake = FakeRouter(this, server, "admin", "secret") { with(writer) { handle(it) } }
        val connection = NativeConnection.open(client, "admin", "secret", this)
        try {
            withTimeout(30_000) { block(NativeObjectModel(connection), writer) }
        } finally {
            connection.close()
            fake.close()
        }
    }

    private fun M2Message.fieldsAfterTheHead() = fields.filter { it.key < 0xFF0000 }.map { it.key to it.value }

    @Test
    fun aRecordOfATableIsChangedWithItsIdAndOnlyTheFieldsGiven() = session { model, writer ->
        model.change(scripts, "*A", mapOf("name" to "rd-test", "dont-require-permissions" to "yes"))
        val asked = writer.asked.single()
        assertEquals(SET_RECORD, asked.number(M2Key.Command))
        assertEquals(listOf(48L, 101L), asked.numbers(M2Key.To)?.toList()?.takeLast(2))
        assertEquals(listOf(0xFE0001 to M2Value.U32(10), 0x66 to M2Value.Text("rd-test"), 0x74 to M2Value.Bool(true)), asked.fieldsAfterTheHead())
    }

    @Test
    fun aValueTakenOffGoesAsTheEmptyValueOfItsTypeAndItsKeyWithThatTypeAmongTheUnset() = session { model, writer ->
        model.change(scripts, "*A", mapOf("name" to null, "dont-require-permissions" to null))
        val asked = writer.asked.single()
        assertEquals(listOf(0xFE0001 to M2Value.U32(10), 0x66 to M2Value.Text(""), 0x74 to M2Value.Bool(false)), asked.fieldsAfterTheHead())
        assertEquals(listOf(0x20000066L, 0x00000074L), asked.numbers(M2Key.Unset))
    }

    @Test
    fun aButtonOfAWindowIsPressedWithACommandOfOneByteAndTheIdOfItsElement() = session { model, writer ->
        model.act(scripts, 7, "*A")
        model.act(clock, 9, null)
        val (row, holder) = writer.asked
        assertEquals(M2Value.U8(7), row[M2Key.Command])
        assertEquals(listOf(0xFE0001 to M2Value.U32(10)), row.fieldsAfterTheHead())
        assertEquals(M2Value.U8(9), holder[M2Key.Command])
        assertEquals(emptyList(), holder.fieldsAfterTheHead())
    }

    @Test
    fun anElementThatHoldsSettingsIsChangedWithoutAnId() = session { model, writer ->
        model.change(clock, null, mapOf("time-zone-name" to "Asia/Tokyo"))
        val asked = writer.asked.single()
        assertEquals(SET_SINGLETON, asked.number(M2Key.Command))
        assertEquals(listOf(0x1A to M2Value.Text("Asia/Tokyo")), asked.fieldsAfterTheHead())
    }

    @Test
    fun aNewElementIsAddedAndItsIdComesBackAsTheConsoleWritesIt() = session { model, writer ->
        assertEquals("*7", model.create(scripts, mapOf("name" to "rd-test")))
        assertEquals(ADD, writer.asked.single().number(M2Key.Command))
        assertEquals(listOf(0x66 to M2Value.Text("rd-test")), writer.asked.single().fieldsAfterTheHead())
        model.create(scripts, mapOf("name" to "next"), before = "*3")
        assertEquals(listOf(0x66 to M2Value.Text("next"), 0xFE0005 to M2Value.U32(3)), writer.asked.last().fieldsAfterTheHead())
    }

    @Test
    fun anElementIsRemovedByItsIdAndMovedBeforeAnotherOrToTheEnd() = session { model, writer ->
        model.delete(scripts, "*2")
        model.move(scripts, "*3", "*1")
        model.move(scripts, "*3", null)
        assertEquals(listOf(REMOVE, MOVE, MOVE), writer.asked.map { it.number(M2Key.Command) })
        assertEquals(
            listOf(listOf(0xFE0001 to M2Value.U32(2)), listOf(0xFE0001 to M2Value.U32(3), 0xFE0005 to M2Value.U32(1)), listOf(0xFE0001 to M2Value.U32(3))),
            writer.asked.map { it.fieldsAfterTheHead() },
        )
    }

    @Test
    fun aFieldThatIsNotWritableOrNotThereOrAnElementWithoutAnIdIsRefusedBeforeAnythingLeaves() = session { model, writer ->
        assertFailsWith<DeviceException> { model.change(scripts, "*1", mapOf("run-count" to "3")) }
        assertFailsWith<DeviceException> { model.change(scripts, "*1", mapOf("colour" to "red")) }
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { model.change(scripts, "#0", mapOf("name" to "a")) }.category)
        assertEquals(ErrorCategory.ArgumentValue, assertFailsWith<DeviceException> { model.change(scripts, "*1", mapOf("dont-require-permissions" to "maybe")) }.category)
        assertEquals(emptyList(), writer.asked)
    }

    @Test
    fun aValueTheRouterRejectsComesBackWithTheWordsOfTheRouter() = session(Writer(0xFE0006, "invalid time zone name")) { model, _ ->
        assertEquals("invalid time zone name", assertFailsWith<DeviceException> { model.change(clock, null, mapOf("time-zone-name" to "Nowhere/None")) }.message)
    }

    @Test
    fun anElementThatIsGoneIsAMissingItem() = session(Writer(0xFE0004)) { model, _ ->
        assertEquals(ErrorCategory.MissingItem, assertFailsWith<DeviceException> { model.change(scripts, "*9", mapOf("name" to "a")) }.category)
    }

    @Test
    fun aUserWithoutTheRightIsDenied() = session(Writer(0xFE0009)) { model, _ ->
        assertEquals(ErrorCategory.PermissionDenied, assertFailsWith<DeviceException> { model.delete(scripts, "*1") }.category)
    }

    @Test
    fun aCommandTheHandlerDoesNotHaveIsUnsupported() = session(Writer(0xFE0003)) { model, _ ->
        assertEquals(ErrorCategory.Unsupported, assertFailsWith<DeviceException> { model.move(scripts, "*1", null) }.category)
    }
}
