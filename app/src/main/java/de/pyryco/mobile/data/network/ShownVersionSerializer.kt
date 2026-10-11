package de.pyryco.mobile.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

/** A malformed reading is unavailable, so it cannot preserve stale usable metadata. */
internal object ShownVersionSerializer : KSerializer<ULong?> {
    override val descriptor = ULong.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): ULong? {
        val value = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return null
        return try {
            JsonObject(mapOf("version" to value)).threadInteger("version").toULong()
        } catch (_: IllegalArgumentException) {
            RelayLog.d { "event=thread_shown_metadata code=unavailable" }
            null
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: ULong?,
    ) = encoder.encodeSerializableValue(ULong.serializer().nullable, value)
}
