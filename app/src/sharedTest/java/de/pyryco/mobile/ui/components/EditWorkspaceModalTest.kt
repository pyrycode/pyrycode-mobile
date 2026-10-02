package de.pyryco.mobile.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_BYTES
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditWorkspaceModalTest {
    @get:Rule
    val rule = createComposeRule()

    private val events = mutableListOf<String>()
    private val submitted = mutableListOf<String>()
    private val initialName = mutableStateOf("Second Brain")
    private val hostAvailable = mutableStateOf(true)
    private val loading = mutableStateOf(false)
    private val confirming = mutableStateOf(false)

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    /** Walks the confirmation the way the view model does, so one composition covers request → decline. */
    private fun show() {
        rule.setContent {
            PyrycodeMobileTheme {
                EditWorkspaceModal(
                    serverId = "host-1",
                    cwd = "/w/Second Brain",
                    initialName = initialName.value,
                    folderName = "Second Brain",
                    onDismissRequest = { events += "dismiss" },
                    onSubmit = { submitted += it },
                    onArchiveRequested = {
                        events += "request"
                        confirming.value = true
                    },
                    onArchiveConfirmed = { events += "confirm" },
                    onArchiveDeclined = {
                        events += "decline"
                        confirming.value = false
                    },
                    hostAvailable = hostAvailable.value,
                    loading = loading.value,
                    confirmingArchive = confirming.value,
                )
            }
        }
    }

    private fun field() = rule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG)

    private fun archive() = rule.onNodeWithText(string(R.string.edit_workspace_archive)).performScrollTo()

    private fun ok() = rule.onNodeWithText("OK")

    private fun type(text: String) {
        field().performTextClearance()
        if (text.isNotEmpty()) field().performTextInput(text)
    }

    private fun length(bytes: Int) = rule.onNodeWithText(string(R.string.edit_workspace_name_length, bytes, MAX_WORKSPACE_LABEL_BYTES))

    @Test
    fun rendersTheFrameSeededWithTheShownName() {
        show()

        rule.onNodeWithText(string(R.string.edit_workspace_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.edit_workspace_name_label)).assertIsDisplayed()
        field().assertTextContains("Second Brain")
        length("Second Brain".length).assertExists()
        archive().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        ok().assertIsEnabled()
    }

    @Test
    fun okReportsTheTrimmedName_andABlankNameIsAllowedBecauseItClears() {
        show()
        type("  Notes  ")
        ok().performClick()
        type("   ")
        ok().assertIsEnabled().performClick()

        rule.runOnIdle {
            assertEquals(listOf("Notes", ""), submitted)
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun aNameOverTheByteBoundDisablesOk_andTheLengthLineCountsBytes() {
        show()

        type("ö".repeat(64))
        length(MAX_WORKSPACE_LABEL_BYTES).assertExists()
        ok().assertIsEnabled()

        type("ö".repeat(65))
        length(MAX_WORKSPACE_LABEL_BYTES + 2).assertExists()
        ok().assertIsNotEnabled().performClick()
        // Archive has nothing to do with the name field.
        archive().assertIsEnabled()

        rule.runOnIdle { assertTrue(submitted.isEmpty()) }
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
            assertTrue(events.isEmpty())
        }
    }

    @Test
    fun archiveAsksInPlace_okConfirms_cancelDeclinesBackToTheTypedName() {
        show()
        type("Typed")

        archive().performClick()
        rule.onNodeWithText(string(R.string.edit_workspace_archive_confirm_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.edit_workspace_archive_confirm_body, "Second Brain")).assertIsDisplayed()
        field().assertDoesNotExist()

        rule.onNodeWithText("Cancel").performClick()
        field().assertTextContains("Typed")

        // Espresso does not wait for Compose, so Back is pressed only once the confirmation is drawn.
        archive().performClick()
        rule.onNodeWithText(string(R.string.edit_workspace_archive_confirm_title)).assertIsDisplayed()
        Espresso.pressBack()
        field().assertTextContains("Typed")

        // In the confirmation the close glyph closes the whole modal rather than declining (#1560).
        archive().performClick()
        ok().performClick()
        rule.onNodeWithContentDescription("Close").performClick()

        rule.runOnIdle {
            assertEquals(listOf("request", "decline", "request", "decline", "request", "confirm", "dismiss"), events)
            assertTrue(submitted.isEmpty())
        }
    }

    @Test
    fun theConfirmationNeedsItsHost() {
        confirming.value = true
        hostAvailable.value = false
        show()

        ok().assertIsNotEnabled().performClick()
        rule.runOnIdle { hostAvailable.value = true }
        ok().assertIsEnabled()
        rule.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @Test
    fun anOversizedShownNameIsClampedBeforeTheFieldAndThePrompt() {
        initialName.value = "n".repeat(MAX_WORKSPACE_LABEL_CHARS - 1) + "😀" + "w".repeat(5_000)
        show()

        val kept = "n".repeat(MAX_WORKSPACE_LABEL_CHARS - 1)
        field().assertTextContains(kept)
        archive().performClick()
        rule.onNodeWithText(string(R.string.edit_workspace_archive_confirm_body, kept)).assertExists()
    }
}
