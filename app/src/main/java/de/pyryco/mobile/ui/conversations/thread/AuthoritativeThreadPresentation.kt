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
) {
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
): AuthoritativeThreadPresentation {
    val scope = listOf(host, conversation, snapshot.epoch.orEmpty(), snapshot.generation.toString()).joinToString("") { "${it.length}:$it" }

    fun identity(id: Long) = "$scope:$id"
    val held = snapshot.items.groupBy { it.id }.mapValues { (_, versions) -> versions.maxBy { it.rev } }
    val ordered = held.values.filter { (it.order ?: 0) > 0 }.sortedBy { it.order }
    val agents = held.values.filter { it.kind == "agent" }.associateBy { it.id }
    // Parent chains are facts, not raw-event nesting. Hidden/unordered ancestors remain in this map.
    val owners = mutableMapOf<Long, Long?>()
    val depths = mutableMapOf<String, Int>()
    for (item in held.values) {
        val path = mutableSetOf(item.id)
        var parent = item.parent
        var owner: Long? = null
        var depth = 0
        var cyclic = false
        while (parent != null && parent > 0) {
            if (!path.add(parent)) {
                cyclic = true
                break
            }
            val fact = held[parent] ?: break
            if (fact.kind == "agent") {
                if (owner == null) owner = parent
                depth++
            }
            parent = fact.parent
        }
        // Cyclic attribution never creates a self-owning block.
        if (cyclic) owner = null
        owners[item.id] = owner
        if (owner != null) depths[identity(item.id)] = depth
    }
    val background =
        agents.values
            .filter { it.recordedTask() != null }
            .filter { (it.order ?: 0) > 0 }
            .associateBy { it.id }
    val visible = ordered.filter { it.shown }
    val adapted =
        visible.associate { item ->
            val block = if (item.id in agents) identity(item.id) else owners[item.id]?.let(::identity)
            item.id to
                item.displayRow(
                    identity(item.id),
                    block,
                    item.parent?.let(::identity).orEmpty(),
                    de.pyryco.mobile.data.repository
                        .CanonicalRefusalIdentity(host, conversation, snapshot.epoch.orEmpty(), item.id),
                    if (item.active && item.session == progress.sessionId) {
                        (item.content as? JsonObject)?.text("tool_use_id")?.let { progress.tools[it] }
                    } else {
                        null
                    },
                )
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
            .filter { it.shown && it.kind == "user_message" && (it.order ?: 0) <= 0 && it.status in setOf("queued", "lost") }
            .sortedBy { it.id }
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

    fun selected(row: ThreadRow) = selectedKeys == null || row.listKey(0) in selectedKeys
    val selectedRows = rows.filter(::selected)
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

private fun ThreadItemDto.toolName(): String? = (content as? JsonObject)?.let { it.text("name") ?: it.text("tool") }

private fun ThreadItemDto.recordedTask(): Pair<String, String>? {
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
                                description(),
                                if (kind == "agent" && !active) terminalText() else result?.text("result_summary").orEmpty().displayText(),
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
