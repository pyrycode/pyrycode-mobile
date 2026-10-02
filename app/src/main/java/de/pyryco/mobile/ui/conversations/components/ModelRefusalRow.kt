package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl
import kotlinx.datetime.Instant

private val RefusalRowVerticalSpacing = 12.dp
private val RefusalVerticalPadding = 8.dp
private val RefusalCollapsedGap = 4.dp
private val RefusalExpandedGap = 8.dp
private val SwitchBackBorderWidth = 1.dp
private val SwitchBackHorizontalPadding = 16.dp
private val SwitchBackVerticalPadding = 7.dp
private const val SWITCH_BACK_PENDING_ALPHA = 0.38f

/**
 * The thread's offer to switch back to the model claude refused on (#1360), drawn on the refusal row that
 * armed it ([armedBy]). Only a live session-scoped fallback refusal arms one, never a restored row; the
 * ViewModel owns that rule. [originalModel] is claude-authored and verbatim; the button strips it with
 * [refusalModelDisplay]. [pending] means a model write is outstanding; [failed] means this offer's last write
 * was refused or failed.
 */
@Immutable
data class SwitchBackOffer(
    val occurredAt: Instant,
    val originalModel: String,
    val pending: Boolean,
    val failed: Boolean,
) {
    /** Whether [item] is the fallback refusal row that armed this offer: the row's own `(type, ts)` identity. */
    fun armedBy(item: ThreadItem.ModelRefusal): Boolean = item.fallbackModel != null && item.occurredAt == occurredAt
}

/**
 * A refusal appears as bare text in the message stream, matching Figma's Thread notification component.
 * When the agent sent an explanation, a tap opens it in place with attribution.
 *
 * Security — every string on [ThreadItem.ModelRefusal] is claude-authored and unsanitized. The render-time
 * obligations:
 * - **Stripped** of control characters and terminal escapes at this one sink: the banner by
 *   [bannerDisplayText], each model identifier by [refusalModelDisplay], which also drops line breaks.
 * - **Inert** — plain [Text] only: never markdown, no link detection, no `SelectionContainer`. The one click
 *   is the expand toggle, and a row with no explanation has none.
 * - **Unforgeable copy** — the title is client-owned spans with each model name as its own span: a model the
 *   thread's menu knows ([knownModelLabel], #1494) as its menu label in the title's style, any other identifier in
 *   monospace, so an unknown identifier cannot pass itself off as the surrounding words; the banner's
 *   "<agent>: " is its own medium-weight span, as in [BannerNoticeRow], naming the conversation's [agent]
 *   (#1113). Keep all of them separate spans.
 * - **No logging** — only the expand [Boolean] reaches saved state. The thread cache stores the row as held
 *   (#1353), so a restored row renders through this same boundary.
 *
 * [knownModelLabel] maps a raw identifier to the menu's label for it, or `null` when the menu does not know it
 * (`ThreadRunConfig.knownModelLabel`); the default knows nothing.
 */
@Composable
fun ModelRefusalRow(
    item: ThreadItem.ModelRefusal,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
    switchBack: SwitchBackOffer? = null,
    onSwitchBack: () -> Unit = {},
    knownModelLabel: (String) -> String? = { null },
) {
    // Only the toggle is saved, scoped by the row's LazyColumn key.
    var expanded by rememberSaveable { mutableStateOf(false) }
    ModelRefusalRowContent(
        item = item,
        agent = agent,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        switchBack = switchBack,
        onSwitchBack = onSwitchBack,
        knownModelLabel = knownModelLabel,
    )
}

@Composable
private fun ModelRefusalRowContent(
    item: ThreadItem.ModelRefusal,
    agent: ConversationAgent,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    switchBack: SwitchBackOffer? = null,
    onSwitchBack: () -> Unit = {},
    knownModelLabel: (String) -> String? = { null },
) {
    val banner = bannerDisplayText(item.banner)
    val expandable = banner.isNotBlank()
    val name = agentName(agent)
    val clickLabel =
        if (expanded) {
            stringResource(R.string.cd_thread_refusal_collapse, name)
        } else {
            stringResource(R.string.cd_thread_refusal_expand, name)
        }
    // #1360: the toggle's hit area stays the title block; the switch-back button sits below it, outside it.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = MessageContentGutter, end = MessageContentGutter, bottom = RefusalRowVerticalSpacing),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.clickable(onClickLabel = clickLabel, onClick = onToggle) else Modifier)
                    .padding(vertical = RefusalVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(if (expanded) RefusalExpandedGap else RefusalCollapsedGap),
        ) {
            Text(
                text = refusalTitle(item, knownModelLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (expandable && expanded) {
                Text(
                    text = attributedBanner(name, banner, item.bannerTruncated),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (expandable) {
                Text(
                    text = stringResource(if (expanded) R.string.thread_refusal_hide_details else R.string.thread_refusal_show_details),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (switchBack != null) {
            SwitchBackAction(
                offer = switchBack,
                onSwitchBack = onSwitchBack,
                knownModelLabel = knownModelLabel,
                modifier = Modifier.padding(bottom = RefusalVerticalPadding),
            )
        }
    }
}

/**
 * The switch-back button (#1360, Figma 646-2833): the small secondary button, disabled and drawn at 38% while
 * a model write is pending, with the retry line under it after a failed write. The block above it ends in
 * [RefusalVerticalPadding], which stands for Figma's 4 dp gap plus 4 dp top padding.
 */
@Composable
private fun SwitchBackAction(
    offer: SwitchBackOffer,
    onSwitchBack: () -> Unit,
    knownModelLabel: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(RefusalCollapsedGap)) {
        Surface(
            onClick = onSwitchBack,
            enabled = !offer.pending,
            shape = MaterialTheme.shapes.modalControl,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(SwitchBackBorderWidth, MaterialTheme.colorScheme.primary),
            // A clickable Surface reserves Material's 48 dp touch target around the 30 dp button.
            modifier = Modifier.alpha(if (offer.pending) SWITCH_BACK_PENDING_ALPHA else 1f),
        ) {
            Text(
                text = switchBackLabel(offer.originalModel, knownModelLabel),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = SwitchBackHorizontalPadding, vertical = SwitchBackVerticalPadding),
            )
        }
        if (offer.failed) {
            Text(
                text = stringResource(R.string.thread_refusal_switch_back_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "Switch back to " in client copy, then the model as its own span by the title's rule, in the button's style. */
@Composable
private fun switchBackLabel(
    model: String,
    knownModelLabel: (String) -> String?,
): AnnotatedString {
    val prefix = stringResource(R.string.thread_refusal_switch_back)
    val unknownModel = stringResource(R.string.thread_refusal_unknown_model)
    return buildAnnotatedString {
        append(prefix)
        appendModel(
            model = model,
            knownModelLabel = knownModelLabel,
            unknownModel = unknownModel,
            clientStyle = SpanStyle(),
            identifierStyle = SpanStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

/** The title: client-owned copy in the muted style, each claude-named model its own span by [appendModel]. */
@Composable
private fun refusalTitle(
    item: ThreadItem.ModelRefusal,
    knownModelLabel: (String) -> String?,
): AnnotatedString {
    val clientStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val identifierStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    val unknownModel = stringResource(R.string.thread_refusal_unknown_model)
    val refusedOn = stringResource(R.string.thread_refusal_refused_on)
    val continuedOn = stringResource(R.string.thread_refusal_continued_on)
    val refusedBy = stringResource(R.string.thread_refusal_refused_by)
    return buildAnnotatedString {
        fun model(value: String) = appendModel(value, knownModelLabel, unknownModel, clientStyle, identifierStyle)
        val fallbackModel = item.fallbackModel
        if (fallbackModel != null) {
            withStyle(clientStyle) { append(refusedOn) }
            model(item.originalModel)
            withStyle(clientStyle) { append(continuedOn) }
            model(fallbackModel)
        } else {
            withStyle(clientStyle) { append(refusedBy) }
            model(item.originalModel)
        }
    }
}

/**
 * One claude-named model as its own span (#1494): nothing readable left reads [unknownModel]; a model the
 * menu knows reads as its stripped menu label in [clientStyle], a span of its own even where the style matches
 * the copy around it; any other identifier is stripped and drawn in [identifierStyle]. The lookup takes the
 * raw [model]; stripping is only for what is drawn.
 */
private fun AnnotatedString.Builder.appendModel(
    model: String,
    knownModelLabel: (String) -> String?,
    unknownModel: String,
    clientStyle: SpanStyle,
    identifierStyle: SpanStyle,
) {
    val display = refusalModelDisplay(model)
    val label = display?.let { knownModelLabel(model) }?.let(::refusalModelDisplay)
    when {
        display == null -> withStyle(clientStyle) { append(unknownModel) }
        label != null -> withStyle(clientStyle) { append(label) }
        else -> withStyle(identifierStyle) { append(display) }
    }
}

/** The opened explanation: the client-owned attribution span, the stripped prose, then the cut mark if cut. */
@Composable
private fun attributedBanner(
    agentName: String,
    banner: String,
    truncated: Boolean,
): AnnotatedString {
    val attribution = stringResource(R.string.thread_banner_attribution, agentName)
    val truncatedMark = stringResource(R.string.thread_banner_truncated)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(attribution) }
        append(banner)
        if (truncated) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(truncatedMark) }
        }
    }
}

private val LineBreakOrTab = Regex("""[\t\n\r]""")

/**
 * A refusal's claude-authored model identifier as the title draws it (#875), or **null** when nothing
 * readable is left — the caller then shows "unknown model". [bannerDisplayText]'s stripping plus tabs and
 * line breaks: an identifier has no business spanning lines, and one that did could fake a second row.
 */
internal fun refusalModelDisplay(model: String): String? = bannerDisplayText(model).replace(LineBreakOrTab, "").takeIf { it.isNotBlank() }

// Preview fixtures are hand-written literals, never captured live payloads.
private val PreviewInstant = Instant.parse("2026-09-23T12:00:00Z")

private val PreviewFallback =
    ThreadItem.ModelRefusal(
        originalModel = "claude-opus-5-5",
        fallbackModel = "claude-sonnet-5",
        banner = "This request was declined on Opus, so it was retried on Sonnet for the rest of this session.",
        bannerTruncated = false,
        occurredAt = PreviewInstant,
    )

// The labels a menu publishing both models gives them, as frames 620:1577 and 646:4707 draw (#1494).
private val PreviewMenuLabels = mapOf("claude-opus-5-5" to "Opus", "claude-sonnet-5" to "Sonnet")

private val PreviewOffer = SwitchBackOffer(PreviewInstant, "claude-opus-5-5", pending = false, failed = false)

private val PreviewTruncated =
    ThreadItem.ModelRefusal(
        originalModel = "claude-opus-5-5",
        fallbackModel = null,
        banner = "This request was declined and was not retried on another model. You can rephrase it and",
        bannerTruncated = true,
        occurredAt = PreviewInstant,
    )

private val PreviewUnknownNoBanner =
    ThreadItem.ModelRefusal(
        originalModel = "",
        fallbackModel = null,
        banner = "",
        bannerTruncated = false,
        occurredAt = PreviewInstant,
    )

@Composable
private fun ModelRefusalRowPreviewMatrix() {
    Column {
        ModelRefusalRowContent(item = PreviewFallback, agent = ConversationAgent.Claude, expanded = false, onToggle = {})
        ModelRefusalRowContent(item = PreviewFallback, agent = ConversationAgent.Claude, expanded = true, onToggle = {})
        ModelRefusalRowContent(item = PreviewTruncated, agent = ConversationAgent.Codex, expanded = true, onToggle = {})
        ModelRefusalRowContent(item = PreviewUnknownNoBanner, agent = ConversationAgent.Claude, expanded = false, onToggle = {})
        ModelRefusalRowContent(
            item = PreviewFallback,
            agent = ConversationAgent.Claude,
            expanded = false,
            onToggle = {},
            switchBack = PreviewOffer,
        )
        ModelRefusalRowContent(
            item = PreviewFallback,
            agent = ConversationAgent.Claude,
            expanded = false,
            onToggle = {},
            switchBack = PreviewOffer.copy(pending = true),
        )
        ModelRefusalRowContent(
            item = PreviewFallback,
            agent = ConversationAgent.Claude,
            expanded = false,
            onToggle = {},
            switchBack = PreviewOffer.copy(failed = true),
        )
        ModelRefusalRowContent(
            item = PreviewFallback,
            agent = ConversationAgent.Claude,
            expanded = false,
            onToggle = {},
            switchBack = PreviewOffer,
            knownModelLabel = PreviewMenuLabels::get,
        )
    }
}

@Preview(name = "ModelRefusalRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ModelRefusalRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ModelRefusalRowPreviewMatrix()
        }
    }
}

// The dark preview is the fidelity reference against Figma 16:8.
@Preview(
    name = "ModelRefusalRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ModelRefusalRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ModelRefusalRowPreviewMatrix()
        }
    }
}
