package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshot
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.data.repository.threadSnapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.atomic.AtomicReference

/**
 * The parser-gap sentinel (#586): a [TestRule] that fails **any** scenario during which the daemon
 * reported a claude message kind its stream-json parser could not map (#609's `unrecognized_message` →
 * [ThreadItem.UnrecognizedMessage]).
 *
 * Declaring one field on a test class arms every method in it, present and future — the guard is a
 * property of the live path, not a line each scenario author remembers to copy:
 *
 * ```
 * @get:Rule
 * val unrecognizedRowSentinel = UnrecognizedRowSentinel()
 * ```
 *
 * **Red does not mean broken.** It means claude gained a message kind and the daemon's measured
 * ignore-list needs re-taking — the alternative is an operator meeting a silently dropped message on
 * their own screen.
 *
 * Three properties, each load-bearing:
 *
 *  * **Accumulating, not snapshotting.** [UnrecognizedRowRecorder] keeps every row it saw for the
 *    duration of the scenario, so a row produced mid-scenario is still found when the scenario ends on a
 *    conversation-list surface with the thread's collector long cancelled. Half of the curated `LIVE=1`
 *    octet (delete, archive-restore, rename, save-as-channel) finishes off the thread, so an end-of-run
 *    look at the screen would be vacuous for them.
 *  * **A red body keeps its own cause.** The likeliest real manifestation of a parser gap is the
 *    scenario's *own* assertion timing out because the reply never rendered; the finding is attached with
 *    [Throwable.addSuppressed] so the operator sees it beside the timeout instead of a bare
 *    `waitUntil timed out`. The original throwable propagates unchanged.
 *  * **Ordering against `composeTestRule` is immaterial** — the reset happens before the body and the
 *    check after it whichever way JUnit nests the two, and the recorder holds its data independently of
 *    whether the activity has been torn down. Deliberately **no** `RuleChain` / `@Rule(order = …)`.
 *
 * The rule asserts; it never observes. Rows arrive through [TappingConversationRepository], installed in
 * the Koin graph by [E2eTestApplication]'s relay branch. Rung 4
 * ([DeterministicInteractiveStreamE2ETest]) records through the same tap but installs no rule, which is
 * correct: its PTY runner has no `unrecognized_message` emitter, so a rule there would be permanently
 * vacuous (#613).
 */
class UnrecognizedRowSentinel : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                UnrecognizedRowRecorder.reset()
                try {
                    base.evaluate()
                } catch (failure: Throwable) {
                    unrecognizedFinding(UnrecognizedRowRecorder.observed())?.let {
                        failure.addSuppressed(AssertionError(it))
                    }
                    throw failure
                }
                unrecognizedFinding(UnrecognizedRowRecorder.observed())?.let { throw AssertionError(it) }
            }
        }
}

/**
 * Process-global accumulator of the [ThreadItem.UnrecognizedMessage] rows that reached the app during the
 * current scenario. Global by necessity: the Koin repository singleton outlives any one test, so the tap
 * that feeds this cannot be per-test. [UnrecognizedRowSentinel] owns the lifecycle ([reset] per scenario);
 * the rung-2 [de.pyryco.mobile.ui.conversations.thread.ScriptedUnrecognizedMessageTest] resets it itself.
 *
 * **Deduped by structural equality, not by [ThreadItem.UnrecognizedMessage.id].** `observeMessages`
 * re-emits the whole thread on every change, so the same row arrives many times; `data class` equality
 * includes `occurredAt`, which dedups those re-emissions while staying immune to the `unrecognized-<n>`
 * counter restarting at 1 when a reconnect builds a fresh repository.
 *
 * **Thread-safe by atomic swap of an immutable set** — [record] runs on the app's collector dispatcher,
 * [reset] / [observed] on the JUnit test thread, and `updateAndGet`'s function is pure so a retried CAS is
 * harmless. No check-then-mutate.
 *
 * **[record] never throws.** It runs inside a flow the app under test is collecting; a throw would
 * propagate into the thread screen and break the very scenario the sentinel is guarding. All assertion
 * lives in the rule.
 */
internal object UnrecognizedRowRecorder {
    /**
     * Upper bound on retained rows. A new claude message kind emitted per content block could produce one
     * frame per delta, and each row carries up to 16 KiB of `raw`. The guard fails on the first row, so
     * nothing past the cap is diagnostically load-bearing — it only makes the reported count a floor, and
     * [unrecognizedFinding] says so in words rather than reporting a saturated count as exact.
     */
    internal const val MAX_RECORDED_ROWS = 32

    private val recorded = AtomicReference<Set<ThreadItem.UnrecognizedMessage>>(emptySet())

    fun reset() {
        recorded.set(emptySet())
    }

    fun record(items: List<ThreadItem>) {
        val rows = items.filterIsInstance<ThreadItem.UnrecognizedMessage>()
        if (rows.isEmpty()) return
        recorded.updateAndGet { current ->
            if (current.size >= MAX_RECORDED_ROWS || current.containsAll(rows)) {
                current
            } else {
                LinkedHashSet(current).apply {
                    for (row in rows) {
                        if (size >= MAX_RECORDED_ROWS) break
                        add(row)
                    }
                }
            }
        }
    }

    fun observed(): List<ThreadItem.UnrecognizedMessage> = recorded.get().toList()
}

/**
 * The operator-facing failure text for [rows], or **null** when there is nothing to report. Pure, so
 * rungs 2 and 3 share one definition of "the guard fired" and one definition of what may be printed.
 *
 * **This is the only function in the suite permitted to read a payload field**, and it is a deliberate,
 * narrow carve-out from the contract stated on `UnrecognizedMessageRow` and
 * `ThreadItem.UnrecognizedMessage` ("no logging: nothing on this path logs any payload field"). A
 * sentinel that says "a parser gap occurred" without naming the kind is undiagnosable. The carve-out is
 * narrowed four ways:
 *
 *  * **`androidTest` only** — never in a shipped APK, never in Logcat on a user's device.
 *  * **Operator-local** — instrumentation output, on a run the operator invoked, on their own machine.
 *  * **[ThreadItem.UnrecognizedMessage.raw] is never read.** Not capped, not sanitized: never read.
 *  * **No conversation id** — structurally impossible, since [ThreadItem.UnrecognizedMessage] carries
 *    none and this function takes nothing else.
 *
 * [ThreadItem.UnrecognizedMessage.site] is client-owned (the closed six-value [UnrecognizedSite] a
 * hostile daemon cannot widen) and printed verbatim **as its wire token** — `assistant_block`, not
 * `AssistantBlock` — because that is the string an operator greps the daemon's `internal/streamsup/`
 * for. [ThreadItem.UnrecognizedMessage.messageType] is daemon-supplied and untrusted, so it is
 * allowlist-sanitized and length-capped by [describe]; [ThreadItem.UnrecognizedMessage.truncated] is a
 * decoded `Boolean` with no injection surface.
 */
internal fun unrecognizedFinding(rows: List<ThreadItem.UnrecognizedMessage>): String? {
    if (rows.isEmpty()) return null
    val elided = rows.size - MAX_LISTED_ROWS
    // A saturated recorder makes the count a floor, not a total (see MAX_RECORDED_ROWS) — say which, so the
    // operator does not read "32 rows" as the measured extent of the parser gap.
    val count =
        if (rows.size >= UnrecognizedRowRecorder.MAX_RECORDED_ROWS) "at least ${rows.size}" else "${rows.size}"
    return buildString {
        append("Parser-gap sentinel (#586): $count unrecognized-message row(s) reached the thread during this scenario.")
        rows.take(MAX_LISTED_ROWS).forEach { append("\n  - ${describe(it)}") }
        if (elided > 0) append("\n  - (and $elided further row(s), not listed)")
        append("\n$REMEDY")
    }
}

/** One row's bounded, sanitized line. Reads three fields; `raw` is not one of them. */
private fun describe(row: ThreadItem.UnrecognizedMessage): String {
    val sanitized = sanitize(row.messageType)
    val shown = sanitized.take(MAX_MESSAGE_TYPE_CHARS)
    val elision = (sanitized.length - shown.length).let { if (it > 0) " (+$it char(s) elided)" else "" }
    // Quoted so the `undecodable` case's documented empty value reads as "" rather than as a blank.
    return "site=${row.site.wireToken()} message_type=\"$shown\"$elision truncated=${row.truncated}"
}

/**
 * Replace every character **outside** a conservative permitted set — this is an allowlist, not a blocklist
 * of known-bad characters. Permitted: printable ASCII (0x20..0x7E) minus `"` and `\`, which would let an
 * untrusted value close or escape [describe]'s quoting. A newline (which could forge a further finding
 * line) and an ANSI escape (which could drive the operator's terminal) both fall outside the range.
 *
 * Non-ASCII is replaced too, so a non-Latin message kind reads as a run of [SANITIZED_REPLACEMENT] rather
 * than as itself. That is the deliberate conservative start: widening the set to make such a kind
 * readable would defend a case nobody has met, and the `site` + `truncated` fields still identify it.
 */
private fun sanitize(value: String): String =
    buildString(value.length) {
        for (character in value) {
            val permitted =
                character.code in PRINTABLE_ASCII_FIRST..PRINTABLE_ASCII_LAST &&
                    character != '"' &&
                    character != '\\'
            append(if (permitted) character else SANITIZED_REPLACEMENT)
        }
    }

/** The daemon-side spelling, not the Kotlin enum name — see [unrecognizedFinding]. */
private fun UnrecognizedSite.wireToken(): String =
    when (this) {
        UnrecognizedSite.LineType -> "line_type"
        UnrecognizedSite.AssistantBlock -> "assistant_block"
        UnrecognizedSite.UserBlock -> "user_block"
        UnrecognizedSite.Undecodable -> "undecodable"
        UnrecognizedSite.CodexMethod -> "codex_method"
        UnrecognizedSite.CodexItem -> "codex_item"
    }

/**
 * Pass-through decorator recording the [ThreadItem.UnrecognizedMessage] rows on a thread the app is
 * **already** subscribed to. It observes an emission the app was going to receive anyway: it issues no
 * request, opens no subscription, and never filters, reorders, or alters what it sees.
 *
 * That inertness is the whole design. `RemoteConversationRepository.observeMessages` sends a full-history
 * `backfill_since` on **every** subscription, so a guard that opened its own per-conversation collectors
 * would fire one full-history request per conversation on the operator's real `$HOME` — repeated on every
 * conversation-list change, during a timing-sensitive real-claude turn. A sentinel that adds flakiness of
 * its own is worse than no sentinel.
 *
 * **Coverage boundary, documented rather than engineered around:** a row is seen only for a conversation
 * the app is currently subscribed to. In practice that is the conversation whose live claude turn could
 * produce the frame, and the frame routes strictly by `conversation_id`, so it cannot arrive on another
 * thread. Widening this would cost exactly the backfill storm rejected above.
 *
 * [conversationId] is deliberately **not** passed to [UnrecognizedRowRecorder]: keeping it out means no
 * conversation id can reach the failure output by any later edit.
 *
 * Kotlin interface delegation carries the rest of [ConversationRepository], so the contract can grow
 * without touching this class.
 */
internal class TappingConversationRepository(
    private val delegate: ConversationRepository,
) : ConversationRepository by delegate,
    ThreadSnapshotSource {
    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = observeThreadSnapshot(conversationId).map { it.rows }

    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> =
        delegate.threadSnapshots(conversationId).onEach { snapshot ->
            UnrecognizedRowRecorder.record(snapshot.rows)
            DurableHistoryProbe.record(conversationId, snapshot.rows)
        }

    override suspend fun requestHistory(
        conversationId: String,
        cursor: String,
        limit: Int,
    ): HistoryPage {
        DurableHistoryProbe.asked(conversationId, cursor.isEmpty())
        return delegate.requestHistory(conversationId, cursor, limit).also { DurableHistoryProbe.received(conversationId) }
    }
}

/** Rows enumerated in a finding; a flood must not produce megabytes of instrumentation output. */
private const val MAX_LISTED_ROWS = 5

/** Characters of a sanitized `message_type` printed per row. */
private const val MAX_MESSAGE_TYPE_CHARS = 64

private const val PRINTABLE_ASCII_FIRST = 0x20

private const val PRINTABLE_ASCII_LAST = 0x7E

private const val SANITIZED_REPLACEMENT = '?'

private const val REMEDY =
    "This is not a client defect: the daemon's stream-json parser met a claude message kind it could not map, " +
        "so its measured ignore-list needs re-taking (docs/e2e-interactive-stream.md § Follow-ups to ticket)."
