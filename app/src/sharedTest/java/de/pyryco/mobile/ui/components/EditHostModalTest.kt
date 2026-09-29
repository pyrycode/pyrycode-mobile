package de.pyryco.mobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Robolectric measures text with real fonts here, so the one-line truncation checks hold; the device
// ignores this annotation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class EditHostModalTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissals = 0
    private var unpairs = 0
    private var unpairConfirms = 0
    private var unpairDeclines = 0
    private val submitted = mutableListOf<String>()
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val confirming = mutableStateOf(false)

    // The frame's own sample values, so a reader can line the suite up against the design.
    private val identity = "345345-345345345-gw3vw-w4wv34-vw34t"
    private val relay = "https://asdf.afwevawef.fwef/asdffe"
    private var hostName = "Pyrybox"

    // Well past MAX_WORKSPACE_LABEL_CHARS, and different characters per field so the two clamped
    // values can never satisfy one another's assertion.
    private val oversizedIdentity = "z".repeat(4000)
    private val oversizedRelay = "y".repeat(4000)

    // The viewport the shell's own overflow case uses, and the one AC5 names.
    private val smallSize = DpSize(320.dp, 640.dp)
    private val figmaSize = DpSize(412.dp, 892.dp)

    // One bodyMedium line is 20sp, so two lines of it clear this comfortably.
    private val singleLineCeiling = 28.dp

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun show(
        serverIdentity: String = identity,
        relayAddress: String = relay,
        small: Boolean = false,
        figma: Boolean = false,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            PyrycodeMobileTheme {
                if (small || figma) {
                    val size = if (small) smallSize else figmaSize
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                            Modal(serverIdentity, relayAddress, Modifier.size(size))
                        }
                    }
                } else {
                    Modal(serverIdentity, relayAddress)
                }
            }
        }
    }

    @Composable
    private fun Modal(
        serverIdentity: String,
        relayAddress: String,
        modifier: Modifier = Modifier,
    ) {
        EditHostModal(
            serverIdentity = serverIdentity,
            relayAddress = relayAddress,
            initialHostName = hostName,
            onDismissRequest = { dismissals++ },
            onSubmit = { submitted += it },
            onUnpairRequested = { unpairs++ },
            onUnpairConfirmed = { unpairConfirms++ },
            onUnpairDeclined = { unpairDeclines++ },
            modifier = modifier,
            loading = loading.value,
            error = error.value,
            confirmingUnpair = confirming.value,
        )
    }

    /** The value half of one identity row, taken from the unmerged tree so its own bounds are readable. */
    private fun valueNode(text: String) = rule.onNode(hasText(text), useUnmergedTree = true)

    private fun assertInert(text: String) {
        valueNode(text)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.EditableText))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
    }

    private fun assertWithin(
        right: Dp,
        what: String,
    ) = assertTrue("$what right edge $right exceeds the ${smallSize.width} viewport", right <= smallSize.width)

    private fun assertSingleLine(
        height: Dp,
        what: String,
    ) = assertTrue("$what is $height tall, so it wrapped instead of truncating", height <= singleLineCeiling)

    private fun assertNoTextOverflow(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("$text is clipped (lines=${layout.lineCount}, size=${layout.size})", layout.hasVisualOverflow)
    }

    @Test
    fun rendersTheFrameWithInertIdentityRowsAndAPrefilledName() {
        show()

        listOf(
            string(R.string.edit_host_title),
            string(R.string.edit_host_server_identity_label),
            string(R.string.edit_host_relay_address_label),
            string(R.string.edit_host_name_label),
            identity,
            relay,
        ).forEach { rule.onNodeWithText(it).assertIsDisplayed() }

        // AC1: the frame calls these "read only textfields"; they must be text, not fields.
        assertInert(identity)
        assertInert(relay)

        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed().assertTextContains(hostName)
        rule.onNodeWithText(string(R.string.edit_host_unpair)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    @Test
    fun identityValuesFollowNaturalWidthLabelsAndNameWellMatchesDesignHeight() {
        show(figma = true)
        val valueStarts = mutableListOf<Dp>()
        listOf(
            string(R.string.edit_host_server_identity_label) to identity,
            string(R.string.edit_host_relay_address_label) to relay,
        ).forEach { (label, value) ->
            val labelBounds = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val valueBounds = valueNode(value).getUnclippedBoundsInRoot()
            valueStarts += valueBounds.left
            assertEquals(10f, (valueBounds.left - labelBounds.right).value, 1f)
            assertEquals(labelBounds.top.value, valueBounds.top.value, 1f)
        }
        assertTrue("identity values must align within the reference row", (valueStarts[0] - valueStarts[1]).value < 7f)
        val well = rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).getUnclippedBoundsInRoot()
        assertEquals(52f, well.height.value, 1f)
    }

    @Test
    fun clearingTheNameSubmitsBlankWithoutTriggeringUnpair() {
        show()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithText("OK").performClick()
        rule.runOnIdle {
            assertEquals(listOf(""), submitted)
            assertEquals(0, unpairs)
        }
    }

    @Test
    fun keyboardDoneSubmitsTheEditedName() {
        show()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextInput("Renamed host")
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performImeAction()
        rule.runOnIdle { assertEquals(listOf("Renamed host"), submitted) }
    }

    @Test
    fun submitAndUnpairReportSeparatelyAndNeitherCloses() {
        show()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextInput("Pyrybox two")

        rule.onNodeWithText("OK").performClick()
        rule.runOnIdle {
            assertEquals(listOf("Pyrybox two"), submitted)
            assertEquals(0, unpairs)
            assertEquals(0, dismissals)
        }
        rule.onNodeWithText(string(R.string.edit_host_title)).assertIsDisplayed()

        rule.onNodeWithText(string(R.string.edit_host_unpair)).performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, unpairs)
            assertEquals(listOf("Pyrybox two"), submitted)
            assertEquals(0, dismissals)
        }
        rule.onNodeWithText(string(R.string.edit_host_title)).assertIsDisplayed()

        rule.onNodeWithContentDescription("Close").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(2, dismissals) }
        Espresso.pressBack()
        rule.runOnIdle {
            assertEquals(3, dismissals)
            assertEquals(listOf("Pyrybox two"), submitted)
            assertEquals(1, unpairs)
        }
    }

    /**
     * The confirmation #745 adds is an in-place content swap, not a second window, and the shell's own
     * footer carries the decision. A stacked `Dialog` would have given one destructive decision two back
     * targets; this asserts the swap instead — the editor's children are *gone*, not covered — and that
     * no route out of the step can be mistaken for the one that removes the host.
     */
    @Test
    fun unpairConfirmationReplacesTheContentInPlaceAndEveryDismissalRouteDeclines() {
        show()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextInput("Draft name")

        // The action reports the intent and changes nothing: the step is the caller's, like visibility.
        rule.onNodeWithText(string(R.string.edit_host_unpair)).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, unpairs) }
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed()

        rule.runOnIdle { confirming.value = true }
        rule.onNodeWithText(string(R.string.edit_host_unpair_confirm_title)).assertIsDisplayed()
        rule.onNodeWithText(hostName, substring = true).assertIsDisplayed()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertDoesNotExist()
        rule.onNodeWithText(identity).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.edit_host_unpair)).assertDoesNotExist()

        // Cancel, the close glyph and system Back all decline; none of the three dismisses the modal,
        // and none of them removes anything.
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        Espresso.pressBack()
        rule.runOnIdle {
            assertEquals(3, unpairDeclines)
            assertEquals(0, dismissals)
            assertEquals(0, unpairConfirms)
        }

        // Only the shell's OK confirms, and it submits no name while the confirmation is up.
        rule.onNodeWithText("OK").performClick()
        rule.runOnIdle {
            assertEquals(1, unpairConfirms)
            assertTrue(submitted.isEmpty())
        }

        // Declining returns to the editor with the draft intact: the name buffer is keyed on the
        // identity, not on the step.
        rule.runOnIdle { confirming.value = false }
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextContains("Draft name")
    }

    @Test
    fun errorAndLoadingPreserveTheEnteredNameAndTheIdentityRows() {
        show()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextClearance()
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextInput("Keep this name")

        rule.runOnIdle {
            error.value = "That name is already taken"
            loading.value = true
        }

        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextContains("Keep this name")
        rule.onNodeWithText(identity).assertIsDisplayed()
        rule.onNodeWithText(relay).assertIsDisplayed()
        rule
            .onNodeWithText("That name is already taken")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun oversizedIdentityAndRelayAreClampedAndTruncateWithinTheirRow() {
        show(serverIdentity = oversizedIdentity, relayAddress = oversizedRelay, small = true)

        listOf(oversizedIdentity, oversizedRelay).forEach { raw ->
            val clamped = raw.take(MAX_WORKSPACE_LABEL_CHARS)
            valueNode(clamped).assertIsDisplayed()
            rule.onAllNodes(hasText(raw), useUnmergedTree = true).assertCountEquals(0)

            val bounds = valueNode(clamped).getUnclippedBoundsInRoot()
            assertWithin(bounds.right, "clamped value")
            assertSingleLine(bounds.height, "clamped value")
        }
    }

    @Test
    fun theClampNeverSplitsASurrogatePair() {
        val kept = "n".repeat(MAX_WORKSPACE_LABEL_CHARS - 1)
        hostName = kept + "😀" + "tail"
        show()

        rule
            .onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(kept)))
        rule.onNodeWithText("OK").performClick()
        rule.runOnIdle { assertEquals(listOf(kept), submitted) }
    }

    @Test
    fun smallViewportKeepsEveryControlReachableAndUnpairAtFortyEightDp() {
        show(small = true)

        listOf(identity, relay).forEach {
            rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performScrollTo().assertIsDisplayed()
        rule
            .onNodeWithText(string(R.string.edit_host_unpair))
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun enlargedTextAtCompactWidthKeepsLabelsValuesAndActionsReachable() {
        show(serverIdentity = oversizedIdentity, relayAddress = oversizedRelay, small = true, fontScale = 1.5f)

        listOf(
            string(R.string.edit_host_server_identity_label) to oversizedIdentity.take(MAX_WORKSPACE_LABEL_CHARS),
            string(R.string.edit_host_relay_address_label) to oversizedRelay.take(MAX_WORKSPACE_LABEL_CHARS),
        ).forEach { (label, value) ->
            val labelBounds = rule.onNodeWithText(label, useUnmergedTree = true).performScrollTo().getUnclippedBoundsInRoot()
            val valueBounds = valueNode(value).getUnclippedBoundsInRoot()
            assertNoTextOverflow(label)
            assertTrue("$label overlaps its value", labelBounds.right <= valueBounds.left)
            assertWithin(valueBounds.right, value)
            val layouts = mutableListOf<TextLayoutResult>()
            valueNode(value).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue("$label value did not ellipsize", layouts.single().isLineEllipsized(0))
        }
        assertNoTextOverflow(string(R.string.edit_host_title))
        rule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(string(R.string.edit_host_unpair)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(listOf(hostName), submitted) }
    }
}
