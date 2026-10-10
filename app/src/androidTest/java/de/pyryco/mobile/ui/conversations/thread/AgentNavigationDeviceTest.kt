package de.pyryco.mobile.ui.conversations.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly select the shared production-screen cancellation probes in the routine UI gate. */
@RunWith(AndroidJUnit4::class)
class AgentNavigationDeviceTest : AgentNavigationScreenTest() {
    @Test override fun rootReturnsWhileWaiting_navigatesOnce() = super.rootReturnsWhileWaiting_navigatesOnce()

    @Test override fun gestureWhileWaiting_cancelsNavigation() = super.gestureWhileWaiting_cancelsNavigation()

    @Test override fun accessibilityScrollWhileWaiting_cancelsNavigation() = super.accessibilityScrollWhileWaiting_cancelsNavigation()

    @Test override fun departureAndReturn_doesNotReviveRequest() = super.departureAndReturn_doesNotReviveRequest()

    @Test override fun readerInterruptsInProgressNavigation_doesNotRetry() = super.readerInterruptsInProgressNavigation_doesNotRetry()

    @Test override fun remount_doesNotReviveRequest() = super.remount_doesNotReviveRequest()

    @Test override fun conversationSwitch_doesNotReviveRequest() = super.conversationSwitch_doesNotReviveRequest()

    @Test override fun freshTap_replacesUnresolvedRequest() = super.freshTap_replacesUnresolvedRequest()

    @Test override fun freshTapAfterReaderCancellation_navigates() = super.freshTapAfterReaderCancellation_navigates()
}
