package de.pyryco.mobile.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope

/** One page demand for proofs that distinguish reader movement from an idle page arrival. */
internal fun TouchInjectionScope.pullHistoryPage() {
    down(Offset(center.x, height * 0.3f))
    // A swipe has many movements and a continuing fling. Either may legitimately prefetch
    // again after a fast response. One movement followed by a resting release isolates arrival.
    moveBy(Offset(0f, height * 0.4f), delayMillis = 100)
    advanceEventTime(250)
    up()
}
