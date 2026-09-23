package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 1b (#474): the thread's connection banner (#200/#201) driven through the real
 * [ThreadViewModel.connectionState] (`ConnectionStateSource.observe()` → `stateIn`, #307) and rendered
 * by [de.pyryco.mobile.ui.conversations.components.ConnectionBanner] on the [ScriptedThreadHarness].
 * The banner reacts to its **own** connection seam — disjoint from the `FakeSessionPump` envelope
 * stream that drives the #432 deltas / #472 tool rows — so this case scripts `ConnectionState` via
 * `pushConnectionState`, not the pump.
 *
 * Assertions are **tolerant** (presence/absence of the distinctive rendered substring, generous
 * `waitUntil`), never on timing — the `docs/e2e-interactive-stream.md` ladder-doc rule. Banner strings
 * carry non-ASCII glyphs (`Connecting…` U+2026, `Offline — tap to retry` U+2014); each assertion keys
 * on an ASCII-only substring **before** the glyph so the match never depends on reproducing it.
 *
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required), alongside
 * [ScriptedToolRowTest] / [ScriptedThreadRenderTest].
 */
@RunWith(AndroidJUnit4::class)
class ScriptedConnectionBannerTest {
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

    // Connecting (AC #1): push Connecting → the banner shows the "Connecting…" affordance. Assert on the
    // ASCII "Connecting" substring, before the U+2026 ellipsis. The capital-C cannot collide with
    // Reconnecting's lowercase "connecting" — only one banner is on screen per single-state test.
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
    }

    // Reconnecting (AC #2): push Reconnecting(12) → the banner shows the countdown affordance. Assert the
    // full all-ASCII literal "Reconnecting in 12s" — proves the countdown value 12 is surfaced, which the
    // AC explicitly requires (the literal must stay in sync with the pushed secondsRemaining).
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
    }

    // Offline (AC #3): push Offline → the banner shows the offline / tap-to-retry affordance. Assert the
    // ASCII "tap to retry" substring, after the U+2014 em-dash, so the match dodges the glyph.
    @Test
    fun offline_showsTapToRetryAffordance() {
        harness.pushConnectionState(ConnectionState.Offline)

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText("tap to retry", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("tap to retry", substring = true).assertIsDisplayed()
    }

    // Connected (AC #4): a present→absent fence (mirrors the spinner test) so absence is not a trivial
    // never-shown false-green. Push Connecting → wait for it to render → push Connected → wait for the
    // banner to vanish (ConnectionBanner early-returns for Connected) → assert it is gone. Proves the
    // banner appears then disappears on reconnect, which is stronger than asserting the default-absent state.
    @Test
    fun connected_hidesBannerAfterDisconnect() {
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
