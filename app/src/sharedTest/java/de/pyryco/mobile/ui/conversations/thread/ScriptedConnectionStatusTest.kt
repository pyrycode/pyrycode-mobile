package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 1b (#474): the thread's connection state (#200/#201) driven through the real
 * [ThreadViewModel.connectionState] (`ConnectionStateSource.observe()` → `stateIn`, #307) and rendered
 * on the [ScriptedThreadHarness]. The reading reacts to its **own** connection seam — disjoint from the `FakeSessionPump` envelope
 * stream that drives the #432 deltas / #472 tool rows — so this case scripts `ConnectionState` via
 * `pushConnectionState`, not the pump.
 *
 * Assertions wait for distinctive rendered text, then check its placement. Connecting and Reconnecting
 * live in the composer status row; Offline is a top pill with a retry action.
 *
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required), alongside
 * [ScriptedToolRowTest] / [ScriptedThreadRenderTest].
 */
@RunWith(AndroidJUnit4::class)
class ScriptedConnectionStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // Connecting uses the composer's status row.
    @Test
    fun connecting_showsConnectingAffordance() {
        harness.pushConnectionState(ConnectionState.Connecting)

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Connecting", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Connecting", substring = true).assertIsDisplayed()
        assertTrue(
            composeRule
                .onNodeWithText("Connecting", substring = true)
                .getUnclippedBoundsInRoot()
                .top.value >
                composeRule
                    .onRoot()
                    .getUnclippedBoundsInRoot()
                    .bottom.value * 0.7f,
        )
    }

    // Reconnecting keeps the countdown in the same status row.
    @Test
    fun reconnecting_showsCountdownAffordance() {
        harness.pushConnectionState(ConnectionState.Reconnecting(secondsRemaining = 12))

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Reconnecting in 12s", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Reconnecting in 12s", substring = true).assertIsDisplayed()
        assertTrue(
            composeRule
                .onNodeWithText("Reconnecting in 12s", substring = true)
                .getUnclippedBoundsInRoot()
                .top.value >
                composeRule
                    .onRoot()
                    .getUnclippedBoundsInRoot()
                    .bottom.value * 0.7f,
        )
    }

    // Offline appears in the top overlay rather than the status row.
    @Test
    fun offline_showsTopRetryPill() {
        harness.pushConnectionState(ConnectionState.Offline)

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Offline · Retry", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Offline · Retry", substring = true).assertIsDisplayed()
        assertTrue(
            composeRule
                .onNodeWithText("Offline · Retry", substring = true)
                .getUnclippedBoundsInRoot()
                .top.value < 200f,
        )
    }

    // Connected removes the earlier connection reading after recovery.
    @Test
    fun connected_hidesConnectionReadingAfterRecovery() {
        harness.pushConnectionState(ConnectionState.Connecting)
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Connecting", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Connecting", substring = true).assertIsDisplayed()

        harness.pushConnectionState(ConnectionState.Connected)
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("Connecting", substring = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithText("Connecting", substring = true).assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
