package com.vasmarfas.networkhelm.server

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileGatewayStoreTest {

    @Test
    fun writesOfOneDocumentFromTwoStoresAtOnceNeitherFailNorLeaveATemporaryFile() = runBlocking {
        val home = createTempDirectory("rd-test-gateway-store").toFile()
        val stores = List(2) { FileGatewayStore(home, Master(ByteArray(32)).sealing) }
        coroutineScope {
            repeat(40) { number -> launch { stores[number % 2].write("settings.json", "text $number") } }
        }
        assertTrue(stores[0].read("settings.json").orEmpty().startsWith("text "))
        assertEquals(listOf("settings.json"), File(home, "documents").list().orEmpty().sorted())
    }
}
