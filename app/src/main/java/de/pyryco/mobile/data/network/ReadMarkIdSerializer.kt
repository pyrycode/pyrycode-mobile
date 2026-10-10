package de.pyryco.mobile.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Strict uint64 boundary for read facts; default serializers may coerce quoted numbers. */
internal object ReadMarkIdSerializer : KSerializer<ULong> {
    override val descriptor = PrimitiveSerialDescriptor("ReadMarkId", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): ULong {
        val token = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
        if (token == null || token.isString) {
            throw SerializationException("Invalid durable read id")
        }
        return parseReadMarkDecimal(token.content) ?: throw SerializationException("Invalid durable read id")
    }

    override fun serialize(
        encoder: Encoder,
        value: ULong,
    ) = encoder.encodeSerializableValue(ULong.serializer(), value)
}

/** One strict decimal pass; the length/maximum guard makes unsigned multiplication overflow-free. */
internal fun parseReadMarkDecimal(text: String): ULong? {
    if (text.isEmpty() || text.length > 20 || text.length > 1 && text[0] == '0') return null
    if (text.length == 20 && text > "18446744073709551615") return null
    var value = 0uL
    for (character in text) {
        if (character !in '0'..'9') return null
        value = value * 10u + (character - '0').toULong()
    }
    return value
}
