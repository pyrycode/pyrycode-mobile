package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

private val MetaRowSpacing = 8.dp

// The design draws the glyph at 11x12 with no padding around it, which is a 12dp tap target. 6dp of
// symmetric padding inside the clickable widens that to 24dp, growing the meta row from 16dp to 24dp.
// Still under Material's 48dp guidance — a full IconButton would inflate every bubble by ~32dp and
// visibly miss the frame — but the deviation runs toward accessibility and is identical on both roles.
private val CopyTouchPadding = 6.dp

// De-emphasis for the meta row, taken off the host bubble's own content colour rather than a named
// role: M3 has no "de-emphasised content inside a filled container" slot, and one alpha expression
// reads correctly in both bubble variants and both colour schemes. The design names
// `Schemes/inverse-primary` (#32628D), which is the light-scheme primary tone and only reads as
// de-emphasis against the dark reference frame.
private const val META_CONTENT_ALPHA = 0.70f

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
private const val MAX_CLIPBOARD_CHARS = 100_000

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
                    clipboard.setText(AnnotatedString(text.take(MAX_CLIPBOARD_CHARS)))
                }.padding(CopyTouchPadding),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_copy),
            contentDescription = contentDescription,
            tint = LocalContentColor.current,
        )
    }
}

/**
 * The trailing row of the design's shared `Message` component (Figma node `132:4446` for the assistant
 * instance, `132:4435` for the user one): that message's own timestamp and a copy control, 8dp apart.
 *
 * Deliberately wrap-content rather than `fillMaxWidth()`. The design's meta row is `w-full`, but CSS
 * resolves that against the *parent's* width while Compose's `fillMaxWidth` resolves against the
 * incoming max constraint — which would stretch every bubble to the full 272dp lane and lose the
 * shrink-wrap the design's short instances have. Which side the row lands on is therefore the caller's
 * to say, with `Modifier.align(...)` inside the bubble's `Column`.
 *
 * [copyText] is passed explicitly rather than derived from a rendered child so that an assistant bubble
 * copies its markdown source, not the parsed output.
 */
@Composable
internal fun MessageMetaRow(
    timestamp: Instant,
    copyText: String,
    modifier: Modifier = Modifier,
) {
    // One de-emphasised colour for both children: the label takes it directly, the control inherits it
    // through the ambient so it stays reusable at full strength on a surface that wants full strength.
    val metaColor = LocalContentColor.current.copy(alpha = META_CONTENT_ALPHA)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MetaRowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text =
                formatShortDateTime(
                    instant = timestamp,
                    timeZone = TimeZone.currentSystemDefault(),
                    locale = Locale.getDefault(),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = metaColor,
        )
        CompositionLocalProvider(LocalContentColor provides metaColor) {
            CopyTextControl(
                text = copyText,
                contentDescription = stringResource(R.string.cd_thread_copy_message),
            )
        }
    }
}
