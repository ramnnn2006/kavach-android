package app.kavach.android.core.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/** Postgres `timestamptz` ("2026-10-08T12:34:56.123456+00:00") <-> [Instant]. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("kavach.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = parseInstant(decoder.decodeString())
}

/** Postgres `date` ("2026-10-08") <-> [LocalDate]. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("kavach.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().take(10))
}

fun parseInstant(text: String): Instant =
    runCatching { OffsetDateTime.parse(text).toInstant() }.getOrElse {
        // "2026-10-08 12:34:56+00" (no T, short offset) from some RPC rows. Parsed as an offset
        // date-time: Instant.parse() on Android 8-11 only accepts a trailing "Z".
        val fixed = text.trim().replace(' ', 'T').let { if (SHORT_OFFSET.containsMatchIn(it)) "$it:00" else it }
        runCatching { OffsetDateTime.parse(fixed).toInstant() }.getOrElse { Instant.parse(fixed) }
    }

private val SHORT_OFFSET = Regex("[+-]\\d{2}$")

typealias IsoInstant = @kotlinx.serialization.Serializable(with = InstantSerializer::class) Instant
typealias IsoDate = @kotlinx.serialization.Serializable(with = LocalDateSerializer::class) LocalDate
