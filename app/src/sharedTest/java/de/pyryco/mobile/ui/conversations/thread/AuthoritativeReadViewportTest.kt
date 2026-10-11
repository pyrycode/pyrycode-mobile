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
        state.value = state.value.copy(authoritativeThread = projectAuthoritativeThread("h", "c", snapshot), readMarks = facts(30u))
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
                    readMarks = facts(31u),
                )
        }
        settle()
        compose.runOnIdle {
            assertEquals(1, events.size)
            state.value = state.value.copy(readMarks = facts(null))
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        settle()
        compose.runOnIdle {
            assertEquals(1, events.size)
            state.value = state.value.copy(readMarks = facts(31u))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { events.size == 2 }
        compose.runOnIdle { assertEquals(31uL, events.last().checkpoint) }
    }

    private fun mount() {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
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
