package com.vasmarfas.networkhelm.app

import com.vasmarfas.networkhelm.sync.recordId
import com.vasmarfas.networkhelm.ui.table.CommentMode
import com.vasmarfas.networkhelm.ui.theme.Legacy
import com.vasmarfas.networkhelm.ui.theme.ThemeFile
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OlderVersionTest {
    private val routerList = ListSerializer(Version130.SavedRouter.serializer())
    private val snippetList = ListSerializer(Version130.Snippet.serializer())
    private val themeList = ListSerializer(Version130.ThemeFile.serializer())

    private val routers = listOf(
        Version130.SavedRouter("192.0.2.1", "admin", group = "lab", comment = "core"),
        Version130.SavedRouter("192.0.2.2", "admin", Version130.Access.KeeneticSsl, ssh = "192.0.2.2:2222"),
        Version130.SavedRouter("192.0.2.3", "monitor", Version130.Access.Snmp3, snmpAuth = Version130.AuthProtocol.Md5, snmpPriv = Version130.PrivProtocol.Des),
    )
    private val snippets = listOf(Version130.Snippet("Routes", "/ip route print"), Version130.Snippet("", "/system resource print"))
    private val settings = Version130.Settings(theme = "Mine", accent = Version130.Colour("#2E7D32"), rowSpacing = 4, barHidden = setOf(Version130.BarItem.Disk))

    // a theme of 1.3.0 is read from what this version writes for one
    private val themes = listOf(Version130.settingsJson.decodeFromString(Version130.ThemeFile.serializer(), AppSettings.json.encodeToString(ThemeFile.serializer(), Legacy.copy(name = "Mine"))))

    private suspend fun olderFiles(): MemoryStorage {
        val storage = MemoryStorage()
        storage.write("routers.json", Version130.listJson.encodeToString(routerList, routers))
        storage.write("snippets.json", Version130.listJson.encodeToString(snippetList, snippets))
        storage.write("settings.json", Version130.settingsJson.encodeToString(Version130.Settings.serializer(), settings))
        storage.write("themes.json", Version130.settingsJson.encodeToString(themeList, themes))
        return storage
    }

    @Test
    fun theFilesOf130TakeRecordsAndStayInPlaceAndBesideAsTheyWere() = runTest {
        val storage = olderFiles()
        val files = listOf("routers.json", "snippets.json", "settings.json", "themes.json").associateWith { storage.read(it) }
        val saved = SavedRouters(storage).also { it.load() }
        val kept = Snippets(storage).also { it.load() }
        val app = AppSettings(storage).also { it.load() }

        for ((name, text) in files) {
            assertEquals(text, storage.read(name), name)
            assertEquals(text, storage.read(name.removeSuffix(".json") + "-1.3.json"), name)
        }
        val records = saved.records.all + kept.records.all + app.settingRecords.all + app.themeRecords.all
        assertTrue(records.all { it.hlc.time == 0L && !it.deleted })
        assertEquals(routers.map { recordId(it.address, it.user) }, saved.records.all.map { it.id })
        assertEquals(snippets.map { recordId(it.name, it.text) }, kept.records.all.map { it.id })

        // the records hold every entry whole, as 1.3.0 reads it
        assertEquals(routers, saved.records.all.map { Version130.listJson.decodeFromJsonElement(Version130.SavedRouter.serializer(), it.data) })
        assertEquals(snippets, kept.records.all.map { Version130.listJson.decodeFromJsonElement(Version130.Snippet.serializer(), it.data) })
        assertEquals(themes, app.themeRecords.all.map { Version130.settingsJson.decodeFromJsonElement(Version130.ThemeFile.serializer(), it.data) })
        val values = JsonObject(app.settingRecords.all.associate { it.id to checkNotNull(it.data["value"]) })
        assertEquals(settings, Version130.settingsJson.decodeFromJsonElement(Version130.Settings.serializer(), values))
    }

    @Test
    fun version130ReadsTheFilesAfterEveryKindOfEditOf140() = runTest {
        val storage = olderFiles()
        val saved = SavedRouters(storage).also { it.load() }
        saved.save(SavedRouter("192.0.2.4", "admin", Access.Ssh, comment = "new"))
        saved.remove(saved.routers.value.first { it.address == "192.0.2.1" })
        val kept = Snippets(storage).also { it.load() }
        kept.save(kept.all.value, kept.all.value.drop(1) + Snippet("Log", "/log print"))
        val app = AppSettings(storage).also { it.load() }
        app.change { it.copy(hidePasswords = false, comments = CommentMode.InColumn) }
        app.arrange { it.copy(listsBelow = true) }
        app.keep(ThemeFile("Second"))
        app.remove(app.themes.first { it.name == "Mine" })

        val routersRead = Version130.listJson.decodeFromString(routerList, checkNotNull(storage.read("routers.json")))
        assertEquals(listOf("192.0.2.2", "192.0.2.3", "192.0.2.4"), routersRead.map { it.address })
        assertEquals(Version130.SavedRouter("192.0.2.4", "admin", Version130.Access.Ssh, comment = "new"), routersRead.last())
        val snippetsRead = Version130.listJson.decodeFromString(snippetList, checkNotNull(storage.read("snippets.json")))
        assertEquals(listOf(Version130.Snippet("", "/system resource print"), Version130.Snippet("Log", "/log print")), snippetsRead)
        val settingsRead = Version130.settingsJson.decodeFromString(Version130.Settings.serializer(), checkNotNull(storage.read("settings.json")))
        assertEquals(settings.copy(hidePasswords = false, comments = Version130.CommentMode.InColumn, listsBelow = true), settingsRead)
        val themesRead = Version130.settingsJson.decodeFromString(themeList, checkNotNull(storage.read("themes.json")))
        assertEquals(listOf("Second"), themesRead.map { it.name })
    }

    @Test
    fun whatACopyOf130ChangesInTheFilesIsMatchedByIdAndStamped() = runTest {
        val storage = olderFiles()
        val saved = SavedRouters(storage).also { it.load() }
        val kept = Snippets(storage).also { it.load() }
        AppSettings(storage).load()

        // the copy of 1.3.0 changes a comment, removes a router, adds one, edits a snippet and a setting
        val listed = Version130.listJson.decodeFromString(routerList, checkNotNull(storage.read("routers.json")))
        val changed = listed.map { if (it.address == "192.0.2.1") it.copy(comment = "edge") else it }.filterNot { it.address == "192.0.2.2" } +
            Version130.SavedRouter("192.0.2.9", "root", Version130.Access.Rest)
        storage.write("routers.json", Version130.listJson.encodeToString(routerList, changed))
        storage.write("snippets.json", Version130.listJson.encodeToString(snippetList, listOf(snippets[0].copy(text = "/ip route print detail"), snippets[1])))
        storage.write("settings.json", Version130.settingsJson.encodeToString(Version130.Settings.serializer(), settings.copy(rowSpacing = 2)))

        val routersBefore = saved.records.all.associateBy { it.id }
        saved.load()
        kept.load()
        val restarted = AppSettings(storage).also { it.load() }
        val routersAfter = saved.records.all.associateBy { it.id }
        val edited = recordId("192.0.2.1", "admin")
        assertEquals(JsonPrimitive("edge"), routersAfter.getValue(edited).data["comment"])
        assertTrue(routersAfter.getValue(edited).hlc > routersBefore.getValue(edited).hlc)
        assertTrue(routersAfter.getValue(recordId("192.0.2.2", "admin")).deleted)
        assertEquals(routersBefore.getValue(recordId("192.0.2.3", "monitor")), routersAfter.getValue(recordId("192.0.2.3", "monitor")))
        assertEquals(JsonPrimitive("Rest"), routersAfter.getValue(recordId("192.0.2.9", "root")).data["protocol"])
        assertEquals(4, routersAfter.size)

        val snippetsAfter = kept.records.all.associateBy { it.id }
        assertTrue(snippetsAfter.getValue(recordId("Routes", "/ip route print")).deleted)
        assertTrue(snippetsAfter.getValue(recordId("Routes", "/ip route print detail")).hlc.time > 0)
        assertEquals(0L, snippetsAfter.getValue(recordId("", "/system resource print")).hlc.time)

        val stamped = restarted.settingRecords.all.filter { it.hlc.time > 0 }
        assertEquals(listOf("rowSpacing"), stamped.map { it.id })
        assertEquals(JsonPrimitive(2), stamped.single().data["value"])
    }
}
