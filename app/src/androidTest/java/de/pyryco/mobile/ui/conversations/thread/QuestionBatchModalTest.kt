package de.pyryco.mobile.ui.conversations.thread

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.components.MobileModalTestIme
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** #661: the question modal driven by a real [ThreadViewModel] over a fake batch and recording sends. */
@OptIn(ExperimentalTestApi::class)
class QuestionBatchModalTest {
    @get:Rule(order = 0)
    val imeBeforeActivity =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()

                    fun shell(command: String) =
                        ParcelFileDescriptor
                            .AutoCloseInputStream(
                                instrumentation.uiAutomation.executeShellCommand(command),
                            ).bufferedReader()
                            .use {
                                it.readText()
                            }

                    fun override(output: String) =
                        output.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ") ?: "reset"
                    val oldSize = override(shell("wm size"))
                    val oldDensity = override(shell("wm density"))
                    val compact = description.methodName.startsWith("ime_") || description.methodName.contains("large_text")
                    shell("wm density 160")
                    shell(if (compact) "wm size 320x700" else "wm size 412x892")
                    try {
                        instrumentation.waitForIdleSync()
                        if (description.methodName.startsWith("ime_")) withTestIme { base.evaluate() } else base.evaluate()
                    } finally {
                        shell("wm size $oldSize")
                        shell("wm density $oldDensity")
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val answers = mutableListOf<List<QuestionAnswer>>()
    private val refusals = mutableListOf<String>()
    private var failure: Throwable? = null
    private var observedDialogView: View? = null
    private var promptView: View? = null
    private val batches = MutableStateFlow<QuestionBatch?>(null)

    /** Keep the gate window found before the IME can move focus away from it. */
    private fun dialogView(): View = checkNotNull(observedDialogView)

    private fun show(
        batch: QuestionBatch = batch(),
        fontScale: Float = 1f,
    ) {
        batches.value = batch
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to CONV)),
                FakeConversationRepository(),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                questionBatch = { batches },
                answerQuestionBatch = { _, values ->
                    failure?.let { throw it }
                    answers += values
                },
                refuseQuestionBatch = { id ->
                    failure?.let { throw it }
                    refusals += id
                },
            )
        rule.runOnUiThread {
            rule.activity.enableEdgeToEdge()
            rule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        rule.setContent {
            PyrycodeMobileTheme {
                val state by vm.questionModal.collectAsStateWithLifecycle()
                val view = LocalView.current
                SideEffect { promptView = view }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ThreadScreen(
                        state = ThreadUiState(CONV, "Client planning"),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        questionState = state,
                        onQuestionEvent = { event, generation -> vm.onQuestionEvent(event, generation) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test
    fun single_choice_uses_radio_semantics_and_other_clears_the_pick() {
        show()
        textNode("Kotlin")
            .assert(hasRole(Role.RadioButton))
            .performClick()
            .assertIsSelected()
        textNode("Rust").assertIsNotSelected()
        tagNode("question_other_0").performTextInput("Go")
        textNode("Kotlin").assertIsNotSelected()
        tagNode("question_control_0_other_row").assertIsSelected()
        textNode("Rust").performClick()
        tagNode("question_control_0_other_row").assertIsNotSelected()
    }

    @Test
    fun question_components_use_figma_control_geometry_and_keep_other_editable() {
        show()
        tagNode("question_header_glyph_0", useUnmergedTree = true).assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(16.dp)
        tagNode("question_control_0_0", useUnmergedTree = true).assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        tagNode("question_control_1_0", useUnmergedTree = true).assertWidthIsEqualTo(20.dp).assertHeightIsEqualTo(20.dp)
        textNode("Kotlin")
            .assertHeightIsAtLeast(48.dp)
            .performTouchInput { click(center) }
            .assertIsSelected()
        // #1501, Figma 636:3279: the 32 dp well is the field's layout; its 48 dp touch target lies outside it.
        val other = tagNode("question_other_0").performScrollTo().assertHeightIsEqualTo(32.dp)
        assertTrue(
            "Other retains a 48dp touch region",
            other.fetchSemanticsNode().touchBoundsInRoot.height >= with(rule.density) { 48.dp.toPx() } - 0.5f,
        )
        other.performTouchInput { click(Offset(center.x, height + 6.dp.toPx())) }.assertIsFocused()
        tagNode("question_other_0").performTextInput("Go")
        textNode("Kotlin").assertIsNotSelected()
        tagNode("question_control_0_other", useUnmergedTree = true).performTouchInput { click(center) }
        tagNode("question_control_0_other_row").assertIsNotSelected()
        tagNode("question_other_0").assertTextContains("Go")
    }

    @Test
    fun long_daemon_text_wraps_and_other_draft_survives_a_choice_change_at_large_text() {
        val longLabel = "A deliberately long server-authored choice with https://example.invalid/a/b and more words to wrap"
        val longQuestion =
            Question(
                question = "Which of these very long alternatives should be used when the available width is narrow?",
                header = "Clarification with a longer header that must wrap safely",
                options =
                    listOf(
                        QuestionOption(longLabel, "More server-authored explanation that spans several lines"),
                        QuestionOption("Last choice", "Short"),
                    ),
                multiSelect = false,
            )
        show(batch = batch().copy(questions = listOf(longQuestion)), fontScale = 1.5f)
        val label = textNode(longLabel).performScrollTo().assertIsDisplayed()
        assertTrue("the full server label should wrap", label.fetchSemanticsNode().boundsInRoot.height > 32f)
        textNode("Last choice").performScrollTo().assertIsDisplayed()
        tagNode("question_other_0").performScrollTo().performTextInput("saved draft")
        textNode("Last choice").performScrollTo().performClick()
        tagNode("question_control_0_other_row").performScrollTo().performClick()
        tagNode("question_other_0").performScrollTo().assertTextContains("saved draft")
        textNode("Cancel").assertIsDisplayed()
        textNode("Continue").assertIsDisplayed()
    }

    @Test
    fun multiple_choice_uses_checkboxes_and_continue_sends_one_answer() {
        show()
        textNode("Continue").assertIsNotEnabled()
        textNode("Android")
            .assert(hasRole(Role.Checkbox))
            .performClick()
            .assertIsOn()
        textNode("Desktop")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("question_other_1"))
        tagNode("question_control_1_other_row")
            .performScrollTo()
            .assert(hasRole(Role.Checkbox))
            .assertIsOff()
        tagNode("question_other_1").performScrollTo().performTextInput("Web")
        tagNode("question_control_1_other_row").assertIsOn()
        textNode("Android").assertIsOn()
        textNode("Continue").assertIsNotEnabled()
        textNode("Kotlin").performScrollTo().performClick()
        textNode("Continue").assertIsEnabled().performClick()
        textNode("Continue").assertIsNotEnabled()
        textNode("Cancel").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(
                listOf(listOf(QuestionAnswer(0, listOf("Kotlin")), QuestionAnswer(1, listOf("Android", "Desktop", "Web")))),
                answers,
            )
            assertTrue(refusals.isEmpty())
        }
    }

    @Test
    fun failed_refusal_shows_an_error_and_keeps_selections() {
        failure = IllegalStateException("no active connection")
        show()
        textNode("Kotlin").performClick()
        textNode("Cancel").performTouchInput { click(Offset(center.x, bottom - 1f)) }
        textNode("Couldn't send. Try again.").performScrollTo().assertIsDisplayed()
        textNode("Kotlin").assertIsSelected()
        failure = null
        textNode("Cancel").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("batch-1"), refusals) }
    }

    @Test
    fun ime_keeps_the_last_other_field_and_actions_reachable_at_320_by_700() {
        show(batch(extra = 3), fontScale = 1.5f)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var focusEvidence = "no process windows observed"
        try {
            rule.waitUntil(10_000) {
                val focused =
                    rule.runOnIdle {
                        val windows = WindowInspector.getGlobalWindowViews()
                        focusEvidence = "windowCount=${windows.size}, focused=${windows.map { it.hasWindowFocus() }}"
                        val dialog = windows.firstOrNull()
                        if (dialog?.hasWindowFocus() == true) {
                            observedDialogView = dialog
                            true
                        } else {
                            false
                        }
                    }
                if (!focused) {
                    ParcelFileDescriptor
                        .AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand(
                                "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
                            ),
                        ).use { it.readBytes() }
                }
                focused
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Question dialog did not gain focus: $focusEvidence", e)
        }
        rule.runOnIdle { assertTrue("Question dialog lost focus: $focusEvidence", dialogView().hasWindowFocus()) }
        val last = "question_other_4"
        tagNode(last)
            .performScrollTo()
            .performClick()
            .assertIsFocused()
        tagNode(last).performTextInput("draft")
        rule.runOnIdle { dialogView().windowInsetsController?.show(WindowInsets.Type.ime()) }
        rule.waitUntil(5_000) {
            rule.runOnIdle { ViewCompat.getRootWindowInsets(dialogView())?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
        rule
            .onNodeWithTag(last)
            .assertIsFocused()
            .assertIsDisplayed()
            .assertTextContains("draft")
            .performTextInput(" typed")
        rule.onNodeWithTag(last).assertTextContains("draft typed")
        val field = rule.onNodeWithTag(last).fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val ime = ViewCompat.getRootWindowInsets(dialogView())?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            assertTrue(
                "Other automatically scrolls above the keyboard",
                field.bottom <= dialogView().resources.displayMetrics.heightPixels - ime + 1,
            )
        }
        textNode("Extra 4 option").performScrollTo().assertIsDisplayed()
        textNode("Kotlin").performScrollTo().assertIsDisplayed()
        textNode("Cancel").assertIsDisplayed()
        val footer =
            textNode("Continue")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        rule.runOnIdle {
            val dialogView = dialogView()
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            val ime = ViewCompat.getRootWindowInsets(dialogView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            assertTrue(ime > 0)
            assertTrue(
                "footer=${location[1] + footer.bottom} screen=${dialogView.resources.displayMetrics.heightPixels} ime=$ime",
                location[1] + footer.bottom <= dialogView.resources.displayMetrics.heightPixels - ime + 1,
            )
        }
        rule.runOnIdle { dialogView().windowInsetsController?.hide(WindowInsets.Type.ime()) }
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                ViewCompat.getRootWindowInsets(dialogView())?.isVisible(WindowInsetsCompat.Type.ime()) !=
                    true
            }
        }
        tagNode(last).assertTextContains("draft typed")
    }

    @Test
    fun large_text_actions_stack_and_pointer_edges_submit_only_this_batch() {
        show(fontScale = 1.5f)
        // Display-size changes can leave the ATD launcher focused while semantic actions still work.
        rule.waitUntil(10_000) {
            val focused =
                rule.runOnIdle {
                    rule.activity.window.decorView
                        .hasWindowFocus()
                }
            if (!focused) {
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                            "am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS",
                        ),
                    ).use { it.readBytes() }
            }
            focused
        }
        readableTextNode("Kotlin").performClick().assertIsSelected()
        readableTextNode("Android").performClick().assertIsOn()
        val submit = textNode("Continue").assertIsDisplayed().assertIsEnabled()
        // ScrollTo only sees the full drawing viewport, including rows behind chrome. Put the
        // actions at the newest resting end before testing their actual pointer hit regions.
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val cancel = rule.onNodeWithText("Cancel").assertIsDisplayed()
        val cancelBounds = cancel.fetchSemanticsNode().boundsInRoot
        val submitBounds = submit.fetchSemanticsNode().boundsInRoot
        assertTrue("stacked actions cannot overlap", cancelBounds.bottom <= submitBounds.top)
        val cancelTouch = cancel.fetchSemanticsNode().touchBoundsInRoot
        val submitTouch = submit.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("Cancel retains a 48dp touch region", cancelTouch.height >= 48f)
        assertTrue("Continue retains a 48dp touch region", submitTouch.height >= 48f)
        assertTrue("neighboring touch regions cannot overlap", cancelTouch.bottom <= submitTouch.top)
        val chromeTop =
            rule
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top
        val headerBottom =
            rule
                .onNodeWithTag("thread-top-bar")
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        assertTrue("the tested Continue edge must be readable above chrome", submitBounds.top - 1f in headerBottom..chromeTop)
        rule.runOnIdle {
            assertTrue(
                "physical input needs the thread window focused",
                rule.activity.window.decorView
                    .hasWindowFocus(),
            )
        }
        submit.assertIsEnabled()
        submit.performTouchInput { click(Offset(center.x, -1f)) }
        rule.runOnIdle {
            assertEquals(1, answers.size)
            assertTrue(refusals.isEmpty())
        }
    }

    @Test
    fun inline_prompt_protects_capture_rejects_obscured_touches_and_restores_window_policy() {
        show()
        val control = tagNode("question_control_0_0_row")
        val bounds = control.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val view = checkNotNull(promptView)
            assertTrue(view.filterTouchesWhenObscured)
            assertTrue(rule.activity.window.decorView.filterTouchesWhenObscured)
            assertTrue(rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            val properties =
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 0
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    },
                )
            val coordinates =
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        x = bounds.center.x
                        y = bounds.center.y
                        pressure = 1f
                        size = 1f
                    },
                )
            val now = android.os.SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event =
                    MotionEvent.obtain(
                        now,
                        now,
                        action,
                        1,
                        properties,
                        coordinates,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                    )
                assertTrue(
                    "the protected Compose view rejects the overlay event",
                    !rule.activity.window.decorView
                        .dispatchTouchEvent(event),
                )
                event.recycle()
            }
        }
        control.assertIsNotSelected().performTouchInput { click(center) }.assertIsSelected()
        rule.runOnIdle { batches.value = null }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(!checkNotNull(promptView).filterTouchesWhenObscured)
            assertTrue(!rule.activity.window.decorView.filterTouchesWhenObscured)
            assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    @Test
    fun overlapping_prompts_keep_protection_when_the_outgoing_owner_exits_first() = overlapProtection(true, false)

    @Test
    fun overlapping_prompts_keep_protection_when_the_incoming_owner_exits_first() = overlapProtection(false, false)

    @Test
    fun overlapping_prompts_restore_an_originally_protected_window() = overlapProtection(true, true)

    private fun overlapProtection(
        releaseFirst: Boolean,
        protectedOriginally: Boolean,
    ) {
        var first by mutableStateOf(true)
        var second by mutableStateOf(false)
        var clicks = 0
        rule.runOnUiThread {
            if (protectedOriginally) rule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            rule.activity.window.decorView.filterTouchesWhenObscured = protectedOriginally
        }
        rule.setContent {
            val view = LocalView.current
            SideEffect { promptView = view }
            Box(Modifier.fillMaxSize().testTag("overlap-touch-target").clickable { clicks++ }) {
                if (first) key("outgoing") { QuestionPromptProtection() }
                if (second) key("incoming") { QuestionPromptProtection() }
            }
        }
        rule.runOnIdle { second = true }
        rule.waitForIdle()
        rule.runOnIdle { if (releaseFirst) first = false else second = false }
        rule.waitForIdle()
        rule.runOnIdle {
            val window = rule.activity.window
            assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertTrue(window.decorView.filterTouchesWhenObscured)
            assertTrue(checkNotNull(promptView).filterTouchesWhenObscured)
            val now = android.os.SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
            try {
                // onFilterTouchEventForSecurity is the decor's actual dispatch gate.
                val obscured =
                    MotionEvent.obtain(
                        now,
                        now,
                        MotionEvent.ACTION_DOWN,
                        1,
                        arrayOf(MotionEvent.PointerProperties().apply { id = 0 }),
                        arrayOf(
                            MotionEvent.PointerCoords().apply {
                                x = 100f
                                y = 100f
                            },
                        ),
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_TOUCHSCREEN,
                        MotionEvent.FLAG_WINDOW_IS_OBSCURED,
                    )
                try {
                    assertTrue(!window.decorView.dispatchTouchEvent(obscured))
                } finally {
                    obscured.recycle()
                }
                assertTrue(window.decorView.onFilterTouchEventForSecurity(event))
                assertTrue("unobscured input must reach the same target", window.decorView.dispatchTouchEvent(event))
                event.action = MotionEvent.ACTION_UP
                assertTrue(window.decorView.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals("only the unobscured input activates the target", 1, clicks)
            first = false
            second = false
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(protectedOriginally, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(protectedOriginally, rule.activity.window.decorView.filterTouchesWhenObscured)
            assertTrue(!checkNotNull(promptView).filterTouchesWhenObscured)
        }
    }

    private fun textNode(text: String): SemanticsNodeInteraction {
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return rule.onNodeWithText(text)
    }

    /** The list draws under chrome; ScrollTo's viewport alone does not establish a usable pointer target. */
    private fun readableTextNode(text: String): SemanticsNodeInteraction {
        val node = textNode(text)
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val header = rule.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
        val composer = rule.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
        val shift =
            when {
                bounds.top < header.bottom -> header.bottom - bounds.top + 4f
                bounds.bottom > composer.top -> composer.top - bounds.bottom - 4f
                else -> 0f
            }
        if (shift != 0f) {
            rule.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, shift) }
        }
        val visible = node.fetchSemanticsNode().boundsInRoot
        assertTrue("$text must be readable between chrome: $visible", visible.top >= header.bottom && visible.bottom <= composer.top)
        return node
    }

    private fun tagNode(
        tag: String,
        useUnmergedTree: Boolean = false,
    ): SemanticsNodeInteraction {
        rule.onNode(hasScrollToNodeAction(), useUnmergedTree = useUnmergedTree).performScrollToNode(hasTestTag(tag))
        return rule.onNodeWithTag(tag, useUnmergedTree)
    }

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun batch(extra: Int = 0) =
        QuestionBatch(
            conversationId = CONV,
            questionBatchId = "batch-1",
            questions =
                listOf(
                    Question(
                        "Which language?",
                        "Language",
                        listOf(QuestionOption("Kotlin", "JVM"), QuestionOption("Rust", "Native")),
                        false,
                    ),
                    Question(
                        "Which targets?",
                        "Targets",
                        listOf(QuestionOption("Android", "Phone"), QuestionOption("Desktop", "Laptop")),
                        true,
                    ),
                ) +
                    (2 until 2 + extra).map {
                        Question(
                            "Extra question $it",
                            "Extra $it",
                            listOf(QuestionOption("Extra $it option", "A longer description")),
                            false,
                        )
                    },
        )

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
            instrumentation.waitForIdleSync()
            block()
        } finally {
            if (!previous.isNullOrEmpty()) shell("ime set $previous")
            if (!wasEnabled) shell("ime disable $imeId")
            if (previous.isNullOrEmpty()) shell("settings delete secure default_input_method")
        }
    }

    private companion object {
        const val CONV = "conv-1"
    }
}
