package de.pyryco.mobile.ui.conversations.components

import androidx.annotation.StringRes
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent

/** The client-owned name of the agent a conversation runs on (#1115). Never daemon text. */
@StringRes
internal fun ConversationAgent.nameRes(): Int =
    when (this) {
        ConversationAgent.Claude -> R.string.agent_name_claude
        ConversationAgent.Codex -> R.string.agent_name_codex
    }
