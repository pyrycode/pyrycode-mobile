package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import de.pyryco.mobile.ui.theme.modalContainer

/**
 * Presentation only: the caller owns visibility, editable values and submission state.
 * Content is a non-lazy column; the shell provides scrolling and IME avoidance.
 * Request any initial field focus inside [content], in the dialog's subcomposition.
 * The close glyph fires [onCloseRequest]; Cancel and Back fire [onDismissRequest]. A caller whose Cancel
 * steps back rather than closing, such as a confirmation step (#1560), passes its own close here.
 */
@Composable
internal fun MobileModal(
    title: String,
    onDismissRequest: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onCloseRequest: () -> Unit = onDismissRequest,
    submissionEnabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    cancelLabel: String = "Cancel",
    // Null omits the submit, leaving Cancel as the only action (#1386), as on [MobileGateModal].
    submitLabel: String? = "OK",
    content: @Composable ColumnScope.() -> Unit,
) {
    MobileModalShell(
        title = title,
        onDismissRequest = onDismissRequest,
        gate = false,
        modifier = modifier,
        error = error,
        onCloseRequest = onCloseRequest,
        footer = { dismiss ->
            ModalCancelButton(label = cancelLabel, onClick = dismiss)
            if (submitLabel != null) {
                ModalSubmitButton(label = submitLabel, onClick = onSubmit, enabled = submissionEnabled && !loading, loading = loading)
            }
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
 * The editing shell with nothing to submit (#678), for a read-only panel such as the background-task
 * list. It has no footer (#1496): the close glyph and Back dismiss, outside taps do not. The sheet runs to
 * the screen's bottom edge, as in the 568:876 frames, while its content stays above the navigation bar.
 */
@Composable
internal fun MobileReadOnlyModal(
    title: String,
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
        footer = null,
        extendToBottom = true,
        bottomPadding = 24.dp,
        content = content,
    )
}

/** A single filled dismissal action, with content starting below the header. */
@Composable
internal fun MobileDismissModal(
    title: String,
    actionLabel: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Settings' Figma frame omits Android bars; the shared shell still applies real safe drawing insets.
    MobileModalShell(
        title = title,
        onDismissRequest = onDismissRequest,
        gate = false,
        error = null,
        modifier = modifier,
        contentAlignment = Alignment.Top,
        footerAlignment = Alignment.End,
        // The default 20 dp plus Done's 4 dp touch margin below its 40 dp surface gives the frames' 24 px (#1503).
        footer = { dismiss ->
            ModalSubmitButton(label = actionLabel, onClick = dismiss, enabled = true, loading = false, logSubmit = false)
        },
        content = content,
    )
}

/**
 * [gate] is the only switch between the editing shell and the hardened decision gate. A null [footer]
 * draws no footer row. [onCloseRequest] is the close glyph's own route. [extendToBottom] lets the sheet reach the screen's bottom edge and moves the bottom
 * safe-drawing inset inside it, so the content still ends above the navigation bar and keyboard.
 */
@Composable
private fun MobileModalShell(
    title: String,
    onDismissRequest: () -> Unit,
    gate: Boolean,
    error: String?,
    footer: (@Composable RowScope.(dismiss: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
    extendToBottom: Boolean = false,
    contentAlignment: Alignment.Vertical = Alignment.CenterVertically,
    footerAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    bottomPadding: androidx.compose.ui.unit.Dp = 20.dp,
    onCloseRequest: () -> Unit = onDismissRequest,
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
    val close = {
        logModalEvent("close_requested")
        onCloseRequest()
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
        // The sheet keeps its scoped modalContainer fill: Figma's On Primary Fixed navy in dark,
        // primaryContainer in light (#1142). Keep the reference corners local to this shell and its actions.
        Surface(
            modifier =
                modifier
                    .fillMaxSize()
                    .then(
                        if (extendToBottom) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            )
                        } else {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                        },
                    ).semantics { paneTitle = title },
            shape = RoundedCornerShape(44.dp),
            color = MaterialTheme.colorScheme.modalContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            // Too short to pin the chrome, as in landscape with the keyboard up (#1135), the header,
            // content and footer scroll as one column. Only modifiers change between the modes, so the
            // caller's content keeps its state and focus when the keyboard flips them. The outer scroll
            // is always applied (with zero range while pinned) so the scroll that sees the keyboard
            // shrink the viewport is the one that keeps the focused field in view.
            val shellScroll = rememberScrollState()
            val contentScroll = rememberScrollState()
            BoxWithConstraints {
                val pinned = maxHeight >= MinPinnedShellHeight
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(shellScroll)
                            .then(if (pinned) Modifier.height(maxHeight) else Modifier)
                            .then(
                                if (extendToBottom) {
                                    Modifier.windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                                    )
                                } else {
                                    Modifier
                                },
                            ).padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = bottomPadding),
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
                                // The visible row is 28 dp; the centred 48 dp hit area fits within its 20 dp
                                // title gap and 12 dp separator gap without changing either Figma measure.
                                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                                    IconButton(onClick = close, modifier = Modifier.requiredSize(48.dp)) {
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
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.6f))
                    }
                    BoxWithConstraints((if (pinned) Modifier.weight(1f) else Modifier).fillMaxWidth()) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (pinned) Modifier.verticalScroll(contentScroll).heightIn(min = maxHeight) else Modifier),
                            verticalArrangement = Arrangement.spacedBy(12.dp, contentAlignment),
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
                    if (footer != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(20.dp, footerAlignment),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            footer(dismiss)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalCancelButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value && enabled
    val rippleConfiguration = LocalRippleConfiguration.current
    CompositionLocalProvider(LocalRippleConfiguration provides if (hovered) null else rippleConfiguration) {
        OutlinedButton(
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = if (hovered) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            onClick = onClick,
            interactionSource = source,
            // Material reserves an invisible 48 dp hit area around the 40 dp surface.
            modifier = Modifier.minimumInteractiveComponentSize(),
            enabled = enabled,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 19.dp, vertical = 7.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModalSubmitButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
    interactionSource: MutableInteractionSource? = null,
    logSubmit: Boolean = true,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hovered = source.collectIsHoveredAsState().value && enabled
    val rippleConfiguration = LocalRippleConfiguration.current
    CompositionLocalProvider(LocalRippleConfiguration provides if (hovered) null else rippleConfiguration) {
        Button(
            onClick = {
                if (enabled) {
                    if (logSubmit) logModalEvent("submit_requested")
                    onClick()
                }
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (hovered) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            interactionSource = source,
            modifier = Modifier.minimumInteractiveComponentSize(),
            enabled = enabled,
            shape = RoundedCornerShape(6.dp),
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
}

/** About 200 dp of padding, header and footer, plus room for one outlined text field. */
private val MinPinnedShellHeight = 280.dp

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
