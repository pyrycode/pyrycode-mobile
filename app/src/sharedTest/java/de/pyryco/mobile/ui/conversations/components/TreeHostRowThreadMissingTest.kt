package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #2175: a connected host whose daemon lacks threads reuses the #1009 row with daemon copy and the reconnect control. */
@RunWith(AndroidJUnit4::class)
class TreeHostRowThreadMissingTest {
    @get:Rule val compose = createComposeRule()

    private val connected = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    @Test fun missingThreadHostShowsDaemonCaptionAndRetriesThatHost() {
        var reconnects = 0
        var expanded by mutableStateOf(true)
        compose.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "srv",
                    hostName = "Box",
                    connectionStatus = connected,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    onEditTapped = {},
                    onReconnectTapped = { reconnects++ },
                    threadMissing = true,
                )
            }
        }
        compose.onNodeWithText(string(R.string.tree_host_thread_missing)).assertIsDisplayed()
        compose.onAllNodesWithTag(treeHostUpdateTestTag("srv")).assertCountEquals(0)
        compose.onNodeWithTag(treeHostEditTestTag("srv")).assertIsDisplayed()
        compose
            .onNodeWithTag(treeHostReconnectTestTag("srv"))
            .assert(hasContentDescription(string(R.string.cd_tree_host_reconnect, "Box")))
            .performClick()
        assertEquals(1, reconnects)
        expanded = false
        compose.waitForIdle()
        compose.onNodeWithText(string(R.string.tree_host_thread_missing)).assertIsDisplayed()
    }

    @Test fun appUpdateKeepsItsOwnTreatmentOverAMissingThread() {
        compose.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "srv",
                    hostName = "Box",
                    connectionStatus = ConnectionStatus(RelayLinkStatus.UpdateRequired(null), PyrycodeLinkStatus.Down),
                    expanded = true,
                    onToggleExpanded = {},
                    onEditTapped = {},
                    threadMissing = true,
                )
            }
        }
        compose.onNodeWithText(string(R.string.tree_host_update_required)).assertIsDisplayed()
        compose.onNodeWithTag(treeHostUpdateTestTag("srv")).assertIsDisplayed()
        compose.onAllNodesWithText(string(R.string.tree_host_thread_missing)).assertCountEquals(0)
    }

    @Test fun capableHostDrawsNoDaemonTreatment() {
        compose.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "srv",
                    hostName = "Box",
                    connectionStatus = connected,
                    expanded = true,
                    onToggleExpanded = {},
                    onEditTapped = {},
                )
            }
        }
        compose.onAllNodesWithText(string(R.string.tree_host_thread_missing)).assertCountEquals(0)
        compose.onAllNodesWithTag(treeHostReconnectTestTag("srv")).assertCountEquals(0)
    }
}
