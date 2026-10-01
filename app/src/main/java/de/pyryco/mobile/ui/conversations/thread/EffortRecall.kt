package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The phone's one remembered effort level (#686), read and written by [EffortRecall]. The value is a
 * published level string, daemon-authored: it is compared and sent back, never rendered or logged.
 */
interface RememberedEffortStore {
    suspend fun read(): String?

    suspend fun remember(level: String)

    /** Remembers nothing and recalls nothing: the demo, fake and test default. */
    object None : RememberedEffortStore {
        override suspend fun read(): String? = null

        override suspend fun remember(level: String) = Unit
    }
}

/** The production store: [AppPreferences.rememberedEffort], app-wide across chats, channels and hosts. */
fun AppPreferences.asRememberedEffortStore(): RememberedEffortStore =
    object : RememberedEffortStore {
        override suspend fun read(): String? = rememberedEffort.first()

        override suspend fun remember(level: String) {
            setRememberedEffort(level)
        }
    }

/**
 * The once-per-opening recall of the remembered effort (#686, desktop #1549 / #1554), one per
 * [ThreadViewModel]. It waits for usable settings, an addressable session and a model menu offering the
 * remembered level. A saved effort or an effort tap ends the recall; otherwise it hands [start] one
 * write through the thread's normal effort write path. A started write is never retried: only a new
 * opening, meaning a new instance, tries again.
 *
 * Main-thread only, like the rest of the view model: [offer] runs in the `state` collector and every
 * other entry point on `viewModelScope`.
 */
internal class EffortRecall(
    scope: CoroutineScope,
    private val store: RememberedEffortStore,
    private val start: (sessionId: String, level: String) -> Job,
) {
    private var decided = false
    private var loaded = false
    private var remembered: String? = null
    private var config: ThreadRunConfig? = null
    private var liveSessionId = ""
    private var write: Job? = null

    init {
        scope.launch {
            remembered =
                try {
                    store.read()
                } catch (e: IOException) {
                    RelayLog.w { "event=effort_recall outcome=read_failed" }
                    null
                }
            loaded = true
            decide()
        }
    }

    /** The latest run configuration and the conversation's live session id. */
    fun offer(
        config: ThreadRunConfig,
        liveSessionId: String,
    ) {
        this.config = config
        this.liveSessionId = liveSessionId
        decide()
    }

    /** A user effort tap: an undecided recall is dropped, so the tap is the only write. */
    fun cancel() {
        if (decided) return
        decided = true
        if (remembered != null) RelayLog.d { "event=effort_recall outcome=cancelled_by_tap" }
    }

    /** Suspends until the recall write has settled, whether it succeeded or failed; returns at once when
     *  none was started. Never waits for a reading or the menu. */
    suspend fun awaitWrite() {
        write?.join()
    }

    /** Called after every acknowledged effort write, tap or recall, with the level that was sent. */
    suspend fun remember(level: String) {
        if (level.length > MAX_REMEMBERED_EFFORT_CHARS) {
            RelayLog.d { "event=effort_remember outcome=too_long" }
            return
        }
        store.remember(level)
    }

    private fun decide() {
        if (decided || !loaded) return
        val config = config ?: return
        if (!config.settingsAvailable || !config.menuAvailable || config.pending) return
        // #1320: a reading held across a reconnect may name a session or effort the daemon has since
        // changed, and in the disconnected gap a write cannot be sent at all. Wait for the live reply.
        if (config.settingsHeld) return
        // A reading for a session the conversation has already replaced waits for the live one, the
        // same rule `forLiveSession` applies to what is shown. An empty live id is the summary's
        // placeholder and proves nothing.
        if (config.writable && liveSessionId.isNotEmpty() && liveSessionId != config.sessionId) return
        decided = remembered == null || config.savedEffort.isNotEmpty()
        // Nothing remembered is the fresh-install case and every inert (demo, test) opening: silent.
        val level = remembered ?: return
        val skipped =
            when {
                config.savedEffort.isNotEmpty() -> "saved_choice"
                !config.writable -> "no_session"
                config.effortChoices.none { it.value == level } -> "unpublished"
                else -> null
            }
        if (skipped != null) {
            RelayLog.d { "event=effort_recall outcome=$skipped" }
            return
        }
        decided = true
        RelayLog.d { "event=effort_recall outcome=started" }
        write = start(config.sessionId, level)
    }
}

/** The longest level worth remembering: the footer's own label bound, far above any real level name. */
private const val MAX_REMEMBERED_EFFORT_CHARS = 128
