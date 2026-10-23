package de.pyryco.mobile.data.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Supplied query contract only; no production advertisement, scheduling or row-count cutoff. */
internal sealed class ThreadQueryRequest(
    val conversationId: String,
    val limit: Long?,
) {
    init {
        require(limit == null || limit >= 0) { "invalid thread query limit" }
    }

    /** Ordered window rows only; a nonzero catch-up is never truncated by this limit. */
    val windowLimit: Int = if (limit == null || limit == 0L) 50 else limit.coerceAtMost(256).toInt()
    abstract val type: String

    protected abstract fun fields(): JsonObject

    fun payload(): JsonObject = JsonObject(fields() + buildMap { limit?.let { put("limit", JsonPrimitive(it.coerceAtMost(256))) } })

    final override fun toString(): String = "ThreadQueryRequest(redacted)"

    sealed class Expansion(
        conversation: String,
        open val epoch: String,
        open val itemId: ULong,
        open val expectedRev: ULong,
        limit: Long?,
    ) : ThreadQueryRequest(conversation, limit)

    data class Children(
        val conversation: String,
        override val epoch: String,
        override val itemId: ULong,
        override val expectedRev: ULong,
        val cursor: JsonObject? = null,
    ) : Expansion(conversation, epoch, itemId, expectedRev, 50) {
        init {
            require(epoch.isNotEmpty() && itemId > 0uL && expectedRev > 0uL)
        }

        override val type = "thread_read_children"

        override fun fields() =
            JsonObject(
                buildMap {
                    put("conversation_id", JsonPrimitive(conversation))
                    put("epoch", JsonPrimitive(epoch))
                    put("item_id", threadUnsignedJson(itemId))
                    put("expected_rev", threadUnsignedJson(expectedRev))
                    cursor?.let { put("cursor", it) }
                },
            )
    }

    data class Body(
        val conversation: String,
        override val epoch: String,
        override val itemId: ULong,
        override val expectedRev: ULong,
        val kind: String,
    ) : Expansion(conversation, epoch, itemId, expectedRev, null) {
        init {
            require(epoch.isNotEmpty() && itemId > 0uL && expectedRev > 0uL && kind in setOf("tool_call", "agent"))
        }

        override val type = "thread_read_tool_body"

        override fun fields() =
            JsonObject(
                mapOf(
                    "conversation_id" to JsonPrimitive(conversation),
                    "epoch" to JsonPrimitive(epoch),
                    "item_id" to threadUnsignedJson(itemId),
                    "expected_rev" to threadUnsignedJson(expectedRev),
                ),
            )
    }

    class CatchUp(
        conversationId: String,
        val epoch: String,
        val version: ULong,
        limit: Long? = null,
    ) : ThreadQueryRequest(conversationId, limit) {
        override val type: String = "thread_catch_up"

        override fun fields(): JsonObject =
            JsonObject(
                mapOf(
                    "conversation_id" to JsonPrimitive(conversationId),
                    "epoch" to JsonPrimitive(epoch),
                    "version" to threadUnsignedJson(version),
                ),
            )
    }

    class Page(
        conversationId: String,
        val beforeOrder: ULong,
        limit: Long? = null,
    ) : ThreadQueryRequest(conversationId, limit) {
        override val type: String = "thread_page"

        override fun fields(): JsonObject =
            JsonObject(
                mapOf(
                    "conversation_id" to JsonPrimitive(conversationId),
                    "before_order" to threadUnsignedJson(beforeOrder),
                ),
            )
    }
}

/** Batches are complete facts, never completion evidence; only Success carries coverage. */
internal sealed class ThreadQueryReply(
    val raw: JsonObject,
) {
    final override fun toString(): String = "ThreadQueryReply(content=***)"

    class Checkpoint(
        raw: JsonObject,
    ) {
        val conversationId: String = raw.threadString("conversation_id")
        val epoch: String = raw.threadString("epoch")
        val version: ULong = raw.threadUnsigned("version")

        override fun toString(): String = "ThreadQueryReply.Checkpoint(redacted)"
    }

    sealed class Coverage {
        final override fun toString(): String = "ThreadQueryReply.Coverage(redacted)"

        class CatchUp(
            val fromVersion: ULong,
        ) : Coverage()

        class Window(
            val lowerOrder: ULong,
            val upperOrder: ULong,
            val continuation: ULong,
            val olderExists: Boolean,
        ) : Coverage()
    }

    class Batch(
        raw: JsonObject,
        val checkpoint: Checkpoint,
        val items: List<ThreadItemDto>,
    ) : ThreadQueryReply(raw)

    class Body(
        raw: JsonObject,
    ) : ThreadQueryReply(raw) {
        val checkpoint = Checkpoint(raw)
        val itemId = raw.threadUnsigned("item_id")
        val rev = raw.threadUnsigned("rev")
        val kind = raw.threadString("kind")
        val final = raw.threadBoolean("final")
        val content = raw["content"] as? JsonObject

        init {
            require(kind in setOf("tool_call", "agent") && itemId > 0uL && rev > 0uL)
            require(if (final) "content" !in raw else content != null)
            require((threadQueryCoverageKeys + setOf("items", "data", "base_rev")).none { it in raw })
        }
    }

    class Children(
        raw: JsonObject,
    ) : ThreadQueryReply(raw) {
        val checkpoint = Checkpoint(raw)
        val itemId = raw.threadUnsigned("item_id")
        val rev = raw.threadUnsigned("rev")
        val final = raw.threadBoolean("final")
        val children =
            (raw["children"] as? JsonArray ?: throw IllegalArgumentException("invalid children")).also { require(it.size <= 256) }.map {
                decodeThreadSummary(it as? JsonObject ?: throw IllegalArgumentException("invalid summary"))
            }
        val more = if (final) raw.threadBoolean("more") else null
        val cursor = raw["cursor"] as? JsonObject

        init {
            require(itemId > 0uL && rev > 0uL)
            require((threadQueryCoverageKeys + setOf("items", "content", "data", "base_rev")).none { it in raw })
            require(
                if (final) {
                    children.isEmpty() && (more == true) == ("cursor" in raw)
                } else {
                    "more" !in raw &&
                        "cursor" !in raw &&
                        children.isNotEmpty()
                },
            )
            require("cursor" !in raw || cursor != null)
        }
    }

    class Success(
        raw: JsonObject,
        val checkpoint: Checkpoint,
        val coverage: Coverage,
    ) : ThreadQueryReply(raw)

    class Reset(
        raw: JsonObject,
        val checkpoint: Checkpoint,
    ) : ThreadQueryReply(raw)

    /** Existing error DTO plus raw optional fields, including retry_after_s and conversation_id. */
    class Failure(
        raw: JsonObject,
        val error: ErrorPayload,
    ) : ThreadQueryReply(raw)
}

internal val threadQueryCoverageKeys = setOf("from_version", "lower_order", "upper_order", "continuation", "older_exists")

internal fun decodeThreadQueryReply(
    type: String,
    raw: JsonObject,
): ThreadQueryReply? =
    try {
        require(validThreadJson(raw))
        when (type) {
            "thread_children" -> ThreadQueryReply.Children(raw)
            "thread_tool_body" -> ThreadQueryReply.Body(raw)
            "thread_items" -> {
                val checkpoint = ThreadQueryReply.Checkpoint(raw)
                val items =
                    (raw["items"] as? JsonArray ?: throw IllegalArgumentException("invalid thread batch")).map {
                        ThreadItemDto.decode(it as? JsonObject ?: throw IllegalArgumentException("invalid thread item"))
                    }
                require("data" !in raw && "item_id" !in raw && "rev" !in raw && "base_rev" !in raw)
                if (!raw.threadBoolean("final")) {
                    require(threadQueryCoverageKeys.none { it in raw })
                    ThreadQueryReply.Batch(raw, checkpoint, items)
                } else {
                    require(items.isEmpty())
                    val coverage =
                        if ("from_version" in raw) {
                            require((threadQueryCoverageKeys - "from_version").none { it in raw })
                            val from = raw.threadUnsigned("from_version")
                            require(from > 0uL && from <= checkpoint.version)
                            ThreadQueryReply.Coverage.CatchUp(from)
                        } else {
                            val lower = raw.threadUnsigned("lower_order")
                            val upper = raw.threadUnsigned("upper_order")
                            val next = raw.threadUnsigned("continuation")
                            require(lower <= upper && next == lower)
                            ThreadQueryReply.Coverage.Window(lower, upper, next, raw.threadBoolean("older_exists"))
                        }
                    ThreadQueryReply.Success(raw, checkpoint, coverage)
                }
            }
            "thread_reset" -> {
                require((threadQueryCoverageKeys + setOf("items", "final", "data", "item_id", "rev", "base_rev")).none { it in raw })
                ThreadQueryReply.Reset(raw, ThreadQueryReply.Checkpoint(raw))
            }
            "error" -> {
                if ("conversation_id" in raw) raw.threadString("conversation_id")
                if ("retry_after_s" in raw) raw.threadInteger("retry_after_s", Long.MAX_VALUE)
                val minimum = if ("min_client_version" in raw) raw.threadString("min_client_version") else null
                ThreadQueryReply.Failure(
                    raw,
                    ErrorPayload(raw.threadString("code"), raw.threadString("message"), raw.threadBoolean("retryable"), minimum),
                )
            }
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

/** Never route unsigned history tokens through signed Long or floating point. */
internal fun JsonObject.threadUnsigned(key: String): ULong {
    val primitive = this[key] as? JsonPrimitive ?: throw IllegalArgumentException("invalid thread unsigned field")
    val token = primitive.content
    require(!primitive.isString && token.isNotEmpty() && token.all { it in '0'..'9' } && (token == "0" || token[0] != '0'))
    return requireNotNull(token.toULongOrNull()) { "invalid thread unsigned field" }
}

internal fun threadUnsignedJson(value: ULong) = MobileJson.parseToJsonElement(value.toString())

/** Summary metadata stays inert and separate from full facts; no body or inventory is admitted. */
private fun decodeThreadSummary(raw: JsonObject): ThreadItemDto {
    require(setOf("content", "children", "items", "descendants").none { it in raw })
    require(raw.threadInteger("id") > 0 && raw.threadInteger("rev") > 0)
    raw.threadInteger("order")
    raw.threadInteger("parent")
    raw.threadBoolean("shown")
    require(raw.threadBoolean("detail_omitted"))

    fun text(key: String) {
        val value = raw.threadString(key)
        require(value.codePointCount(0, value.length) <= 200 && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' })
    }
    text("headline")
    if ("error_summary" in raw) text("error_summary")
    if ("subtree_version" in raw) raw.threadInteger("subtree_version")
    if ("has_public_children" in raw) raw.threadBoolean("has_public_children")
    if ("public_child_count" in raw) {
        val count = raw.threadInteger("public_child_count")
        require(raw.threadBoolean("has_public_children") == (count > 0))
    }
    return ThreadItemDto.decode(
        JsonObject(
            raw + mapOf("summary" to raw.getValue("headline"), "content" to kotlinx.serialization.json.JsonNull),
        ),
    )
}
