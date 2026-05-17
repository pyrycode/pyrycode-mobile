package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import de.pyryco.mobile.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    overflowExpanded: Boolean,
    onOverflowDismiss: () -> Unit,
    onOverflowEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        title = {
            Text(
                text = title,
                modifier =
                    Modifier
                        .clickable(onClick = onTitleClick)
                        .semantics { role = Role.Button },
            )
        },
        actions = {
            Box {
                IconButton(onClick = onOverflowClick) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_actions),
                    )
                }
                ThreadOverflowMenu(
                    expanded = overflowExpanded,
                    onDismiss = onOverflowDismiss,
                    onEvent = onOverflowEvent,
                )
            }
        },
    )
}
