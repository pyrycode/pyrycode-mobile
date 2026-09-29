package de.pyryco.mobile.ui.components

import android.app.UiAutomation
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

@OptIn(ExperimentalTestApi::class)
class MobileModalTest {
    @get:Rule(order = 0)
    val imeBeforeActivity =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    var run = { base.evaluate() }
                    if (description.getAnnotation(Landscape::class.java) != null) {
                        val inner = run
                        run = { inLandscape(inner) }
                    }
                    if (description.getAnnotation(WithTestIme::class.java) != null) {
                        val inner = run
                        run = { withTestIme(inner) }
                    }
                    run()
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var dismissals = 0
    private var submissions = 0
    private val enabled = mutableStateOf(true)
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private lateinit var dialogView: View
    private var keyboardController: SoftwareKeyboardController? = null

    private fun show(
        overflow: Boolean = false,
        small: Boolean = false,
    ) {
        rule.setContent {
            PyrycodeMobileTheme {
                if (small) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                        ModalContent(overflow, Modifier.size(320.dp, 640.dp))
                    }
                } else {
                    ModalContent(overflow)
                }
            }
        }
    }

    @Composable
    private fun ModalContent(
        overflow: Boolean,
        modifier: Modifier = Modifier,
    ) {
        MobileModal(
            title = "Independent title",
            onDismissRequest = { dismissals++ },
            onSubmit = { submissions++ },
            modifier = modifier,
            submissionEnabled = enabled.value,
            loading = loading.value,
            error = error.value,
        ) {
            dialogView = LocalView.current
            keyboardController = LocalSoftwareKeyboardController.current
            var value by remember { mutableStateOf("") }
            if (overflow) repeat(18) { Text("Item $it") }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Editable value") },
                modifier = Modifier.fillMaxWidth().testTag("field"),
            )
            Text("Final item")
        }
    }

    @Test
    fun short_content_and_accessible_actions_are_visible() {
        show()
        rule.onNodeWithText("Independent title").assertIsDisplayed()
        rule.onNodeWithTag("field").assertIsDisplayed()
        rule
            .onNodeWithContentDescription("Close")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        listOf("Cancel", "OK").forEach { label ->
            rule
                .onNodeWithText(label)
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsEqualTo(40.dp)
        }
        val title = rule.onNodeWithText("Independent title").fetchSemanticsNode().boundsInRoot
        val field = rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        assertTrue(field.top > title.bottom)
        assertTrue(field.bottom < footer.top)
        assertTrue(field.center.y > title.bottom + (footer.top - title.bottom) / 3)
    }

    @Test
    fun close_cancel_and_back_only_request_dismissal_once_each() {
        show()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
        // The Figma surface is 40 dp high; its reserved target accepts a touch just above it.
        rule.onNodeWithText("Cancel").performTouchInput { click(Offset(center.x, -3f)) }
        rule.runOnIdle { assertEquals(2, dismissals) }
        pressBackOnFocusedWindow()
        rule.runOnIdle {
            assertEquals(3, dismissals)
            assertEquals(0, submissions)
        }
    }

    @Test
    fun plain_shell_window_is_not_hardened() {
        show()
        rule.runOnIdle {
            val window = dialogWindow()
            assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertFalse(window.decorView.filterTouchesWhenObscured)
        }
    }

    @Test
    fun gate_window_is_secure_filters_obscured_touches_and_only_cancel_dismisses() {
        rule.setContent {
            PyrycodeMobileTheme {
                MobileGateModal(title = "Gate title", cancelLabel = "Cancel", onCancel = { dismissals++ }) {
                    dialogView = LocalView.current
                    Text("Gate content")
                }
            }
        }
        rule.runOnIdle {
            val window = dialogWindow()
            assertNotEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
            assertTrue(window.decorView.filterTouchesWhenObscured)
        }
        rule.onNodeWithContentDescription("Close").assertDoesNotExist()
        rule.onNodeWithText("OK").assertDoesNotExist()

        pressBackOnFocusedWindow()
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.onNodeWithText("Gate content").assertIsDisplayed()

        rule
            .onNodeWithText("Cancel")
            .assertHeightIsEqualTo(40.dp)
            .performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    private fun dialogWindow(): Window =
        checkNotNull((dialogView.parent as? DialogWindowProvider)?.window) { "modal content is not hosted in a dialog window" }

    // Espresso can select the unfocused activity root while a dialog owns input.
    private fun pressBackOnFocusedWindow() {
        ParcelFileDescriptor
            .AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK"),
            ).use { it.readBytes() }
        rule.waitForIdle()
    }

    @Test
    fun disabled_and_loading_block_submission_but_keep_dismissal_available() {
        enabled.value = false
        show()
        rule.onNodeWithText("OK").assertIsNotEnabled().performClick()
        rule.runOnIdle { enabled.value = true }
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Independent title").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, submissions)
            assertEquals(0, dismissals)
            loading.value = true
        }
        rule.onNodeWithText("OK").assertIsNotEnabled().performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Cancel").performClick()
        pressBackOnFocusedWindow()
        rule.runOnIdle {
            assertEquals(1, submissions)
            assertEquals(3, dismissals)
        }
    }

    @Test
    fun error_and_loading_recomposition_preserve_entered_value_and_focus() {
        show()
        rule.onNodeWithTag("field").performClick().performTextInput("Keep this value")
        rule.runOnIdle {
            error.value = "Try a different value"
            loading.value = true
        }
        rule.onNodeWithTag("field").assertIsFocused().assertTextContains("Keep this value")
        rule
            .onNodeWithText("Try a different value")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun small_window_overflow_scrolls_with_header_and_footer_fixed() {
        show(overflow = true, small = true)
        val header = rule.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Final item").assertIsNotDisplayed()
        rule.onNodeWithText("Final item").performScrollTo().assertIsDisplayed()
        assertEquals(header, rule.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @WithTestIme
    fun ime_keeps_focused_field_final_item_and_actions_reachable() {
        show(overflow = true, small = true)
        rule.waitUntil(5_000) {
            rule.runOnIdle { ::dialogView.isInitialized && dialogView.hasWindowFocus() }
        }
        rule
            .onNodeWithTag("field")
            .performScrollTo()
            .performClick()
            .assertIsFocused()
        rule.runOnIdle { checkNotNull(keyboardController).show() }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        rule
            .onNodeWithTag("field")
            .assertIsFocused()
            .assertIsDisplayed()
            .performTextInput("Keyboard entry")
        rule.onNodeWithText("Final item").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            val ime = ViewCompat.getRootWindowInsets(dialogView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            assertTrue(ime > 0)
            assertTrue(location[1] + footer.bottom <= dialogView.resources.displayMetrics.heightPixels - ime + 1)
        }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    @WithTestIme
    fun editHostFieldAndUnpairRemainReachableWithKeyboard() {
        var unpairs = 0
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                EditHostModal(
                    serverIdentity = "345345-345345345-gw3vw-w4wv34-vw34t",
                    relayAddress = "https://asdf.afwevawef.fwef/asdffe",
                    initialHostName = "Pyrybox",
                    onDismissRequest = {},
                    onSubmit = {},
                    onUnpairRequested = { unpairs++ },
                    onUnpairConfirmed = {},
                    onUnpairDeclined = {},
                )
            }
        }
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTouchInput { click(Offset(8f, center.y)) }.assertIsFocused()
        rule.runOnIdle { assertEquals(0, unpairs) }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed().performTextInput(" two")
        val action = rule.onNodeWithText("Unpair host").performScrollTo().assertIsDisplayed()
        val fieldBounds = rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue("field and Unpair touch areas overlap", fieldBounds.bottom < actionBounds.top)
        action.performTouchInput { click(Offset(center.x, 1f)) }
        rule.runOnIdle { assertEquals(1, unpairs) }
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    @WithTestIme
    @Landscape
    fun landscape_ime_keeps_focused_field_following_content_and_actions_reachable() {
        rotateToLandscape()
        show()
        rule.waitUntil(5_000) {
            rule.runOnIdle { ::dialogView.isInitialized && dialogView.hasWindowFocus() }
        }
        rule.runOnIdle { assertEquals(Configuration.ORIENTATION_LANDSCAPE, dialogView.resources.configuration.orientation) }
        rule
            .onNodeWithTag("field")
            .performClick()
            .assertIsFocused()
        rule.runOnIdle { checkNotNull(keyboardController).show() }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("field").assertIsFocused()
        assertAboveKeyboard(rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("field").performTextInput("Keyboard entry")
        listOf("Final item", "OK", "Cancel").forEach { label ->
            rule.onNodeWithText(label).performScrollTo()
            assertAboveKeyboard(rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
        }
        rule.onNodeWithTag("field").assertIsFocused().assertTextContains("Keyboard entry")
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    /** The node's full height sits on screen, below the dialog's top and above the keyboard. */
    private fun assertAboveKeyboard(bounds: Rect) {
        rule.runOnIdle {
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            val ime = ViewCompat.getRootWindowInsets(dialogView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val keyboardTop = dialogView.resources.displayMetrics.heightPixels - ime
            assertTrue("keyboard inset $ime", ime > 0)
            assertTrue("bounds $bounds, keyboard top $keyboardTop", bounds.height > 0 && bounds.top >= 0)
            assertTrue("bounds $bounds, keyboard top $keyboardTop", location[1] + bounds.bottom <= keyboardTop + 1)
        }
    }

    @Test
    fun keyboard_navigation_stays_in_dialog_and_restores_launcher_focus() {
        val launcher = FocusRequester()
        lateinit var launcherInputMode: InputModeManager
        lateinit var dialogInputMode: InputModeManager
        rule.setContent {
            PyrycodeMobileTheme {
                var open by remember { mutableStateOf(false) }
                launcherInputMode = LocalInputModeManager.current
                Column {
                    Button(onClick = { open = true }, modifier = Modifier.focusRequester(launcher)) { Text("Launch") }
                    Button(onClick = {}) { Text("Other background action") }
                }
                if (open) {
                    MobileModal("Keyboard modal", onDismissRequest = {
                        dismissals++
                        open = false
                    }, onSubmit = { submissions++ }) {
                        dialogInputMode = LocalInputModeManager.current
                        Text("Body")
                    }
                }
            }
        }
        rule.runOnIdle {
            assertTrue(launcherInputMode.requestInputMode(InputMode.Keyboard))
            launcher.requestFocus()
        }
        rule.onNodeWithText("Launch").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertTrue(dialogInputMode.requestInputMode(InputMode.Keyboard)) }
        rule.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(6) {
            rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.Tab) }
            rule.onNode(isFocused() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        rule.onNodeWithText("OK").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("OK").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, submissions) }
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithText("Launch").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Keyboard modal").assertIsDisplayed()
        rule.runOnIdle { assertTrue(dialogInputMode.requestInputMode(InputMode.Keyboard)) }
        rule.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithContentDescription("Close").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Launch").assertIsFocused()
        rule.runOnIdle {
            assertEquals(2, dismissals)
            assertEquals(1, submissions)
        }
    }

    /** Restores the device rotation after the inner Compose rule has closed its activity. */
    private fun inLandscape(block: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            block()
        } finally {
            automation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    /**
     * The portrait-only launcher keeps the display upright until the host activity is on top, so
     * rotate after launch and before content is set; the host's relaunch then carries no content.
     */
    private fun rotateToLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val displays = instrumentation.targetContext.getSystemService(DisplayManager::class.java)

        fun rotation() = displays.getDisplay(Display.DEFAULT_DISPLAY).rotation
        assertTrue(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (rotation() != Surface.ROTATION_90 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("display rotation", Surface.ROTATION_90, rotation())
        // Wait for the host's relaunch, so setContent reaches the landscape instance. The ATD image's
        // Bluetooth crash dialog can hold window focus after the rotation; close system dialogs until
        // the host has it.
        rule.waitUntil(10_000) {
            var ready = false
            rule.activityRule.scenario.onActivity {
                ready = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && it.hasWindowFocus()
            }
            if (!ready) {
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS"),
                    ).use { it.readBytes() }
            }
            ready
        }
    }

    private fun withTestIme(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val imeId = "${instrumentation.context.packageName}/${MobileModalTestIme::class.java.name}"
        val previous = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val manager = instrumentation.targetContext.getSystemService(InputMethodManager::class.java)
        val wasEnabled = manager.enabledInputMethodList.any { it.id == imeId }

        fun shell(command: String) =
            ParcelFileDescriptor
                .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader()
                .use { it.readText() }
        try {
            shell("ime enable $imeId")
            shell("ime set $imeId")
            assertEquals(imeId, Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            // Drain configuration delivery before the inner Compose rule launches its activity.
            instrumentation.waitForIdleSync()
            block()
        } finally {
            // The inner rule has already closed the host before restoring the device IME.
            if (!previous.isNullOrEmpty()) shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $imeId")
            if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method")
        }
    }
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class WithTestIme

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
private annotation class Landscape
