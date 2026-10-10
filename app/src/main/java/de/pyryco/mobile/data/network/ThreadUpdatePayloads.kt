package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonArray
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
                require(validThreadJson(raw))
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

// Tree parsing admits some non-JSON numeric tokens. Preserve valid unknown numbers without coercion.
private val threadJsonNumber = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun validThreadJson(root: JsonElement): Boolean {
    val remaining = ArrayDeque<JsonElement>()
    remaining.add(root)
    while (remaining.isNotEmpty()) {
        when (val value = remaining.removeLast()) {
            is JsonObject -> remaining.addAll(value.values)
            is JsonArray -> remaining.addAll(value)
            is JsonPrimitive ->
                if (!value.isString &&
                    value.content !in setOf("null", "true", "false") &&
                    !threadJsonNumber.matches(value.content)
                ) {
                    return false
                }
        }
    }
    return true
}

/** Count compact JSON bytes without recursive serialization or allocating the encoded payload. */
internal fun boundedThreadJsonSize(
    root: JsonElement,
    maximum: Int,
): Int? {
    var bytes = 0L

    fun charge(count: Long): Boolean {
        bytes += count
        return bytes <= maximum
    }

    fun string(
        text: String,
        quoted: Boolean,
    ): Boolean {
        if (quoted && !charge(2)) return false
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            val size =
                when {
                    quoted && (char == '"' || char == '\\' || char in "\b\t\n\u000c\r") -> 2
                    quoted && char < ' ' -> 6
                    char < '\u0080' -> 1
                    char < '\u0800' -> 2
                    char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                        index++
                        4
                    }
                    char.isSurrogate() -> 1 // Matches Java UTF-8 replacement in the existing serializer.
                    else -> 3
                }
            if (!charge(size.toLong())) return false
        }
        return true
    }
    val remaining = ArrayDeque<Iterator<JsonElement>>()
    remaining.add(listOf(root).iterator())
    while (remaining.isNotEmpty()) {
        val iterator = remaining.last()
        if (!iterator.hasNext()) {
            remaining.removeLast()
            continue
        }
        when (val value = iterator.next()) {
            is JsonObject -> {
                if (!charge(2L + value.size + (value.size - 1).coerceAtLeast(0))) return null
                for (key in value.keys) if (!string(key, true)) return null
                remaining.add(value.values.iterator())
            }
            is JsonArray -> {
                if (!charge(2L + (value.size - 1).coerceAtLeast(0))) return null
                remaining.add(value.iterator())
            }
            is JsonPrimitive -> if (!string(value.content, value.isString)) return null
        }
    }
    return bytes.toInt()
}

private enum class ThreadJsonState {
    Value,
    Done,
    FirstKey,
    Key,
    Colon,
    ObjectValue,
    ObjectEnd,
    FirstElement,
    Element,
    ArrayEnd,
}

private class ThreadJsonFrame(
    var state: ThreadJsonState,
    val fields: MutableMap<String, JsonElement>? = null,
    val elements: MutableList<JsonElement>? = null,
) {
    var key: String? = null
}

/** Stack-safe strict parsing; use MobileJson only for validated leaves, never nested containers. */
internal fun parseThreadJson(text: String): JsonObject? {
    var index = 0

    fun leaf(token: String): JsonPrimitive? =
        try {
            MobileJson.parseToJsonElement(token) as? JsonPrimitive
        } catch (_: IllegalArgumentException) {
            null
        }

    fun string(): JsonPrimitive? {
        val start = index
        if (text.getOrNull(index++) != '"') return null
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') return leaf(text.substring(start, index))
            if (char < ' ') return null
            if (char == '\\') {
                when (text.getOrNull(index++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' ->
                        repeat(4) {
                            val hex = text.getOrNull(index++) ?: return null
                            if (hex !in '0'..'9' && hex !in 'a'..'f' && hex !in 'A'..'F') return null
                        }
                    else -> return null
                }
            }
        }
        return null
    }
    val frames = ArrayDeque<ThreadJsonFrame>()
    frames.add(ThreadJsonFrame(ThreadJsonState.Value))
    var root: JsonElement? = null

    fun replace(state: ThreadJsonState) {
        frames.last().state = state
    }

    fun append(value: JsonElement) {
        val parent = frames.last()
        when {
            parent.fields != null -> parent.fields[requireNotNull(parent.key)] = value
            parent.elements != null -> parent.elements.add(value)
            else -> root = value
        }
    }

    fun close() {
        val frame = frames.removeLast()
        append(frame.fields?.let(::JsonObject) ?: JsonArray(requireNotNull(frame.elements)))
    }
    while (frames.isNotEmpty()) {
        while (index < text.length && text[index] in " \t\n\r") index++
        val char = text.getOrNull(index)
        when (val state = frames.last().state) {
            ThreadJsonState.Done -> return if (index == text.length) root as? JsonObject else null
            ThreadJsonState.FirstKey, ThreadJsonState.Key -> {
                if (state == ThreadJsonState.FirstKey && char == '}') {
                    index++
                    close()
                } else {
                    frames.last().key = (string() ?: return null).content
                    replace(ThreadJsonState.Colon)
                }
            }
            ThreadJsonState.Colon -> {
                if (char != ':') return null
                index++
                replace(ThreadJsonState.ObjectValue)
            }
            ThreadJsonState.ObjectEnd, ThreadJsonState.ArrayEnd -> {
                if (char == ',') {
                    index++
                    replace(if (state == ThreadJsonState.ObjectEnd) ThreadJsonState.Key else ThreadJsonState.Element)
                } else if (char == if (state == ThreadJsonState.ObjectEnd) '}' else ']') {
                    index++
                    close()
                } else {
                    return null
                }
            }
            else -> {
                if (state == ThreadJsonState.FirstElement && char == ']') {
                    index++
                    close()
                    continue
                }
                replace(
                    when (state) {
                        ThreadJsonState.Value -> ThreadJsonState.Done
                        ThreadJsonState.ObjectValue -> ThreadJsonState.ObjectEnd
                        else -> ThreadJsonState.ArrayEnd
                    },
                )
                when (char) {
                    '{', '[' -> {
                        index++
                        frames.add(
                            if (char ==
                                '{'
                            ) {
                                ThreadJsonFrame(ThreadJsonState.FirstKey, fields = mutableMapOf())
                            } else {
                                ThreadJsonFrame(ThreadJsonState.FirstElement, elements = mutableListOf())
                            },
                        )
                    }
                    '"' -> append(string() ?: return null)
                    else -> {
                        val start = index
                        while (index < text.length && text[index] !in " \t\n\r,]}:") index++
                        val token = text.substring(start, index)
                        if (token !in setOf("null", "true", "false") && !threadJsonNumber.matches(token)) return null
                        append(leaf(token) ?: return null)
                    }
                }
            }
        }
    }
    return null
}
