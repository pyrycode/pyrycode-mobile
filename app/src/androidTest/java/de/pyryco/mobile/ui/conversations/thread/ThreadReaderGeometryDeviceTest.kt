package de.pyryco.mobile.ui.conversations.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Required Android-visible rendered-frame and touch/fling probes for the shared real-screen cases. */
@RunWith(AndroidJUnit4::class)
class ThreadReaderGeometryDeviceTest : ThreadReaderGeometryTest() {
    @Test override fun streamingReader_holdsTopAndOlderRowsEveryFrame() = super.streamingReader_holdsTopAndOlderRowsEveryFrame()

    @Test override fun settledMarkdown_holdsTopForGrowthAndShrink() = super.settledMarkdown_holdsTopForGrowthAndShrink()

    @Test override fun restingTouch_holdsReaderEveryFrame() = super.restingTouch_holdsReaderEveryFrame()

    @Test override fun movingReader_preservesConsumedMovement() = super.movingReader_preservesConsumedMovement()

    @Test override fun endSpacing_preservesReaderInBothDirections() = super.endSpacing_preservesReaderInBothDirections()
}
