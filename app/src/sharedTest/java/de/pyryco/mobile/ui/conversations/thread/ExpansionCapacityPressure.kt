package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadQueries
import de.pyryco.mobile.data.repository.AuthoritativeThreadRoute
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import java.io.Closeable

/** Real bounded replies fill another conversation of this host, without adding rows to the mounted screen. */
internal fun occupyExpansionCapacity(
    store: AuthoritativeThreadStore,
    host: String,
): Closeable {
    val conversation = "capacity-fixture"

    fun json(text: String) = MobileJson.parseToJsonElement(text) as JsonObject
    val items =
        (1..64).map { id ->
            ThreadItemDto.decode(
                json(
                    """{"id":$id,"rev":4000,"order":0,"parent":0,"kind":"tool_call","status":"done","shown":true,"active":false,"summary":"pressure","detail_omitted":true,"content":{"name":"Read"}}""",
                ),
            )
        }
    store.establishComplete(host, conversation, "e", 4000, items, 0)
    var serial = 0L
    var request: Envelope? = null
    val lane =
        AuthoritativeThreadQueries(host, store, AuthoritativeThreadRoute(host, store), { true }, { ++serial }, {
            request = it
            true
        }, { 0L }, expansionEnabled = { true })
    val contents = JsonObject(mapOf("unused" to JsonPrimitive("x".repeat(256 * 1024 - "{\"unused\":\"\"}".length))))
    assertEquals(256 * 1024, contents.toString().toByteArray(Charsets.UTF_8).size)
    items.forEach { item ->
        lane.bodies(conversation, item, store.current(host, conversation), setOf(item.id))
        val sent = requireNotNull(request)
        for (final in listOf(false, true)) {
            val raw =
                json(
                    """{"conversation_id":"$conversation","epoch":"e","version":5000,"item_id":${item.id},"rev":4000,"kind":"tool_call","final":$final}""",
                )
            lane.accept(
                Envelope(
                    900,
                    "thread_tool_body",
                    "now",
                    JsonObject(raw + if (final) emptyMap() else mapOf("content" to contents)),
                    inReplyTo = sent.id,
                ),
            )
        }
    }
    assertEquals(64, store.current(host, conversation).bodyContents.size)
    return Closeable { lane.abandon() }
}
