package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Controlled authenticated message boundary; never manufactures a store snapshot. */
internal class CompactRootFixture(
    scope: CoroutineScope,
    host: String = "h",
    store: AuthoritativeThreadStore = AuthoritativeThreadStore(),
    compact: Boolean = true,
) {
    private val input = Channel<Envelope>(Channel.UNLIMITED)
    val sent = mutableListOf<Envelope>()
    val repo =
        RemoteConversationRepository(
            object : SessionPump {
                override val inbound = input.receiveAsFlow()

                override fun send(envelope: Envelope): Boolean {
                    sent += envelope
                    return true
                }
            },
            scope,
            negotiatedCapabilities = {
                setOf(
                    "thread",
                    "interactive",
                    "thread_expansion",
                ) + if (compact) setOf("thread_compact") else emptySet()
            },
            authoritativeHostId = host,
            authoritativeThreads = store,
            suppliedCompactRootPages = true,
        )
    val roots get() = sent.filter { it.type in setOf("thread_catch_up", "thread_page") }
    val details get() = sent.filter { it.type in setOf("thread_read_tool_body", "thread_read_children") }

    fun push(
        raw: JsonObject,
        request: Envelope = roots.last(),
        type: String = "thread_items",
    ) {
        input.trySend(Envelope(900, type, "now", raw, inReplyTo = request.id)).getOrThrow()
    }

    fun batch(
        items: List<JsonObject> = emptyList(),
        headers: List<JsonObject> = emptyList(),
        version: Long = 100,
    ) = JsonObject(scope(version) + mapOf("items" to JsonArray(items), "headers" to JsonArray(headers), "final" to JsonPrimitive(false)))

    fun marker(
        lower: Long = 10,
        upper: Long = 101,
        older: Boolean = true,
        version: Long = 100,
    ) = JsonObject(
        scope(version) +
            mapOf(
                "items" to JsonArray(emptyList()),
                "final" to JsonPrimitive(true),
                "lower_order" to JsonPrimitive(lower),
                "upper_order" to JsonPrimitive(upper),
                "continuation" to JsonPrimitive(lower),
                "older_exists" to JsonPrimitive(older),
            ),
    )

    private fun scope(version: Long) =
        mapOf(
            "projection" to JsonPrimitive("compact-v1"),
            "conversation_id" to JsonPrimitive("c"),
            "epoch" to JsonPrimitive("e"),
            "version" to JsonPrimitive(version),
        )

    companion object {
        fun obj(raw: String) = MobileJson.parseToJsonElement(raw) as JsonObject

        fun header(
            id: Long,
            order: Long = id,
            rev: Long = id,
            facts: String = "",
            kind: String = "tool_call",
        ) = obj(
            """{"id":$id,"kind":"$kind","order":$order,"rev":$rev,"parent":0,"status":"done","active":false,"shown":true,"headline":"Root $id","detail_omitted":true$facts}""",
        )

        fun message(
            id: Long,
            order: Long = id,
            rev: Long = id,
        ) = obj(
            """{"id":$id,"kind":"assistant_message","order":$order,"rev":$rev,"parent":0,"status":"done","active":false,"shown":true,"summary":"Message $id","content":{"text":"Message $id"}}""",
        )
    }
}
