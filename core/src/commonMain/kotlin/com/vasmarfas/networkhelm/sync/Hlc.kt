package com.vasmarfas.networkhelm.sync

import com.vasmarfas.networkhelm.keenetic.hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.random.Random
import kotlin.time.Clock

// a stamp of the hybrid logical clock: milliseconds, a counter for the edits that cannot move the time on, and the writer for a tie
@Serializable(HlcText::class)
data class Hlc(val time: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc) = compareValuesBy(this, other, { it.time }, { it.counter }, { it.device })

    override fun toString() = "${time.toString().padStart(13, '0')}-${counter.toString().padStart(4, '0')}-$device"

    companion object {
        fun parse(text: String): Hlc? {
            val parts = text.split('-', limit = 3)
            if (parts.size != 3 || parts[2].isEmpty()) return null
            val time = parts[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val counter = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return Hlc(time, counter, parts[2])
        }
    }
}

object HlcText : KSerializer<Hlc> {
    override val descriptor = PrimitiveSerialDescriptor("Hlc", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Hlc) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Hlc {
        val text = decoder.decodeString()
        return Hlc.parse(text) ?: throw SerializationException("$text is not a stamp such as 1763400000000-0000-5f0c2a9e41d7b388")
    }
}

// the writer is random for every clock, not the device: two copies of the app on one folder then never give two edits one stamp
class HlcClock(val device: String = hex(Random.nextBytes(8)), private val clock: Clock = Clock.System) {
    private val last = MutableStateFlow(Hlc(0, 0, device))

    // later than every stamp this clock gave or saw, though the time of the system went back
    fun now(): Hlc = last.updateAndGet { before ->
        val time = clock.now().toEpochMilliseconds()
        if (time > before.time) Hlc(time, 0, device) else Hlc(before.time, before.counter + 1, device)
    }

    fun observe(stamp: Hlc) = last.update { before ->
        if (stamp.time > before.time || stamp.time == before.time && stamp.counter > before.counter) Hlc(stamp.time, stamp.counter, device) else before
    }
}
