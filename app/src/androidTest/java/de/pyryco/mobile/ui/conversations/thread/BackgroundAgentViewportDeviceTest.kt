package de.pyryco.mobile.ui.conversations.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Required Android-visible rendered-frame probes; the shared class also runs on JVM. */
@RunWith(AndroidJUnit4::class)
class BackgroundAgentViewportDeviceTest : BackgroundAgentViewportTest() {
    @Test override fun completionAcrossFinishedBlock_preservesTallStationaryChild() =
        super.completionAcrossFinishedBlock_preservesTallStationaryChild()

    @Test override fun splitCompletionAcrossFinishedBlock_preservesTallStationaryChild() =
        super.splitCompletionAcrossFinishedBlock_preservesTallStationaryChild()

    @Test override fun fullViewportCompletion_withInvalidatedExpandedTool_retainsBoundary() =
        super.fullViewportCompletion_withInvalidatedExpandedTool_retainsBoundary()

    @Test override fun fullViewportCompletion_withRestoredExpandedTool_retainsBoundary() =
        super.fullViewportCompletion_withRestoredExpandedTool_retainsBoundary()

    @Test override fun newestReceiptAcrossRunningBlock_followerKeepsNewestEveryFrame() =
        super.newestReceiptAcrossRunningBlock_followerKeepsNewestEveryFrame()

    @Test override fun newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_collapsed() =
        super.newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_collapsed()

    @Test override fun newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_uncollapsed() =
        super.newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_uncollapsed()

    @Test override fun fullViewportCompletion_withColdMeasurements_retainsOlderBoundary() =
        super.fullViewportCompletion_withColdMeasurements_retainsOlderBoundary()

    @Test override fun followerCompletion_keepsNewestEveryRenderedFrame() = super.followerCompletion_keepsNewestEveryRenderedFrame()

    @Test override fun followerCompletion_withAnotherRunningBlock_keepsNewestEveryFrame() =
        super.followerCompletion_withAnotherRunningBlock_keepsNewestEveryFrame()

    @Test override fun followerMultipleCompletions_keepNewestEveryFrame() = super.followerMultipleCompletions_keepNewestEveryFrame()

    @Test override fun delayedReceipt_followerKeepsNewestEveryFrame() = super.delayedReceipt_followerKeepsNewestEveryFrame()

    @Test override fun delayedReceiptAtNewest_followerKeepsNewestEveryFrame() = super.delayedReceiptAtNewest_followerKeepsNewestEveryFrame()

    @Test override fun delayedReceipt_readerKeepsStationaryRows_collapsed() = super.delayedReceipt_readerKeepsStationaryRows_collapsed()

    @Test override fun delayedReceipt_readerKeepsStationaryRows_uncollapsed() = super.delayedReceipt_readerKeepsStationaryRows_uncollapsed()

    @Test override fun delayedReceiptAtNewest_readerKeepsStationaryRows() = super.delayedReceiptAtNewest_readerKeepsStationaryRows()

    @Test override fun visibleCompletion_preservesStationaryRows_collapsed() = super.visibleCompletion_preservesStationaryRows_collapsed()

    @Test override fun visibleCompletion_preservesStationaryRows_uncollapsed() =
        super
            .visibleCompletion_preservesStationaryRows_uncollapsed()

    @Test override fun visibleCompletion_withStationaryGrowth_preservesTopEveryFrame() =
        super.visibleCompletion_withStationaryGrowth_preservesTopEveryFrame()

    @Test override fun offscreenCompletion_preservesStationaryRows() = super.offscreenCompletion_preservesStationaryRows()

    @Test override fun fullViewportCompletion_fillsVacancyAndClamps() = super.fullViewportCompletion_fillsVacancyAndClamps()

    @Test override fun fullViewportCompletion_retainsOlderBoundaryAgainstRemainingBlock() =
        super.fullViewportCompletion_retainsOlderBoundaryAgainstRemainingBlock()

    @Test override fun multipleCompletions_keepStationaryReader() = super.multipleCompletions_keepStationaryReader()
}
