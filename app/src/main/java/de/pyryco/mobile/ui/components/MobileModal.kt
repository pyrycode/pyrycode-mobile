package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * Presentation only: the caller owns visibility, editable values and submission state.
 * Content is a non-lazy column; the shell provides scrolling and IME avoidance.
 * Request any initial field focus inside [content], in the dialog's subcomposition.
 */
@Composable
internal fun MobileModal(
    title: String,
    onDismissRequest: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submissionEnabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MobileModalShell(
        title = title,
        onDismissRequest = onDismissRequest,
        gate = false,
        modifier = modifier,
        error = error,
        footer = { dismiss ->
            ModalCancelButton(label = "Cancel", onClick = dismiss)
            ModalSubmitButton(label = "OK", onClick = onSubmit, enabled = submissionEnabled && !loading, loading = loading)
        },
        content = content,
    )
}

/**
 * The shell hardened for a decision gate (#815), such as the permission prompt: the caller's [content]
 * carries the actions and the footer's [onCancel] is the only dismissal control.
 *
 * The gate's own window sets `FLAG_SECURE` ([SecureFlagPolicy.SecureOn], since the host Activity is not
 * secure) and drops touches delivered while another window obscures it. Back and outside taps are
 * ignored, and no close glyph is drawn, so a stray gesture is never read as an answer.
 *
 * A gate whose content is a form (#661, the question modal) passes [submitLabel] for a footer submit
 * action and shows its send failure in [error]. While [sending], Cancel and submit are both disabled:
 * on a gate, Cancel is itself a decision the caller sends.
 */
@Composable
internal fun MobileGateModal(
    title: String,
    cancelLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String? = null,
    onSubmit: () -> Unit = {},
    submissionEnabled: Boolean = true,
    sending: Boolean = false,
    error: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MobileModalShell(
        title = title,
        onDismissRequest = { if (!sending) onCancel() },
        gate = true,
        modifier = modifier,
        error = error,
        footer = { dismiss ->
            ModalCancelButton(label = cancelLabel, onClick = dismiss, enabled = !sending)
            if (submitLabel != null) {
                ModalSubmitButton(label = submitLabel, onClick = onSubmit, enabled = submissionEnabled && !sending, loading = sending)
            }
        },
        content = content,
    )
}

/**
 * The editing shell with nothing to submit (#678): its footer holds one [closeLabel] button, which
 * dismisses exactly as the close glyph and Back do. For a read-only panel such as the background-task
 * list; outside taps still do not dismiss.
 */
@Composable
internal fun MobileReadOnlyModal(
    title: String,
    closeLabel: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    MobileModalShell(
        title = title,
        onDismissRequest = onDismissRequest,
        gate = false,
        modifier = modifier,
        error = null,
        footer = { dismiss -> ModalCancelButton(label = closeLabel, onClick = dismiss) },
        content = content,
    )
}

/** [gate] is the only switch between the editing shell and the hardened decision gate. */
@Composable
private fun MobileModalShell(
    title: String,
    onDismissRequest: () -> Unit,
    gate: Boolean,
    error: String?,
    footer: @Composable RowScope.(dismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DisposableEffect(Unit) {
        logModalEvent("opened")
        onDispose { logModalEvent("closed") }
    }
    LaunchedEffect(error != null) {
        if (error != null) logModalEvent("caller_error_present")
    }
    val dismiss = {
        logModalEvent("dismiss_requested")
        onDismissRequest()
    }
    Dialog(
        onDismissRequest = dismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = !gate,
                dismissOnClickOutside = false,
                securePolicy = if (gate) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        if (gate) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect { dialogWindow?.decorView?.filterTouchesWhenObscured = true }
        }
        // Figma's onPrimaryFixed and 44/6 dp shapes are not configured in our theme.
        // Use its adaptive container pair and extraLarge/small shapes in both modes.
        Surface(
            modifier =
                modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .semantics { paneTitle = title },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // A gate's footer Cancel is its only dismissal control, so it draws no close glyph.
                        if (!gate) {
                            IconButton(onClick = dismiss, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_modal_close),
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier =
                                        Modifier
                                            .size(28.dp)
                                            .background(MaterialTheme.colorScheme.onPrimary, CircleShape),
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.6f))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    ) {
                        content()
                        if (error != null) {
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier =
                                    Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        error(error)
                                    },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    footer(dismiss)
                }
            }
        }
    }
}

@Composable
private fun ModalCancelButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModalSubmitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
) {
    Button(
        onClick = {
            if (enabled) {
                logModalEvent("submit_requested")
                onClick()
            }
        },
        modifier = Modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp).size(20.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

private fun logModalEvent(event: String) {
    if (BuildConfig.DEBUG) Log.d("MobileModal", "event=$event")
}

@Preview(name = "Mobile modal — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Mobile modal — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MobileModalPreview() {
    PyrycodeMobileTheme {
        MobileModal(title = "Modal title", onDismissRequest = {}, onSubmit = {}) {
            Text("Caller supplied content", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
