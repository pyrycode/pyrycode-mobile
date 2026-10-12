package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.BackgroundTaskProgress

/** Supplied session-local readings; item facts and read evidence remain independent. */
internal data class AuthoritativeLiveProgress(
    val sessionId: String? = null,
    val tools: Map<String, Int> = emptyMap(),
    val tasks: Map<String, BackgroundTaskProgress> = emptyMap(),
) {
    override fun toString(): String = "AuthoritativeLiveProgress(content=***)"
}
