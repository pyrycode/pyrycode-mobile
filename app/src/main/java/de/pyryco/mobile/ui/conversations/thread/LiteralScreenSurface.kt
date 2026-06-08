package de.pyryco.mobile.ui.conversations.thread

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R

/**
 * The manual "show the literal screen" surface (#381) — the parser-independent floor of the Phase-2
 * degrade strategy. Renders the merged [LiteralScreenViewModel]'s hoisted [state] and dispatches its
 * two events; holds no state of its own beyond UI scroll position. The snapshot [text][LiteralScreenUiState.Content.text]
 * is server-originated and may carry sensitive on-screen content, so it is rendered **verbatim** as
 * plain monospace (never through [de.pyryco.mobile.ui.conversations.components.MarkdownText]), never
 * logged, never written to saved-instance-state, and screen capture is blocked while the surface is
 * shown ([SecureScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteralScreenSurface(
    state: LiteralScreenUiState,
    onEvent: (LiteralScreenEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Block screenshots / screen-recording / cast of the sensitive screen for the whole surface
    // lifecycle (cleared on dispose). No-op under createComposeRule (no Activity window).
    SecureScreen()
    // Event-driven, not auto-loading: the VM never fetches in init, so this is the only Request
    // trigger. key = Unit fires it once per surface entry; a recomposition does not re-fire.
    LaunchedEffect(Unit) { onEvent(LiteralScreenEvent.Request) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.literal_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.literal_screen_back),
                        )
                    }
                },
                actions = {
                    // Refresh re-fetches in place (AC#2) — offered once there is a screen to refresh.
                    if (state is LiteralScreenUiState.Content) {
                        IconButton(onClick = { onEvent(LiteralScreenEvent.Retry) }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.literal_screen_refresh),
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            )
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                when (state) {
                    LiteralScreenUiState.Loading -> LoadingContent()
                    is LiteralScreenUiState.Content -> SnapshotContent(text = state.text)
                    is LiteralScreenUiState.Error -> ErrorContent(reason = state.reason, onEvent = onEvent)
                }
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.literal_screen_loading)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

// Verbatim render (AC#1) — the load-bearing rule. The snapshot is shown byte-for-byte as plain
// monospace: never routed through MarkdownText, never pre-processed (no trim/replace/regex/strip),
// and never wrapped in a SelectionContainer (text selection would expose a clipboard exfiltration
// path past FLAG_SECURE). softWrap = false preserves the terminal grid; the container scrolls
// (both axes) so long/tall output stays reachable without altering the bytes.
@Composable
private fun SnapshotContent(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

@Composable
private fun ErrorContent(
    reason: LiteralScreenError,
    onEvent: (LiteralScreenEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Each reason maps to its own string — no server-supplied string is ever shown (the enum carries
    // no copy by design). NotConnected is a calm "reconnect and retry", not an alarming error.
    val message =
        when (reason) {
            LiteralScreenError.UnknownConversation -> stringResource(R.string.literal_screen_error_unknown_conversation)
            LiteralScreenError.ServerError -> stringResource(R.string.literal_screen_error_server)
            LiteralScreenError.NotConnected -> stringResource(R.string.literal_screen_error_not_connected)
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onEvent(LiteralScreenEvent.Retry) }) {
            Text(text = stringResource(R.string.literal_screen_retry))
        }
    }
}

// Blocks screen capture (screenshot / recording / cast) of the sensitive snapshot while the surface
// is composed, cleared on dispose so it does not silently harden unrelated screens. Null-safe: under
// createComposeRule the host is a non-Activity context, so findActivity() returns null and the effect
// no-ops — no crash, no Activity dependency in tests. The app uses FLAG_SECURE nowhere else.
@Composable
private fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
