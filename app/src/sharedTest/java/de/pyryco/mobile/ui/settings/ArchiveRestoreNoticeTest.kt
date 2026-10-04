package de.pyryco.mobile.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveRestoreNoticeTest {
    @get:Rule val rule = createComposeRule()
    private val effects = Channel<ArchivedDiscussionsEffect>(Channel.BUFFERED)
    private val effectFlow = effects.receiveAsFlow()
    private val visible = mutableStateOf(true)
    private val host = mutableStateOf("Host")
    private val events = mutableListOf<ArchivedDiscussionsEvent>()
    private val timeoutCalls = mutableListOf<List<Any>>()
    private val row =
        Conversation(
            id = "archived",
            name = "Old channel",
            cwd = "~/old",
            currentSessionId = "session",
            sessionHistory = emptyList(),
            isPromoted = true,
            archived = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
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
                        if (visible.value) {
                            ArchivedDiscussionsScreen(
                                ArchivedDiscussionsUiState.Loaded(listOf(row), emptyList(), ArchiveTab.Channels),
                                onEvent = events::add,
                                effects = effectFlow,
                                hostName = host.value,
                            )
                        }
                    }
                }
            }
        }
        advance(64)
    }

    private fun advance(millis: Long) {
        rule.mainClock.advanceTimeBy(millis)
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun emit(effect: ArchivedDiscussionsEffect) {
        rule.runOnIdle { check(effects.trySend(effect).isSuccess) }
        advance(64)
    }

    private fun pill() = rule.onNodeWithTag("archive-restore-error")

    @Test fun failedRestoreOverlaysBodyWithoutMovingHostTabsRowsOrCoveringHeader() {
        setScreen()
        val tags = listOf("archive_header", "archive_tabs")
        val before = tags.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        val hostBefore = rule.onNodeWithText("Host").getUnclippedBoundsInRoot()
        val rowBefore = rule.onNodeWithText("Old channel").getUnclippedBoundsInRoot()
        rule.onNodeWithContentDescription("Restore Old channel").performClick()
        assertEquals(listOf(ArchivedDiscussionsEvent.RestoreRequested("archived", "Old channel")), events)
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        pill().assertIsDisplayed().assert(hasText(FAILURE)).assert(!hasClickAction())
        pill().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onAllNodes(hasText(FAILURE)).assertCountEquals(1)
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(0)
        rule.onNodeWithContentDescription("Dismiss notice").assertDoesNotExist()
        val notice = pill().getUnclippedBoundsInRoot()
        assertEquals(before[1].bottom.value + 28f, notice.top.value, 0.5f)
        assertEquals(before[1].right.value - 20f, notice.right.value, 1.5f)
        assertTrue(notice.left >= 20.dp)
        assertEquals(before, tags.map { rule.onNodeWithTag(it).getUnclippedBoundsInRoot() })
        assertEquals(hostBefore, rule.onNodeWithText("Host").getUnclippedBoundsInRoot())
        assertEquals(rowBefore, rule.onNodeWithText("Old channel").getUnclippedBoundsInRoot())
        rule.onNodeWithText("Discussions (0)").performTouchInput { click() }
        rule.onNodeWithText("Channels (1)").performTouchInput { click() }
        assertEquals(
            listOf(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Discussions), ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Channels)),
            events.takeLast(2),
        )
        rule.onNodeWithContentDescription("Back").performTouchInput { click() }
        assertEquals(ArchivedDiscussionsEvent.BackTapped, events.last())
    }

    @Test fun headerMeasurementFollowsHostLabelRemovalWithoutMovingTabs() {
        setScreen()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        rule.runOnIdle { host.value = "" }
        advance(64)
        val tabs = rule.onNodeWithTag("archive_tabs").getUnclippedBoundsInRoot()
        assertEquals(tabs.bottom.value + 28f, pill().getUnclippedBoundsInRoot().top.value, 0.5f)
        rule.onNodeWithText("Host").assertDoesNotExist()
    }

    @Test fun successfulRestoreKeepsSnackbarAndNeverShowsErrorPill() {
        setScreen()
        rule.onNodeWithContentDescription("Restore Old channel").performClick()
        emit(ArchivedDiscussionsEffect.RestoreSucceeded("Old channel"))
        advance(400)
        rule.onNodeWithText("Restored Old channel").assertIsDisplayed()
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).assertCountEquals(1)
        pill().assertDoesNotExist()
        advance(4_500)
        rule.onNodeWithText("Restored Old channel").assertDoesNotExist()
    }

    @Test fun shortExpiryDoesNotReplayOnRecompositionAndLaterFailureGetsFreshLifetime() {
        setScreen()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        advance(3_700)
        pill().assertIsDisplayed()
        advance(400)
        pill().assertDoesNotExist()
        rule.runOnIdle { host.value = "Renamed host" }
        advance(64)
        pill().assertDoesNotExist()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        pill().assertIsDisplayed()
        advance(4_100)
        pill().assertDoesNotExist()
    }

    @Test fun accessibilityExtendsShortLifetimeWithSnackbarFlags() {
        setScreen(adjustedTimeout = 8_000)
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        assertEquals(listOf(listOf(4_000L, true, true, false)), timeoutCalls)
        advance(4_100)
        pill().assertIsDisplayed()
        advance(4_000)
        pill().assertDoesNotExist()
    }

    @Test fun queuedIdenticalFailuresRemainObservableBeforeQueuedSuccess() {
        setScreen()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        val firstId = pill().fetchSemanticsNode().id
        rule.runOnIdle {
            check(effects.trySend(ArchivedDiscussionsEffect.RestoreFailed).isSuccess)
            check(effects.trySend(ArchivedDiscussionsEffect.RestoreSucceeded("Old channel")).isSuccess)
        }
        advance(3_700)
        assertEquals(firstId, pill().fetchSemanticsNode().id)
        advance(500)
        pill().assertIsDisplayed()
        assertTrue(firstId != pill().fetchSemanticsNode().id)
        rule.onNodeWithText("Restored Old channel").assertDoesNotExist()
        advance(3_500)
        pill().assertIsDisplayed()
        advance(700)
        pill().assertDoesNotExist()
        rule.onNodeWithText("Restored Old channel").assertIsDisplayed()
    }

    @Test fun leavingScreenCancelsActiveNotice() {
        setScreen()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        advance(1_000)
        rule.runOnIdle { visible.value = false }
        advance(64)
        pill().assertDoesNotExist()
        advance(4_100)
        rule.runOnIdle { visible.value = true }
        advance(64)
        pill().assertDoesNotExist()
        emit(ArchivedDiscussionsEffect.RestoreFailed)
        pill().assertIsDisplayed()
    }

    private companion object {
        const val FAILURE = "Couldn't restore this conversation. Try again."
    }
}
