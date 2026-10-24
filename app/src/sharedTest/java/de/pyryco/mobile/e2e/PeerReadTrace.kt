package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

internal fun peerReadIdentity(value: String?): String =
    value?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } } ?: "none"

/** Bounded metadata only. Arbitrary daemon strings never become diagnostic labels. */
internal fun peerReadTrace(frame: Envelope): String? {
    if (frame.type !in
        setOf(
            "thread_catch_up",
            "thread_items",
            "conversations",
            "list_conversations",
            "mark_conversation_read",
            "conversation_updated",
            "error",
        )
    ) {
        return null
    }

    fun JsonElement?.fields(): String {
        val obj = this as? JsonObject ?: return "invalid"

        fun primitive(key: String) = obj[key] as? JsonPrimitive

        fun number(key: String) =
            primitive(key)
                ?.takeUnless { it.isString }
                ?.content
                ?.toULongOrNull()
                ?.toString() ?: "none"

        fun flag(key: String) = primitive(key)?.takeUnless { it.isString }?.content?.takeIf { it == "true" || it == "false" } ?: "none"

        fun identity(key: String) = peerReadIdentity(primitive(key)?.content)
        val code =
            primitive("code")?.content?.takeIf {
                it in
                    setOf("history.unavailable", "protocol.malformed", "conversation.not_found", "read_mark.unavailable")
            }
                ?: "none"
        return "conversation=${identity("conversation_id")} row=${identity("id")} epoch=${identity("epoch")} " +
            "version=${number("version")} from=${number("from_version")} final=${flag("final")} " +
            "id=${number("id")} rev=${number("rev")} up_to=${number("up_to")} " +
            "read=${number("read_up_to")} shown=${number("last_shown_version")} " +
            "done=${primitive("status")?.content in setOf("done", "finished")} active=${flag("active")} code=$code"
    }
    val payload = frame.payload as? JsonObject
    val children = (payload?.get("items") ?: payload?.get("conversations")) as? JsonArray
    return "type=${frame.type} request=${frame.id} reply_to=${frame.inReplyTo} ${frame.payload.fields()} " +
        "count=${children?.size ?: 0} records=[${children?.take(8)?.joinToString(";") { it.fields() }.orEmpty()}]"
}
