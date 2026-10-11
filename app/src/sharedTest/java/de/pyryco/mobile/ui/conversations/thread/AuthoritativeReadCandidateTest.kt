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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ConversationReadMarks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoritativeReadCandidateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedRevealAndRetiredGenerationCallbacksCannotQualifyNewContent() {
        val snapshot = authoritativeSnapshot(authoritativeItem(1, rev = 8)).copy(checkpoint = 8)
        val p = projectAuthoritativeThread("h", "c", snapshot)
        val retired = ThreadReadCandidate(p.rows.single().listKey(0), p.items.single(), 8)
        val candidate = mutableStateOf(retired)
        val state =
            mutableStateOf(
                ThreadUiState("c", "Thread", authoritativeThread = p, readUpTo = 0u, readMarks = ConversationReadMarks(0u, 100u, 8u, true)),
            )
        val events = mutableListOf<ThreadEvent.NewestContentPresented>()
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                val list = rememberLazyListState()
                LazyColumn(state = list) { item(key = candidate.value.key) { Box(Modifier.size(100.dp)) } }
                ThreadReadViewport(state.value, list, candidate.value, mutableStateOf(Rect(0f, 0f, 600f, 600f)), 10.dp, 10.dp, true) {
                    if (it is ThreadEvent.NewestContentPresented) events += it
                }
            }
        }

        fun ready(
            c: ThreadReadCandidate,
            reveal: Boolean,
        ) {
            compose.runOnIdle {
                c.laidOut = true
                c.trailingEdge = 400f
                c.revealed = reveal
                Snapshot.sendApplyNotifications()
            }
            compose.waitForIdle()
        }
        ready(retired, false)
        compose.runOnIdle { assertEquals(0, events.size) }
        ready(retired, true)
        compose.waitUntil(5000) { events.size == 1 }
        val next =
            projectAuthoritativeThread(
                "h",
                "c",
                snapshot.copy(generation = 1, checkpoint = 9, items = listOf(authoritativeItem(1, rev = 9))),
            )
        val replacement = ThreadReadCandidate(next.rows.single().listKey(0), next.items.single(), 9)
        compose.runOnIdle {
            candidate.value = replacement
            state.value =
                state.value.copy(authoritativeThread = next, readMarks = ConversationReadMarks(0u, 100u, 9u, true))
        }
        compose.waitForIdle()
        ready(retired, true)
        compose.runOnIdle { assertEquals(1, events.size) }
        ready(replacement, false)
        compose.runOnIdle { assertEquals(1, events.size) }
        ready(replacement, true)
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle {
            assertEquals(next, events.last().presentation)
            assertEquals(9uL, events.last().checkpoint)
        }
    }
}
