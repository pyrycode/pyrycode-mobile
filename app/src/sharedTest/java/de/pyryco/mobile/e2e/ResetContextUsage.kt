package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ContextUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Subscribe before reset and require its clear before accepting a reply, even with equal token totals.
 * The concrete remote repository exposes a direct, unbuffered StateFlow projection. Collect inline:
 * a dispatched resumption can miss its transient null when the inbound collector applies both frames
 * before that resumption runs. Keep this observer nonsuspending between emissions and free of UI work.
 * The caller owns the timeout and cancellation.
 */
internal suspend fun Flow<ContextUsage?>.awaitResetContextUsage(): ContextUsage =
    withContext(Dispatchers.Unconfined) {
        dropWhile { it != null }.filterNotNull().first()
    }
