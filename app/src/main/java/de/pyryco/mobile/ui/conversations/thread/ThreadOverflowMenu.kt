package de.pyryco.mobile.ui.conversations.thread

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayPlacement
import de.pyryco.mobile.ui.conversations.components.shouldOfferMemoryInstall

@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    isPromoted: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
    // Gated on the thread state's "mutations supported" signal (#507): false in relay mode, where these
    // actions throw or no-op. Defaulted for previews/tests only — production always threads the real value.
    mutationsSupported: Boolean = true,
    memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
    onBackgroundTasks: () -> Unit = {},
    anchor: Rect = Rect.Zero,
) {
    val uriHandler = LocalUriHandler.current
    if (!expanded) return
    val view = LocalView.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // A header menu takes Back before the IME; the shared overlay's BackHandler remains the
    // activity-dispatch fallback. Register only while this menu is mounted and release on dismissal.
    DisposableEffect(view) {
        val dispatcher = view.findOnBackInvokedDispatcher()
        val callback = OnBackInvokedCallback { currentOnDismiss() }
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
    val rows =
        buildList<Pair<Int, () -> Unit>> {
            if (!isPromoted) add(R.string.save_as_channel_action to { onEvent(ThreadEvent.SaveAsChannel) })
            if (mutationsSupported) {
                add(R.string.thread_overflow_new_session to { onEvent(ThreadEvent.NewSession) })
                if (isPromoted) {
                    add(R.string.thread_overflow_edit to { onEvent(ThreadEvent.EditChannel) })
                } else {
                    add(R.string.thread_overflow_rename to { onEvent(ThreadEvent.Rename) })
                }
                add(R.string.thread_overflow_archive to { onEvent(ThreadEvent.Archive) })
            }
            add(R.string.thread_overflow_channel_info to { onEvent(ThreadEvent.ChannelInfo) })
            add(R.string.background_tasks_title to onBackgroundTasks)
            if (isPromoted && memorySearch.shouldOfferMemoryInstall()) {
                add(R.string.thread_overflow_install_memory_plugin to { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) })
            }
        }
    OptionsOverlay(
        options = rows.map { (label, _) -> OptionsOverlayOption(label.toString(), stringResource(label)) },
        selectedValue = "",
        notListed = 0,
        anchor = anchor,
        onSelect = { value ->
            rows.firstOrNull { it.first.toString() == value }?.second?.let { action ->
                onDismiss()
                action()
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
        actions = true,
        placement = OptionsOverlayPlacement.Below,
    )
}
