package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SlashCommandListPayloadDto
import de.pyryco.mobile.data.network.toMenu
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The slash-command menu of every conversation on one connection (#882). The [ModelMenuProjection] shape
 * without its ask: `slash_command_list` declares no inbound verb, so there is nothing to send and no refusal
 * to correlate. The repository keeps the routing: its `onInbound` arm calls [apply] only behind the negotiated
 * `interactive` gate, and [RemoteConversationRepository.observeSlashCommandMenu] reads [observe].
 *
 * One instance per repository, and a fresh repository per connection (#351), so a reconnect or host switch
 * starts empty and the daemon's connect-time snapshot fills it again. Nothing here logs.
 */
internal class SlashCommandMenuProjection {
    /**
     * `conversationId -> the slash-command menu this connection heard for it`. Written **only** from the
     * repository's single inbound collector: each frame is a full snapshot that **replaces** that
     * conversation's entry and leaves every other conversation untouched. The atomic [MutableStateFlow.update]
     * matches the sibling projections' memory-visibility posture.
     *
     * Nothing removes a key and no connection edge clears the map: absence of a frame is the wire's only
     * "no menu" signal, so a clear would manufacture a reading the daemon never stated.
     */
    private val menusByConversation = MutableStateFlow<Map<String, SlashCommandMenu>>(emptyMap())

    /**
     * Apply one `slash_command_list` envelope. Routing is the payload's own `conversation_id` and nothing
     * else — never the envelope id, which every frame in the connect-time burst repeats, and never burst
     * position. An unreadable frame writes nothing, so the previously retained menu stands and the single
     * inbound collector survives. Like the `model_list` arm this folds no thread row and clears no stall: a
     * menu is not turn forward progress. Dropped silently, since every row string is workspace-authored.
     */
    fun apply(envelope: Envelope) {
        decodeSlashCommandList(envelope)?.let { (conversationId, menu) ->
            menusByConversation.update { it + (conversationId to menu) }
        }
    }

    /**
     * The menu this connection heard for [conversationId], or `null` when it heard none. A cold projection of
     * the shared map; [distinctUntilChanged] keeps a frame for another conversation, and a value-identical
     * re-snapshot, from re-emitting. Sends nothing on subscription — there is no verb to ask with.
     */
    fun observe(conversationId: String): Flow<SlashCommandMenu?> =
        menusByConversation
            .map { it[conversationId] }
            .distinctUntilChanged()

    /**
     * Decode one envelope to its routing conversation id and [SlashCommandMenu], or **null** when it cannot be
     * used. One `try`/`catch (IllegalArgumentException)` around the [MobileJson] decode
     * ([kotlinx.serialization.SerializationException] is a subtype), so a missing or wrong-typed field, a
     * `commands` or `aliases` that is explicitly `null`, or a row missing a required string drops the whole
     * frame. The caught throwable is discarded because its message can quote the offending input.
     *
     * An **empty `conversation_id`** is also dropped: it names no conversation, so there is no usable routing
     * key (the daemon's zero-value fixture carries one). This is the one step beyond `ModelMenuProjection`'s
     * decoder, which would retain such a frame under `""`.
     */
    private fun decodeSlashCommandList(envelope: Envelope): Pair<String, SlashCommandMenu>? =
        try {
            val dto = MobileJson.decodeFromJsonElement<SlashCommandListPayloadDto>(envelope.payload)
            if (dto.conversationId.isEmpty()) null else dto.conversationId to dto.toMenu()
        } catch (e: IllegalArgumentException) {
            null
        }
}
