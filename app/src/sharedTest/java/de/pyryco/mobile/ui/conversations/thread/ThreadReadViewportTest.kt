package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class ThreadReadViewportTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test open fun scrolling_doesNotRecomposeProductionListHost() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val rows = (1..60).map(::row)
        val fixedState = state().copy(items = rows)
        val events = mutableListOf<ThreadEvent.NewestContentPresented>()
        var hostCompositions = 0
        var list: LazyListState? = null
        val observer: (LazyListState) -> Unit = {
            hostCompositions++
            list = it
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalThreadListCompositionObserver provides observer) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(fixedState, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                        if (it is ThreadEvent.NewestContentPresented) events += it
                    })
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        var before = 0
        var newestOffset = 0
        compose.runOnIdle {
            val current = requireNotNull(list)
            assertEquals(60, current.layoutInfo.totalItemsCount)
            newestOffset =
                current.layoutInfo.visibleItemsInfo
                    .first { it.index == 0 }
                    .offset
            before = hostCompositions
            assertTrue("scroll consumes nonzero distance", current.dispatchRawDelta(20f) > 0f)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val newest = requireNotNull(list).layoutInfo.visibleItemsInfo.first { it.index == 0 }
            assertTrue("newest row moved within view", newest.offset != newestOffset)
            assertEquals("moving candidate must not recompose list host", before, hostCompositions)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        compose.waitForIdle()
        compose.runOnIdle {
            val current = requireNotNull(list)
            assertTrue("scroll reached older rows", current.firstVisibleItemIndex >= 35)
            assertFalse("newest is offscreen", current.layoutInfo.visibleItemsInfo.any { it.index == 0 })
            assertEquals("laying out older rows must not recompose list host", before, hostCompositions)
            assertTrue(events.isEmpty())
        }
    }

    @Test fun viewportEdgeExcludesComposerAndImeAndAllowsTallRowTrailingEdge() {
        assertTrue(qualifiesReadEdge(400f, 0f, 600f, 69f, 160f))
        assertFalse(qualifiesReadEdge(450f, 0f, 600f, 69f, 160f))
        assertFalse(qualifiesReadEdge(350f, 0f, 480f, 69f, 160f))
        assertFalse(qualifiesReadEdge(20f, 0f, 600f, 69f, 160f))
    }

    private fun state(): ThreadUiState {
        val rows = (1..30).map(::row)
        return ThreadUiState(
            "c",
            "Read viewport",
            hasMessages = true,
            items = rows,
            readUpTo = 0u,
        )
    }

    private fun row(n: Int): ThreadItem =
        ThreadItem.MessageItem(Message("m$n", "", Role.Assistant, "Row $n.", Instant.parse("2026-10-07T00:00:00Z"), isStreaming = false))
}
