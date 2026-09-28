package de.pyryco.mobile.ui.conversations.thread

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
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
                    if (description.methodName.startsWith("ime_")) withTestIme { base.evaluate() } else base.evaluate()
                }
            }
        }

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val answers = mutableListOf<List<QuestionAnswer>>()
    private val refusals = mutableListOf<String>()
    private var failure: Throwable? = null
    private var observedDialogView: View? = null

    /** Keep the gate window found before the IME can move focus away from it. */
    private fun dialogView(): View = checkNotNull(observedDialogView)

    private fun show(
        batch: QuestionBatch = batch(),
        small: Boolean = false,
    ) {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to CONV)),
                FakeConversationRepository(),
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                questionBatch = { MutableStateFlow(batch) },
                answerQuestionBatch = { _, values ->
                    failure?.let { throw it }
                    answers += values
                },
                refuseQuestionBatch = { id ->
                    failure?.let { throw it }
                    refusals += id
                },
            )
        rule.setContent {
            PyrycodeMobileTheme {
                val state by vm.questionModal.collectAsStateWithLifecycle()
                val size = if (small) DpSize(320.dp, 640.dp) else DpSize(412.dp, 892.dp)
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                    state?.let { QuestionBatchModal(state = it, onEvent = vm::onQuestionEvent, modifier = Modifier.size(size)) }
                }
            }
        }
    }

    @Test
    fun single_choice_uses_radio_semantics_and_other_clears_the_pick() {
        show()
        rule
            .onNodeWithText("Kotlin")
            .assert(hasRole(Role.RadioButton))
            .performClick()
            .assertIsSelected()
        rule.onNodeWithText("Rust").assertIsNotSelected()
        rule.onNodeWithTag("question_other_0").performTextInput("Go")
        rule.onNodeWithText("Kotlin").assertIsNotSelected()
        rule.onAllNodesWithText("Other")[0].assertIsSelected()
        rule.onNodeWithText("Rust").performClick()
        rule.onAllNodesWithText("Other")[0].assertIsNotSelected()
    }

    @Test
    fun multiple_choice_uses_checkboxes_and_continue_sends_one_answer() {
        show()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule
            .onNodeWithText("Android")
            .assert(hasRole(Role.Checkbox))
            .performClick()
            .assertIsOn()
        rule
            .onNodeWithText("Desktop")
            .performScrollTo()
            .performClick()
            .assertIsOn()
        rule
            .onAllNodesWithText("Other")[1]
            .performScrollTo()
            .assert(hasRole(Role.Checkbox))
            .assertIsOff()
        rule.onNodeWithTag("question_other_1").performScrollTo().performTextInput("Web")
        rule.onAllNodesWithText("Other")[1].assertIsOn()
        rule.onNodeWithText("Android").assertIsOn()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Kotlin").performScrollTo().performClick()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
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
        rule.onNodeWithText("Kotlin").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Couldn't send. Try again.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Kotlin").assertIsSelected()
        failure = null
        rule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf("batch-1"), refusals) }
    }

    @Test
    fun ime_keeps_the_last_other_field_and_actions_reachable_at_320_by_640() {
        show(batch(extra = 3), small = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var focusEvidence = "no process windows observed"
        try {
            rule.waitUntil(10_000) {
                val focused =
                    rule.runOnIdle {
                        val windows = WindowInspector.getGlobalWindowViews()
                        focusEvidence = "windowCount=${windows.size}, focused=${windows.map { it.hasWindowFocus() }}"
                        val dialog = windows.lastOrNull().takeIf { windows.size > 1 }
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
        rule
            .onNodeWithTag(last)
            .performScrollTo()
            .performClick()
            .assertIsFocused()
        rule.runOnIdle { dialogView().windowInsetsController?.show(WindowInsets.Type.ime()) }
        rule.waitUntil(5_000) {
            rule.runOnIdle { ViewCompat.getRootWindowInsets(dialogView())?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        }
        rule
            .onNodeWithTag(last)
            .assertIsFocused()
            .assertIsDisplayed()
            .performTextInput("typed")
        rule.onNodeWithText("Extra 4 option").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Kotlin").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        val footer =
            rule
                .onNodeWithText("Continue")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        rule.runOnIdle {
            val dialogView = dialogView()
            val location = IntArray(2)
            dialogView.getLocationOnScreen(location)
            val ime = ViewCompat.getRootWindowInsets(dialogView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            assertTrue(ime > 0)
            assertTrue(location[1] + footer.bottom <= dialogView.resources.displayMetrics.heightPixels - ime + 1)
        }
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
