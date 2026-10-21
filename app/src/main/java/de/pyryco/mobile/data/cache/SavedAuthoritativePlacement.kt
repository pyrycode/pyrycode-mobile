package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.cache.ConversationCacheDatabase.AuthoritativeItem
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Metadata-only ancestor/descendant lookups never return a saved payload.
internal const val AUTHORITATIVE_INDEX_FIELDS =
    "host,conversation,epoch,id,ordinal,orderKey,'' AS payload,display,extra,parentId," +
        "agent,background,active,endedOrder,displayKey,launchKey,rendererOrder,anchorId,tieId"

internal fun ThreadItemDto.savedBackgroundAgent(): Boolean {
    val fields = content as? JsonObject ?: return false
    val task = fields["task_id"] as? JsonPrimitive
    return kind == "agent" && task?.isString == true && task.content.isNotBlank() && fields["task_link"] is JsonObject && (order ?: 0) > 0
}

/** The renderer sorts placements, then walks each agent block depth first. Launches are separate rows. */
internal fun AuthoritativeItem.savedPlacementKeys(
    lookup: (Long) -> AuthoritativeItem?,
    unorderedAnchor: (Long) -> Triple<Long, AuthoritativeItem, AuthoritativeItem>?,
): Pair<String, String> {
    val path = mutableListOf(this)
    val seen = mutableSetOf(id)
    var parent = parentId
    var cyclic = false
    while (parent > 0) {
        if (!seen.add(parent)) {
            cyclic = true
            break
        }
        val fact = lookup(parent) ?: break
        path += fact
        parent = fact.parentId
    }
    val agents = if (cyclic) emptyList() else path.drop(1).filter { it.agent }
    val block =
        (listOf(this).filter { it.agent } + agents).let { chain ->
            chain.firstOrNull { it.background } ?: chain.lastOrNull() ?: this
        }

    fun number(value: Long) = value.toString().padStart(19, '0')

    fun segment(item: AuthoritativeItem) = number(item.rendererOrder) + (item.ordinal.toLong() - Int.MIN_VALUE).toString().padStart(10, '0')

    fun key(
        root: AuthoritativeItem,
        descendants: List<AuthoritativeItem>,
        standalone: Boolean = false,
    ): String {
        val activeBlock = root.background && root.active && !standalone
        val anchor = if (root.orderKey == Long.MAX_VALUE && !standalone) unorderedAnchor(root.id) else null
        val order =
            when {
                standalone -> orderKey
                root.background && !root.active -> root.endedOrder.takeIf { it > 0 } ?: root.orderKey
                root.orderKey == Long.MAX_VALUE -> anchor?.first ?: orderKey
                else -> root.orderKey
            }
        val phase =
            when {
                standalone -> "2"
                root.orderKey == Long.MAX_VALUE -> "1"
                else -> "0"
            }
        return (if (activeBlock) "1" else "0") + number(order) + phase +
            segment(anchor?.second ?: root) + ":" + descendants.joinToString(":", transform = ::segment)
    }
    val descendants = (listOf(this) + agents.takeWhile { it.id != block.id }).asReversed().filter { it.id != block.id }
    // Hidden unordered agents retain a non-row metadata key for bounded supplemental lookup.
    val content =
        when {
            display -> key(block, descendants)
            agent && orderKey == Long.MAX_VALUE -> "!" + key(block, descendants)
            else -> ""
        }
    val launch =
        if (background && display) {
            // A foreground family embeds a nested launch; moving background families leave it at launch order.
            val foregroundRoot = agents.lastOrNull().takeIf { agents.none { it.background } }
            if (foregroundRoot == null) {
                key(this, emptyList(), standalone = true)
            } else {
                key(foregroundRoot, (listOf(this) + agents.takeWhile { it.id != foregroundRoot.id }).asReversed())
            }
        } else {
            ""
        }
    return content to launch
}

internal fun savedUnorderedAnchor(
    root: Long,
    facts: Collection<AuthoritativeItem>,
): Triple<Long, AuthoritativeItem, AuthoritativeItem>? {
    val children = facts.groupBy { it.parentId }
    val seen = mutableSetOf<Long>()
    val pending = ArrayDeque<Pair<Long, Boolean>>().also { it.add(root to true) }
    var anchor: Long? = null
    var first: AuthoritativeItem? = null
    var anchorItem: AuthoritativeItem? = null
    while (pending.isNotEmpty()) {
        val (id, direct) = pending.removeFirst()
        if (!seen.add(id)) continue
        for (child in children[id].orEmpty()) {
            if (child.display && child.orderKey < (anchor ?: Long.MAX_VALUE)) {
                anchor = child.orderKey
                anchorItem = child
            }
            if (direct && (child.agent || child.display)) {
                val held = first
                if (held == null ||
                    child.rendererOrder < held.rendererOrder ||
                    child.rendererOrder == held.rendererOrder &&
                    child.ordinal < held.ordinal
                ) {
                    first = child
                }
            }
            if (!child.background) pending.add(child.id to (direct && !child.agent))
        }
    }
    return anchor?.let { Triple(it, requireNotNull(first), requireNotNull(anchorItem)) }
}
