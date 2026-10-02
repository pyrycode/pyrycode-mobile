package de.pyryco.mobile.ui.conversations.thread

import android.view.inspector.WindowInspector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// #1496: the 568:876 frames run the sheet to the screen's bottom edge with no footer row.
@Config(qualifiers = "w412dp-h892dp")
@RunWith(AndroidJUnit4::class)
class BackgroundTaskPanelInsetsTest {
    @get:Rule
    val rule = createComposeRule()

    private var navigationBottom = 0f

    private val roster = mutableStateOf<BackgroundTaskRoster?>(null)

    private fun setPanel() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                BackgroundTaskPanel(roster = roster.value, onDismiss = {})
            }
        }
        // Robolectric reports no system bars, so fixed ones are applied to every window, the dialog's included.
        // Before #1496 the sheet stopped at this inset, so the bounds below also prove Compose received it.
        rule.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews()
            val density =
                views
                    .last()
                    .resources.displayMetrics.density
            navigationBottom = 48 * density
            val bars =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (40 * density).toInt(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (48 * density).toInt()))
                    .build()
            views.forEach { ViewCompat.dispatchApplyWindowInsets(it, bars) }
        }
        rule.waitForIdle()
    }

    private fun dialogBottom() =
        rule
            .onNode(isDialog())
            .fetchSemanticsNode()
            .boundsInRoot.bottom

    private fun assertAboveNavigationBar(text: String) {
        val bottom =
            rule
                .onNodeWithText(text)
                .performScrollTo()
                .fetchSemanticsNode()
                .boundsInRoot.bottom
        val limit = dialogBottom() - navigationBottom
        assertTrue("$text bottom $bottom below navigation bar limit $limit", bottom <= limit + 1)
    }

    private fun assertSheetReachesBottomWithoutFooter() {
        val sheet = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).fetchSemanticsNode()
        assertEquals("sheet bottom", dialogBottom(), sheet.boundsInRoot.bottom, 1f)
        rule.onNode(hasText("Close") and hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun populatedAndCapped_runToBottomEdgeAndKeepCardsAboveNavigationBar() {
        roster.value = BackgroundTaskRoster((1..14).map(::task), droppedTasks = 0)
        setPanel()
        assertSheetReachesBottomWithoutFooter()
        assertAboveNavigationBar("task 14")

        rule.runOnIdle { roster.value = BackgroundTaskRoster((1..14).map(::task), droppedTasks = 3) }
        assertSheetReachesBottomWithoutFooter()
        assertAboveNavigationBar("Partial list (3 not shown)")
        assertAboveNavigationBar("task 14")
    }

    @Test
    fun emptyAndNeverReported_runToBottomEdgeAndKeepTextAboveNavigationBar() {
        roster.value = BackgroundTaskRoster(emptyList(), droppedTasks = 0)
        setPanel()
        assertSheetReachesBottomWithoutFooter()
        assertAboveNavigationBar("Claude has nothing running in the background for this conversation.")

        rule.runOnIdle { roster.value = null }
        assertSheetReachesBottomWithoutFooter()
        assertAboveNavigationBar("The daemon has not reported on this conversation since the app connected.")
    }

    private fun task(n: Int) = BackgroundTask("t$n", "toolu_$n", "local_bash", "task $n", null, null, null, false)
}
