package com.vasmarfas.networkhelm.ui.main

import com.vasmarfas.networkhelm.openwrt.RequestOutcomeUnknownException
import kotlinx.coroutines.test.runTest
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ErrorTextTest {

    private val part = Regex("""%\d+[$]s""")

    private fun strings(folder: String): Map<String, String> = File("src/commonMain/composeResources/$folder").listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }.orEmpty().flatMap { file ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
    }.toMap()

    private fun placeholders(text: String) = part.findAll(text).map { it.value }.toList()

    private fun found(message: String) = errorResource(message)?.let { (key, parts) -> key.key to parts.toList() }

    @Test
    fun everyTemplateIsItsEnglishStringAndRussianKeepsItsParts() {
        val english = strings("values")
        val russian = strings("values-ru")
        val templates = allErrorTemplates()
        for ((template, key) in templates) {
            assertEquals(template, english[key.key], key.key)
            assertEquals(List(placeholders(template).size) { "%${it + 1}\$s" }, placeholders(template), key.key)
            assertEquals(placeholders(template).toSet(), placeholders(assertNotNull(russian[key.key], key.key)).toSet(), key.key)
        }
        assertEquals(templates.size, templates.map { it.first }.toSet().size)
    }

    @Test
    fun aMessageOfTheAppFindsItsNarrowestTemplateAndATextOfTheRouterNone() {
        assertEquals("error_keenetic_move_one" to emptyList<String>(), found("Keenetic moves one rule at a time"))
        assertEquals("error_host_no_address" to listOf("192.0.2.5/24"), found("the host has no address 192.0.2.5/24"))
        assertEquals("sync_error_sign_in_refused" to listOf("oauth.yandex.ru", "access_denied: the user said no"), found("oauth.yandex.ru refused the sign-in: access_denied: the user said no"))
        assertEquals("error_openwrt_acl" to listOf("Permission denied: /etc/config/rpcd"), found("the ACL was not laid on the router: Permission denied: /etc/config/rpcd"))
        assertEquals("error_no_rule" to listOf("ufw", "7"), found("ufw has no rule 7"))
        assertEquals("error_path_missing" to listOf("ip/dhcp/pool", "lan"), found("ip/dhcp/pool has no lan"))
        assertEquals("error_demo_no_files" to emptyList<String>(), found("this is a demo router: it makes no files"))
        assertEquals("error_request_outcome_unknown" to emptyList<String>(), found(RequestOutcomeUnknownException.MESSAGE))
        assertNull(found("no such item"))
        assertNull(found("input does not match any value of interface"))
        assertNull(found("mkdir: cannot create directory 'x': File exists"))
        assertNull(found("name: A name is required"))
    }

    @Test
    fun theRussianInterfaceTranslatesTheTextOfTheAppAndKeepsThatOfTheRouter() = runTest {
        Locale.setDefault(Locale.forLanguageTag("ru"))
        assertEquals("для этого нужен root, а на хосте нет sudo", deviceText("this needs root, and the host has no sudo"))
        assertEquals("ACL не установлен на роутер: Permission denied", deviceText("the ACL was not laid on the router: Permission denied"))
        assertEquals("в ip/dhcp/pool нет lan", deviceText("ip/dhcp/pool has no lan"))
        assertEquals("no such item", deviceText("no such item"))
        Locale.setDefault(Locale.ENGLISH)
    }
}
