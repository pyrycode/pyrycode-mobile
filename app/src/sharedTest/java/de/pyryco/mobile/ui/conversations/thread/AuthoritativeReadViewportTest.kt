package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoritativeReadViewportTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableListOf<ThreadEvent.NewestContentPresented>()
    private lateinit var list: LazyListState
    private val state = mutableStateOf(ThreadUiState("c", "Thread", hasMessages = true, readUpTo = 0u))
    private val owner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.STARTED }
            override val lifecycle: Lifecycle = registry
        }

    @Test fun partialCatchUpRequiresCompletionForegroundAndAnUncoveredView() {
        val snapshot = authoritativeSnapshot(authoritativeItem(1, rev = 8)).copy(checkpoint = 5)
        state.value = state.value.copy(authoritativeThread = projectAuthoritativeThread("h", "c", snapshot), readMarks = facts(8u))
        mount()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state.value = state.value.copy(showRenameDialog = true)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            state.value =
                state.value.copy(authoritativeThread = projectAuthoritativeThread("h", "c", snapshot.copy(checkpoint = 8)))
        }
        settle()
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state.value = state.value.copy(showRenameDialog = false)
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 1 }
        compose.runOnIdle {
            assertEquals(8uL, events.single().checkpoint)
            assertNotNull(events.single().binding)
            state.value = state.value.copy(displayName = "Unrelated recomposition")
        }
        settle()
        compose.runOnIdle { assertEquals(1, events.size) }
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            state.value =
                state.value.copy(
                    authoritativeThread =
                        projectAuthoritativeThread(
                            "h",
                            "c",
                            authoritativeSnapshot(
                                authoritativeItem(1, rev = 9, content = """{"text":"new unseen text"}"""),
                            ).copy(checkpoint = 9),
                        ),
                    readMarks = facts(9u),
                )
        }
        settle()
        compose.runOnIdle {
            assertEquals(1, events.size)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle { assertEquals(9uL, events.last().checkpoint) }
    }

    @Test fun scrolledAwayAppendAndUnavailableSummaryCannotQualify() {
        val items = (1L..30L).map { authoritativeItem(it) }
        val snapshot = authoritativeSnapshot(*items.toTypedArray()).copy(checkpoint = 30)
        owner.registry.currentState = Lifecycle.State.RESUMED
        state.value =
            state.value.copy(
                authoritativeThread = projectAuthoritativeThread("h", "c", snapshot),
                readMarks = facts(30u).copy(readUpTo = 29u),
            )
        mount()
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    authoritativeThread =
                        projectAuthoritativeThread(
                            "h",
                            "c",
                            snapshot.copy(
                                items =
                                    items.dropLast(1) + authoritativeItem(30, rev = 31),
                                checkpoint = 31,
                            ),
                        ),
                    readMarks = facts(31u).copy(readUpTo = 30u),
                )
        }
        settle()
        compose.runOnIdle {
            assertEquals(1, events.size)
            state.value = state.value.copy(readMarks = facts(null).copy(readUpTo = 30u))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        settle()
        compose.runOnIdle {
            assertEquals(1, events.size)
            state.value = state.value.copy(readMarks = facts(31u).copy(readUpTo = 30u))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle { assertEquals(31uL, events.last().checkpoint) }
    }

    @Test fun offscreenIntermediateAppendRequiresItsOwnSightBeforeNewestCheckpoint() {
        val items =
            (1L..30L).map {
                authoritativeItem(
                    it,
                    rev =
                        when (it) {
                            1L -> 6
                            30L -> 8
                            else -> 5
                        },
                )
            }
        val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(*items.toTypedArray()).copy(checkpoint = 8))
        owner.registry.currentState = Lifecycle.State.RESUMED
        state.value = state.value.copy(authoritativeThread = p, readMarks = facts(8u).copy(readUpTo = 5u))
        mount()
        settle()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        settle()
        compose.runOnIdle { assertTrue(events.isEmpty()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitUntil(5000) { events.size == 1 }
        compose.runOnIdle { assertEquals(8uL, events.single().checkpoint) }
    }

    @Test fun equalBannersMustNotShareNewestSight() {
        val content = """{"text":"same warning","level":"warning"}"""
        val items =
            listOf(authoritativeItem(1, kind = "notice", subtype = "banner", rev = 6, content = content)) +
                (2L..29L).map { authoritativeItem(it, rev = 5) } +
                authoritativeItem(30, kind = "notice", subtype = "banner", rev = 8, content = content)
        val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(*items.toTypedArray()).copy(checkpoint = 8))
        assertEquals(p.items.first(), p.items.last())
        state.value = state.value.copy(authoritativeThread = p, readMarks = facts(8u).copy(readUpTo = 5u))
        mount()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(29)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
        compose.runOnIdle {
            assertTrue(list.layoutInfo.visibleItemsInfo.any { it.key == p.rows.first().listKey(0) })
            assertTrue(list.layoutInfo.visibleItemsInfo.none { it.key == p.rows.last().listKey(0) })
            assertTrue(events.isEmpty())
        }
    }

    @Test fun equalBannerSiblingsCarryNewestKeyAndExactSightAfterHostBecomesAvailable() {
        val content = """{"text":"same warning","level":"warning"}"""
        val p =
            projectAuthoritativeThread(
                "h",
                "c",
                authoritativeSnapshot(
                    authoritativeItem(1, kind = "notice", subtype = "banner", rev = 6, content = content),
                    authoritativeItem(2, kind = "notice", subtype = "banner", rev = 8, content = content),
                ).copy(checkpoint = 8),
            )
        owner.registry.currentState = Lifecycle.State.RESUMED
        state.value = state.value.copy(authoritativeThread = p, readMarks = facts(8u).copy(readUpTo = 5u), hostAvailable = false)
        mount()
        settle()
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state.value = state.value.copy(hostAvailable = true)
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 1 }
        compose.runOnIdle {
            assertEquals(8uL, events.single().checkpoint)
            assertEquals(p.rows.last().listKey(0), events.single().rowKey)
            assertEquals(p.shownRowRevisions, events.single().seenRowRevisions)
        }
    }

    @Test fun sendingTailAllowsViewedHistoryAndReplyWhileFeedbackRemains() = readWithTail("sending")

    @Test fun waitingTailAllowsViewedHistoryAndReplyWhileFeedbackRemains() = readWithTail("queued")

    @Test fun lostTailAllowsViewedHistoryAndReplyWhileFeedbackRemains() = readWithTail("lost")

    private fun readWithTail(status: String) {
        val snapshot = authoritativeSendTail(status)
        val p = projectAuthoritativeThread("h", "c", snapshot)
        val tailKey = p.rows.last().listKey(0)
        val tailOnly = projectAuthoritativeThread("h", "c", snapshot.copy(items = snapshot.items.filter { it.order == null }))
        owner.registry.currentState = Lifecycle.State.RESUMED
        state.value = state.value.copy(authoritativeThread = tailOnly, readMarks = facts(4u))
        mount()
        settle()
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            state.value = state.value.copy(authoritativeThread = p)
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 1 }
        compose.runOnIdle {
            assertEquals(4uL, events.single().checkpoint)
            assertEquals(p.rows.first().listKey(0), events.single().rowKey)
            assertEquals(p.shownRowRevisions, events.single().seenRowRevisions)
        }

        val reply =
            projectAuthoritativeThread(
                "h",
                "c",
                snapshot.copy(items = snapshot.items + authoritativeItem(3, rev = 5), checkpoint = 5),
            )
        compose.runOnIdle {
            assertEquals(tailKey, reply.rows.last().listKey(0))
            state.value = state.value.copy(authoritativeThread = reply, readMarks = facts(5u).copy(readUpTo = 4u))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle {
            assertEquals(listOf(4uL, 5uL), events.map { it.checkpoint })
            assertEquals(reply, events.last().presentation)
            assertEquals(
                reply.rows
                    .filterIsInstance<ThreadRow.Delivered>()
                    .last()
                    .listKey(0),
                events.last().rowKey,
            )
            assertEquals(reply.shownRowRevisions, events.last().seenRowRevisions)
            assertTrue(events.all { tailKey !in it.seenRowRevisions })
        }
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalThreadListCompositionObserver provides { list = it }) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(state.value, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                        if (it is ThreadEvent.NewestContentPresented) {
                            events +=
                                it
                        }
                    })
                }
            }
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun facts(shown: ULong?) = ConversationReadMarks(0u, 100u, shown, true)
}
