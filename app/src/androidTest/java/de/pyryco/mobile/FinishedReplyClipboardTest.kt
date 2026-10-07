package de.pyryco.mobile

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.e2e.SELECTION_REPLY
import de.pyryco.mobile.e2e.assertSelectedWordOnClipboard
import de.pyryco.mobile.e2e.selectionClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android clipboard attribution and delayed UI dispatch cannot be proved by its JVM shadow. */
@RunWith(AndroidJUnit4::class)
class FinishedReplyClipboardTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun delayedClipboardReplacement_waitsPastUiIdlenessForTheSelectedWord() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            // Hold a real-service replacement beyond the initial idle baseline observation.
            composeTestRule.runOnIdle { assertEquals(BASELINE, clipboardText(clipboard)) }
            scope.launch {
                delay(500)
                clipboard.setPrimaryClip(ClipData.newPlainText("delayed result", "cobalt"))
            }
            composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 5_000)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unchangedBaseline_timesOutWithoutRetryingCopy() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        assertThrows(ComposeTimeoutException::class.java) {
            composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
        }
        composeTestRule.runOnIdle { assertEquals(BASELINE, clipboardText(clipboard)) }
    }

    @Test
    fun wholeReplyOnClipboard_cannotSatisfyPartialCopy() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        composeTestRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("incorrect whole reply", SELECTION_REPLY))
        }
        assertThrows(ComposeTimeoutException::class.java) {
            composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
        }
    }

    @Test
    fun androidAttributedTargetManager_activityClipboardStillSeedsAndReadsBaseline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val activity = composeTestRule.activity
        // Use Android's real manager/service, with the recorded invalid package attribution.
        // Its legacy Context/Handler constructor is accessible on API 33; no private cache edits.
        val wronglyAttributed =
            object : ContextWrapper(context) {
                override fun getOpPackageName(): String = "android"
            }
        val targetClipboard =
            ClipboardManager::class.java
                .getDeclaredConstructor(Context::class.java, Handler::class.java)
                .newInstance(wronglyAttributed, Handler(Looper.getMainLooper()))
        composeTestRule.runOnIdle {
            val failure =
                assertThrows(SecurityException::class.java) {
                    targetClipboard.setPrimaryClip(ClipData.newPlainText("ownership control", BASELINE))
                }
            assertTrue(failure.message.orEmpty().contains("Package android does not belong"))
        }
        val faultyTarget =
            object : ContextWrapper(context) {
                override fun getSystemService(name: String): Any? =
                    if (name == Context.CLIPBOARD_SERVICE) targetClipboard else super.getSystemService(name)
            }
        val faultyInstrumentation =
            object : Instrumentation() {
                override fun getTargetContext(): Context = faultyTarget
            }
        // Register only while obtaining the manager; restore before any test-rule/UI operations.
        val activityClipboard =
            try {
                InstrumentationRegistry.registerInstance(faultyInstrumentation, arguments)
                activity.selectionClipboard()
            } finally {
                InstrumentationRegistry.registerInstance(instrumentation, arguments)
            }
        assertEquals("de.pyryco.mobile", activity.opPackageName)
        seedBaseline(activityClipboard)
    }

    private fun seedBaseline(clipboard: ClipboardManager) {
        composeTestRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("selection baseline", BASELINE))
            assertEquals(BASELINE, clipboardText(clipboard))
        }
    }

    private fun clipboardText(clipboard: ClipboardManager): String? =
        clipboard.primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()

    private companion object {
        const val BASELINE = "unrelated clipboard baseline"
    }
}
