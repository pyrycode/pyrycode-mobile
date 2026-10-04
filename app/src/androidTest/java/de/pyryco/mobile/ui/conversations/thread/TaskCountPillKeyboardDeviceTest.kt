package de.pyryco.mobile.ui.conversations.thread

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.components.MobileModalTestIme
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real IME and pointer coverage for the thread's running-task entry. */
@RunWith(AndroidJUnit4::class)
class TaskCountPillKeyboardDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val imeId get() = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
    private var originalIme: String? = null
    private var imeEnabled = false

    @Before
    fun selectTestIme() {
        originalIme = Settings.Secure.getString(instrumentation.targetContext.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        imeEnabled =
            instrumentation.targetContext
                .getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList
                .any { it.id == imeId }
        shell("ime enable $imeId")
        shell("ime set $imeId")
        instrumentation.waitForIdleSync()
    }

    @After
    fun restoreIme() {
        scenario?.close()
        originalIme?.takeIf { it.isNotEmpty() }?.let { shell("ime set $it") }
        if (!imeEnabled) shell("ime disable $imeId")
        if (originalIme.isNullOrEmpty()) shell("settings delete secure default_input_method")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }
    }

    @Test
    fun keyboardAndActionsMenu_keepRunningPillReachable() {
        var composeView: View? = null
        val roster = BackgroundTaskRoster(listOf(BackgroundTask("t1", "toolu_t1", "local_bash", "sleep 300", null, null, null, false)), 0)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario?.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            activity.setContent {
                PyrycodeMobileTheme(darkTheme = true) {
                    composeView = LocalView.current
                    ThreadScreen(
                        state = ThreadUiState("c1", "Keyboard thread", backgroundTasks = roster, backgroundTaskCount = 2),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isThinking = true,
                    )
                }
            }
        }

        val field = rule.onNode(hasSetTextAction())
        field.performTouchInput { click() }
        field.assertIsFocused()
        rule.runOnIdle { composeView?.windowInsetsController?.show(WindowInsets.Type.ime()) }
        rule.waitUntil(10_000) {
            rule.runOnIdle { composeView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        }

        val pill = rule.onNodeWithContentDescription("2 tasks running")
        val imeBottom = rule.runOnIdle { checkNotNull(composeView).rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom }
        val viewHeight = rule.runOnIdle { checkNotNull(composeView).height }
        val density = rule.runOnIdle { checkNotNull(composeView).resources.displayMetrics.density }
        val pillBottomPx = pill.getUnclippedBoundsInRoot().bottom.value * density
        assertTrue(
            "running pill clears the real keyboard: pill=$pillBottomPx view=$viewHeight ime=$imeBottom",
            pillBottomPx <= viewHeight - imeBottom,
        )

        pill.performTouchInput { click() }
        rule.onNodeWithText("Background tasks").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performTouchInput { click() }

        rule.onNodeWithText("Actions").performTouchInput { click() }
        rule.onNodeWithText("Background tasks (2)").assertDoesNotExist()
        rule.onNodeWithText("Background tasks").assertDoesNotExist()
        rule.onNodeWithText("Compact session").assertIsDisplayed()
        rule.onNodeWithText("Knowledge capture").assertIsDisplayed()
        pill.assertIsDisplayed()
    }
}
