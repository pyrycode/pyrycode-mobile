package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Session(
    val id: String,
    val conversationId: String,
    val claudeSessionUuid: String,
    val startedAt: Instant,
    val endedAt: Instant?,
)
