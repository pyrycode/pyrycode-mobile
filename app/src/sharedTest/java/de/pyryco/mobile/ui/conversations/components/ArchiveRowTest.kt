package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
class ArchiveRowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun subtitle_readsTheArchiveTime_notTheLastUse() {
        val now = Clock.System.now()
        val conversation =
            Conversation(
                id = "archived",
                name = "old-project",
                cwd = DEFAULT_SCRATCH_CWD,
                currentSessionId = "",
                sessionHistory = emptyList(),
                isPromoted = false,
                lastUsedAt = now - 90.days,
                archived = true,
                archivedAt = now - 8.days,
            )
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ArchiveRow(conversation = conversation, displayName = "old-project", onRestore = {})
            }
        }

        compose.onNodeWithText("Archived 1 week ago").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Archived 3 months ago").fetchSemanticsNodes().size)
    }
}
