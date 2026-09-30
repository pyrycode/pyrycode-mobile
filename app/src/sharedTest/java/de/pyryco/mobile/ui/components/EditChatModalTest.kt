package de.pyryco.mobile.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class EditChatModalTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissals = 0
    private var archives = 0
    private val submitted = mutableListOf<String>()
    private val initialName = mutableStateOf("Release notes")
    private val hostAvailable = mutableStateOf(true)
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun show() {
        rule.setContent {
            PyrycodeMobileTheme {
                EditChatModal(
                    conversationId = "conversation-1",
                    initialName = initialName.value,
                    onDismissRequest = { dismissals++ },
                    onSubmit = { submitted += it },
                    onArchiveRequested = { archives++ },
                    hostAvailable = hostAvailable.value,
                    loading = loading.value,
                    error = error.value,
                )
            }
        }
    }

    private fun field() = rule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG)

    private fun archive() = rule.onNodeWithText(string(R.string.edit_chat_archive)).performScrollTo()

    private fun ok() = rule.onNodeWithText("OK")

    private fun type(text: String) {
        field().performTextClearance()
        if (text.isNotEmpty()) field().performTextInput(text)
    }

    @Test
    fun rendersTheTitleThePrefilledNameTheArchiveActionAndTheFooter() {
        show()

        rule.onNodeWithText(string(R.string.edit_chat_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.edit_chat_name_label)).assertIsDisplayed()
        field().assertIsDisplayed().assertTextContains("Release notes")
        assertEquals(
            listOf(string(R.string.edit_chat_name_label)),
            field().fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        archive().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        ok().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nameWellMatchesTheFigmaVisibleHeight() {
        show()
        val height = field().fetchSemanticsNode().boundsInRoot.height
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density
        assertEquals(52f * density, height, 1f)
    }

    @Test
    fun pointerTapsFocusTheNameAndReachTheArchiveSurface() {
        show()
        field().performTouchInput { click(center) }
        field().assertIsFocused()
        archive().performTouchInput { click(Offset(center.x, 1f)) }
        rule.runOnIdle { assertEquals(1, archives) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactWidthAndLargeTextKeepTheNameArchiveAndFooterReachable() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(280.dp, 560.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                    PyrycodeMobileTheme {
                        EditChatModal("chat", "Release notes", {}, {}, { archives++ })
                    }
                }
            }
        }
        rule.onNodeWithText(string(R.string.edit_chat_name_label)).assertIsDisplayed()
        field().assertIsDisplayed()
        archive().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        ok().assertIsDisplayed()
    }

    @Test
    fun okReportsTheTrimmedNameOnceAndArchiveReportsOnceAndNeitherCloses() {
        show()
        type("  Renamed chat  ")

        ok().performClick()
        rule.runOnIdle {
            assertEquals(listOf("Renamed chat"), submitted)
            assertEquals(0, archives)
            assertEquals(0, dismissals)
        }
        rule.onNodeWithText(string(R.string.edit_chat_title)).assertIsDisplayed()

        archive().performClick()
        rule.runOnIdle {
            assertEquals(1, archives)
            assertEquals(listOf("Renamed chat"), submitted)
            assertEquals(0, dismissals)
        }
        rule.onNodeWithText(string(R.string.edit_chat_title)).assertIsDisplayed()
    }

    /** Desktop's guard split: a blank name blocks OK and never Archive. */
    @Test
    fun blankOrWhitespaceNameDisablesOkButNotArchive() {
        show()

        listOf("", "   ").forEach { blank ->
            type(blank)
            ok().assertIsNotEnabled().performClick()
            archive().assertIsEnabled()
        }
        archive().performClick()
        rule.runOnIdle {
            assertTrue(submitted.isEmpty())
            assertEquals(1, archives)
        }
    }

    @Test
    fun anUnavailableHostOrAWriteInFlightDisablesBothActions() {
        show()

        rule.runOnIdle { hostAvailable.value = false }
        ok().assertIsNotEnabled().performClick()
        archive().assertIsNotEnabled().performClick()

        rule.runOnIdle {
            hostAvailable.value = true
            loading.value = true
        }
        ok().assertIsNotEnabled().performClick()
        archive().assertIsNotEnabled().performClick()

        rule.runOnIdle {
            assertTrue(submitted.isEmpty())
            assertEquals(0, archives)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun theTypedNameSurvivesErrorLoadingAvailabilityAndAnIncomingNameUpdate() {
        show()
        type("Keep this name")

        rule.runOnIdle { error.value = "Couldn't rename the chat" }
        rule
            .onNodeWithText("Couldn't rename the chat")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
        field().assertTextEquals("Keep this name")

        rule.runOnIdle { loading.value = true }
        field().assertTextEquals("Keep this name")
        rule.runOnIdle {
            loading.value = false
            hostAvailable.value = false
        }
        field().assertTextEquals("Keep this name")
        rule.runOnIdle {
            hostAvailable.value = true
            error.value = null
            initialName.value = "Renamed elsewhere"
        }
        field().assertTextEquals("Keep this name")
    }

    @Test
    fun anOversizedPrefilledNameIsClamped() {
        val raw = "n".repeat(4000)
        initialName.value = raw
        show()

        field().assertTextEquals(raw.take(MAX_WORKSPACE_LABEL_CHARS))
        rule.onAllNodes(hasText(raw), useUnmergedTree = true).assertCountEquals(0)
    }

    /** A clamp through an emoji would leave a lone surrogate that OK then sends as a mangled name. */
    @Test
    fun theClampNeverSplitsASurrogatePair() {
        val kept = "n".repeat(MAX_WORKSPACE_LABEL_CHARS - 1)
        initialName.value = kept + "😀" + "tail"
        show()

        field().assertTextEquals(kept)
        ok().performClick()
        rule.runOnIdle { assertEquals(listOf(kept), submitted) }
    }
}
