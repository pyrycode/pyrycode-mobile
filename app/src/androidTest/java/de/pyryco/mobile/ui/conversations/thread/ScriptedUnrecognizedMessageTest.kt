package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.e2e.UnrecognizedRowRecorder
import de.pyryco.mobile.e2e.UnrecognizedRowSentinel
import de.pyryco.mobile.e2e.unrecognizedFinding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 2 (#586): the **non-vacuity proof** for the parser-gap sentinel [UnrecognizedRowSentinel]. A
 * scripted `unrecognized_message` envelope is driven through the real [ScriptedThreadHarness] chain —
 * `RemoteConversationRepository`'s #609 fold → `TappingConversationRepository` → [UnrecognizedRowRecorder]
 * → [unrecognizedFinding] — proving the guard is *capable of failing* rather than merely never firing.
 *
 * The proof cannot live on the deterministic rung: the daemon emits `unrecognized_message` only from its
 * **stream-json** runner, and rung 4 runs the PTY runner, so a fixture line with an unknown `type`
 * produces nothing (#613 is the blocked follow-up). Rung 3 carries the gate, rung 2 carries the proof —
 * the #594/#597 carve-out inverted (see `docs/e2e-interactive-stream.md` § Follow-ups → Coverage).
 *
 * Assertions are on the *finding string*, not on rendered UI: the row's own render is #608's concern and
 * is covered there. Two of the six cases are security assertions rather than behaviour ones — the finding
 * must never contain the payload body, and an untrusted `message_type` must not be able to forge a line.
 * Runs under `./gradlew connectedAndroidTest` (device/emulator required).
 */
@RunWith(AndroidJUnit4::class)
class ScriptedUnrecognizedMessageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    @Before
    fun setUp() {
        // The harness is per-test; the recorder is a process-global object whose lifecycle the sentinel
        // rule owns at rung 3. There is no rule here, so this class resets it itself.
        UnrecognizedRowRecorder.reset()
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #4: a scripted frame driven through the real fold makes the guard fire. This is the whole
    // ticket's non-vacuity proof — everything below refines what the fired finding may say.
    @Test
    fun scriptedRow_makesTheGuardFire() {
        harness.pushUnrecognizedMessage(site = "assistant_block", messageType = MESSAGE_TYPE, raw = RAW_WITH_QUOTES)

        awaitRecordedRow()

        assertNotNull(unrecognizedFinding(UnrecognizedRowRecorder.observed()))
    }

    // AC #1: the failure names the frame's site and message_type, so the operator can re-take the
    // daemon's measured ignore-list without re-running the gate to find out what fired.
    @Test
    fun finding_namesTheSiteAndTheMessageType() {
        harness.pushUnrecognizedMessage(site = "assistant_block", messageType = MESSAGE_TYPE, raw = RAW_WITH_QUOTES)
        awaitRecordedRow()

        val finding = requireNotNull(unrecognizedFinding(UnrecognizedRowRecorder.observed()))

        assertTrue(finding, finding.contains("assistant_block"))
        assertTrue(finding, finding.contains(MESSAGE_TYPE))
    }

    // AC #1 (security): the offending payload body is never in the output. `raw` is the most untrusted
    // string the thread holds and `unrecognizedFinding` must never read it — asserted, not commented.
    @Test
    fun finding_neverContainsThePayloadBody() {
        harness.pushUnrecognizedMessage(site = "user_block", messageType = MESSAGE_TYPE, raw = RAW_WITH_SENTINEL)
        awaitRecordedRow()

        val finding = requireNotNull(unrecognizedFinding(UnrecognizedRowRecorder.observed()))

        assertFalse(finding, finding.contains(RAW_SENTINEL))
    }

    // Security review §(b): an untrusted `message_type` must not be able to forge an extra finding line
    // (newline) or drive the operator's terminal (ANSI escape), and must be length-bounded.
    @Test
    fun finding_sanitizesAndBoundsTheMessageType() {
        harness.pushUnrecognizedMessage(site = "line_type", messageType = HOSTILE_MESSAGE_TYPE, raw = "{}")
        awaitRecordedRow()

        val finding = requireNotNull(unrecognizedFinding(UnrecognizedRowRecorder.observed()))

        // One row in ⇒ exactly one row line out: the embedded newline forged nothing.
        assertEquals(finding, 1, finding.lines().count { it.trimStart().startsWith("- site=") })
        assertFalse(finding, finding.contains(ESCAPE))
        assertFalse(finding, finding.contains(PADDING.take(MESSAGE_TYPE_CAP + 1)))
    }

    // AC #5's rung-2 mirror, and the fence proving the assertions above are not matching everything: a
    // thread that never sees the frame yields no finding at all.
    @Test
    fun cleanHarness_producesNoFinding() {
        harness.pushAssistantDelta(turnId = TURN_ID, seq = 0, text = CLEAN_TEXT)
        harness.pushTurnEnd(turnId = TURN_ID)
        awaitText(CLEAN_TEXT)

        assertNull(UnrecognizedRowRecorder.observed().toString(), unrecognizedFinding(UnrecognizedRowRecorder.observed()))
    }

    // Proves the probe exercises the REAL mapper rather than a shortcut: `toRow()` returns null off the
    // closed four-value `site` set, so the frame drops. The delta pushed after it on the same collector
    // is the fence — its render proves the dropped envelope was processed, not merely still in flight.
    @Test
    fun unrecognizedSiteToken_dropsWithNoFinding() {
        harness.pushUnrecognizedMessage(site = "not_a_site", messageType = MESSAGE_TYPE, raw = RAW_WITH_QUOTES)
        harness.pushAssistantDelta(turnId = TURN_ID, seq = 0, text = CLEAN_TEXT)
        harness.pushTurnEnd(turnId = TURN_ID)
        awaitText(CLEAN_TEXT)

        assertNull(UnrecognizedRowRecorder.observed().toString(), unrecognizedFinding(UnrecognizedRowRecorder.observed()))
    }

    private fun awaitRecordedRow() {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) { UnrecognizedRowRecorder.observed().isNotEmpty() }
    }

    private fun awaitText(text: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(text, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** Mirrors `UnrecognizedRowSentinel`'s cap; the padding below straddles it deliberately. */
        const val MESSAGE_TYPE_CAP = 64

        const val TURN_ID = "t1"

        /** Distinctive, all-printable-ASCII, so sanitization must pass it through verbatim. */
        const val MESSAGE_TYPE = "e2e_probe_kind_586"

        /** Realistic `raw`: itself JSON, so a string-interpolated envelope builder would malform. */
        const val RAW_WITH_QUOTES = """{"type":"e2e_probe_kind_586","body":"quoted \"inner\" value"}"""

        /** Appears nowhere else in the suite — its absence from the finding is the security assertion. */
        const val RAW_SENTINEL = "zqx586-payload-body-must-not-leak"

        const val RAW_WITH_SENTINEL = """{"secret":"$RAW_SENTINEL"}"""

        /** A bare ANSI escape (ESC, 0x1B) — outside the finding's printable-ASCII allowlist. */
        const val ESCAPE = "\u001B"

        const val PADDING = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        /** A newline to forge a second finding line, an ANSI escape, and a length well past the cap. */
        const val HOSTILE_MESSAGE_TYPE = "kind$ESCAPE[31m\nsite=forged$PADDING"

        /** Rendered by the streaming row — the arrival fence for the two never-fires cases. */
        const val CLEAN_TEXT = "ordinary delta text"
    }
}
