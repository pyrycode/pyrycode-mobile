package de.pyryco.mobile.ui.conversations.thread

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Required device probes exercise the platform destination lifecycle and obscuring dialog window. */
@RunWith(AndroidJUnit4::class)
class ThreadReadViewportDeviceTest : ThreadReadViewportTest() {
    @Test override fun foregroundCheckpoint_requiresResumedDestinationAndDoesNotRepeat() =
        super.foregroundCheckpoint_requiresResumedDestinationAndDoesNotRepeat()

    @Test override fun foregroundCheckpoint_excludesScrolledAwayRowUpdates() = super.foregroundCheckpoint_excludesScrolledAwayRowUpdates()

    @Test override fun foregroundCheckpoint_excludesReplyArrivingBehindRenameDialog() =
        super.foregroundCheckpoint_excludesReplyArrivingBehindRenameDialog()
}
