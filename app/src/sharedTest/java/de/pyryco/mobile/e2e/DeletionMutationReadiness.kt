package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** The live deletion drive's owning host, rather than the selected host's legacy Connected label. */
internal suspend fun awaitDeletionMutationReady(current: StateFlow<ConversationRepository?>) {
    current.filterNotNull().first()
}
