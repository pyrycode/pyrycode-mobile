package de.pyryco.mobile.ui.conversations.thread

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.ui.theme.modalControl
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText
import kotlinx.coroutines.launch

@Composable
internal fun QuestionBatchTitle(state: QuestionModalState) {
    Text(
        stringResource(if (state.agent == ConversationAgent.Claude) R.string.question_modal_title else R.string.question_modal_title_codex),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.semantics { heading() }.testTag("question-batch-title"),
    )
}

/** Cancel (refuse) and Continue, disabled while the host is not [connected] (#1321); the rows stay editable. */
@Composable
internal fun QuestionBatchActions(
    state: QuestionModalState,
    connected: Boolean,
    onEvent: (QuestionModalEvent) -> Unit,
) {
    Column(Modifier.fillMaxWidth().testTag("question-batch-actions"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.phase == QuestionSendPhase.Failed) {
            val failure = stringResource(R.string.question_send_failed)
            Text(
                failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            error(failure)
                        }.testTag("question-send-failed"),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 221.dp * LocalDensity.current.fontScale
            val cancel: @Composable () -> Unit = {
                OutlinedButton(
                    onClick = { onEvent(QuestionModalEvent.Cancel) },
                    enabled = !state.locked && connected,
                    shape = MaterialTheme.shapes.modalControl,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.modal_cancel),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            val submit: @Composable () -> Unit = {
                val enabled = state.canContinue && connected
                // Figma 636:3535: disabled is the enabled button at 38 % layer opacity, so the label fades with its fill.
                val primary = MaterialTheme.colorScheme.primary
                val onPrimary = MaterialTheme.colorScheme.onPrimary
                Button(
                    onClick = { onEvent(QuestionModalEvent.Continue) },
                    modifier = if (enabled) Modifier else Modifier.alpha(DISABLED_CONTINUE_ALPHA),
                    enabled = enabled,
                    shape = MaterialTheme.shapes.modalControl,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = onPrimary,
                            disabledContainerColor = primary,
                            disabledContentColor = onPrimary,
                        ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.question_continue),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            if (stacked) {
                // Figma 636:4325: the column wraps the wider Continue at the card's start, Cancel centred over it.
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    cancel()
                    submit()
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally)) {
                    cancel()
                    submit()
                }
            }
        }
    }
}

private const val DISABLED_CONTINUE_ALPHA = 0.38f

/** Protect the activity surface for the full lifetime of a mounted batch, including offscreen rows. */
@Composable
internal fun QuestionPromptProtection() {
    val view = LocalView.current
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    DisposableEffect(view, activity) {
        val releaseWindow = activity?.window?.let { QuestionProtectionOwners.secure(it) }
        // AndroidComposeView overrides touch dispatch; the window's decor is the load-bearing overlay guard.
        val releaseDecor = activity?.window?.decorView?.let { QuestionProtectionOwners.filter(it) }
        val releaseView = QuestionProtectionOwners.filter(view)
        onDispose {
            releaseView()
            releaseDecor?.invoke()
            releaseWindow?.invoke()
        }
    }
}

/** Composition effects run on Main. Navigation transitions may mount several owners of the same surface. */
private object QuestionProtectionOwners {
    private class Policy(
        val restore: () -> Unit,
        var count: Int = 1,
    )

    private val policies = mutableMapOf<Any, Policy>()

    fun secure(window: Window): () -> Unit =
        retain(window) {
            val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            val restore = { if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            restore
        }

    fun filter(view: View): () -> Unit =
        retain(view) {
            val wasFiltered = view.filterTouchesWhenObscured
            view.filterTouchesWhenObscured = true
            val restore = { view.filterTouchesWhenObscured = wasFiltered }
            restore
        }

    private fun retain(
        key: Any,
        protect: () -> (() -> Unit),
    ): () -> Unit {
        val policy = policies[key]?.also { it.count++ } ?: Policy(protect()).also { policies[key] = it }
        return {
            if (--policy.count == 0) {
                policies.remove(key)
                policy.restore()
            }
        }
    }
}

/** The [QuestionBlock] reveal for a question whose Other field does not bring the actions along. */
internal val NoReveal: suspend () -> Unit = {}

/** Bounds each daemon-authored string rendered by a question row. */
private const val MAX_QUESTION_TEXT = 8192

/**
 * Figma `347:6697`: a tertiary header line above a bordered card holding the question and its rows.
 *
 * [revealActions] scrolls the batch's actions into view; a focused Other field runs it before bringing itself into
 * view, so the field and both actions show above the keyboard (#1484, Figma `636:3803`). The thread passes it to the
 * last question only; the others keep [NoReveal].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuestionBlock(
    index: Int,
    question: Question,
    selection: QuestionSelection,
    enabled: Boolean,
    onEvent: (QuestionModalEvent) -> Unit,
    revealActions: suspend () -> Unit = NoReveal,
) {
    val currentRevealActions by rememberUpdatedState(revealActions)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Image(
                painter = painterResource(R.drawable.ic_question_glyph),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.size(width = 14.dp, height = 16.dp).testTag("question_header_glyph_$index"),
            )
            Text(
                text = question.header.take(MAX_QUESTION_TEXT).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("question_card_$index"),
            shape = MaterialTheme.shapes.modalControl,
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(
                modifier = Modifier.padding(16.dp).then(if (question.multiSelect) Modifier else Modifier.selectableGroup()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = question.question.take(MAX_QUESTION_TEXT),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeightStyle = FrameLineBox),
                    fontWeight = FontWeight.Medium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.options.forEachIndexed { optionIndex, option ->
                        ChoiceRow(
                            selected = optionIndex in selection.optionIndices,
                            multiSelect = question.multiSelect,
                            enabled = enabled,
                            controlTag = "question_control_${index}_$optionIndex",
                            onClick = { onEvent(QuestionModalEvent.OptionToggled(index, optionIndex)) },
                        ) {
                            Column(Modifier.padding(top = 2.dp)) {
                                Text(
                                    option.label.take(MAX_QUESTION_TEXT),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(option.description.take(MAX_QUESTION_TEXT), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    ChoiceRow(
                        selected = selection.otherTicked,
                        multiSelect = question.multiSelect,
                        enabled = enabled,
                        controlTag = "question_control_${index}_other",
                        onClick = { onEvent(QuestionModalEvent.OtherToggled(index)) },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Figma `636:3279`: the label's full 20 dp line box, top-aligned with the control (#1501).
                            Text(
                                stringResource(R.string.question_other),
                                style = MaterialTheme.typography.labelLarge.copy(lineHeightStyle = FrameLineBox),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            val requester = remember { BringIntoViewRequester() }
                            val scope = rememberCoroutineScope()
                            var focused by remember { mutableStateOf(false) }
                            val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                            val relocationSpec = LocalBringIntoViewSpec.current
                            // The last run sees the settled inset, so every run ends at the same position. The effect can
                            // start while the lazy item is composed inside the list's measure, where a scroll may not run,
                            // so the scroll waits for the next frame.
                            val reveal: suspend () -> Unit = {
                                withFrameNanos {}
                                currentRevealActions()
                                requester.bringIntoView()
                            }
                            LaunchedEffect(focused, imeBottom, relocationSpec) {
                                if (focused) reveal()
                            }
                            val placeholder = stringResource(R.string.question_other_placeholder)
                            // Figma `636:3279`: the 32 dp well is the field's whole layout, 28 dp below the label's top. Its 48 dp
                            // touch target comes from pointer hit-test expansion to `ViewConfiguration.minimumTouchTargetSize`,
                            // which takes no layout space (#1501).
                            // The field's own scrolling uses its local well, not the thread's chrome reservations.
                            val fieldRelocationSpec = remember { object : BringIntoViewSpec {} }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides fieldRelocationSpec) {
                                BasicTextField(
                                    value = selection.otherText,
                                    onValueChange = { onEvent(QuestionModalEvent.OtherTextChanged(index, it)) },
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .bringIntoViewRequester(requester)
                                            .onFocusChanged { focus ->
                                                focused = focus.isFocused
                                                if (focused) scope.launch { reveal() }
                                            }.testTag("question_other_$index"),
                                    enabled = enabled,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.modalFieldText),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { field ->
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 32.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.modalFieldContainer,
                                                        MaterialTheme.shapes.modalControl,
                                                    ).padding(horizontal = 12.dp, vertical = 8.dp),
                                        ) {
                                            if (selection.otherText.isEmpty()) {
                                                Text(
                                                    placeholder,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.inversePrimary,
                                                )
                                            }
                                            field()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Figma draws the question and "Other" lines in their full 20 dp boxes; the theme's styles would trim them to the glyphs. */
private val FrameLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** One option or Other row: radio semantics on a single-choice question, checkbox on a multiple-choice. */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    controlTag: String,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
) {
    val interaction =
        if (multiSelect) {
            Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
        } else {
            Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(interaction)
                .testTag("${controlTag}_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val tertiary = MaterialTheme.colorScheme.tertiary
        val shape = if (multiSelect) MaterialTheme.shapes.extraSmall else CircleShape
        Box(
            modifier =
                Modifier
                    .size(20.dp)
                    .border(2.dp, tertiary, shape)
                    .testTag(controlTag),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                if (multiSelect) {
                    Image(
                        painter = painterResource(R.drawable.ic_question_check),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tertiary),
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Box(Modifier.size(10.dp).background(tertiary, CircleShape))
                }
            }
        }
        Column(Modifier.weight(1f)) { label() }
    }
}
