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
    /** Runtime full-source captures are never restored from disk or borrowed from fetched bodies. */
    val fullSources: Map<Long, FullSource> = emptyMap(),
    /** Retained full bodies cannot be revived by evicting their fetched replacement. */
    val staleFullBodies: Map<Long, Long> = emptyMap(),
) {
    internal data class FullSource(
        val revision: Long,
        val version: Long,
    )

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
    val firstKnownAncestors = mutableSetOf<Long>()
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
        // Restore may retain old full facts without revising the live observation or query capture.
        if (restoring && previous != null && item.rev <= previous.revision) continue
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
        if (previous == null && item.summaryPayload != null) firstKnownAncestors += item.id
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
            fullSources =
                if (restoring) {
                    detailValidity.fullSources
                } else {
                    detailValidity.fullSources +
                        incoming.filter { it.id in completeBodies }.associate { item ->
                            val previous = detailValidity.fullSources[item.id]?.takeIf { it.revision == item.rev }
                            item.id to ExpansionDetailValidity.FullSource(item.rev, maxOf(version, previous?.version ?: 0))
                        }
                },
            serials = detailValidity.serials + affected.associateWith { detailValidity.serial(it) + 1 },
            floors = detailValidity.floors + affected.associateWith { maxOf(version, detailValidity.floors[it] ?: 0) },
            bodies = (detailValidity.bodies + affectedBodies) - completeBodies,
            children = detailValidity.children + affected,
            staleFullBodies =
                if (restoring) {
                    detailValidity.staleFullBodies
                } else {
                    (
                        detailValidity.staleFullBodies +
                            items.filter { it.id in affectedBodies && it.hasToolBody }.associate { it.id to it.rev }
                    ) -
                        completeBodies
                },
        )
    if (affected.isNotEmpty()) RelayLog.i { "event=thread_detail code=invalidated" }
    val observed =
        copy(
            detailValidity = validity,
            bodyLoads = bodyLoads.filterKeys { it.first !in affected },
            childLoads = childLoads.filterKeys { it.first !in affected },
        )
    return if (firstKnownAncestors.isEmpty()) observed else observed.restoreDetailValidity(items, firstKnownAncestors)
}

/** Saved facts cannot certify incompatible or newly known ancestry, or revoke current fetched work. */
internal fun AuthoritativeThreadSnapshot.restoreDetailValidity(
    incoming: List<ThreadItemDto>,
    firstKnownAncestors: Set<Long> = emptySet(),
): AuthoritativeThreadSnapshot {
    if (incoming.isEmpty() || !compactSelected && firstKnownAncestors.isEmpty()) return this
    val full = items.associateBy { it.id }
    val descendants = expansionItems.groupBy { it.parent }
    val pending =
        ArrayDeque(
            firstKnownAncestors.ifEmpty {
                expansionItems
                    .filter { summary ->
                        summary.summaryPayload != null &&
                            full[summary.id]?.let { fact ->
                                fact.rev != summary.rev ||
                                    fact.subtreeFreshness == null ||
                                    fact.subtreeFreshness != summary.subtreeFreshness
                            } != false
                    }.map { it.id }
                    .toSet()
            },
        )
    val stale = mutableSetOf<Long>()
    while (pending.isNotEmpty()) {
        val id = pending.removeFirst()
        if (!stale.add(id)) continue
        descendants[id].orEmpty().forEach { pending.addLast(it.id) }
    }
    // Every first-known ancestor in one observation belongs to that same query capture.
    val ancestorCapture = firstKnownAncestors.mapNotNull { detailValidity.observations[it]?.version }.maxOrNull()
    val restoredStale =
        incoming.filter { item ->
            item.hasToolBody &&
                item.id in stale &&
                (
                    ancestorCapture == null ||
                        detailValidity.fullSources[item.id]?.let { it.revision == item.rev && it.version > ancestorCapture } != true
                )
        }
    // These captures were admitted against the current source before the disk read completed.
    val fetchedChildren = (childPages.keys + childLoads.keys).mapTo(mutableSetOf()) { it.first }
    val fetched = (bodyContents.keys + bodyLoads.keys).mapTo(fetchedChildren.toMutableSet()) { it.first }
    return copy(
        detailValidity =
            detailValidity.copy(
                staleFullBodies =
                    (
                        if (firstKnownAncestors.isNotEmpty()) {
                            detailValidity.staleFullBodies
                        } else {
                            detailValidity.staleFullBodies -
                                incoming
                                    .map {
                                        it.id
                                    }.toSet()
                        }
                    ) +
                        restoredStale.associate { it.id to it.rev },
                children = detailValidity.children + restoredStale.filter { it.id !in fetchedChildren }.map { it.id },
                floors =
                    detailValidity.floors +
                        restoredStale.filter { it.id !in fetched }.associate {
                            it.id to
                                maxOf(completedVersion, detailValidity.floors[it.id] ?: 0)
                        },
            ),
    )
}
