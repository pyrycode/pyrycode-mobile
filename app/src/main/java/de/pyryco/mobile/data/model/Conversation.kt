package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant

data class Conversation(
    val id: String,
    val name: String?,
    val cwd: String,
    val currentSessionId: String,
    val sessionHistory: List<String>,
    val isPromoted: Boolean,
    val lastUsedAt: Instant,
)

/**
 * Sentinel `cwd` for conversations with no bound workspace.
 * Conversations whose `cwd` equals this value render without a workspace label.
 */
const val DefaultScratchCwd: String = "~/.pyrycode/scratch"
