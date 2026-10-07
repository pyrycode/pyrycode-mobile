package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Keep fenced-code copy compact. Compose expands pointer hit testing toward its minimum touch
// target without enlarging layout; side-message copy defines its own explicit 48dp target.
private val CopyTouchHorizontalPadding = 6.dp
private val CopyTouchVerticalPadding = 2.dp

// The Figma inverse-primary tint has insufficient contrast on either fixed-dark bubble. Derive
// de-emphasis from the host's M3 on-container role so both bubbles retain readable metadata.
private const val META_CONTENT_ALPHA = 0.80f

/**
 * Upper bound on what one tap can put on the clipboard.
 *
 * [Message.content][de.pyryco.mobile.data.model.Message.content] is daemon-authored and bounded nowhere
 * on the inbound path, while `ClipData` crosses a Binder transaction with a ~1MB ceiling — so an
 * unbounded `setText` turns a long assistant turn into a `TransactionTooLargeException` on the user's
 * own tap. 100k characters is roughly 200KB once parcelled as UTF-16: orders of magnitude above any
 * real message and well under the ceiling. The bound lives here rather than at the call sites so every
 * caller inherits it — the `text` parameter carries no trust signal in its type.
 */
internal const val MAX_CLIPBOARD_CHARS = 100_000

/**
 * The design's date-then-time timestamp (Figma `Meta row`, node `132:4446` — sample `13.01.2026 - 13:55`).
 *
 * Both halves come from `DateTimeFormatter.ofLocalized*(FormatStyle.SHORT)`, so neither carries a
 * hardcoded pattern; the separator and the date-before-time order are the design's and are fixed here
 * rather than delegated to `ofLocalizedDateTime`, which would let a locale reorder them. The time half
 * is [formatShortTime], already shipped for the session-boundary label.
 */
internal fun formatShortDateTime(
    instant: Instant,
    timeZone: TimeZone,
    locale: Locale,
): String {
    val localDate = instant.toLocalDateTime(timeZone).date.toJavaLocalDate()
    val date =
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.SHORT)
            .withLocale(locale)
            .format(localDate)
    return "$date - ${formatShortTime(instant, timeZone, locale)}"
}

/**
 * [formatShortDateTime] in the device's zone and locale, held across recompositions. Two
 * `DateTimeFormatter`s are built per call; keyed on the zone and locale as well as the instant because
 * both are read here rather than passed in, so the cache stays honest without a configuration change.
 * Shared by the meta row and the bubble's screen-reader description (#1621) so the two cannot drift.
 */
@Composable
internal fun rememberFormattedTimestamp(timestamp: Instant): String {
    val timeZone = TimeZone.currentSystemDefault()
    val locale = Locale.getDefault()
    return remember(timestamp, timeZone, locale) {
        formatShortDateTime(instant = timestamp, timeZone = timeZone, locale = locale)
    }
}

/**
 * The one clipboard write behind every copy affordance: [text] bounded by [MAX_CLIPBOARD_CHARS]. Shared
 * by [CopyTextControl] and the bubble's side copy control, so both put the same text on the clipboard.
 */
internal fun ClipboardManager.setBoundedText(text: String) {
    setText(AnnotatedString(text.take(MAX_CLIPBOARD_CHARS)))
}

/**
 * The copy affordance from the design's `Meta row` (glyph node `132:4382`).
 *
 * Copies the caller-supplied [text] verbatim (bounded by [MAX_CLIPBOARD_CHARS]) — it reads nothing out
 * of the composition tree, so the text on the clipboard is always the string the caller holds and never
 * something re-derived from rendered output. Deliberately `internal` rather than file-private to the
 * bubble: #657 mounts the same control beside a fenced code block and must not parse the rendered block
 * back out of the UI to find its text.
 *
 * Tint comes from [LocalContentColor], so the glyph inherits whatever de-emphasis its host surface
 * provides. Nothing here logs: the copied text is conversation plaintext, and Logcat is readable over
 * ADB.
 */
@Composable
internal fun CopyTextControl(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Box(
        modifier =
            modifier
                .clickable(role = Role.Button) {
                    clipboard.setBoundedText(text)
                }.padding(horizontal = CopyTouchHorizontalPadding, vertical = CopyTouchVerticalPadding),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
        )
    }
}

/** The optional timestamp below a message body; no action or action-width reservation. */
@Composable
internal fun MessageMetaRow(
    timestamp: Instant,
    modifier: Modifier = Modifier,
) {
    Text(
        text = rememberFormattedTimestamp(timestamp),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current.copy(alpha = META_CONTENT_ALPHA),
    )
}
