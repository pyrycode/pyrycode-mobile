package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent

/**
 * The client-owned name of [agent] (#1113), for copy that credits the agent with what it said. Always a
 * string resource picked from the enum, never daemon text, so an attribution built on it cannot be forged.
 */
@Composable
fun agentName(agent: ConversationAgent): String =
    stringResource(
        when (agent) {
            ConversationAgent.Claude -> R.string.agent_name_claude
            ConversationAgent.Codex -> R.string.agent_name_codex
        },
    )
