package de.pyryco.mobile.ui.conversations.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun channel(id: String, name: String): Conversation = Conversation(
        id = id,
        name = name,
        cwd = "/tmp",
        currentSessionId = "$id-s",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = Instant.fromEpochSeconds(0),
    )

    private fun loaded(vararg channels: Conversation): ChannelListUiState.Loaded =
        ChannelListUiState.Loaded(
            channels = channels.toList(),
            recentDiscussions = emptyList(),
            recentDiscussionsCount = 0,
        )

    private fun string(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Test
    fun topAppBar_rendersTitleAndSettingsAction_whenLoaded() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(channel("c1", "alpha")),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNode(hasText(string(R.string.app_name))).assertIsDisplayed()
        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
            .assertIsDisplayed()
    }

    @Test
    fun channelList_rendersEachChannelName_whenLoaded() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(channel("c1", "alpha"), channel("c2", "bravo")),
                    onEvent = {},
                )
            }
        }

        composeTestRule.onNode(hasText("alpha")).assertExists()
        composeTestRule.onNode(hasText("bravo")).assertExists()
    }

    @Test
    fun channelRow_emitsRowTappedWithId() {
        val events = mutableListOf<ChannelListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(channel("c1", "alpha"), channel("c2", "bravo")),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule.onNode(hasText("alpha")).performClick()

        assertEquals(listOf(ChannelListEvent.RowTapped("c1")), events)
    }

    @Test
    fun fab_emitsCreateDiscussionTapped() {
        val events = mutableListOf<ChannelListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(channel("c1", "alpha")),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_new_discussion)))
            .performClick()

        assertEquals(listOf(ChannelListEvent.CreateDiscussionTapped), events)
    }

    @Test
    fun settingsGear_emitsSettingsTapped() {
        val events = mutableListOf<ChannelListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(channel("c1", "alpha")),
                    onEvent = events::add,
                )
            }
        }

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
            .performClick()

        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    @Test
    fun emptyState_rendersPlaceholder_whenEmpty() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = ChannelListUiState.Empty(
                        recentDiscussions = emptyList(),
                        recentDiscussionsCount = 0,
                    ),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText(string(R.string.channel_list_empty)))
            .assertIsDisplayed()
    }
}
