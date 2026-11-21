package com.vasmarfas.networkhelm.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// the saved files as NetworkHelm 1.3.0 (commit 50a07a0) reads and writes them: its classes, enums and Json, copied and kept as they were
object Version130 {
    val listJson = Json { prettyPrint = true }

    val settingsJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    enum class Access { ApiSsl, Api, RestSsl, Rest, Tcp8291, MacTelnet, OpenWrtSsh, OpenWrtUbusSsl, OpenWrtUbus, Ssh, Opnsense, Pfsense, KeeneticSsl, Keenetic, KeeneticSsh, Vyos, Snmp, Snmp3, Upnp }

    enum class ApiEncoding { Utf8, Windows1251 }

    enum class AuthProtocol { Md5, Sha1 }

    enum class PrivProtocol { Des, Aes }

    @Serializable
    data class SavedRouter(
        val address: String,
        val user: String,
        val protocol: Access = Access.ApiSsl,
        val group: String = "",
        val comment: String = "",
        val encoding: ApiEncoding = ApiEncoding.Utf8,
        val agent: Boolean = false,
        val via: String = "",
        val snmpAuth: AuthProtocol = AuthProtocol.Sha1,
        val snmpPriv: PrivProtocol = PrivProtocol.Aes,
        val ssh: String = "",
    )

    @Serializable
    data class Snippet(val name: String, val text: String)

    enum class BarItem { Router, Cpu, Memory, Disk, Traffic, Uptime, Clock }

    enum class CommentMode { Above, InColumn, Hidden }

    enum class ThemeFont { Inter, System }

    // HexColor of 1.3.0: six or eight hex digits after an optional #
    @Serializable(ColourText::class)
    data class Colour(val text: String)

    object ColourText : KSerializer<Colour> {
        override val descriptor = PrimitiveSerialDescriptor("Colour130", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Colour) = encoder.encodeString(value.text)

        override fun deserialize(decoder: Decoder): Colour {
            val text = decoder.decodeString()
            val digits = text.trim().removePrefix("#")
            if (digits.length != 6 && digits.length != 8 || digits.toLongOrNull(16) == null) throw SerializationException("$text is not a colour such as #1C2025")
            return Colour(text)
        }
    }

    @Serializable
    data class Settings(
        val language: String? = null,
        val theme: String = "Modern",
        val dark: Boolean? = null,
        val accent: Colour = Colour("#14202B"),
        val systemColours: Boolean = true,
        val scale: Float = 1f,
        val probe: Boolean = true,
        val confirmRemoval: Boolean = true,
        val tablesAsTabs: Boolean = false,
        val rowSpacing: Int = 0,
        val columnLines: Boolean = true,
        val comments: CommentMode = CommentMode.Above,
        val listsBelow: Boolean = false,
        val menuBelow: Boolean = false,
        val connectSplit: Float? = null,
        val resourceBar: Boolean = true,
        val barOrder: List<BarItem> = BarItem.entries,
        val barHidden: Set<BarItem> = emptySet(),
        val barInterface: String = "",
        val terminalLines: Int = 10000,
        val hidePasswords: Boolean = true,
        val checkUpdates: Boolean = true,
    )

    @Serializable
    data class Palette(
        val workspace: Colour,
        val surface: Colour,
        val raised: Colour,
        val line: Colour,
        val text: Colour,
        val muted: Colour,
        val faint: Colour,
        val accent: Colour,
        val onAccent: Colour,
        val selection: Colour,
        val badge: Colour,
        val danger: Colour,
        val success: Colour,
        val warning: Colour,
        val title: Colour? = null,
        val grid: Colour? = null,
        val frame: Colour? = null,
        val inactiveTitle: Colour? = null,
        val tab: Colour? = null,
        val divider: Colour? = null,
        val band: Colour? = null,
        val highlight: Colour? = null,
        val shadow: Colour? = null,
        val container: Colour? = null,
        val inverse: Colour? = null,
        val onInverse: Colour? = null,
    )

    // Dp of 1.3.0 is written as a number
    @Serializable
    data class Metrics(
        val row: Float = 24f,
        val menuItem: Float = 26f,
        val control: Float = 26f,
        val title: Float = 30f,
        val toolbar: Float = 28f,
        val icon: Float = 16f,
        val gap: Float = 6f,
        val padding: Float = 8f,
        val radius: Float = 4f,
    )

    @Serializable
    data class ThemeFile(
        val name: String,
        val light: Palette? = null,
        val dark: Palette? = null,
        val metrics: Metrics = Metrics(),
        val fontSize: Float = 13f,
        val font: ThemeFont = ThemeFont.Inter,
        val classic: Boolean = false,
        val material: Boolean = false,
        val icons: Map<String, String> = emptyMap(),
        val followsSystem: Boolean = true,
    )
}
