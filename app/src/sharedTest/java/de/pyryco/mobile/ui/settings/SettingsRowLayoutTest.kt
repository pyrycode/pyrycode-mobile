package de.pyryco.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsRowLayoutTest {
    private var fixtureDensity = 1f

    @get:Rule val rule = createComposeRule()

    @Test fun normalScale_hasDesignHeightsTypographyAndAccessibleInteractions() {
        var clicks = 0
        var checked by mutableStateOf(false)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) {
                    fixtureDensity = LocalDensity.current.density
                    PyrycodeMobileTheme {
                        Column(Modifier.fillMaxWidth()) {
                            SettingsRow("Subtitle", "Supporting", onClick = { clicks++ })
                            SettingsRow("Single action", onClick = { clicks++ })
                            SettingsRow("Inert")
                            SettingsRow("Single switch", trailing = {
                                Switch(checked, { checked = it }, Modifier.testTag("switch"))
                            })
                            SettingsRow("Default YOLO", "off", trailing = { Switch(false, {}) })
                        }
                    }
                }
            }
        }
        for ((label, height) in listOf(
            "Subtitle" to 62,
            "Single action" to 48,
            "Inert" to 44,
            "Single switch" to 52,
            "Default YOLO" to 62,
        )) {
            val bounds = rule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertEquals("$label width", 412f * fixtureDensity, bounds.width, 1f)
            assertEquals("$label height", height * fixtureDensity, bounds.height, 1f)
        }
        val supporting = layout("Supporting")
        assertEquals(12.sp, supporting.layoutInput.style.fontSize)
        assertEquals(16.sp, supporting.layoutInput.style.lineHeight)
        assertEquals(1, supporting.lineCount)
        rule.onNodeWithText("Subtitle").performClick()
        rule.onNodeWithText("Single action").performClick()
        assertEquals(2, clicks)
        rule.onNodeWithText("Inert").assertHasNoClickAction()
        val toggle = rule.onNodeWithTag("switch")
        toggle
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assertIsOff()
            .performClick()
            .assertIsOn()
        // The switch draws a 32 dp track inside its expanded 48 dp touch target.
        val target = toggle.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("switch touch height: ${target.height}", target.height >= 48 * fixtureDensity - 1f)
        assertTrue("switch touch width: ${target.width}", target.width >= 48 * fixtureDensity - 1f)
    }

    @Test fun longText_wrapsAndGrowsWithoutOverlappingTheSwitch_atBothFontScales() {
        val headline = "A longer Settings or About headline that needs multiple lines"
        val subtitle = "Supporting text describing the setting with enough detail to wrap across several lines"
        var scale by mutableStateOf(1f)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(scale)) {
                    fixtureDensity = LocalDensity.current.density
                    PyrycodeMobileTheme {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            SettingsRow(headline, subtitle, trailing = { Switch(false, {}, Modifier.testTag("switch")) })
                            SettingsRow("Final row", onClick = {})
                        }
                    }
                }
            }
        }
        var previousHeight = 62f * fixtureDensity
        for (fontScale in listOf(1f, 2f)) {
            rule.runOnIdle { scale = fontScale }
            val row =
                rule
                    .onNodeWithText(headline)
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .boundsInRoot
            assertTrue("row grows at $fontScale", row.height > previousHeight)
            previousHeight = row.height
            val titleBounds = rule.onNodeWithText(headline, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val subtitleBounds = rule.onNodeWithText(subtitle, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val switchBounds = rule.onNodeWithTag("switch").fetchSemanticsNode().boundsInRoot
            for (text in listOf(headline, subtitle)) {
                val result = layout(text)
                assertTrue(result.lineCount > 1)
                assertFalse(result.hasVisualOverflow)
            }
            assertTrue(titleBounds.top >= row.top)
            assertTrue(titleBounds.bottom <= subtitleBounds.top)
            assertTrue(subtitleBounds.bottom <= row.bottom)
            assertTrue(titleBounds.right <= switchBounds.left)
            assertTrue(subtitleBounds.right <= switchBounds.left)
            assertTrue(switchBounds.top >= row.top && switchBounds.bottom <= row.bottom)
            rule.onNodeWithText("Final row").performScrollTo().assertIsDisplayed()
        }
    }

    private fun layout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNode(hasText(text), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }
}
