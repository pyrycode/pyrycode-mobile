package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadReadEvidence
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadReadCandidateTest {
    @get:Rule val compose =
        androidx.compose.ui.test.junit4
            .createComposeRule()

    private val events = mutableListOf<ThreadEvent.NewestContentPresented>()
    private val active = mutableStateOf<ThreadReadCandidate?>(null)
    private val state = mutableStateOf(ThreadUiState("c", "Read candidate"))

    @Test fun replacementVersion_requiresItsOwnLayoutEdgeAndReveal() {
        val retired = candidate("original")
        mount(retired, 1u)
        present(retired)
        compose.waitUntil(5000) { events.size == 1 }
        val replacement = candidate("replacement")
        replace(replacement, 2u)
        // These are the exact closures that a removed modifier / reveal job could still hold.
        val oldLayout = { retired.laidOut = true }
        val oldEdge = { retired.trailingEdge = 400f }
        val oldReveal = { retired.revealed = true }
        compose.runOnIdle {
            oldLayout()
            oldEdge()
            oldReveal()
        }
        assertCount(1)
        compose.runOnIdle {
            replacement.laidOut = true
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        assertCount(1)
        compose.runOnIdle {
            replacement.trailingEdge = 400f
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        assertCount(1)
        compose.runOnIdle {
            replacement.revealed = true
            Snapshot.sendApplyNotifications()
        }
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle {
            assertEquals(replacement.row, events.last().row)
            assertEquals(2uL, events.last().checkpoint)
        }
    }

    @Test fun removedThenReintroducedVersion_cannotReuseRetiredCallbacks() {
        val retired = candidate("same version")
        mount(retired, 1u)
        present(retired)
        compose.waitUntil(5000) { events.size == 1 }
        compose.runOnIdle { active.value = null }
        compose.waitForIdle()
        val replacement = candidate("same version")
        replace(replacement, 2u)
        present(retired)
        assertCount(1)
        compose.runOnIdle {
            assertTrue(!replacement.laidOut)
            assertEquals(null, replacement.trailingEdge)
            assertTrue(!replacement.revealed)
        }
        present(replacement)
        compose.waitUntil(5000) { events.size == 2 }
    }

    private fun candidate(content: String) =
        ThreadReadCandidate(
            "m",
            ThreadItem.MessageItem(Message("m", "", Role.Assistant, content, Instant.parse("2026-10-07T00:00:00Z"), isStreaming = false)),
        )

    private fun replace(
        candidate: ThreadReadCandidate,
        checkpoint: ULong,
    ) {
        compose.runOnIdle { select(candidate, checkpoint) }
        compose.waitForIdle()
    }

    private fun select(
        candidate: ThreadReadCandidate,
        checkpoint: ULong,
    ) {
        active.value = candidate
        state.value =
            state.value.copy(
                readUpTo = 0u,
                readEvidence =
                    ThreadReadEvidence(
                        versions = mapOf(candidate.row to setOf(checkpoint)),
                        facts = mapOf(checkpoint to false),
                    ),
            )
    }

    private fun present(candidate: ThreadReadCandidate) {
        compose.runOnIdle {
            candidate.laidOut = true
            candidate.trailingEdge = 400f
            candidate.revealed = true
        }
        compose.waitForIdle()
    }

    private fun assertCount(count: Int) {
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(count, events.size) }
    }

    private fun mount(
        candidate: ThreadReadCandidate,
        checkpoint: ULong,
    ) {
        select(candidate, checkpoint)
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val viewport = mutableStateOf<Rect?>(Rect(0f, 0f, 600f, 600f))
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val list = rememberLazyListState()
                // Use the same list whose measured membership the production observer consumes.
                LazyColumn(state = list) { item(key = "m") { Box(Modifier.size(100.dp)) } }
                ThreadReadViewport(state.value, list, active.value, viewport, 10.dp, 10.dp, true) {
                    if (it is ThreadEvent.NewestContentPresented) events += it
                }
            }
        }
        compose.waitForIdle()
    }
}
