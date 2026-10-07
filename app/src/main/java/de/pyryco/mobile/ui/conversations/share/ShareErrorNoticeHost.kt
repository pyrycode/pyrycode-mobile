package de.pyryco.mobile.ui.conversations.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.ui.conversations.thread.LocalNavigationErrorNotice
import de.pyryco.mobile.ui.conversations.thread.formatMegabytes
import de.pyryco.mobile.ui.conversations.thread.rememberTransientErrorNoticeState

/** Activity-level collector, kept outside navigation so selection notices survive the destination change. */
@Composable
internal fun ShareErrorNoticeHost(
    intake: ShareIntakeViewModel,
    content: @Composable () -> Unit,
) {
    val resources = LocalContext.current.resources
    val notices = rememberTransientErrorNoticeState(intake)
    LaunchedEffect(intake, notices) {
        intake.notices.collect { (message, count) ->
            val text =
                when {
                    count > 0 -> resources.getQuantityString(message, count, count)
                    message == R.string.thread_attachment_send_too_large ->
                        resources.getString(
                            message,
                            formatMegabytes(AttachmentUploadLimit.MAX_BYTES),
                        )
                    else -> resources.getString(message)
                }
            notices.enqueue(this, text)
        }
    }
    CompositionLocalProvider(LocalNavigationErrorNotice provides notices) { content() }
}
