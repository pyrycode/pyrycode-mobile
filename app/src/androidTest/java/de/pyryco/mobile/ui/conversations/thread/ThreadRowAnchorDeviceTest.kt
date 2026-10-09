package de.pyryco.mobile.ui.conversations.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Exposes the shared real-screen anchor regression to the routine Android UI gate. */
@RunWith(AndroidJUnit4::class)
class ThreadRowAnchorDeviceTest : ThreadRowAnchorTest() {
    @Test override fun loneToolGrowth_preservesBottomAnchorAndOffset() = super.loneToolGrowth_preservesBottomAnchorAndOffset()
}
