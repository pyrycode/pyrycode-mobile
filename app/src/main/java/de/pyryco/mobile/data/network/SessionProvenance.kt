package de.pyryco.mobile.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Null raw means omitted; JsonNull is positive no-session provenance. */
@Serializable(with = SessionProvenanceSerializer::class)
data class SessionProvenance(
    val raw: JsonElement? = null,
) {
    val usable: Boolean get() =
        raw == null ||
            raw === JsonNull ||
            (raw as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true

    override fun toString(): String = "SessionProvenance(redacted)"
}

internal object SessionProvenanceSerializer : KSerializer<SessionProvenance> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SessionProvenance = SessionProvenance((decoder as JsonDecoder).decodeJsonElement())

    override fun serialize(
        encoder: Encoder,
        value: SessionProvenance,
    ) {
        (encoder as JsonEncoder).encodeJsonElement(value.raw ?: JsonNull)
    }
}
