package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.sync.HlcClock
import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TwoCopiesTest {

    @Test
    fun twoCopiesOnOneFolderGiveARouterOneIdAndStampTheEditsOfEach() = runTest {
        val directory = createTempDirectory("networkhelm-copies").toFile()
        val first = SavedRouters(FileStorage(directory), HlcClock("first")).also { it.load() }
        val second = SavedRouters(FileStorage(directory), HlcClock("second")).also { it.load() }
        first.save(SavedRouter("192.0.2.1", "admin"))
        second.save(SavedRouter("192.0.2.2", "admin"))
        first.remove(SavedRouter("192.0.2.2", "admin"))
        second.save(SavedRouter("192.0.2.1", "admin", comment = "core"))
        first.load()

        assertEquals(first.records.all, second.records.all)
        val (core, removed) = first.records.all
        assertEquals(listOf(recordId("192.0.2.1", "admin"), recordId("192.0.2.2", "admin")), listOf(core.id, removed.id))
        assertEquals(JsonPrimitive("core"), core.data["comment"])
        assertEquals("second", core.hlc.device)
        assertTrue(removed.deleted)
        assertEquals("first", removed.hlc.device)
        assertTrue(core.hlc > removed.hlc)
        assertEquals(listOf(SavedRouter("192.0.2.1", "admin", comment = "core")), first.routers.value)
    }

    @Test
    fun twoCopiesStampTheSettingsThemesAndSnippetsEachOfThemChanged() = runTest {
        val directory = createTempDirectory("networkhelm-copies").toFile()
        val first = AppSettings(FileStorage(directory), HlcClock("first")).also { it.load() }
        val second = AppSettings(FileStorage(directory), HlcClock("second")).also { it.load() }
        first.change { it.copy(rowSpacing = 4) }
        second.change { it.copy(confirmRemoval = false) }
        first.keep(ThemeFile("First"))
        second.keep(ThemeFile("Second"))
        val restarted = AppSettings(FileStorage(directory), HlcClock("third")).also { it.load() }

        val settings = restarted.settingRecords.all.associateBy { it.id }
        assertEquals("first", settings.getValue("rowSpacing").hlc.device)
        assertEquals("second", settings.getValue("confirmRemoval").hlc.device)
        assertEquals(JsonPrimitive(4), settings.getValue("rowSpacing").data["value"])
        assertEquals(JsonPrimitive(false), settings.getValue("confirmRemoval").data["value"])
        assertEquals(listOf("first", "second"), restarted.themeRecords.all.map { it.hlc.device })

        val snippets = Snippets(FileStorage(directory), HlcClock("first")).also { it.load() }
        val other = Snippets(FileStorage(directory), HlcClock("second")).also { it.load() }
        snippets.save(emptyList(), listOf(Snippet("Routes", "/ip route print")))
        other.load()
        other.save(other.all.value, listOf(Snippet("Routes", "/ip route print detail")))
        snippets.load()
        assertEquals(other.records.all, snippets.records.all)
        assertEquals(listOf(true, false), snippets.records.all.map { it.deleted })
        assertEquals(recordId("Routes", "/ip route print detail"), snippets.records.all.last().id)
    }
}
