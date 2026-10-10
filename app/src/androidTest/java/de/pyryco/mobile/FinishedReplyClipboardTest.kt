package de.pyryco.mobile

import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.e2e.SELECTION_REPLY
import de.pyryco.mobile.e2e.assertSelectedWordOnClipboard
import de.pyryco.mobile.e2e.selectionClipboard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
            }
        assertTrue(failure.message.orEmpty().contains("clipboard=baseline"))
        assertTrue(failure.message.orEmpty().contains("items=1 textLength=${BASELINE.length}"))
        assertTrue(failure.cause is ComposeTimeoutException)
        composeTestRule.runOnIdle { assertEquals(BASELINE, clipboardText(clipboard)) }
    }

    @Test
    fun wholeReplyOnClipboard_cannotSatisfyPartialCopy() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        composeTestRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("incorrect whole reply", SELECTION_REPLY))
        }
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
            }
        assertTrue(failure.message.orEmpty().contains("clipboard=whole_reply"))
        assertTrue(failure.message.orEmpty().contains("items=1 textLength=${SELECTION_REPLY.length}"))
    }

    @Test
    fun absentClip_reportsNoReadableClipboardWithoutAcceptingIt() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        composeTestRule.runOnIdle { clipboard.clearPrimaryClip() }
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
            }
        assertTrue(failure.message.orEmpty().contains("clipboard=absent items=0 textLength=none"))
    }

    @Test
    fun nonTextClip_reportsItsKindWithoutCoercingOrAcceptingIt() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        composeTestRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newIntent("non-text control", Intent("test.selection.NON_TEXT")))
        }
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
            }
        assertTrue(failure.message.orEmpty().contains("clipboard=non_text items=1 textLength=none"))
    }

    @Test
    fun wrongWordAfterBaseline_reportsTheLastOutcomeAndFocusBeforeTeardown() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val baselineObserved = CompletableDeferred<Unit>()
        var diagnosticCalled = false
        try {
            scope.launch {
                baselineObserved.await()
                clipboard.setPrimaryClip(ClipData.newPlainText("wrong selection control", "jade"))
            }
            // Delay the helper beyond the old 500 ms write schedule without losing its baseline.
            runBlocking { delay(1_000) }
            assertTrue(!baselineObserved.isCompleted)
            composeTestRule.runOnIdle { assertEquals(BASELINE, clipboardText(clipboard)) }
            val failure =
                assertThrows(ComposeTimeoutException::class.java) {
                    composeTestRule.assertSelectedWordOnClipboard(
                        clipboard,
                        SELECTION_REPLY,
                        "cobalt",
                        1_500,
                        onBaselineObserved = { baselineObserved.complete(Unit) },
                    ) {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        assertTrue(!composeTestRule.activity.isDestroyed)
                        diagnosticCalled = true
                        "copy_checkpoint=before_teardown focused=${composeTestRule.activity.hasWindowFocus()}"
                    }
                }
            val message = failure.message.orEmpty()
            assertTrue(baselineObserved.isCompleted)
            assertTrue(diagnosticCalled)
            assertTrue(message.contains("last=[clipboard=reply_span span=13:17 items=1 textLength=4"))
            assertTrue(message.contains("clipboard=baseline"))
            assertTrue(message.contains("copy_checkpoint=before_teardown focused=true"))
            composeTestRule.runOnIdle { assertEquals("jade", clipboardText(clipboard)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unrelatedLongClipboard_reportsBoundedMetadataWithoutItsTextOrLabel() {
        val clipboard = composeTestRule.activity.selectionClipboard()
        seedBaseline(clipboard)
        val privateText = "private clipboard contents ".repeat(1_000)
        composeTestRule.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("private clipboard label", privateText))
        }
        val failure =
            assertThrows(ComposeTimeoutException::class.java) {
                composeTestRule.assertSelectedWordOnClipboard(clipboard, SELECTION_REPLY, "cobalt", 200)
            }
        val message = failure.message.orEmpty()
        assertTrue(message.contains("clipboard=other_text prefixSha256="))
        assertTrue(message.contains("items=1 textLength=${privateText.length}"))
        assertTrue(!message.contains("private clipboard"))
        assertTrue("failure metadata must be bounded", message.length < 1_500)
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
        assertThrows(SecurityException::class.java) {
            composeTestRule.assertSelectedWordOnClipboard(targetClipboard, SELECTION_REPLY, "cobalt", 200)
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
