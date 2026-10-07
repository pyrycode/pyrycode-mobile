package de.pyryco.mobile.data.network

import kotlin.time.TimeMark

/** Content-free diagnostic of the current wait, anchored before any observer is scheduled. */
internal class RelayBackoff(
    val attempt: Int,
    val startedAt: TimeMark,
)
