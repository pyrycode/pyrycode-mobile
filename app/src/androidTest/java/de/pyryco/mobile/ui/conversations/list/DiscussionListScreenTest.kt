package de.pyryco.mobile.ui.conversations.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscussionListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun discussion(
        id: String,
        name: String? = null,
    ): Conversation =
        Conversation(
            id = id,
            name = name,
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "$id-s",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.fromEpochSeconds(0),
        )

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun dialog_appears_whenPendingPromotionIsNonNull() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", "alpha")),
                            pendingPromotion = PendingPromotion("d1", "alpha"),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(string(R.string.promote_dialog_title))
            .assertIsDisplayed()
        composeTestRule
            .onNode(hasText("alpha", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun dialog_doesNotAppear_whenPendingPromotionIsNull() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", "alpha")),
                            pendingPromotion = null,
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText(string(R.string.promote_dialog_title))
            .assertDoesNotExist()
    }

    @Test
    fun confirmButton_emitsPromoteConfirmed() {
        val events = mutableListOf<DiscussionListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", "alpha")),
                            pendingPromotion = PendingPromotion("d1", "alpha"),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNodeWithText(string(R.string.promote_dialog_confirm))
            .performClick()

        assertEquals(listOf<DiscussionListEvent>(DiscussionListEvent.PromoteConfirmed), events)
    }

    @Test
    fun cancelButton_emitsPromoteCancelled() {
        val events = mutableListOf<DiscussionListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", "alpha")),
                            pendingPromotion = PendingPromotion("d1", "alpha"),
                        ),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNodeWithText(string(R.string.promote_dialog_cancel))
            .performClick()

        assertEquals(listOf<DiscussionListEvent>(DiscussionListEvent.PromoteCancelled), events)
    }

    @Test
    fun dialogBody_usesSourceName_whenPresent() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", "ad-hoc kotlin question")),
                            pendingPromotion = PendingPromotion("d1", "ad-hoc kotlin question"),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("ad-hoc kotlin question", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun dialogBody_usesUntitledDiscussionFallback_whenSourceNameIsNull() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                DiscussionListScreen(
                    state =
                        DiscussionListUiState.Loaded(
                            discussions = listOf(discussion("d1", null)),
                            pendingPromotion = PendingPromotion("d1", null),
                        ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText("Untitled discussion", substring = true))
            .assertIsDisplayed()
    }
}
