package de.pyryco.mobile.ui.conversations.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateChatFailureNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val state = mutableStateOf(HostChannelListState(hosts = listOf(host())))
    private val visible = mutableStateOf(true)
    private val events = mutableListOf<ChannelListEvent>()
    private val timeoutCalls = mutableListOf<List<Any>>()

    private fun host() =
        HostChannelListEntry(
            host =
                HostConversationSnapshot(
                    serverId = "host",
                    displayName = "Host",
                    connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                    channels = emptyList(),
                    chats = emptyList(),
                ),
            recentChats = emptyList(),
            chatCount = 0,
            channelGroups = emptyList(),
            chatGroups = emptyList(),
        )

    private fun setScreen(adjustedTimeout: Long? = null) {
        rule.mainClock.autoAdvance = false
        val accessibility =
            adjustedTimeout?.let { timeout ->
                object : AccessibilityManager {
                    override fun calculateRecommendedTimeoutMillis(
                        originalTimeoutMillis: Long,
                        containsIcons: Boolean,
                        containsText: Boolean,
                        containsControls: Boolean,
                    ): Long {
                        timeoutCalls += listOf(originalTimeoutMillis, containsIcons, containsText, containsControls)
                        return timeout
                    }
                }
            }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                    PyrycodeMobileTheme {
                        if (visible.value) ChannelListScreen(state.value, events::add)
                    }
                }
            }
        }
        advance(64)
    }

    private fun fail(request: Long = 1) {
        rule.runOnIdle { state.value = state.value.copy(createChat = CreateChatState("host", request, saving = false, failed = true)) }
        rule.waitUntil(5_000) {
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            rule.onAllNodesWithTag("channel-list-create-chat-error").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun pill() = rule.onNodeWithTag("channel-list-create-chat-error")

    @Test fun failureOverlaysTreeBelowMeasuredHeaderWithoutSnackbarOrAction() {
        setScreen()
        pill().assertDoesNotExist()
        val treeBefore = rule.onNodeWithContentDescription("New chat on Host").getUnclippedBoundsInRoot()
        fail()
        pill().assertIsDisplayed().assert(!hasClickAction())
        rule.onAllNodes(hasContentDescription("Dismiss notice")).assertCountEquals(0)
        rule.onAllNodes(hasText("Couldn’t create the chat. Try again.")).assertCountEquals(1)
        val notice = pill().getUnclippedBoundsInRoot()
        val header = rule.onNodeWithTag("channel-list-header").getUnclippedBoundsInRoot()
        assertEquals(header.bottom.value + 28f, notice.top.value, 0.5f)
        assertEquals(header.right.value - 20f, notice.right.value, 1.5f)
        assertTrue(notice.left >= 20.dp)
        assertTrue(notice.right - notice.left < 372.dp)
        assertEquals(treeBefore, rule.onNodeWithContentDescription("New chat on Host").getUnclippedBoundsInRoot())
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(0)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        listOf("Settings", "Archive").forEach { label ->
            rule.onNodeWithContentDescription("Open menu").performClick()
            advance(64)
            rule.onNode(hasText(label)).performClick()
            advance(64)
        }
        rule.onNodeWithContentDescription("Pair another host").performTouchInput { click() }
        assertEquals(listOf(ChannelListEvent.SettingsTapped, ChannelListEvent.ArchiveTapped, ChannelListEvent.PairHostTapped), events)
    }

    @Test fun shortTimeoutExpiresAndRecompositionDoesNotReplayButNewRequestDoes() {
        setScreen()
        fail()
        advance(3_800)
        pill().assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(selected = HostConversationTarget("host", "first")) }
        advance(300)
        pill().assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(selected = HostConversationTarget("host", "other")) }
        advance(64)
        pill().assertDoesNotExist()
        fail(2)
        pill().assertIsDisplayed()
        advance(4_100)
        pill().assertDoesNotExist()
    }

    @Test fun accessibilityAdjustsShortTimeoutWithSnackbarFlags() {
        setScreen(adjustedTimeout = 8_000)
        fail()
        assertEquals(listOf(listOf(4_000L, true, true, false)), timeoutCalls)
        advance(4_100)
        pill().assertIsDisplayed()
        advance(4_000)
        pill().assertDoesNotExist()
    }

    @Test fun newRequestCancelsPriorTimerAndLeavingScreenCancelsNotice() {
        setScreen()
        fail()
        advance(3_000)
        fail(2)
        advance(1_100)
        pill().assertIsDisplayed()
        rule.runOnIdle { visible.value = false }
        advance(64)
        pill().assertDoesNotExist()
        advance(4_100)
        rule.runOnIdle {
            state.value = state.value.copy(createChat = null)
            visible.value = true
        }
        advance(64)
        pill().assertDoesNotExist()
        fail(3)
        pill().assertIsDisplayed()
    }
}
