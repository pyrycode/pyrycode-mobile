package de.pyryco.mobile.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class EditHostModalTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissals = 0
    private var unpairs = 0
    private val submitted = mutableListOf<String>()
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)

    // The frame's own sample values, so a reader can line the suite up against the design.
    private val identity = "345345-345345345-gw3vw-w4wv34-vw34t"
    private val relay = "https://asdf.afwevawef.fwef/asdffe"
    private val hostName = "Pyrybox"

    // Well past MAX_WORKSPACE_LABEL_CHARS, and different characters per field so the two clamped
    // values can never satisfy one another's assertion.
    private val oversizedIdentity = "z".repeat(4000)
    private val oversizedRelay = "y".repeat(4000)

    // The viewport the shell's own overflow case uses, and the one AC5 names.
    private val smallSize = DpSize(320.dp, 640.dp)

    // One bodyMedium line is 20sp, so two lines of it clear this comfortably.
    private val singleLineCeiling = 28.dp

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun show(
        serverIdentity: String = identity,
        relayAddress: String = relay,
        small: Boolean = false,
    ) {
        rule.setContent {
            PyrycodeMobileTheme {
                if (small) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(smallSize)) {
                        Modal(serverIdentity, relayAddress, Modifier.size(smallSize))
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
            modifier = modifier,
            loading = loading.value,
            error = error.value,
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
}
