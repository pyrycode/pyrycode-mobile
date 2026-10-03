package de.pyryco.mobile.data.diagnostics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The trail of every message this device sends (#1564): one line per state a message reaches, keyed by its
 * `message_id`, so a message that went missing can be matched against the daemon's history and journal.
 *
 * **Kept in release builds, on purpose** (decided 2026-10-02): the Play test-track release build is the only
 * one where these bugs show up. That is why this is not a [de.pyryco.mobile.data.network.RelayLog] channel,
 * whose gate is `BuildConfig.DEBUG` by construction. Nothing here reads `BuildConfig`.
 *
 * It is safe in release because it takes no text. Every input has a fixed shape, checked here: a
 * `message_id` must be a lowercase UUID as this app mints them (anything else records nothing), a connection
 * token must be `RelayLog.redactConnId`'s 8 hex characters (else `conn=none`), and a daemon error code must
 * be a dotted lowercase code (else `code=unknown`). States and reasons are fixed labels. Never pass message
 * text, attachment names, the relay host, the pairing token, a full `conn_id` or a daemon error message —
 * the shape checks refuse them, and nothing else could carry them.
 *
 * A line reads `<instant> id=<uuid> state=<state>[ conn=<token>][ reason=<reason>][ code=<code>]`. A state a
 * message has already reached is not logged again. Each line goes to [logcat] at once, and to [file] through
 * one writer coroutine on [writerDispatcher], so no caller ever waits on the disk and the file keeps the record
 * order. The file is capped at [maxBytes]: an append that would pass the cap first rewrites the file to the
 * newest whole lines that fit in half of it. A failed write loses that line and nothing else.
 *
 * One instance for the process, bound in `AppModule`; [dispose] stops the writer.
 */
class MessageTrail(
    file: () -> File? = { null },
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val now: () -> Instant = Clock.System::now,
    private val logcat: (String) -> Unit = {},
) {
    /** Why a send failed. A user's drop of a queued message is [dropped], not a failure. */
    enum class Failure(
        val label: String,
    ) {
        NOT_CONNECTED("not_connected"),
        DAEMON_ERROR("daemon_error"),
        TORN_DOWN("torn_down"),
    }

    private enum class State(
        val label: String,
    ) {
        SENT("sent"),
        ACKNOWLEDGED("acknowledged"),
        QUEUED("queued"),
        DELIVERED("delivered"),
        FAILED("failed"),
        DROPPED("dropped"),
    }

    /** The states already logged per message, eldest message forgotten first. */
    private val reached =
        object : LinkedHashMap<String, MutableSet<State>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<State>>?): Boolean =
                size > REMEMBERED_MESSAGES
        }

    private val scope = CoroutineScope(SupervisorJob() + writerDispatcher)

    private val lines = Channel<String>(WRITE_BUFFER, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            val target = resolve(file)
            for (line in lines) target?.let { append(it, line) }
        }
    }

    /** The `send_message` frame was handed to the open connection identified by [connToken]. */
    fun sent(
        messageId: String,
        connToken: String?,
    ) = record(messageId, State.SENT, " conn=${connToken?.takeIf { CONN_TOKEN.matches(it) } ?: "none"}")

    /** The daemon's `ack` to the send arrived. */
    fun acknowledged(messageId: String) = record(messageId, State.ACKNOWLEDGED, "")

    /** A `queue_state` snapshot carried the message. */
    fun queued(messageId: String) = record(messageId, State.QUEUED, "")

    /** The message left the daemon's queue, or came back as a delivered `message`. */
    fun delivered(messageId: String) = record(messageId, State.DELIVERED, "")

    /** The send failed for [failure]; [errorCode] is the daemon `error` code, never its message. */
    fun failed(
        messageId: String,
        failure: Failure,
        errorCode: String? = null,
    ) {
        val code = if (failure == Failure.DAEMON_ERROR) " code=${errorCode?.takeIf { ERROR_CODE.matches(it) } ?: "unknown"}" else ""
        record(messageId, State.FAILED, " reason=${failure.label}$code")
    }

    /** The user dropped the queued message and the daemon confirmed it. */
    fun dropped(messageId: String) = record(messageId, State.DROPPED, " reason=user_dropped")

    /** Stops the writer. Lines recorded afterwards still reach logcat. */
    fun dispose() {
        scope.cancel()
    }

    @Synchronized
    private fun record(
        messageId: String,
        state: State,
        fields: String,
    ) {
        if (!MESSAGE_ID.matches(messageId)) return
        if (!reached.getOrPut(messageId) { mutableSetOf() }.add(state)) return
        val line = "${now()} id=$messageId state=${state.label}$fields"
        logcat(line)
        lines.trySend(line)
    }

    /** An uncaught throw on the writer would crash the process, so storage trouble means no file. */
    private fun resolve(file: () -> File?): File? =
        try {
            file()
        } catch (_: Exception) {
            null
        }

    private fun append(
        target: File,
        line: String,
    ) {
        val bytes = "$line\n".toByteArray(Charsets.UTF_8)
        try {
            if (target.length() + bytes.size > maxBytes) trim(target, maxBytes / 2 - bytes.size)
            FileOutputStream(target, true).use { it.write(bytes) }
        } catch (_: Exception) {
            // This line is lost; the send it describes is untouched, and the next line tries again.
        }
    }

    /** Rewrites [target] to its newest whole lines totalling at most [budget] bytes, through a renamed temp file. */
    private fun trim(
        target: File,
        budget: Long,
    ) {
        var size = 0L
        val kept =
            target
                .readLines(Charsets.UTF_8)
                .asReversed()
                .takeWhile { line ->
                    size += line.toByteArray(Charsets.UTF_8).size + 1
                    size <= budget
                }.asReversed()
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(kept.joinToString("") { "$it\n" }, Charsets.UTF_8)
        if (!temp.renameTo(target)) throw IOException("message trail trim not renamed")
    }

    companion object {
        /** Pulled with `adb pull /sdcard/Android/data/de.pyryco.mobile/files/message-trail.log`. */
        const val FILE_NAME = "message-trail.log"
        const val LOG_TAG = "PyryMessageTrail"
        const val DEFAULT_MAX_BYTES = 512L * 1024
        private const val REMEMBERED_MESSAGES = 256
        private const val WRITE_BUFFER = 1024
        private val MESSAGE_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val CONN_TOKEN = Regex("^[0-9a-f]{8}$")
        private val ERROR_CODE = Regex("^[a-z0-9_.]{1,64}$")
    }
}
