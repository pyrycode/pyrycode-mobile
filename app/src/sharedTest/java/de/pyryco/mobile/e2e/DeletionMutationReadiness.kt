package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** The owning host's publication must still be synchronously available before a one-shot UI action. */
internal suspend fun awaitDeletionMutationReady(current: StateFlow<ConversationRepository?>) {
    current.first { it != null && it === current.value }
}
