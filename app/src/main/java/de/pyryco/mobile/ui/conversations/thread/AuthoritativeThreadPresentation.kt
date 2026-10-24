package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.network.attachmentDisplayName
import de.pyryco.mobile.data.network.isAttachmentIdShape
import de.pyryco.mobile.data.repository.AuthoritativeLiveProgress
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.CanonicalRefusalIdentity
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.data.repository.authoritativeQueueTarget
import de.pyryco.mobile.ui.conversations.components.toolSubjectField
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Supplied-input presentation seam for #1988. No legacy roster or live-session attribution enters it. */
class AuthoritativeThreadPresentation internal constructor(
    val scope: String,
    val host: String,
    val conversation: String,
    val rows: List<ThreadRow>,
    val items: List<ThreadItem>,
    val depths: Map<String, Int>,
    val tasks: BackgroundTaskRoster,
    internal val backgroundBlocks: Map<String, AuthoritativeAgentPlacement>,
    internal val foregroundTool: ToolCall?,
    internal val certifiedCheckpoint: Long?,
    internal val shownRowRevisions: Map<String, Long>,
    internal val sourceSnapshot: AuthoritativeThreadSnapshot,
    private val parents: Map<String, String?>,
    private val expandable: Set<String>,
    private val resolveContents: ((String) -> ThreadRow.Delivered?)?,
    internal val sourceConnection: Any? = null,
) {
    internal val defersContents = resolveContents != null
    private val summaryKeys =
        sourceSnapshot.expansionItems
            .filter { "headline" in it.raw && !sourceSnapshot.hasToolBody(it) }
            .mapTo(HashSet()) { "msg:$scope:${it.id}" }
    private val bodyItems =
        sourceSnapshot.expansionItems
            .filter { it.kind in setOf("tool_call", "agent") }
            .groupBy { it.id }
            .values
            .associate { versions -> versions.maxBy { it.rev }.let { "msg:$scope:${it.id}" to it } }
    private val invalidatedKeys =
        sourceSnapshot.expansionItems
            .filter {
                it.id in sourceSnapshot.detailValidity.bodies ||
                    sourceSnapshot.detailValidity.staleFullBodies[it.id] == it.rev &&
                    !sourceSnapshot.hasToolBody(it)
            }.mapTo(HashSet()) { "msg:$scope:${it.id}" }

    internal fun bodyItem(key: String): ThreadItemDto? = bodyItems[key]

    internal fun missingBody(key: String): Boolean = bodyItem(key)?.let { !sourceSnapshot.hasToolBody(it) } == true

    internal fun childParent(key: String): ThreadItemDto? =
        bodyItem(key)?.takeIf {
            it.kind == "agent" && it.raw["detail_omitted"] == JsonPrimitive(true)
        }

    /** Place each control after the last currently displayed descendant, without adding timeline facts. */
    internal fun childControls(
        displayed: List<ThreadRow>,
        expanded: Set<String>,
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for ((key, item) in bodyItems) {
            val id = key.removePrefix("msg:")
            if (item.kind != "agent" || childParent(key) == null || id !in expanded || displayed.none { it.listKey(0) == key }) continue
            val last =
                displayed.lastOrNull { row ->
                    var parent = row.listKey(0).removePrefix("msg:")
                    val visited = mutableSetOf<String>()
                    while (visited.add(parent)) {
                        if (parent == id) return@lastOrNull true
                        parent = parents[parent] ?: break
                    }
                    false
                } ?: continue
            result.getOrPut(last.listKey(0)) { mutableListOf() }.add(key)
        }
        return result
    }

    /** Fold summaries first. An omitted ancestor cannot expose a separately placed descendant. */
    internal fun demandRows(
        folded: List<ThreadRow>,
        expandedTools: Set<String>,
    ): List<ThreadRow> {
        val resolve = resolveContents ?: return folded
        val displayed = folded.filterIsInstance<ThreadRow.Delivered>().mapTo(HashSet()) { it.listKey(0).removePrefix("msg:") }
        val available = rows.filterIsInstance<ThreadRow.Delivered>().mapTo(HashSet()) { it.listKey(0).removePrefix("msg:") }

        val visibility = mutableMapOf<String, Boolean>()

        fun visible(id: String): Boolean {
            val path = mutableSetOf<String>()
            var parent = parents[id]
            var result = true
            while (parent != null && path.add(parent)) {
                val known = visibility[parent]
                if (known != null) {
                    result = known
                    break
                }
                if (parent in expandable && parent in available && parent !in displayed) {
                    result = false
                    break
                }
                if (childParent("msg:$parent") != null && parent !in expandedTools) {
                    result = false
                    break
                }
                parent = parents[parent]
            }
            path.forEach { visibility[it] = result }
            return result
        }
        return folded
            .filter { row ->
                when (row) {
                    is ThreadRow.Delivered -> visible(row.listKey(0).removePrefix("msg:"))
                    is ThreadRow.ToolRun -> visible(row.runId)
                    is ThreadRow.AgentStartMarker -> visible(row.agentId)
                    is ThreadRow.Queued -> true
                }
            }.map { row ->
                if (row is ThreadRow.Delivered &&
                    (
                        !row.isToolRow() ||
                            (row.item as ThreadItem.MessageItem).message.id in expandedTools &&
                            bodyItem(row.listKey(0))?.let { it.hasToolBody || sourceSnapshot.retainedBody(it) != null } == true
                    )
                ) {
                    resolve(row.listKey(0)) ?: row
                } else {
                    row
                }
            }
    }

    internal fun suppliesReadSight(
        row: ThreadRow.Delivered,
        expandedTools: Set<String>,
    ): Boolean =
        row.listKey(0) !in summaryKeys &&
            row.listKey(0) !in invalidatedKeys &&
            !missingBody(row.listKey(0)) &&
            (resolveContents == null || !row.isToolRow() || (row.item as ThreadItem.MessageItem).message.id in expandedTools)

    /** Summaries are never proof of rendered contents. Resolve only the carried, exact current revision. */
    internal fun matchesReadContents(
        key: String?,
        item: ThreadItem,
        revision: Long?,
    ): Boolean {
        if (key == null || revision == null || shownRowRevisions[key] != revision) return false
        if (key in summaryKeys) return false
        if (key in invalidatedKeys) return false
        if (missingBody(key)) return false
        return if (resolveContents != null) {
            resolveContents.invoke(key)?.item == item
        } else {
            rows.any { it is ThreadRow.Delivered && it.listKey(0) == key && it.item == item }
        }
    }

    /** Latest certified estimate from this session's root terminals. Outcomes do not change cost ownership. */
    internal fun reportedSessionCost(session: String): Double? {
        val snapshot = sourceSnapshot
        if (session.isEmpty() || snapshot.epoch.isNullOrEmpty() || snapshot.repairNeeded || snapshot.resetNeeded) return null
        return snapshot.items
            .asSequence()
            .filter { item ->
                item.kind == "turn_end" &&
                    !item.active &&
                    item.status in setOf("done", "finished", "failed") &&
                    item.session == session &&
                    !item.turn.isNullOrEmpty() &&
                    (item.parent ?: 0) == 0L &&
                    item.order != null &&
                    item.order > 0 &&
                    item.rev <= snapshot.checkpoint
            }.sortedByDescending { it.order }
            .mapNotNull { item ->
                ((item.content as? JsonObject)?.get("cost_usd_total") as? JsonPrimitive)
                    ?.takeUnless { it.isString }
                    ?.doubleOrNull
                    ?.takeIf { it.isFinite() && it > 0 }
            }.firstOrNull()
    }

    /** Waiting feedback carries no ordered read revision; anchor sight to admitted history. */
    internal val newestReadKey = rows.lastOrNull { it is ThreadRow.Delivered && it.listKey(0) in shownRowRevisions }?.listKey(0)

    /** Revisions impose conservative sight requirements; only the certified boundary supplies coverage. */
    internal fun readCheckpoint(
        newestKey: String?,
        marks: ConversationReadMarks?,
        seen: Map<String, Long>,
    ): ULong? {
        if (marks?.threadSupported != true) return null
        val shown = marks.lastShownVersion ?: return null
        val confirmed = marks.readUpTo ?: return null
        val coverage = certifiedCheckpoint?.toULong() ?: return null
        if (shown == 0uL || shown <= confirmed || coverage < shown || newestKey == null) return null
        val newestRevision = shownRowRevisions[newestKey] ?: return null
        if (newestReadKey != newestKey || seen[newestKey] != newestRevision) return null
        if (shownRowRevisions.values.any { it.toULong() > coverage }) return null
        // The summary can be newer than every supplied shown row: complete sight is then unproven.
        if (shownRowRevisions.values.none { it.toULong() >= shown }) return null
        val required = shownRowRevisions.filterValues { it.toULong() > confirmed }
        if (required.any { (key, revision) -> seen[key] != revision }) return null
        return minOf(coverage, shown)
    }

    override fun toString(): String = "AuthoritativeThreadPresentation(content=***)"
}

/** Structural movement is independent of whether the root has a visible content row. */
internal data class AuthoritativeAgentPlacement(
    val active: Boolean,
    val settledOrder: Long,
    val rowKeys: Set<String>,
)

/** Projects complete held facts without modifying their revisions, retention or reconnect checkpoint. */
internal fun projectAuthoritativeThread(
    host: String,
    conversation: String,
    snapshot: AuthoritativeThreadSnapshot,
): AuthoritativeThreadPresentation = projectAuthoritativeThread(host, conversation, snapshot, AuthoritativeLiveProgress())

internal fun projectAuthoritativeThread(
    host: String,
    conversation: String,
    snapshot: AuthoritativeThreadSnapshot,
    progress: AuthoritativeLiveProgress,
): AuthoritativeThreadPresentation = projectThread(host, conversation, snapshot, progress, false, null)

/** Production keeps placement/headlines available without adapting hidden bodies. */
internal fun projectDeferredAuthoritativeThread(
    host: String,
    conversation: String,
    snapshot: AuthoritativeThreadSnapshot,
    progress: AuthoritativeLiveProgress = AuthoritativeLiveProgress(),
    onFullConversion: ((Long) -> Unit)? = null,
    sourceConnection: Any? = null,
): AuthoritativeThreadPresentation = projectThread(host, conversation, snapshot, progress, true, onFullConversion, sourceConnection)

private fun projectThread(
    host: String,
    conversation: String,
    snapshot: AuthoritativeThreadSnapshot,
    progress: AuthoritativeLiveProgress,
    deferred: Boolean,
    onFullConversion: ((Long) -> Unit)?,
    sourceConnection: Any? = null,
): AuthoritativeThreadPresentation {
    val scope = listOf(host, conversation, snapshot.epoch.orEmpty(), snapshot.generation.toString()).joinToString("") { "${it.length}:$it" }

    fun identity(id: Long) = "$scope:$id"
    val held = snapshot.expansionItems.groupBy { it.id }.mapValues { (_, versions) -> versions.maxBy { it.rev } }
    val paged =
        snapshot.childPages
            .filterKeys { held[it.first]?.rev == it.second || it.first in snapshot.detailValidity.children }
            .values
            .flatMap { it.items }
            .mapTo(HashSet()) { it.id }
    val ordered = held.values.filter { (it.order ?: 0) > 0 || it.order == 0L && it.id in paged }.sortedBy { it.order }
    val agents = held.values.filter { it.kind == "agent" }.associateBy { it.id }
    // Parent chains are facts, not raw-event nesting. Hidden/unordered ancestors remain in this map.
    val owners = mutableMapOf<Long, Long?>()
    val depths = mutableMapOf<String, Int>()
    val identities = held.keys.associateWith(::identity)
    val parents = held.values.associate { identities.getValue(it.id) to identities[it.parent] }
    val expandable =
        ordered
            .filter {
                it.shown && it.kind in setOf("agent", "tool_call") && it.toolName() != null
            }.mapTo(HashSet()) { identities.getValue(it.id) }
    // Inclusive nearest-agent/depth summaries let shared parent chains be walked once.
    val lineage = mutableMapOf<Long, Pair<Long?, Int>>()
    val cyclic = mutableSetOf<Long>()
    for (item in held.values) {
        if (item.id in lineage || item.id in cyclic) continue
        val path = mutableSetOf<Long>()
        var cursor: Long? = item.id
        while (cursor != null && cursor > 0 && cursor in held && cursor !in lineage && cursor !in cyclic && cursor !in path) {
            path += cursor
            cursor = held.getValue(cursor).parent
        }
        if (cursor in cyclic || cursor in path) {
            // Cyclic attribution never creates a self-owning block, including its incoming paths.
            cyclic += path
            path.forEach { owners[it] = null }
        } else {
            var (owner, depth) = lineage[cursor] ?: (null to 0)
            for (id in path.toList().asReversed()) {
                owners[id] = owner
                if (owner != null) depths[identities.getValue(id)] = depth
                if (held.getValue(id).kind == "agent") {
                    owner = id
                    depth++
                }
                lineage[id] = owner to depth
            }
        }
    }
    val background =
        agents.values
            .filter { it.recordedTask() != null }
            .filter { (it.order ?: 0) > 0 }
            .associateBy { it.id }
    val visible = ordered.filter { it.shown }

    fun adapt(item: ThreadItemDto): ThreadRow.Delivered {
        onFullConversion?.invoke(item.id)
        val block = if (item.id in agents) identity(item.id) else owners[item.id]?.let(::identity)
        val body = snapshot.retainedBody(item)
        val rendered =
            if (body == null) {
                item
            } else {
                ThreadItemDto.decodeReplacement(
                    JsonObject(
                        item.raw + ("content" to JsonObject((item.content as? JsonObject).orEmpty() + body)),
                    ),
                )
            }
        return rendered.displayRow(
            identity(item.id),
            block,
            item.parent?.let(::identity).orEmpty(),
            CanonicalRefusalIdentity(host, conversation, snapshot.epoch.orEmpty(), item.id),
            if (item.active && item.session == progress.sessionId) {
                (item.content as? JsonObject)?.text("tool_use_id")?.let { progress.tools[it] }
            } else {
                null
            },
            bodyRead = body != null,
        )
    }

    // Admitted root prose is always open. Share its full model with demand/read checks
    // instead of retaining an empty summary and repeatedly allocating another model.
    fun admittedRoot(item: ThreadItemDto) =
        (item.parent == null || item.parent == 0L) &&
            item.kind in setOf("user_message", "assistant_message") &&
            snapshot.openingRows?.contains(item.id to false) != false

    val adapted =
        visible.associate { item ->
            val block = if (item.id in agents) identity(item.id) else owners[item.id]?.let(::identity)
            val row =
                if (deferred && !admittedRoot(item)) {
                    item.summaryRow(identity(item.id), block, item.parent?.let(::identity).orEmpty(), progress)
                } else {
                    adapt(item)
                }
            item.id to row
        }
    val children =
        held.values
            .filter { owners[it.id] != null && (it.kind == "agent" || it.id in adapted) }
            .sortedBy { it.order ?: Long.MAX_VALUE }
            .groupBy { owners[it.id] }
    val launches =
        background.filterValues { it.shown }.mapValues { (_, item) ->
            ThreadRow.AgentStartMarker(
                identity(item.id),
                item.description(),
                !item.active,
                item.endedOrder?.takeIf { it > 0 }?.toString(),
            )
        }
    val ordersByKey =
        visible.associate { "msg:${identity(it.id)}" to requireNotNull(it.order) } +
            launches.map { (id, marker) -> marker.listKey(0) to requireNotNull(held.getValue(id).order) }

    fun block(item: ThreadItemDto): List<ThreadRow> =
        buildList {
            val pending = ArrayDeque<ThreadItemDto>()
            pending.addLast(item)
            while (pending.isNotEmpty()) {
                val next = pending.removeLast()
                // A recorded background child owns an independent lifecycle placement.
                if (next.id != item.id && next.id in background) {
                    // Chronological foreground siblings retain the child's launch position. A moving
                    // background family excludes nested markers: those stay in chronological history.
                    if (item.id !in background) launches[next.id]?.let(::add)
                    continue
                }
                adapted[next.id]?.let(::add)
                children[next.id].orEmpty().asReversed().forEach(pending::addLast)
            }
        }
    val backgroundContents = background.mapValues { (_, item) -> block(item) }
    val placements = mutableListOf<Pair<Long, List<ThreadRow>>>()
    for (item in ordered) {
        if (owners[item.id] != null && item.id !in background) continue
        if (item.id in background) {
            if (!item.active) {
                placements +=
                    (item.endedOrder?.takeIf { it > 0 } ?: requireNotNull(item.order)) to backgroundContents.getValue(item.id)
            }
        } else if (item.id in agents) {
            placements += requireNotNull(item.order) to block(item)
        } else {
            adapted[item.id]?.let { placements += requireNotNull(item.order) to listOf(it) }
        }
    }
    // A hidden or unordered supplied parent can still own shown children, without inventing its row.
    for (parent in children.keys) {
        val root = held[parent] ?: continue
        if (owners[root.id] != null) continue
        if ((root.order ?: 0) <= 0) {
            val contents = block(root)
            val anchor = contents.minOfOrNull { ordersByKey.getValue(it.listKey(0)) }
            if (anchor != null) placements += anchor to contents
        }
    }
    val embeddedLaunches = placements.flatMap { it.second }.filterIsInstance<ThreadRow.AgentStartMarker>().mapTo(HashSet()) { it.agentId }
    for ((id, marker) in launches) {
        if (marker.agentId !in embeddedLaunches) placements += requireNotNull(held.getValue(id).order) to listOf(marker)
    }
    val rows =
        placements.sortedBy { it.first }.flatMap { it.second } +
            background.values
                .filter { it.active }
                .sortedBy { it.order }
                .flatMap { backgroundContents.getValue(it.id) }
    val waiting =
        held.values
            .filter {
                it.shown &&
                    it.kind == "user_message" &&
                    (it.parent == null || it.parent == 0L) &&
                    (it.order ?: 0) <= 0 &&
                    it.status in setOf("queued", "lost")
            }.sortedBy { it.id }
            .map { item ->
                val fields = item.content as? JsonObject
                ThreadRow.Queued(
                    queuedMessageId = 0,
                    text = (fields?.text("text") ?: item.summary).displayText(),
                    echoId = null,
                    identityKey = "msg:${identity(item.id)}",
                    target = authoritativeQueueTarget(host, conversation, snapshot.epoch.orEmpty(), snapshot.generation, item),
                    status = item.status,
                    authoritative = true,
                )
            } +
            snapshot.pendingSends.map { send ->
                ThreadRow.Queued(
                    0,
                    send.message.content.displayText(),
                    null,
                    identityKey = "pending:${host.length}:$host${conversation.length}:$conversation:${send.message.id}",
                    status = "sending",
                    authoritative = true,
                )
            }
    val tasks =
        held.values
            .sortedBy { it.order ?: Long.MAX_VALUE }
            .mapNotNull { item ->
                val (taskId, type) = item.recordedTask() ?: return@mapNotNull null
                if (item.kind !in setOf("agent", "tool_call")) return@mapNotNull null
                item to
                    BackgroundTask(
                        taskId,
                        identity(item.id),
                        type,
                        item.description(),
                        null,
                        null,
                        if (item.active) null else BackgroundTaskUpdate("", item.taskStatus(), item.terminalText(), null),
                        isFinished = !item.active,
                        progress = progress.tasks[taskId].takeIf { item.active && item.session == progress.sessionId },
                    )
            }.groupBy { it.second.taskId }
            .values
            .map { entries -> entries.maxBy { it.first.rev }.second }
    val selectedKeys =
        snapshot.openingRows?.mapTo(HashSet()) { (id, launch) ->
            if (launch) "agent-start:${identity(id)}" else "msg:${identity(id)}"
        }

    val pagedKeys = paged.mapTo(HashSet()) { "msg:${identity(it)}" }

    fun selected(row: ThreadRow) = selectedKeys == null || row.listKey(0) in selectedKeys || row.listKey(0) in pagedKeys
    val selectedRows = rows.filter(::selected)
    val resolver: ((String) -> ThreadRow.Delivered?)? =
        if (deferred) {
            // Partition the index: full roots replace their DTO entries, without a second
            // whole-file cache. Neither index is shared with another presentation.
            val roots = visible.filter(::admittedRoot).associate { "msg:${identity(it.id)}" to adapted.getValue(it.id) }
            val byKey = visible.filterNot(::admittedRoot).associateBy { "msg:${identity(it.id)}" }
            val resolve: (String) -> ThreadRow.Delivered? = { key -> roots[key] ?: byKey[key]?.let(::adapt) }
            resolve
        } else {
            null
        }
    return AuthoritativeThreadPresentation(
        scope,
        host,
        conversation,
        selectedRows + waiting,
        selectedRows.filterIsInstance<ThreadRow.Delivered>().map { it.item },
        depths,
        BackgroundTaskRoster(tasks, 0),
        background.mapKeys { (id, _) -> identity(id) }.mapValues { (_, item) ->
            AuthoritativeAgentPlacement(
                item.active,
                item.endedOrder?.takeIf {
                    it > 0
                } ?: requireNotNull(item.order),
                backgroundContents.getValue(item.id).filter(::selected).mapTo(HashSet()) { it.listKey(0) },
            )
        },
        ordered.asReversed().firstNotNullOfOrNull { item ->
            // A tracked foreground shell also has a task link. Only its actual tool result proves
            // that the invocation returned while the linked task can continue independently.
            val returnedTask =
                item.recordedTask() != null &&
                    (item.kind != "tool_call" || (item.content as? JsonObject)?.obj("result") != null)
            if (!item.active || item.parent != null && item.parent > 0 || returnedTask) return@firstNotNullOfOrNull null
            ((adapted[item.id]?.item as? ThreadItem.MessageItem)?.message?.toolCall)?.takeIf { it.status == ToolCallStatus.Running }
        },
        snapshot.checkpoint.takeIf { snapshot.epoch != null && !snapshot.repairNeeded && !snapshot.resetNeeded && it >= 0 },
        visible.mapNotNull { item -> adapted[item.id]?.takeIf(::selected)?.let { it.listKey(0) to item.rev } }.toMap(),
        snapshot,
        parents,
        expandable,
        resolver,
        sourceConnection,
    )
}

/** Only fields used by the closed headline/status enter this lightweight grouping model. */
private fun ThreadItemDto.summaryRow(
    id: String,
    block: String?,
    parentId: String,
    progress: AuthoritativeLiveProgress,
): ThreadRow.Delivered {
    val fields = content as? JsonObject
    val name = toolName()?.takeIf { kind in setOf("agent", "tool_call") && status in knownStatuses }
    val input = fields?.obj("input")
    val headline =
        if (name == null) {
            null
        } else {
            toolSubjectField(name.displayText()) { key ->
                (input?.get(key) as? JsonPrimitive)
                    ?.content
                    ?.displayText()
            }
        }
    val headlineFields =
        when {
            headline != null -> mapOf(headline)
            input?.values?.any { it is JsonPrimitive } != true -> emptyMap()
            else -> mapOf("summary" to "")
        }
    val elapsed =
        if (active && session == progress.sessionId) {
            fields?.text("tool_use_id")?.let { progress.tools[it] }
        } else {
            null
        }
    val tool =
        name?.let {
            ToolCall(
                it.displayText(),
                description(),
                "",
                when {
                    active -> ToolCallStatus.Running
                    status == "denied" -> ToolCallStatus.Denied
                    status == "failed" -> ToolCallStatus.Failed
                    else -> ToolCallStatus.Done
                },
                headlineFields,
                parentId,
                elapsedSeconds = elapsed,
                resultDetail = fields?.obj("result")?.text("result_detail")?.displayText(),
            )
        }
    return ThreadRow.Delivered(
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = session.orEmpty(),
                role = if (tool == null) Role.Assistant else Role.Tool,
                content = "",
                timestamp = displayEpoch,
                isStreaming = false,
                toolCall = tool,
            ),
        ),
        block,
        identityKey = "msg:$id",
        summaryText = if (tool == null && "headline" in raw) summary.displayText() else null,
    )
}

private val displayEpoch = Instant.fromEpochMilliseconds(0)
private const val DISPLAY_TEXT_LIMIT = 65_536
private val knownStatuses =
    setOf(
        "running",
        "stopping",
        "done",
        "delivered",
        "finished",
        "failed",
        "denied",
        "stopped",
        "interrupted",
        "ended_with_session",
        "gone",
    )

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.number(key: String): Long? =
    (this[key] as? JsonPrimitive)
        ?.takeUnless {
            it.isString
        }?.longOrNull
        ?.takeIf { it >= 0 }

private fun String.displayText(): String = take(DISPLAY_TEXT_LIMIT).filter { it == '\n' || it == '\t' || !it.isISOControl() }

private fun ThreadItemDto.toolName(): String? =
    (content as? JsonObject)?.let { it.text("name") ?: it.text("tool") }
        ?: if ("headline" in raw) {
            when (kind) {
                "agent" -> "Agent"
                "tool_call" -> "Tool use"
                else -> null
            }
        } else {
            null
        }

private fun ThreadItemDto.recordedTask(): Pair<String, String>? {
    if (summaryPayload != null) {
        val id = summaryTaskId?.takeUnless { it.isEmpty() } ?: return null
        return id to if (kind == "agent") "local_agent" else ""
    }
    val fields = content as? JsonObject ?: return null
    val link = fields.obj("task_link") ?: return null
    val id = fields.text("task_id")?.takeIf { it.isNotBlank() } ?: return null
    val fact = (link["tasks"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.text("task_id") == id } ?: link
    val type = fact.text("task_type") ?: if (kind == "agent") "local_agent" else ""
    return id to type
}

private fun ThreadItemDto.description(): String {
    val fields = content as? JsonObject
    val link = fields?.obj("task_link")
    val task =
        (link?.get("tasks") as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { it.text("task_id") == fields.text("task_id") }
            ?: link
    return (
        task?.text("description") ?: fields?.obj("input")?.text("description")
            ?: fields?.text("input_summary") ?: summary
    ).displayText()
}

private fun ThreadItemDto.taskStatus() = if (status in setOf("finished", "done")) "completed" else status.displayText()

private fun ThreadItemDto.terminalText(): String {
    val fields = content as? JsonObject
    val ending = fields?.obj("ending") ?: fields?.obj("task_report")
    return listOfNotNull(ending?.text("summary"), ending?.text("cause"), summary).joinToString("\n").displayText()
}

private fun ThreadItemDto.displayRow(
    id: String,
    block: String?,
    parentId: String,
    refusalIdentity: CanonicalRefusalIdentity,
    elapsedSeconds: Int?,
    bodyRead: Boolean = false,
): ThreadRow.Delivered {
    val fields = content as? JsonObject
    val timestamp =
        fields?.let { obj ->
            listOf("sent_at", "occurred_at", "accepted_at", "client_sent_at").firstNotNullOfOrNull { key ->
                obj.text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            }
        } ?: displayEpoch
    val sessionId = session.orEmpty()
    val displayAgent =
        when (agent) {
            "claude" -> ConversationAgent.Claude
            "codex" -> ConversationAgent.Codex
            else -> null
        }

    fun message(
        role: Role,
        text: String,
        tool: ToolCall? = null,
        attachments: List<MessageAttachment> = emptyList(),
    ) = ThreadItem.MessageItem(
        Message(
            id,
            sessionId,
            role,
            text.displayText(),
            timestamp,
            isStreaming = role == Role.Assistant && active,
            toolCall = tool,
            attachments = attachments,
            parentToolUseId = parentId,
        ),
    )
    var adapted: ThreadItem? = null
    if (fields != null && status in knownStatuses) {
        adapted =
            when (kind) {
                "user_message", "assistant_message" ->
                    fields.text("text")?.let { text ->
                        val attachments =
                            (fields["attachment_ids"] as? JsonArray).orEmpty().mapNotNull {
                                (it as? JsonPrimitive)
                                    ?.takeIf { value ->
                                        value.isString && value.content.isNotBlank()
                                    }?.let { value -> MessageAttachment(value.content) }
                            }
                        message(if (kind == "user_message") Role.User else Role.Assistant, text, attachments = attachments)
                    }
                "tool_call", "agent" ->
                    toolName()?.let { name ->
                        val result = fields.obj("result")
                        val toolStatus =
                            when {
                                active -> ToolCallStatus.Running
                                status == "denied" -> ToolCallStatus.Denied
                                status == "failed" -> ToolCallStatus.Failed
                                else -> ToolCallStatus.Done
                            }
                        val input =
                            fields
                                .obj("input")
                                ?.mapNotNull { (key, value) ->
                                    (value as? JsonPrimitive)?.let { key to it.content.displayText() }
                                }?.toMap()
                                .orEmpty()
                        message(
                            Role.Tool,
                            "",
                            ToolCall(
                                name.displayText(),
                                ((if (bodyRead) fields.text("input") else null) ?: description()).displayText(),
                                (
                                    (if (bodyRead) fields.text("output") else null)
                                        ?: if (kind == "agent" &&
                                            !active
                                        ) {
                                            terminalText()
                                        } else {
                                            result?.text("result_summary").orEmpty()
                                        }
                                ).displayText(),
                                toolStatus,
                                input,
                                parentId,
                                denial =
                                    fields.obj("denial")?.let { denial ->
                                        ToolDenial(
                                            name.displayText(),
                                            denial.text("decision_reason_type").orEmpty().displayText(),
                                            denial.text("decision_reason").orEmpty().displayText(),
                                            denial.text("message").orEmpty().displayText(),
                                            null,
                                            null,
                                        )
                                    },
                                elapsedSeconds = elapsedSeconds,
                                resultDetail = result?.text("result_detail")?.displayText(),
                            ),
                        )
                    }
                "session_divider" -> {
                    val reason =
                        when (fields.text("cause") ?: fields.text("reason")) {
                            "operator_reset", "claude_clear", "agent_switch", "clear" -> BoundaryReason.Clear
                            "idle_sleep", "capacity_eviction", "idle_evict" -> BoundaryReason.IdleEvict
                            "workspace_change" -> BoundaryReason.WorkspaceChange
                            else -> null
                        }
                    reason?.let {
                        ThreadItem.SessionBoundary(
                            fields.text("previous_session_id").orEmpty(),
                            fields.text("new_session_id").orEmpty(),
                            it,
                            timestamp,
                            fields.text("workspace_cwd").takeIf { reason == BoundaryReason.WorkspaceChange },
                        )
                    }
                }
                "compaction" ->
                    fields.text("trigger")?.let {
                        ThreadItem.CompactionBoundary(fields.number("pre_tokens"), fields.number("post_tokens"), it == "manual", timestamp)
                    }
                "turn_end" ->
                    if (displayAgent ==
                        null
                    ) {
                        null
                    } else {
                        (fields.text("terminal_reason") ?: fields.text("stop_reason") ?: fields.text("cause"))
                            ?.takeUnless {
                                it in
                                    setOf("end_turn", "completed", "success")
                            }?.let {
                                ThreadItem.StoppedTurn(
                                    id,
                                    it.displayText().take(256),
                                    fields
                                        .text("error_category")
                                        .orEmpty()
                                        .displayText()
                                        .take(256),
                                    timestamp,
                                )
                            }
                    }
                "notice" ->
                    if (displayAgent == null) {
                        null
                    } else {
                        when (subtype) {
                            "attachment_offered" ->
                                fields.text("attachment_id")?.takeIf(::isAttachmentIdShape)?.let { attachmentId ->
                                    fields.text("filename")?.let { filename ->
                                        message(
                                            Role.Assistant,
                                            "",
                                            attachments = listOf(MessageAttachment(attachmentId, attachmentDisplayName(filename))),
                                        )
                                    }
                                }
                            "banner" ->
                                fields.text("text")?.takeUnless { fields.text("level") == "info" }?.let {
                                    ThreadItem.Banner(
                                        if (fields.text("level") ==
                                            "warning"
                                        ) {
                                            BannerLevel.Warning
                                        } else {
                                            BannerLevel.Notice
                                        },
                                        it.displayText(),
                                        false,
                                        timestamp,
                                    )
                                }
                            "model_refusal_fallback", "model_refusal_no_fallback" ->
                                fields.text("banner")?.let {
                                    ThreadItem.ModelRefusal(
                                        fields.text("original_model").orEmpty().displayText(),
                                        fields.text("fallback_model")?.displayText().takeIf { subtype == "model_refusal_fallback" },
                                        it.displayText(),
                                        false,
                                        timestamp,
                                        refusalIdentity,
                                    )
                                }
                            "unrecognized_message" ->
                                fields.text("raw")?.let { raw ->
                                    val site =
                                        when (fields.text("site")) {
                                            "line_type" -> UnrecognizedSite.LineType
                                            "assistant_block" -> UnrecognizedSite.AssistantBlock
                                            "user_block" -> UnrecognizedSite.UserBlock
                                            "undecodable" -> UnrecognizedSite.Undecodable
                                            "codex_method" -> UnrecognizedSite.CodexMethod
                                            "codex_item" -> UnrecognizedSite.CodexItem
                                            else -> null
                                        }
                                    site?.let {
                                        ThreadItem.UnrecognizedMessage(
                                            id,
                                            it,
                                            fields.text("message_type").orEmpty().displayText(),
                                            raw.displayText(),
                                            raw.length > DISPLAY_TEXT_LIMIT,
                                            timestamp,
                                        )
                                    }
                                }
                            else -> null
                        }
                    }
                else -> null
            }
    }
    return ThreadRow.Delivered(
        adapted ?: message(Role.Assistant, ""),
        block,
        identityKey = "msg:$id",
        summaryText = if (adapted == null) summary.displayText() else null,
        displayAgent = displayAgent,
    )
}
