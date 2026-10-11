package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import kotlinx.coroutines.flow.Flow

/** A reading tagged inside its producing connection's subscription, never after switching delegates. */
data class ConnectionReading<out T>(
    val connection: Any?,
    val value: T,
) {
    override fun toString(): String = "ConnectionReading"
}

/** Optional connection provenance for settings consumers that must survive a skipped disconnect gap. */
interface RunConfigurationReadings {
    val currentConnection: Any?

    fun observeConversations(filter: ConversationFilter): Flow<ConnectionReading<List<Conversation>>>

    fun observeSessionSettings(conversationId: String): Flow<ConnectionReading<SessionSettings?>>
}
