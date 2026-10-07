package de.pyryco.mobile.ui.conversations.thread

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
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadReadViewportTest {
    @get:Rule val compose = createComposeRule()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test fun foregroundCheckpoint_requiresResumedDestinationAndDoesNotRepeat() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.STARTED }
        val state = mutableStateOf(state())
        val events = mutableListOf<ThreadEvent.NewestContentPresented>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PyrycodeMobileTheme {
                    ThreadScreen(state.value, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                        if (it is ThreadEvent.NewestContentPresented) {
                            events +=
                                it
                        }
                    })
                }
            }
        }
        compose.runOnIdle {
            assertTrue(events.isEmpty())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil(5000) { events.size == 1 }

        compose.runOnIdle { state.value = state.value.copy(displayName = "Recomposition") }
        compose.runOnIdle {
            assertEquals(1, events.size)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        compose.runOnIdle {
            val newest = row(31)
            state.value = state.value.copy(items = state.value.items + newest, readEvidence = evidence(newest, 41u))
        }
        compose.runOnIdle { assertEquals(1, events.size) }
    }

    @Test fun foregroundCheckpoint_excludesScrolledAwayRowUpdates() {
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val state = mutableStateOf(state())
        val events = mutableListOf<ThreadEvent.NewestContentPresented>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PyrycodeMobileTheme {
                    ThreadScreen(state.value, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                        if (it is ThreadEvent.NewestContentPresented) {
                            events +=
                                it
                        }
                    })
                }
            }
        }
        compose.waitUntil(5000) { events.size == 1 }

        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.runOnIdle {
            val held = state.value.items.last() as ThreadItem.MessageItem
            val updated = ThreadItem.MessageItem(held.message.copy(content = "Unseen updated content."))
            state.value = state.value.copy(items = state.value.items.dropLast(1) + updated, readEvidence = evidence(updated, 41u))
        }
        compose.runOnIdle { assertEquals(1, events.size) }
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
            readEvidence = evidence(rows.last(), 40u),
        )
    }

    private fun evidence(
        row: ThreadItem,
        id: ULong,
    ) = ThreadReadEvidence(versions = mapOf(row to setOf(id)), facts = mapOf(id to false))

    private fun row(n: Int): ThreadItem =
        ThreadItem.MessageItem(Message("m$n", "", Role.Assistant, "Row $n.", Instant.parse("2026-10-07T00:00:00Z"), isStreaming = false))
}
