package de.pyryco.mobile.ui.conversations.share

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.notifications.MAX_TAP_ID_CHARS
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.notifications.notificationTitle
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

internal const val SHARE_CATEGORY = "de.pyryco.mobile.category.CONVERSATION_SHARE"
private const val ID_PREFIX = "share-"

internal data class RecentShareTarget(
    val target: HostConversationTarget,
    val label: String,
) {
    override fun toString() = "RecentShareTarget"
}

/** Pure bounded fold. Only an explicit open inserts a target; snapshots can relabel or remove it. */
internal class RecentShareTargets(
    private val limit: Int,
    encoded: String = "",
    private val appName: String = "Pyrycode",
) {
    var entries: List<RecentShareTarget> = decode(encoded)
        private set

    fun id(target: HostConversationTarget): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (field in listOf(target.serverId, target.conversationId)) {
            hash.update("${field.length}:".toByteArray())
            // Hash code units verbatim: malformed surrogate input cannot alias another pair via UTF-8 replacement.
            field.forEach {
                hash.update((it.code shr 8).toByte())
                hash.update(it.code.toByte())
            }
        }
        return ID_PREFIX + hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun resolve(id: String): HostConversationTarget? = entries.filter { id(it.target) == id }.singleOrNull()?.target

    fun open(
        target: HostConversationTarget,
        name: String?,
    ): Boolean {
        if (!valid(target)) return false
        entries = (listOf(RecentShareTarget(target, clean(name))) + entries.filterNot { it.target == target }).take(limit)
        return true
    }

    fun reconcile(
        saved: Set<String>,
        snapshots: List<HostConversationSnapshot>,
    ) {
        entries =
            entries.mapNotNull { entry ->
                if (entry.target.serverId !in saved) return@mapNotNull null
                val host = snapshots.singleOrNull { it.serverId == entry.target.serverId } ?: return@mapNotNull entry
                val row = (host.channels + host.chats).singleOrNull { it.id == entry.target.conversationId && !it.archived }
                when {
                    row != null -> entry.copy(label = clean(row.name))
                    host.rowsLoaded -> null
                    else -> entry
                }
            }
    }

    fun encode(): String =
        JsonArray(
            entries.map {
                JsonObject(
                    mapOf(
                        "h" to JsonPrimitive(it.target.serverId),
                        "c" to JsonPrimitive(it.target.conversationId),
                        "l" to JsonPrimitive(it.label),
                    ),
                )
            },
        ).toString()

    private fun decode(encoded: String): List<RecentShareTarget> =
        try {
            if (encoded.length > 16_384) {
                emptyList()
            } else {
                Json
                    .parseToJsonElement(encoded)
                    .jsonArray
                    .mapNotNull {
                        val row = it.jsonObject
                        val target =
                            HostConversationTarget(
                                row.getValue("h").jsonPrimitive.content,
                                row.getValue("c").jsonPrimitive.content,
                            )
                        if (valid(target)) RecentShareTarget(target, clean(row.getValue("l").jsonPrimitive.content)) else null
                    }.distinctBy { it.target }
                    .take(limit)
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        } catch (_: NoSuchElementException) {
            emptyList()
        }

    private fun clean(name: String?) = notificationTitle(name) ?: appName

    private fun valid(target: HostConversationTarget) =
        listOf(target.serverId, target.conversationId).all {
            it.isNotBlank() &&
                it.length <= MAX_TAP_ID_CHARS
        }
}

/** App-owned serialized publisher. Android shortcut state is a projection of the private recent ledger. */
internal class SharingShortcuts(
    private val context: Context,
    private val snapshots: StateFlow<List<HostConversationSnapshot>>,
    savedHosts: Flow<Set<String>>,
    file: File = File(context.noBackupFilesDir, "sharing_shortcuts"),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val storage = AtomicFile(file)
    private var ledger: RecentShareTargets? = null
    private var saved: Set<String>? = null
    private val ready = CompletableDeferred<Unit>()
    private var projected = false

    init {
        scope.launch {
            combine(savedHosts, snapshots, ::Pair).collect { (hosts, rows) ->
                mutex.withLock {
                    val current = load()
                    val before = current.entries
                    saved = hosts
                    current.reconcile(hosts, rows)
                    if (!projected || current.entries != before) publish(current, before)
                    projected = true
                    ready.complete(Unit)
                }
            }
        }
    }

    suspend fun resolve(id: String): HostConversationTarget? {
        ready.await()
        return mutex.withLock { ledger?.resolve(id) }
    }

    suspend fun opened(target: HostConversationTarget) =
        withContext(dispatcher) {
            ready.await()
            mutex.withLock {
                val row =
                    snapshots.value
                        .singleOrNull { it.serverId == target.serverId }
                        ?.let { (it.channels + it.chats).singleOrNull { row -> row.id == target.conversationId && !row.archived } }
                        ?: return@withLock
                if (saved?.contains(target.serverId) != true) return@withLock
                val current = load()
                val before = current.entries
                if (!current.open(target, row.name)) {
                    RelayLog.d { "event=sharing_shortcut_rejected code=invalid_target" }
                    return@withLock
                }
                publish(current, before, target)
                RelayLog.d { "event=sharing_shortcut_opened count=${current.entries.size}" }
            }
        }

    private fun load(): RecentShareTargets {
        ledger?.let { return it }
        val encoded =
            try {
                storage.openRead().use { it.readNBytes(16_385).toString(Charsets.UTF_8) }
            } catch (
                _: java.io.IOException,
            ) {
                ""
            }
        return RecentShareTargets(
            minOf(4, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)),
            encoded,
            context.getString(R.string.app_name),
        ).also { ledger = it }
    }

    private fun publish(
        current: RecentShareTargets,
        before: List<RecentShareTarget>,
        opened: HostConversationTarget? = null,
    ) {
        var stream: java.io.FileOutputStream? = null
        try {
            stream = storage.startWrite()
            stream.write(current.encode().toByteArray())
            storage.finishWrite(stream)
        } catch (_: java.io.IOException) {
            storage.failWrite(stream)
            RelayLog.d { "event=sharing_shortcut_storage_failed" }
        }
        try {
            val keep = current.entries.map { current.id(it.target) }.toSet()
            val known =
                ShortcutManagerCompat
                    .getShortcuts(
                        context,
                        ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
                    ).map { it.id }
                    .filter { it.startsWith(ID_PREFIX) }
            val removed = (known + before.map { current.id(it.target) }).distinct().filterNot { it in keep }
            if (removed.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(context, removed)
                ShortcutManagerCompat.removeDynamicShortcuts(context, removed)
                RelayLog.d { "event=sharing_shortcuts_removed count=${removed.size}" }
            }
            val shortcuts = current.entries.mapIndexed { rank, entry -> shortcut(current.id(entry.target), entry, rank) }
            if (opened != null) {
                shortcuts.singleOrNull { it.id == current.id(opened) }?.let {
                    if (!ShortcutManagerCompat.pushDynamicShortcut(
                            context,
                            it,
                        )
                    ) {
                        RelayLog.d { "event=sharing_shortcuts_failed code=rate_limited" }
                    }
                }
            }
            val published = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.toSet()
            val missing = shortcuts.filterNot { it.id in published }
            val retained = shortcuts.filter { it.id in published }
            if (missing.isNotEmpty() &&
                !ShortcutManagerCompat.addDynamicShortcuts(context, missing)
            ) {
                RelayLog.d { "event=sharing_shortcuts_failed code=rate_limited" }
            }
            if (retained.isNotEmpty() &&
                !ShortcutManagerCompat.updateShortcuts(context, retained)
            ) {
                RelayLog.d { "event=sharing_shortcuts_failed code=rate_limited" }
            }
        } catch (
            _: IllegalArgumentException,
        ) {
            RelayLog.d { "event=sharing_shortcuts_failed code=invalid" }
        } catch (
            _: IllegalStateException,
        ) {
            RelayLog.d { "event=sharing_shortcuts_failed code=unavailable" }
        }
    }

    private fun shortcut(
        id: String,
        entry: RecentShareTarget,
        rank: Int,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(context, id)
            .setShortLabel(entry.label)
            .setLongLabel(entry.label)
            .setRank(rank)
            .setCategories(setOf(SHARE_CATEGORY))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(NotificationTap.ACTION_OPEN_CONVERSATION)
                    .putExtra(NotificationTap.EXTRA_SERVER_ID, entry.target.serverId)
                    .putExtra(NotificationTap.EXTRA_CONVERSATION_ID, entry.target.conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ).build()

    fun dispose() {
        scope.cancel()
    }
}
