package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Source observations are independent of retained data and load-state publications. */
internal data class ExpansionDetailValidity(
    val observations: Map<Long, Observation> = emptyMap(),
    val serials: Map<Long, Long> = emptyMap(),
    val floors: Map<Long, Long> = emptyMap(),
    val bodies: Set<Long> = emptySet(),
    val children: Set<Long> = emptySet(),
) {
    internal data class Observation(
        val revision: Long,
        val freshness: Long?,
        val version: Long,
    )

    fun serial(id: Long): Long = serials[id] ?: 0

    override fun toString() = "ExpansionDetailValidity(redacted)"
}

internal val ThreadItemDto.subtreeFreshness: Long?
    get() = (raw["subtree_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

/** Only validated source admission calls this; equal/older captures cannot re-invalidate a replacement. */
internal fun AuthoritativeThreadSnapshot.observeDetails(
    incoming: List<ThreadItemDto>,
    version: Long,
    restoring: Boolean = false,
): AuthoritativeThreadSnapshot {
    val known = expansionItems.associateBy { it.id }
    val observations = detailValidity.observations.toMutableMap()
    val changed = mutableSetOf<Long>()
    val incompleteChanges = mutableSetOf<Long>()
    val completeBodies = mutableSetOf<Long>()
    for (item in incoming) {
        if (item.id in retirements) continue
        if (item.kind !in setOf("agent", "tool_call")) {
            if (item.hasToolBody && item.rev >= (known[item.id]?.rev ?: 0) && version >= (detailValidity.floors[item.id] ?: 0)) {
                completeBodies += item.id
            }
            continue
        }
        val freshness = item.subtreeFreshness
        val previous =
            observations[item.id]?.takeIf { it.revision >= (known[item.id]?.rev ?: 0) } ?: known[item.id]?.let { held ->
                ExpansionDetailValidity.Observation(
                    held.rev,
                    held.subtreeFreshness,
                    -1,
                )
            }
        // Disk facts may arrive after their compact observation. Preserve that observation and
        // the full source fact, but equal revision alone cannot make an old subtree current.
        if (restoring && previous != null && item.rev <= previous.revision) {
            if (known[item.id]?.summaryPayload != null &&
                item.rev == previous.revision &&
                item.hasToolBody &&
                (freshness == null || freshness != previous.freshness)
            ) {
                changed += item.id
                incompleteChanges += item.id
            }
            continue
        }
        if (previous != null &&
            (
                item.rev < previous.revision ||
                    version < previous.version ||
                    version == previous.version &&
                    item.rev <= previous.revision
            )
        ) {
            continue
        }
        observations[item.id] = ExpansionDetailValidity.Observation(item.rev, freshness, version)
        if (previous != null && (previous.revision != item.rev || freshness == null || previous.freshness != freshness)) {
            changed += item.id
            if (!item.hasToolBody) incompleteChanges += item.id
        }
        if (item.hasToolBody && version >= (detailValidity.floors[item.id] ?: 0)) completeBodies += item.id
    }
    val descendants = expansionItems.groupBy { it.parent }
    val affected = mutableSetOf<Long>()
    val pending = ArrayDeque(changed)
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!affected.add(id)) continue
        descendants[id].orEmpty().forEach { pending.addLast(it.id) }
    }
    // Complete delivery revises each full fact independently. Only omitted ancestry makes a
    // retained full descendant depend on expansion freshness; fetched bodies always depend on it.
    val affectedBodies = mutableSetOf<Long>()
    pending.addAll(affected.filter { known[it]?.hasToolBody != true || it in incompleteChanges })
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!affectedBodies.add(id)) continue
        descendants[id].orEmpty().forEach { pending.addLast(it.id) }
    }
    val validity =
        detailValidity.copy(
            observations = observations,
            serials = detailValidity.serials + affected.associateWith { detailValidity.serial(it) + 1 },
            floors = detailValidity.floors + affected.associateWith { maxOf(version, detailValidity.floors[it] ?: 0) },
            bodies = (detailValidity.bodies + affectedBodies) - completeBodies,
            children = detailValidity.children + affected,
        )
    if (affected.isNotEmpty()) RelayLog.i { "event=thread_detail code=invalidated" }
    return copy(
        detailValidity = validity,
        bodyLoads = bodyLoads.filterKeys { it.first !in affected },
        childLoads = childLoads.filterKeys { it.first !in affected },
    )
}
