package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.success

/** Figma 795:7177: inert launch text and a local scroll affordance. */
@Composable
internal fun AgentStartMarker(
    description: String,
    finished: Boolean,
    onGoToAgent: () -> Unit,
) {
    val go = stringResource(R.string.agent_go_to)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MessageContentGutter)
            .clickable(role = Role.Button, onClickLabel = go, onClick = onGoToAgent)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Same 6dp circular theme-backed dot used by ConversationStatusDot.
            Box(
                Modifier
                    .size(
                        6.dp,
                    ).background(if (finished) MaterialTheme.colorScheme.success else MaterialTheme.colorScheme.primary, CircleShape),
            )
            Text(
                stringResource(if (finished) R.string.agent_finished else R.string.agent_still_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(go, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            description.take(4096),
            Modifier.padding(start = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview @Composable
private fun RunningMarkerPreview() {
    PyrycodeMobileTheme(darkTheme = true) { AgentStartMarker("File task-type label tickets", false, {}) }
}

@Preview @Composable
private fun FinishedMarkerPreview() {
    PyrycodeMobileTheme(darkTheme = false) { AgentStartMarker("File task-type label tickets", true, {}) }
}
