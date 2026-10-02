package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.McpStatusReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * The failed MCP servers the operator has acknowledged from the thread's notice (#1345), held for the life of
 * the app process like [UsageLimitDismissals]. Desktop's `acknowledgedFailures`.
 *
 * **Keyed by host, then conversation.** Conversation ids are host-local, so the host comes first, as in
 * [ComposerDraftStore]; acknowledging in one chat leaves every other chat's notice showing. Later reports
 * never prune an entry, so a server that recovers and fails again stays quiet for the rest of the run.
 * [clearHost] runs when the host's pairing is removed.
 *
 * **In-memory only and never logged.** The names are Claude-authored: they are compared only for equality and
 * never reach disk, a log line or an exception message. A plain class, so printing it shows no name.
 */
class McpFailureAcknowledgements {
    private val held = MutableStateFlow<Map<String, Map<String, Set<String>>>>(emptyMap())

    internal val acknowledged: StateFlow<Map<String, Map<String, Set<String>>>> = held.asStateFlow()

    fun observe(
        serverId: String,
        conversationId: String,
    ): Flow<Set<String>> =
        held
            .map { it[serverId]?.get(conversationId).orEmpty() }
            .distinctUntilChanged()

    fun acknowledge(
        serverId: String,
        conversationId: String,
        names: Collection<String>,
    ) {
        if (names.isEmpty()) return
        held.update { hosts ->
            val conversations = hosts[serverId].orEmpty()
            hosts + (serverId to conversations + (conversationId to conversations[conversationId].orEmpty() + names))
        }
    }

    fun clearHost(serverId: String) {
        held.update { it - serverId }
    }
}

/** Desktop's `isMcpServerFailed`: exactly `failed`. Any other status, however close, is not a failure. */
internal fun isMcpServerFailed(status: String): Boolean = status == "failed"

/**
 * Desktop's `selectUnacknowledgedMcpFailureFor`: the first server in [report] order that is failed and not in
 * [acknowledged], or `null`. The built-in filter of Channel info does not apply.
 */
internal fun firstUnacknowledgedMcpFailure(
    report: McpStatusReport?,
    acknowledged: Set<String>,
): String? = report?.servers?.firstOrNull { isMcpServerFailed(it.status) && it.name !in acknowledged }?.name

/** Every server [report] shows as failed: what one tap on the notice acknowledges. */
internal fun failedMcpServerNames(report: McpStatusReport?): Set<String> =
    report
        ?.servers
        .orEmpty()
        .filter { isMcpServerFailed(it.status) }
        .mapTo(LinkedHashSet()) { it.name }
