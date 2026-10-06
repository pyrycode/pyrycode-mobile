package de.pyryco.mobile.ui.conversations.share

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
    @After fun reset() = Dispatchers.resetMain()

    @Test fun freshAndNewIntentsRetainPendingShareAcrossRecreationButNeverReplayConsumption() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller = Robolectric.buildActivity(MainActivity::class.java, textShare("first")).setup()
            try {
                val activity = controller.get()
                val intake = ViewModelProvider(activity)[ShareIntakeViewModel::class.java]
                advanceUntilIdle()
                assertEquals("first", intake.state.value?.text)
                controller.recreate()
                assertSame(intake, ViewModelProvider(controller.get())[ShareIntakeViewModel::class.java])
                assertEquals("first", intake.state.value?.text)
                controller.newIntent(textShare("second"))
                advanceUntilIdle()
                assertEquals("second", intake.state.value?.text)
                controller.newIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "malformed"))
                assertEquals("second", intake.state.value?.text)
                assertTrue(intake.select(HostConversationTarget("host", "x")))
                controller.recreate()
                advanceUntilIdle()
                assertNull(intake.state.value)
                assertEquals("second", GlobalContext.get().get<ComposerDraftStore>().draftFor("host", "x"))
            } finally {
                controller.pause().stop().destroy()
            }
        }

    @Test fun systemBackCancelsWithoutTouchingDraftsAndLauncherDoesNotStartAShare() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN)).setup()
            try {
                val intake = ViewModelProvider(controller.get())[ShareIntakeViewModel::class.java]
                assertNull(intake.state.value)
                controller.newIntent(textShare("cancel me"))
                advanceUntilIdle()
                // Let composition install the picker BackHandler after startup's IO read completes.
                repeat(50) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    advanceUntilIdle()
                }
                controller.get().onBackPressedDispatcher.onBackPressed()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(controller.get().isFinishing)
                assertNull(intake.state.value)
                assertTrue(
                    GlobalContext
                        .get()
                        .get<ComposerDraftStore>()
                        .drafts.value
                        .isEmpty(),
                )
            } finally {
                controller.pause().stop().destroy()
            }
        }

    private fun textShare(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
}
