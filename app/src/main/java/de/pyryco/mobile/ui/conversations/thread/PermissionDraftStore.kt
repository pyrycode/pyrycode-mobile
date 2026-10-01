package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** An accepted "don't ask again this session" offer (#818), keyed on the request and the exact rules it offered. */
internal data class PermissionGrantDraft(
    val modalId: String,
    val rules: List<String>,
)

/**
 * Process-only session-grant drafts (#1306), keyed by server and conversation, so leaving a chat through Back
 * keeps the checkbox for the same outstanding request. A bound host's collector retires a draft as soon as that
 * request is resolved or the draft's conversation shows a different request or offer, including while no thread
 * for the conversation is open. Another conversation's prompt and a reconnect clear never retire it (#1337).
 */
class PermissionDraftStore(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val drafts = MutableStateFlow<Map<Pair<String, String>, PermissionGrantDraft>>(emptyMap())

    private class Binding(
        val owner: Any,
        val job: Job,
    )

    private val bindings = mutableMapOf<String, Binding>()

    /** Follow [serverId]'s host prompts. The same [owner] rebinds as a no-op; a new one starts that host afresh. */
    @Synchronized
    fun bind(
        serverId: String,
        owner: Any,
        modals: StateFlow<HostModalState>,
    ) {
        if (bindings[serverId]?.owner === owner) return
        bindings.remove(serverId)?.job?.cancel()
        clearHost(serverId)
        bindings[serverId] = Binding(owner, scope.launch { modals.collect { retireStale(serverId, owner, it) } })
    }

    @Synchronized
    private fun retireStale(
        serverId: String,
        owner: Any,
        modals: HostModalState,
    ) {
        if (bindings[serverId]?.owner !== owner) return
        val next = drafts.value.filter { (key, draft) -> key.first != serverId || keeps(modals, key.second, draft) }
        if (next.size != drafts.value.size) {
            RelayLog.d { "event=permission_grant_draft action=retired" }
            drafts.value = next
        }
    }

    /**
     * Whether [draft] survives [modals] for [conversationId]: retired once its request is resolved, or once the
     * conversation holds prompts and none is that request with that offer. A conversation holding no prompt keeps
     * it, so a reconnect clear does not untick a request the daemon re-sends unchanged; a stale draft stays
     * invisible because [ThreadViewModel.alwaysAllowAccepted] matches it against the shown prompt.
     */
    private fun keeps(
        modals: HostModalState,
        conversationId: String,
        draft: PermissionGrantDraft,
    ): Boolean {
        if (modals.resolved.any { it.modalId == draft.modalId }) return false
        val held = modals.outstanding.filter { it.conversationId == conversationId }
        return held.isEmpty() ||
            held.any { it.offersAlwaysAllow && draft == PermissionGrantDraft(it.modalId, it.alwaysAllowRules) }
    }

    internal fun observe(
        serverId: String,
        conversationId: String,
    ): Flow<PermissionGrantDraft?> = drafts.map { it[serverId to conversationId] }.distinctUntilChanged()

    internal fun current(
        serverId: String,
        conversationId: String,
    ): PermissionGrantDraft? = drafts.value[serverId to conversationId]

    @Synchronized
    internal fun set(
        serverId: String,
        conversationId: String,
        draft: PermissionGrantDraft?,
    ) {
        val key = serverId to conversationId
        drafts.value = if (draft == null) drafts.value - key else drafts.value + (key to draft)
        RelayLog.d { "event=permission_grant_draft action=${if (draft == null) "cleared" else "set"}" }
    }

    @Synchronized
    private fun clearHost(serverId: String) {
        drafts.value = drafts.value.filterKeys { it.first != serverId }
    }

    @Synchronized
    fun dispose() {
        scope.cancel()
        bindings.clear()
        drafts.value = emptyMap()
    }
}
