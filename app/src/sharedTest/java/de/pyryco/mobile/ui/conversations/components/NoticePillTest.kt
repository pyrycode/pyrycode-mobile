package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticePillTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bothVariantsRetainFullSingleLineBox() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                PyrycodeMobileTheme {
                    Column {
                        NoticePill("Default", isError = false, onDismiss = {})
                        NoticePill("Error", isError = true)
                    }
                }
            }
        }
        listOf("Default", "Error").forEach { label ->
            val text = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
            val pill = rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertEquals("$label text line", 16f, text.height.value, 0.01f)
            assertEquals("$label background", 24f, pill.height.value, 0.01f)
            assertEquals(4f, (text.top - pill.top).value, 0.01f)
            assertEquals(4f, (pill.bottom - text.bottom).value, 0.01f)
        }
    }

    @Test fun largeTextWrapsToTheLimitAndActionsStillRoute() {
        var clicked = 0
        var dismissed = 0
        val label = "Long notice that wraps across several lines of text"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                PyrycodeMobileTheme {
                    Column {
                        NoticePill(label, false, Modifier.width(120.dp), maxLines = 2, onDismiss = { dismissed++ })
                        NoticePill("Error", true, onClick = { clicked++ })
                    }
                }
            }
        }
        val wrapped = rule.onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(48f, wrapped.height.value, 0.01f)
        assertEquals(
            56f,
            rule
                .onNodeWithContentDescription(label)
                .getUnclippedBoundsInRoot()
                .height.value,
            0.01f,
        )
        assertEquals(
            24f,
            rule
                .onNodeWithText("Error", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .height.value,
            0.01f,
        )
        rule.onNodeWithContentDescription("Dismiss notice").performClick()
        rule.onNodeWithContentDescription("Error").performClick()
        assertEquals(1, dismissed)
        assertEquals(1, clicked)
    }
}
