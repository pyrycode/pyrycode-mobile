package de.pyryco.mobile.design

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the harness itself: compact large-text viewport, real bars, the Koin override and the menu helper. */
@RunWith(AndroidJUnit4::class)
class DesignHarnessSmokeTest {
    @get:Rule(order = 0) val viewport = ViewportRule()

    @get:Rule(order = 1) val rule = createEmptyComposeRule()

    @get:Rule(order = 2) val design = DesignCapture(rule)

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun compactLargeTextLaunchCapturesRealFrame() {
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").assertIsDisplayed()
        assertEquals(1.5f, design.view.resources.configuration.fontScale, 0.01f)
        assertEquals(320, design.view.resources.displayMetrics.widthPixels)
        design.capture("smoke", "welcome-320x700-1.5x", "6:32")
    }

    @Test fun everyThreadInputReachesTheThreadViewModel() {
        design.paired = true
        design.inputs.connectionState.value = ConnectionState.Offline
        design.inputs.backgroundTaskCount.value = 2
        design.launch()
        rule.onNodeWithText("Pyrycode Mobile").performScrollTo().performClick()
        rule.waitUntil(5_000) { design.inputs.thread.value != null }
        val inputs = design.inputs.threadInputs
        rule.waitUntil(5_000) { inputs.values.all { it.subscriptionCount.value > 0 } }
        val thread = checkNotNull(design.inputs.thread.value)
        rule.waitUntil(5_000) { thread.state.value.backgroundTaskCount == 2 }
        rule.waitUntil(5_000) { thread.connectionState.value == ConnectionState.Offline }
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        design.capture("smoke", "thread-offline-tasks-menu", "none")
    }
}
