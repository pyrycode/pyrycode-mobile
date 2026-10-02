package de.pyryco.mobile.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
internal fun PairCodeScreen(
    state: PairCodeState,
    onEvent: (PairCodeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onEvent(PairCodeEvent.Back) }
    Box(modifier.fillMaxSize()) {
        if (state.phase == PairCodePhase.Confirming) {
            state.confirmation?.let {
                ScannerScreen(
                    it,
                    {},
                    {},
                    {},
                    Modifier,
                    onConfirmPairing = { onEvent(PairCodeEvent.Confirm) },
                    onDeclinePairing = { onEvent(PairCodeEvent.Back) },
                )
            }
            return@Box
        }
        val editing = state.phase == PairCodePhase.Editing
        // After a verification failure the pairing is saved: the draft is frozen, and Pair can only
        // wait again, which a rejected pairing cannot (#1385).
        val drafting = editing && state.failure == null
        val canRetry = state.failure?.retryable != false
        val saving = state.phase == PairCodePhase.Saving
        val colors = MaterialTheme.colorScheme
        val uriHandler = LocalUriHandler.current
        val codeError = state.error?.takeIf { it == INVALID_CODE_ERROR || it == WRONG_HOST_ERROR }
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surface)
                .onboardingGlow()
                .systemBarsPadding()
                .imePadding(),
        ) {
            PairingHeader(
                title = "Pairing",
                titleColor = colors.onPrimaryContainer,
                onBack = { onEvent(PairCodeEvent.Back) },
                backIcon = painterResource(R.drawable.ic_pair_back),
                backEnabled = !saving,
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState(),
                        ).heightIn(min = maxHeight)
                        .height(IntrinsicSize.Min)
                        .padding(start = 32.dp, end = 32.dp, top = 32.dp, bottom = 4.dp),
                ) {
                    Column(
                        Modifier.weight(1f).heightIn(min = 200.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterVertically),
                    ) {
                        // Re-pairing (#842) names its host and keeps the stored name, so the field shows
                        // that name read-only; the name is daemon-authored and rendered as text only.
                        val targetName = state.targetName
                        if (targetName != null) {
                            PairCodeField("Host name", targetName, false, {}, "Clear host name")
                        } else {
                            PairCodeField("Host name", state.name, drafting, { onEvent(PairCodeEvent.Name(it)) }, "Clear host name")
                        }
                        PairCodeField(
                            "Pairing code",
                            state.code,
                            drafting,
                            { onEvent(PairCodeEvent.Code(it)) },
                            "Clear pairing code",
                            error = codeError,
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.error?.takeUnless { it == codeError }?.let {
                            Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onEvent(PairCodeEvent.Pair) },
                            enabled = editing && canRetry,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                when {
                                    saving -> "Saving…"
                                    state.phase == PairCodePhase.Connecting -> "Connecting…"
                                    state.error != null && codeError == null && canRetry -> "Retry"
                                    else -> "Pair"
                                },
                            )
                        }
                        TextButton(
                            onClick = { onEvent(PairCodeEvent.Back) },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text("Cancel", color = if (saving) colors.onSurface.copy(alpha = 0.38f) else colors.onSurface)
                        }
                        Text(
                            "Open source · github.com/pyrycode/pyrycode-mobile",
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        top = 8.dp,
                                    ).clickable { uriHandler.openUri("https://github.com/pyrycode/pyrycode-mobile") },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PairCodeField(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    clearLabel: String,
    error: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.surfaceContainerLowest.copy(alpha = 0.8f), MaterialTheme.shapes.extraSmall)
                .drawBehind {
                    drawLine(
                        color =
                            when {
                                error != null -> colors.error
                                focused -> colors.primary
                                else -> colors.outline
                            },
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = (if (focused || error != null) 2.dp else 1.dp).toPx(),
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                modifier =
                    Modifier.weight(1f).heightIn(min = 56.dp).semantics {
                        contentDescription = label
                        if (error != null) error(error)
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Column(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (error != null) colors.error else colors.onSurfaceVariant,
                        )
                        Box(Modifier.fillMaxWidth().heightIn(min = 20.dp)) { innerTextField() }
                    }
                },
            )
            IconButton(onClick = { onChange("") }, enabled = enabled) {
                Icon(Icons.Outlined.Cancel, contentDescription = clearLabel)
            }
        }
        if (error != null) {
            Text(
                error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(name = "Pair code dark", widthDp = 412, heightDp = 892)
@Composable
private fun PairCodeDarkPreview() = PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(PairCodeState(), {}) }

@Preview(name = "Pair code light", widthDp = 412, heightDp = 892)
@Composable
private fun PairCodeLightPreview() = PyrycodeMobileTheme(darkTheme = false) { PairCodeScreen(PairCodeState(), {}) }

@Preview(name = "Re-pair host dark", widthDp = 412, heightDp = 892)
@Composable
private fun PairCodeTargetPreview() =
    PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(PairCodeState(targetName = "Pyrybox", error = WRONG_HOST_ERROR), {}) }
