package de.pyryco.mobile.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal const val ATTENTION_CHANNEL_ID = "attention"

/** A longer id is not one a saved host or its daemon issued; the tap is refused rather than routed. */
internal const val MAX_TAP_ID_CHARS = 256

/** Enough recent alerts to recognise any replay a reconnect or a wake window can deliver. */
internal const val MAX_LEDGER_ENTRIES = 512

/** Metadata belongs to the posted completion, so replacement and process restart cannot lose its identity. */
private const val EXTRA_COMPLETION_ENTRY_ID = "de.pyryco.mobile.notification.COMPLETION_ENTRY_ID"

/**
 * Posts one Android notification per new [AttentionAlert] (#685) while the app is in the background.
 *
 * Each alert is deduplicated **before** the gates: an alert already in the ledger is dropped, and a new
 * one is recorded even when the foreground, the switch, a mute or a missing permission then suppresses it.
 * So an alert spent in the foreground, while alerts were off or while its conversation was muted, never
 * posts later. The ledger persists, which
 * is what holds "at most once" across a reconnect's replay, a new wake window and process death.
 *
 * The notification's text is fixed app copy naming the conversation's agent (#1116); its title is the
 * conversation's cleaned name, or the app name (#1330). The alert's ids are identities: they pick the
 * notification's tag and the tap's target, and are never shown, logged or written in the clear.
 */
class AttentionNotifier(
    private val context: Context,
    alerts: Flow<AttentionAlert>,
    private val notificationsEnabled: Flow<Boolean>,
    /** Whether the host [AttentionAlert.serverId] reports its conversation muted (#1022); unknown is not muted. */
    private val isMuted: (serverId: String, conversationId: String) -> Boolean,
    /** The agent host [AttentionAlert.serverId] lists for its conversation (#1116); null when it lists none. */
    private val agentOf: (serverId: String, conversationId: String) -> ConversationAgent?,
    /** The name host [AttentionAlert.serverId] lists for its conversation (#1330); untrusted, never logged. */
    private val nameOf: (serverId: String, conversationId: String) -> String?,
    private val isForeground: () -> Boolean,
    ledgerFile: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Live, host-qualified daemon facts: only confirmed coverage cancels or suppresses a completion. */
    private val readMarks: StateFlow<Map<String, Map<String, ConversationReadMarks>>> = MutableStateFlow(emptyMap()),
    /** The current repository can be ahead of the source flow when a completion arrives. */
    private val readMarksOf: (
        String,
        String,
    ) -> ConversationReadMarks? = { server, conversation -> readMarks.value[server]?.get(conversation) },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val ledger = AlertLedger(ledgerFile)
    private val notificationLock = Mutex()

    init {
        scope.launch { alerts.collect { handle(it) } }
        scope.launch {
            var previous = emptyMap<String, Map<String, ConversationReadMarks>>()
            readMarks.collect {
                notificationLock.withLock {
                    // A queued emission may have been superseded while a post held the lock.
                    val current = readMarks.value
                    val manager = NotificationManagerCompat.from(context)
                    val posted = manager.activeNotifications
                    current.forEach { (serverId, conversations) ->
                        conversations.forEach { (conversationId, marks) ->
                            if (marks != previous[serverId]?.get(conversationId)) {
                                val tag = digest(serverId, conversationId)
                                val completionEntryId =
                                    posted
                                        .firstOrNull { it.tag == tag && it.id == 0 }
                                        ?.notification
                                        ?.extras
                                        ?.getString(EXTRA_COMPLETION_ENTRY_ID)
                                        ?.toULongOrNull()
                                if (marks.coversCompletion(completionEntryId) &&
                                    readMarksOf(serverId, conversationId)?.coversCompletion(completionEntryId) == true
                                ) {
                                    manager.cancel(tag, 0)
                                    RelayLog.d { "event=attention_alert_cancelled reason=daemon_read" }
                                }
                            }
                        }
                    }
                    previous = current
                }
            }
        }
    }

    private suspend fun handle(alert: AttentionAlert) {
        val kind = if (alert.kind == AttentionAlert.Kind.TurnCompleted) "turn" else "prompt"
        val outcome =
            when {
                !ledger.add(digest(alert.serverId, alert.conversationId, alert.kind.name, alert.key)) -> "duplicate"
                isForeground() -> "foreground"
                !notificationsEnabled.first() -> "disabled"
                isMuted(alert.serverId, alert.conversationId) -> "muted"
                !permitted() -> "no_permission"
                else ->
                    notificationLock.withLock {
                        if (alert.kind == AttentionAlert.Kind.TurnCompleted &&
                            readMarksOf(alert.serverId, alert.conversationId)?.coversCompletion(alert.historyEntryId) == true
                        ) {
                            "read"
                        } else {
                            post(alert)
                        }
                    }
            }
        RelayLog.d { "event=attention_alert outcome=$outcome kind=$kind" }
    }

    private fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(alert: AttentionAlert): String {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                ATTENTION_CHANNEL_ID,
                context.getString(R.string.notification_channel_attention),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_attention_description) },
        )
        // One notification per conversation per host: the same id on two hosts gets two tags.
        val tag = digest(alert.serverId, alert.conversationId)
        val text = context.getString(copyFor(alert.kind, agentOf(alert.serverId, alert.conversationId)))
        val title = notificationTitle(nameOf(alert.serverId, alert.conversationId)) ?: context.getString(R.string.app_name)
        val notification =
            NotificationCompat
                .Builder(context, ATTENTION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(NotificationTap.pendingIntent(context, tag, alert.serverId, alert.conversationId))
                .addExtras(
                    Bundle().apply {
                        if (alert.kind == AttentionAlert.Kind.TurnCompleted) {
                            alert.historyEntryId?.let { putString(EXTRA_COMPLETION_ENTRY_ID, it.toString()) }
                        }
                    },
                ).build()
        return try {
            manager.notify(tag, 0, notification)
            "posted"
        } catch (e: SecurityException) {
            // The permission can be revoked between the check and the post.
            "no_permission"
        }
    }

    fun dispose() {
        scope.cancel()
    }
}

/** Absence of either live fact proves nothing; checkpoint zero and unsigned equality are valid. */
private fun ConversationReadMarks.coversLatest(): Boolean {
    val confirmed = readUpTo ?: return false
    val latest = latestEntryId ?: return false
    return confirmed >= latest
}

/** A read replay stays read even when the conversation already holds newer unread activity. */
private fun ConversationReadMarks.coversCompletion(historyEntryId: ULong?): Boolean =
    if (historyEntryId != null) readUpTo?.let { it >= historyEntryId } == true else coversLatest()

/**
 * The notification tap's contract with `MainActivity` (#685). `MainActivity` is exported, so any app can
 * start it with these extras: [target] is the only parse, and the activity still accepts the target only
 * for a saved host. The tap only navigates — it never sends a command or answers a prompt.
 */
object NotificationTap {
    const val ACTION_OPEN_CONVERSATION = "de.pyryco.mobile.action.OPEN_CONVERSATION"
    const val EXTRA_SERVER_ID = "de.pyryco.mobile.extra.SERVER_ID"
    const val EXTRA_CONVERSATION_ID = "de.pyryco.mobile.extra.CONVERSATION_ID"

    /** The host and conversation a well-formed tap names, or null for anything else. */
    fun target(intent: Intent?): HostConversationTarget? {
        if (intent?.action != ACTION_OPEN_CONVERSATION) return null
        val extras = intent.extras ?: return null
        val serverId = extras.getString(EXTRA_SERVER_ID)?.takeIf(::acceptable) ?: return null
        val conversationId = extras.getString(EXTRA_CONVERSATION_ID)?.takeIf(::acceptable) ?: return null
        return HostConversationTarget(serverId, conversationId)
    }

    private fun acceptable(id: String) = id.isNotBlank() && id.length <= MAX_TAP_ID_CHARS

    internal fun pendingIntent(
        context: Context,
        tag: String,
        serverId: String,
        conversationId: String,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_CONVERSATION)
                // Intent equality ignores extras: the identifier keeps each conversation's tap distinct.
                .setIdentifier(tag)
                .putExtra(EXTRA_SERVER_ID, serverId)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

/**
 * Whether host [serverId]'s last known rows hold [conversationId] muted (#1022). Keyed by host first, since
 * two hosts can hold the same conversation id. A host with no list yet, or a conversation missing from it,
 * is not muted: failing open is the safe side for an alert.
 */
internal fun List<HostConversationSnapshot>.isMuted(
    serverId: String,
    conversationId: String,
): Boolean {
    val host = firstOrNull { it.serverId == serverId } ?: return false
    return (host.channels + host.chats).any { it.id == conversationId && it.muted }
}

/** The agent host [serverId]'s last known rows give [conversationId] (#1116), or null when they hold no such row. */
internal fun List<HostConversationSnapshot>.agentOf(
    serverId: String,
    conversationId: String,
): ConversationAgent? {
    val host = firstOrNull { it.serverId == serverId } ?: return null
    return (host.channels + host.chats).firstOrNull { it.id == conversationId }?.agent
}

/** The name host [serverId]'s last known rows give [conversationId] (#1330), or null when unnamed or unlisted. */
internal fun List<HostConversationSnapshot>.nameOf(
    serverId: String,
    conversationId: String,
): String? {
    val host = firstOrNull { it.serverId == serverId } ?: return null
    return (host.channels + host.chats).firstOrNull { it.id == conversationId }?.name
}

/** The most code points of a conversation name an alert title keeps (#1330). */
internal const val MAX_TITLE_CODE_POINTS = 80

/**
 * The alert title for an untrusted conversation name, as desktop's `notificationTitle` (#1330): control
 * characters (`\p{Cc}`) dropped, at most [MAX_TITLE_CODE_POINTS] kept by code point so a surrogate pair is
 * never split, then trimmed. Null when there is no name or nothing is left; the caller falls back to the app name.
 */
internal fun notificationTitle(name: String?): String? {
    if (name == null) return null
    val title = StringBuilder()
    var kept = 0
    var i = 0
    while (i < name.length && kept < MAX_TITLE_CODE_POINTS) {
        val codePoint = name.codePointAt(i)
        i += Character.charCount(codePoint)
        if (Character.isISOControl(codePoint)) continue
        title.appendCodePoint(codePoint)
        kept++
    }
    // JS trim, which desktop uses, also strips U+FEFF; Kotlin's whitespace test does not.
    return title.trim { it.isWhitespace() || it.code == 0xFEFF }.toString().ifEmpty { null }
}

/** The alert's text: Claude's reads as it always has, Codex's names Codex, an unlisted conversation's names no one. */
private fun copyFor(
    kind: AttentionAlert.Kind,
    agent: ConversationAgent?,
): Int {
    val turn = kind == AttentionAlert.Kind.TurnCompleted
    return when (agent) {
        ConversationAgent.Claude -> if (turn) R.string.notification_turn_completed else R.string.notification_prompt
        ConversationAgent.Codex -> if (turn) R.string.notification_turn_completed_codex else R.string.notification_prompt_codex
        null -> if (turn) R.string.notification_turn_completed_neutral else R.string.notification_prompt_neutral
    }
}

/** SHA-256 over length-prefixed fields, so no two field tuples share a digest by concatenation. */
private fun digest(vararg fields: String): String {
    val sha = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        sha.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        sha.update(bytes)
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The digests of alerts already handled, newest last, bounded to [MAX_LEDGER_ENTRIES]. Touched only by the
 * notifier's single collector. Unreadable means empty; a failed write keeps the in-memory set.
 */
internal class AlertLedger(
    private val file: File,
) {
    private var entries: LinkedHashSet<String>? = null

    /** Records [digest]; false when it was already recorded. */
    fun add(digest: String): Boolean {
        val seen = entries ?: load().also { entries = it }
        if (!seen.add(digest)) return false
        while (seen.size > MAX_LEDGER_ENTRIES) seen.remove(seen.first())
        save(seen)
        return true
    }

    private fun load(): LinkedHashSet<String> =
        try {
            if (file.isFile) LinkedHashSet(file.readLines().filter { it.isNotBlank() }) else LinkedHashSet()
        } catch (e: IOException) {
            RelayLog.d { "event=attention_alert_ledger outcome=read_failed" }
            LinkedHashSet()
        }

    private fun save(seen: Set<String>) {
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(seen.joinToString("\n"))
            if (!temp.renameTo(file)) throw IOException("rename failed")
        } catch (e: IOException) {
            RelayLog.d { "event=attention_alert_ledger outcome=write_failed" }
        }
    }
}
