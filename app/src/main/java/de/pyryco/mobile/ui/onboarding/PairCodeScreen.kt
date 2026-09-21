package de.pyryco.mobile.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
        val saving = state.phase == PairCodePhase.Saving
        val colors = MaterialTheme.colorScheme
        val uriHandler = LocalUriHandler.current
        Column(Modifier.fillMaxSize().background(colors.surface).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onEvent(PairCodeEvent.Back) }, enabled = !saving) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Pairing", style = MaterialTheme.typography.titleLarge, color = colors.onPrimaryContainer)
            }
            HorizontalDivider(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp), color = colors.inversePrimary.copy(alpha = 0.6f))
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().drawBehind {
                    val center = Offset(size.width * 0.48f, size.height * 0.30f)
                    scale(scaleX = 1f, scaleY = 2.53f, pivot = center) {
                        drawRect(
                            Brush.radialGradient(
                                0f to colors.primaryContainer,
                                0.7f to Color.Transparent,
                                center = center,
                                radius = size.width * 0.69f,
                            ),
                        )
                    }
                },
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(
                            rememberScrollState(),
                        ).heightIn(min = maxHeight)
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                ) {
                    Column(
                        Modifier.weight(1f).heightIn(min = 200.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterVertically),
                    ) {
                        PairCodeField("Host name", state.name, editing, { onEvent(PairCodeEvent.Name(it)) }, "Clear host name")
                        PairCodeField(
                            "Pairing code",
                            state.code,
                            editing,
                            { onEvent(PairCodeEvent.Code(it)) },
                            "Clear pairing code",
                            error = state.error == "Invalid pairing code",
                        )
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.error?.takeUnless { it == "Invalid pairing code" }?.let {
                            Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onEvent(PairCodeEvent.Pair) },
                            enabled = editing,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Text(
                                when {
                                    saving -> "Saving…"
                                    state.phase == PairCodePhase.Connecting -> "Connecting…"
                                    state.error != null && state.error != "Invalid pairing code" -> "Retry"
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
    error: Boolean = false,
) {
    // Keep M3's native empty-label placement; labels float when focused or populated.
    // The Figma input-text variant shows the floated label even with an empty draft.
    TextField(
        value,
        onChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        isError = error,
        supportingText =
            if (error) {
                { Text("Invalid pairing code") }
            } else {
                null
            },
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f),
            ),
        trailingIcon = {
            IconButton(onClick = { onChange("") }, enabled = enabled) {
                Icon(Icons.Outlined.Cancel, contentDescription = clearLabel)
            }
        },
    )
}

@Preview(name = "Pair code dark", widthDp = 412, heightDp = 892)
@Composable
private fun PairCodeDarkPreview() = PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(PairCodeState(), {}) }

@Preview(name = "Pair code light", widthDp = 412, heightDp = 892)
@Composable
private fun PairCodeLightPreview() = PyrycodeMobileTheme(darkTheme = false) { PairCodeScreen(PairCodeState(), {}) }
