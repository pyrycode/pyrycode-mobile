package de.pyryco.mobile.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConnectionStateSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Deterministic end-to-end test for the mobile interactive event stream (#431 rung 4, ADR 025): the
 * **real** app on a headless emulator connects to a host `pyry` + relay backed by the scripted
 * `fakeclaude` backend (pyrycode #642) — **no real claude, zero claude turns** — and asserts the
 * scripted "ping" reply renders in the thread.
 *
 * It is a thin variant of [InteractiveStreamE2ETest] (rung 3). **One** step differs: instead of
 * tapping "New discussion" (which mints a *fresh* per-conversation claude session that `fakeclaude` —
 * being env-only — never writes to), it taps a **seeded promoted channel** the host pre-binds to the
 * bootstrap session id. One conversation, one session, one fixture file → the daemon's by-id producer
 * tails exactly the file `fakeclaude` writes. See `scripts/e2e-emulator.sh` (DETERMINISTIC mode) and
 * `docs/e2e-interactive-stream.md`.
 *
 * Driven by `DETERMINISTIC=1 bash scripts/e2e-emulator.sh`, which seeds the channel, pre-creates the
 * session JSONL, starts the daemon with `-pyry-claude=<fakeclaude>`, mints a device token with
 * `pyry pair`, and — after the `send_message` ack fence — drops the fixed `ping.jsonl` fixture.
 * [E2eInstrumentationRunner] swaps in [E2eTestApplication] (paired + relay-backed) from the same
 * instrumentation args as rung 3.
 *
 * Deterministic, but assertions stay **tolerant** (substring + case insensitive, generous timeout,
 * never on delta counts or timing) so the test survives harmless streaming/rendering variation. The
 * scripted backend replies "ping" regardless of the prompt, so a non-"ping" prompt keeps the only
 * on-screen "ping" the scripted reply — no baseline/+1 dance is needed.
 *
 * The Compose selectors are text / content-description based (no test tags in the production UI). If
 * the UI strings — or the seeded channel name in `scripts/e2e-emulator.sh` — change, update the
 * constants below.
 */
@RunWith(AndroidJUnit4::class)
class DeterministicInteractiveStreamE2ETest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread() {
        // 1. A paired launch lands on the channel list. The host-seeded promoted channel "e2e-ping"
        //    surfaces once list_conversations round-trips, so waiting for that text node implicitly
        //    waits for the connection + list response.
        composeTestRule.waitUntil(LIST_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(SEED_CHANNEL_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Explicit connection gate before sending — tapping the channel and sending the prompt both
        //    round-trip to the daemon, so the relay session must be Open first.
        awaitConnected()

        // 3. Tap the seeded channel row → the app navigates into its thread. The send button (only on
        //    the thread) is the marker that we have arrived.
        composeTestRule.onAllNodesWithText(SEED_CHANNEL_NAME).onFirst().performClick()
        composeTestRule.waitUntil(THREAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasContentDescription(CD_SEND_MESSAGE)).fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Type a non-"ping" prompt into the only editable field, then send. The scripted backend
        //    replies "ping" regardless, so the prompt text never contributes a "ping" node.
        composeTestRule.onNode(hasSetTextAction()).performTextInput(SEND_PROMPT)
        composeTestRule.onNode(hasContentDescription(CD_SEND_MESSAGE)).performClick()

        // 5. Wait (tolerantly) for the scripted reply to render, then confirm it is on screen. Generous
        //    timeout: the fixture drop is fenced on the host-observed send_message ack, then tails the
        //    real producer → Noise/relay → phone fold.
        composeTestRule.waitUntil(REPLY_TIMEOUT_MS) {
            composeTestRule.onAllNodesWithText(PING, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule
            .onAllNodesWithText(PING, substring = true, ignoreCase = true)
            .onFirst()
            .assertIsDisplayed()
    }

    /** Block until the relay connection reports [ConnectionState.Connected], or fail after a timeout. */
    private fun awaitConnected() {
        val source = GlobalContext.get().get<ConnectionStateSource>()
        runBlocking {
            withTimeout(CONNECT_TIMEOUT_MS) {
                source.observe().first { it is ConnectionState.Connected }
            }
        }
    }

    private companion object {
        const val PING = "ping"

        // The host-seeded promoted channel's name (scripts/e2e-emulator.sh writes conversations.json
        // with name="e2e-ping", is_promoted=true). Keep the two in sync.
        const val SEED_CHANNEL_NAME = "e2e-ping"

        // A non-"ping" prompt: the scripted backend ignores it and always replies "ping", so the only
        // on-screen "ping" is the scripted reply — no baseline/count dance needed.
        const val SEND_PROMPT = "hello"

        // Production UI string (no test tags exist). Keep in sync with res/values/strings.xml:
        //   cd_send_message = "Send message".
        const val CD_SEND_MESSAGE = "Send message"

        const val LIST_TIMEOUT_MS = 30_000L
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val THREAD_TIMEOUT_MS = 30_000L

        // Generous: the fixture drop is fenced on the ack, then tails the real producer over the relay.
        const val REPLY_TIMEOUT_MS = 90_000L
    }
}
