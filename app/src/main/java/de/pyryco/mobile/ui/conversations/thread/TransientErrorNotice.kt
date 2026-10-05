package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.components.NoticePill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Material 3 SnackbarDuration.Short, including its icons/text=true accessibility policy without controls.
private const val SHORT_NOTICE_MILLIS = 4_000L

/** Screen-local transient errors. Each caller owns its wait and cancellation, including queued notices. */
@Stable
class TransientErrorNoticeState internal constructor(
    private val timeoutMillis: () -> Long,
) {
    private val mutex = Mutex()

    // Text can repeat between queued callers; consumers key the pill by its occurrence instead.
    internal var currentOccurrence by mutableLongStateOf(0L)
        private set
    internal var currentMessage by mutableStateOf<String?>(null)
        private set

    internal suspend fun show(message: String) {
        mutex.withLock {
            try {
                currentOccurrence++
                currentMessage = message
                RelayLog.d { "event=transient_error_notice phase=shown" }
                delay(timeoutMillis())
            } finally {
                currentMessage = null
                RelayLog.d { "event=transient_error_notice phase=cleared" }
            }
        }
    }

    /**
     * Shows [message] in a child of [scope] that has joined the queue before this returns, so a signal
     * collector moves on to its next signal at once. A collector that waited inside [show] would hold its
     * next signal outside the queue, where a later failure from another route could overtake it.
     */
    internal fun enqueue(
        scope: CoroutineScope,
        message: String,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { show(message) }
}

@Composable
internal fun rememberTransientErrorNoticeState(key: Any? = Unit): TransientErrorNoticeState {
    val accessibilityManager by rememberUpdatedState(LocalAccessibilityManager.current)
    return remember(key) {
        TransientErrorNoticeState {
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = SHORT_NOTICE_MILLIS,
                containsIcons = true,
                containsText = true,
                containsControls = false,
            ) ?: SHORT_NOTICE_MILLIS
        }
    }
}

/** Figma Error pill, without dismiss or click actions. Only client-owned text may be passed here. */
@Composable
internal fun TransientErrorPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    NoticePill(
        text = text,
        isError = true,
        // Preserve the frame's 24dp line box when shared typography trims short text; wrapped text grows.
        modifier = modifier.heightIn(min = 24.dp).testTag("transient_error_notice").semantics { liveRegion = LiveRegionMode.Polite },
    )
}
