package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Supplied contract only: deliberately absent from production hello advertisement. */
internal const val CAPABILITY_THREAD = "thread"
internal const val TYPE_THREAD_ITEM_ADDED = "thread_item_added"
internal const val TYPE_THREAD_ITEM_CHANGED = "thread_item_changed"
internal const val TYPE_THREAD_TEXT_APPEND = "thread_text_append"

/**
 * Complete, structurally validated daemon fact. All strings and JSON remain untrusted inert data.
 * [raw] retains unknown fields; absent attribution is never inferred. Do not log or interpret content.
 */
internal class ThreadItemDto private constructor(
    val raw: JsonObject,
) {
    val id: Long = raw.threadInteger("id")
    val kind: String = raw.threadString("kind")
    val rev: Long = raw.threadInteger("rev")
    val order: Long? = raw.optionalInteger("order")
    val endedOrder: Long? = raw.optionalInteger("ended_order")
    val session: String? = raw.optionalString("session")
    val agent: String? = raw.optionalString("agent")
    val noChild: Boolean? = raw.optionalBoolean("no_child")
    val turn: String? = raw.optionalString("turn")
    val parent: Long? = raw.optionalInteger("parent")
    val status: String = raw.threadString("status")
    val active: Boolean = raw.threadBoolean("active")
    val shown: Boolean = raw.threadBoolean("shown")
    val summary: String = raw.threadString("summary")
    val subtype: String? = raw.optionalString("subtype")
    val content: JsonElement = requireNotNull(raw["content"])

    override fun toString(): String = "ThreadItemDto(content=***)"

    companion object {
        internal fun decode(raw: JsonObject): ThreadItemDto = ThreadItemDto(raw)
    }
}

/**
 * One entire logical update, never a fragment. Only [decode] creates structurally valid instances.
 * [changes] preserves key presence and whole JSON replacements, including explicit null.
 * [text] is the exact suffix, including empty; applying it and checking base revision belong to the store.
 */
internal class ThreadUpdateDto private constructor(
    val type: String,
    val raw: JsonObject,
) {
    val conversationId: String = raw.threadString("conversation_id")
    val epoch: String = raw.threadString("epoch")
    val version: Long = raw.threadInteger("version")
    val item: ThreadItemDto? =
        if (type ==
            TYPE_THREAD_ITEM_ADDED
        ) {
            ThreadItemDto.decode(raw["item"] as? JsonObject ?: invalidThreadField())
        } else {
            null
        }
    val itemId: Long = item?.id ?: raw.threadInteger("item_id")
    val rev: Long = item?.rev ?: raw.threadInteger("rev")
    val baseRev: Long? = if (type == TYPE_THREAD_ITEM_ADDED) null else raw.threadInteger("base_rev")
    val changes: JsonObject? = if (type == TYPE_THREAD_ITEM_CHANGED) raw["changes"] as? JsonObject ?: invalidThreadField() else null
    val text: String? = if (type == TYPE_THREAD_TEXT_APPEND) raw.threadString("text") else null

    internal fun metadata(): JsonObject =
        JsonObject(
            buildMap {
                put("conversation_id", JsonPrimitive(conversationId))
                put("epoch", JsonPrimitive(epoch))
                put("version", JsonPrimitive(version))
                put("item_id", JsonPrimitive(itemId))
                put("rev", JsonPrimitive(rev))
                baseRev?.let { put("base_rev", JsonPrimitive(it)) }
            },
        )

    override fun toString(): String = "ThreadUpdateDto(content=***)"

    companion object {
        internal fun decode(
            type: String,
            raw: JsonObject,
        ): ThreadUpdateDto? =
            try {
                require(type in setOf(TYPE_THREAD_ITEM_ADDED, TYPE_THREAD_ITEM_CHANGED, TYPE_THREAD_TEXT_APPEND))
                require("continuation" !in raw)
                ThreadUpdateDto(type, raw)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}

/** Tree decoding must not coerce quoted numbers, booleans or numeric strings into routing facts. */
internal fun JsonObject.threadString(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: invalidThreadField()
    require(primitive.isString)
    return primitive.content
}

internal fun JsonObject.threadInteger(
    key: String,
    maximum: Long = 9007199254740991L,
): Long {
    val primitive = this[key] as? JsonPrimitive ?: invalidThreadField()
    val token = primitive.content
    require(!primitive.isString && token.isNotEmpty() && token.all { it in '0'..'9' })
    require(token == "0" || token[0] != '0')
    val value = token.toLongOrNull() ?: invalidThreadField()
    require(value <= maximum)
    return value
}

internal fun JsonObject.threadBoolean(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: invalidThreadField()
    require(!primitive.isString)
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> invalidThreadField()
    }
}

private fun JsonObject.optionalString(key: String): String? = if (key in this) threadString(key) else null

private fun JsonObject.optionalInteger(key: String): Long? = if (key in this) threadInteger(key) else null

private fun JsonObject.optionalBoolean(key: String): Boolean? = if (key in this) threadBoolean(key) else null

private fun invalidThreadField(): Nothing = throw IllegalArgumentException("invalid thread field")
