package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Metadata-only ancestor/descendant lookups never return a saved payload.
internal const val AUTHORITATIVE_INDEX_FIELDS =
    "host,conversation,epoch,id,ordinal,orderKey,'' AS payload,display,extra,parentId," +
        "agent,background,active,endedOrder,displayKey,launchKey,rendererOrder,anchorId,tieId,anchorContribution,tieContribution"

internal fun ThreadItemDto.savedBackgroundAgent(): Boolean {
    val fields = content as? JsonObject ?: return false
    val task = fields["task_id"] as? JsonPrimitive
    return kind == "agent" && task?.isString == true && task.content.isNotBlank() && fields["task_link"] is JsonObject && (order ?: 0) > 0
}

/** Renderer-affecting structure, including content-dependent background classification. */
internal fun ThreadItemDto.sameSavedStructure(other: ThreadItemDto): Boolean =
    parent == other.parent &&
        order == other.order &&
        shown == other.shown &&
        kind == other.kind &&
        active == other.active &&
        endedOrder == other.endedOrder &&
        savedBackgroundAgent() == other.savedBackgroundAgent()

/** A child's contribution to its parent's foreground anchor and held-child order. */
internal fun AuthoritativeItem.savedContributions(children: Pair<String, String>): Pair<String, String> {
    fun key(order: Long) =
        order.toString().padStart(19, '0') +
            (ordinal.toLong() - Int.MIN_VALUE).toString().padStart(10, '0') + ":$id"
    val own = if (display) key(orderKey) else ""
    val anchor = listOf(own, children.first.takeUnless { background }.orEmpty()).filter { it.isNotEmpty() }.minOrNull().orEmpty()
    val tie =
        if (agent) {
            key(rendererOrder)
        } else {
            listOf(if (display) key(rendererOrder) else "", children.second).filter { it.isNotEmpty() }.minOrNull().orEmpty()
        }
    return anchor to tie
}

internal fun savedContributionIndex(facts: Collection<AuthoritativeItem>): Map<Long, Pair<String, String>> {
    val children = facts.groupBy { it.parentId }
    val result = mutableMapOf<Long, Pair<String, String>>()
    for (item in savedContributionPostorder(facts)) {
        // Unfinished ancestors have no summary, preserving the empty contribution of a cycle edge.
        val keys = children[item.id].orEmpty().mapNotNull { result[it.id] }
        val anchor =
            keys
                .map { it.first }
                .filter { it.isNotEmpty() }
                .minOrNull()
                .orEmpty()
        val tie =
            keys
                .map { it.second }
                .filter { it.isNotEmpty() }
                .minOrNull()
                .orEmpty()
        result[item.id] = item.savedContributions(anchor to tie)
    }
    return result
}

/** Held-order DFS without a JVM call frame per parent; back edges never schedule a node twice. */
internal fun savedContributionPostorder(facts: Collection<AuthoritativeItem>): List<AuthoritativeItem> {
    val children = facts.groupBy { it.parentId }
    val seen = mutableSetOf<Long>()
    val result = mutableListOf<AuthoritativeItem>()
    val pending = ArrayDeque<Pair<AuthoritativeItem, Boolean>>()
    for (root in facts) {
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            val (item, expanded) = pending.removeLast()
            if (expanded) {
                result += item
            } else if (seen.add(item.id)) {
                pending.addLast(item to true)
                children[item.id].orEmpty().asReversed().forEach { pending.addLast(it to false) }
            }
        }
    }
    return result
}

/** Shared forest metadata and fixed-size keys: no descendant retains a copy of its ancestry. */
internal class SavedPlacementIndex(
    facts: Collection<AuthoritativeItem>,
) {
    val contributions = savedContributionIndex(facts)
    val anchors: Map<Long, Triple<Long, AuthoritativeItem, AuthoritativeItem>?>
    val keys: Map<Long, Pair<String, String>>
    var ancestryVisits = 0
        private set
    var rankVisits = 0
        private set

    init {
        val indexed = facts.associateBy { it.id }
        val owners = mutableMapOf<Long, Long?>()
        val cyclic = mutableSetOf<Long>()
        // Resolve every parent edge once, including tails leading into a cycle.
        for (item in facts) {
            if (owners.containsKey(item.id)) continue
            val path = mutableListOf<AuthoritativeItem>()
            val seen = mutableSetOf<Long>()
            var next: AuthoritativeItem? = item
            while (next != null && !owners.containsKey(next.id) && seen.add(next.id)) {
                ancestryVisits++
                path += next
                next = indexed[next.parentId]
            }
            val cycle = next != null && (next.id in seen || next.id in cyclic)
            for (record in path.asReversed()) {
                val parent = indexed[record.parentId]
                owners[record.id] =
                    if (cycle) {
                        null
                    } else if (parent?.agent == true) {
                        parent.id
                    } else {
                        owners[parent?.id]
                    }
                if (cycle) cyclic += record.id
            }
        }
        val children = facts.groupBy { it.parentId }
        anchors =
            facts.filter { it.agent }.associate { root ->
                val summaries = children[root.id].orEmpty().mapNotNull { contributions[it.id] }
                val anchor = summaries.map { it.first }.filter { it.isNotEmpty() }.minOrNull()
                val tie = summaries.map { it.second }.filter { it.isNotEmpty() }.minOrNull()
                root.id to
                    if (root.id in cyclic || anchor == null || tie == null) {
                        null
                    } else {
                        Triple(
                            anchor.take(19).toLong(),
                            indexed.getValue(tie.substringAfterLast(':').toLong()),
                            indexed.getValue(anchor.substringAfterLast(':').toLong()),
                        )
                    }
            }
        // Preorder is exactly the old lexicographic agent-path order, with non-agent links collapsed.
        val agentChildren =
            facts
                .sortedWith(
                    compareBy<AuthoritativeItem> { it.rendererOrder }.thenBy { it.ordinal },
                ).groupBy { owners[it.id] }
        val pending = ArrayDeque<AuthoritativeItem>()
        agentChildren[null].orEmpty().asReversed().forEach(pending::addLast)
        val blocks = mutableMapOf<Long, AuthoritativeItem>()
        val result = linkedMapOf<Long, Pair<String, String>>()
        while (pending.isNotEmpty()) {
            val item = pending.removeLast()
            val parentBlock = owners[item.id]?.let { blocks[it] }
            val block = if (item.background) item else parentBlock ?: item
            blocks[item.id] = block
            val rank = (rankVisits++).toString().padStart(10, '0')

            fun key(
                root: AuthoritativeItem,
                standalone: Boolean = false,
            ): String {
                val anchor = if (root.orderKey == Long.MAX_VALUE && !standalone) anchors[root.id] else null
                val order =
                    when {
                        standalone -> item.orderKey
                        root.background && !root.active -> root.endedOrder.takeIf { it > 0 } ?: root.orderKey
                        root.orderKey == Long.MAX_VALUE -> anchor?.first ?: item.orderKey
                        else -> root.orderKey
                    }
                val phase =
                    when {
                        standalone -> "2"
                        root.orderKey == Long.MAX_VALUE -> "1"
                        else -> "0"
                    }
                val tie = anchor?.second ?: root
                return (if (root.background && root.active && !standalone) "1" else "0") +
                    order.toString().padStart(19, '0') + phase + tie.rendererOrder.toString().padStart(19, '0') +
                    (tie.ordinal.toLong() - Int.MIN_VALUE).toString().padStart(10, '0') + ":" + rank
            }
            // Hidden unordered agents retain a non-row family prefix for bounded witness lookup.
            val content =
                when {
                    item.display -> key(block)
                    item.agent && item.orderKey == Long.MAX_VALUE -> "!" + key(block)
                    else -> ""
                }
            val launch =
                if (item.background && item.display) {
                    if (parentBlock != null && !parentBlock.background) key(parentBlock) else key(item, standalone = true)
                } else {
                    ""
                }
            result[item.id] = content to launch
            agentChildren[item.id].orEmpty().asReversed().forEach(pending::addLast)
        }
        keys = result
    }
}
