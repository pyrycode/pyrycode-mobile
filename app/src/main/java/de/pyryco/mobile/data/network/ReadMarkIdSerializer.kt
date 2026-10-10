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
        if (token == null ||
            token.isString ||
            token.content.isEmpty() ||
            token.content.length > 20 ||
            token.content.any { it !in '0'..'9' } ||
            (token.content.length > 1 && token.content.first() == '0')
        ) {
            throw SerializationException("Invalid durable read id")
        }
        return token.content.toULongOrNull() ?: throw SerializationException("Invalid durable read id")
    }

    override fun serialize(
        encoder: Encoder,
        value: ULong,
    ) = encoder.encodeSerializableValue(ULong.serializer(), value)
}
