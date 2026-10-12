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
