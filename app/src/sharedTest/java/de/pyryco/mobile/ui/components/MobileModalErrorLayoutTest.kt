package de.pyryco.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// No editable fields: a wider Robolectric dialog containing them cannot reliably become idle.
@Config(qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class MobileModalErrorLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun createdChannelPromptError_keepsFullLineBoxAndCenteredGroup() =
        assertErrorLayout(R.string.create_channel_prompt_failed, "Create channel")

    @Test
    fun savedChannelPromptError_keepsFullLineBoxAndCenteredGroup() =
        assertErrorLayout(R.string.save_as_channel_prompt_failed, "Save as channel")

    private fun assertErrorLayout(
        errorResource: Int,
        title: String,
    ) {
        var error = ""
        var errorColor = Color.Unspecified
        var density = 0f
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) {
                    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                        error = stringResource(errorResource)
                        errorColor = MaterialTheme.colorScheme.error
                        MobileModal(
                            title = title,
                            onDismissRequest = {},
                            onSubmit = {},
                            error = error,
                            modifier = Modifier.requiredSize(412.dp, 892.dp).testTag("shell"),
                        ) {
                            density = LocalDensity.current.density
                            assertEquals(1f, LocalDensity.current.fontScale, 0f)
                            Box(Modifier.fillMaxWidth().height(80.dp).testTag("name"))
                            Box(Modifier.fillMaxWidth().height(140.dp).testTag("prompt"))
                        }
                    }
                }
            }
        }

        val node = rule.onNodeWithText(error).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertEquals(2, layout.lineCount)
        assertEquals(40f, bounds.height / density, 2f)
        assertEquals(14.sp, layout.layoutInput.style.fontSize)
        assertEquals(20.sp, layout.layoutInput.style.lineHeight)
        assertEquals(FontWeight.Normal, layout.layoutInput.style.fontWeight)
        assertEquals(0.25.sp, layout.layoutInput.style.letterSpacing)
        assertEquals(errorColor, layout.layoutInput.style.color)
        assertEquals(
            LineHeightStyle.Trim.None,
            layout.layoutInput.style.lineHeightStyle
                ?.trim,
        )
        assertEquals(
            LineHeightStyle.Alignment.Center,
            layout.layoutInput.style.lineHeightStyle
                ?.alignment,
        )
        val semantics = node.fetchSemanticsNode().config
        assertEquals(error, semantics[SemanticsProperties.Error])
        assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])

        val shell = rule.onNodeWithTag("shell").fetchSemanticsNode().boundsInRoot
        assertEquals(412f, shell.width / density, 1f)
        assertEquals(892f, shell.height / density, 1f)
        assertEquals(356f, bounds.width / density, 1f)
        val name = rule.onNodeWithTag("name").fetchSemanticsNode().boundsInRoot
        val prompt = rule.onNodeWithTag("prompt").fetchSemanticsNode().boundsInRoot
        assertEquals(12f, (bounds.top - prompt.bottom) / density, 1f)
        // Header row + gap + divider + header padding + shell gap; footer owns a 48 dp hit box.
        val slotTop =
            rule
                .onNodeWithContentDescription("Close", useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot.top + 65f * density
        val slotBottom =
            rule
                .onNodeWithText("Cancel")
                .fetchSemanticsNode()
                .boundsInRoot.top - 20f * density
        assertEquals(
            "slot=$slotTop..$slotBottom group=${name.top}..${bounds.bottom}",
            (slotTop + slotBottom) / 2,
            (name.top + bounds.bottom) / 2,
            2f * density,
        )
    }
}
