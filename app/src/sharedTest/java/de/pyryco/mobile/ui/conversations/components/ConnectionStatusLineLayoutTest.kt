package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.settings.HostIdentityRow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionStatusLineLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connectedGroups_stayWholeAtNormalAndEnlargedText() = exercise(connectedOnly = true)

    @Test fun otherStatuses_stayWholeAtNormalAndEnlargedText() = exercise(connectedOnly = false)

    private fun exercise(connectedOnly: Boolean) {
        var scale by mutableStateOf(1f)
        var dark by mutableStateOf(false)
        var status by mutableStateOf(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
        var density = 1f
        val expectedSizes = mutableMapOf<String, IntSize>()
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    density = LocalDensity.current.density
                    PyrycodeMobileTheme(darkTheme = dark, dynamicColor = false) {
                        val measurer = rememberTextMeasurer()
                        for ((name, visual) in listOf("Relay" to status.relay.toLegVisual(), "Pyrycode" to status.pyrycode.toLegVisual())) {
                            val nameSize = measurer.measure(name, MaterialTheme.typography.labelMedium).size
                            val labelSize = measurer.measure(visual.label, MaterialTheme.typography.labelSmall).size
                            expectedSizes[name] =
                                IntSize(
                                    nameSize.width + labelSize.width +
                                        with(LocalDensity.current) { 8.dp.roundToPx() + 2 * 6.dp.roundToPx() },
                                    maxOf(nameSize.height, labelSize.height),
                                )
                        }
                        Surface {
                            HostIdentityRow(
                                name = "Pyrybox",
                                serverId = "test-server",
                                relayUrl = "wss://relay.example",
                                status = status,
                                modifier = Modifier.fillMaxWidth().testTag("host"),
                            )
                        }
                    }
                }
            }
        }
        val statuses =
            if (connectedOnly) {
                listOf(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
            } else {
                listOf(
                    ConnectionStatus(RelayLinkStatus.Idle, PyrycodeLinkStatus.Down),
                    ConnectionStatus(RelayLinkStatus.Connecting, PyrycodeLinkStatus.Handshaking),
                    ConnectionStatus(RelayLinkStatus.Reconnecting(12), PyrycodeLinkStatus.Handshaking),
                    ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down),
                    ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down),
                    ConnectionStatus(RelayLinkStatus.UpdateRequired(null), PyrycodeLinkStatus.Down),
                    ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
                )
            }
        for (darkTheme in listOf(false, true)) {
            for (fontScale in listOf(1f, 2f)) {
                for (value in statuses) {
                    rule.runOnIdle {
                        scale = fontScale
                        dark = darkTheme
                        status = value
                    }
                    val host = rule.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
                    assertEquals("fixture width", 412 * density, host.width, 1f)

                    fun leg(
                        name: String,
                        description: String,
                    ): Rect {
                        val bounds =
                            rule
                                .onNodeWithContentDescription(description)
                                .assertIsDisplayed()
                                .fetchSemanticsNode()
                                .boundsInRoot
                        val expected = expectedSizes.getValue(name)
                        assertEquals("$description width at $fontScale", expected.width.toFloat(), bounds.width, 1f)
                        assertEquals("$description unwrapped height at $fontScale", expected.height.toFloat(), bounds.height, 1f)
                        assertTrue(
                            "$description inside host",
                            bounds.left >= host.left && bounds.right <= host.right && bounds.bottom <= host.bottom,
                        )
                        return bounds
                    }
                    val relay = leg("Relay", value.relay.toLegVisual().contentDescription)
                    val pyrycode = leg("Pyrycode", value.pyrycode.toLegVisual().contentDescription)
                    assertEquals("Settings start inset", host.left + 16 * density, relay.left, 1f)
                    if (fontScale == 1f) {
                        assertEquals("normal spacing", 24 * density, pyrycode.left - relay.right, 1f)
                        assertEquals("normal alignment", relay.center.y, pyrycode.center.y, 1f)
                    } else {
                        assertTrue("whole groups do not overlap", pyrycode.top >= relay.bottom || pyrycode.left >= relay.right)
                        if (connectedOnly) {
                            assertEquals("stacked start", relay.left, pyrycode.left, 1f)
                            assertTrue("enlarged connected groups stack", pyrycode.top > relay.bottom)
                        }
                    }
                }
            }
        }
    }
}
