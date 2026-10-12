package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/** The permission-held scenario's initial snapshot, following the addressed host through redial. */
internal suspend fun StateFlow<ConversationRepository?>.awaitPermissionHeldConversationIds(timeoutMs: Long): Set<String> =
    permissionHeldConversationIds(timeoutMs) { true }

/** The newly created chat, with the same list predicate and single-new-identity requirement. */
internal suspend fun StateFlow<ConversationRepository?>.awaitPermissionHeldChatId(
    before: Set<String>,
    timeoutMs: Long,
): String = (permissionHeldConversationIds(timeoutMs) { ids -> (ids - before).isNotEmpty() } - before).single()

private suspend fun StateFlow<ConversationRepository?>.permissionHeldConversationIds(
    timeoutMs: Long,
    ready: (Set<String>) -> Boolean,
): Set<String> =
    withTimeout(timeoutMs) {
        firstOnLive({ repository ->
            repository.observeConversations(ConversationFilter.All).map { rows -> rows.mapTo(mutableSetOf()) { it.id } }
        }, ready)
    }
