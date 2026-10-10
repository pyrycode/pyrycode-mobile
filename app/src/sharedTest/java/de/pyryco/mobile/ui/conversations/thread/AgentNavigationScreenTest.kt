package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
open class AgentNavigationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ts = Instant.parse("2026-10-09T10:00:00Z")
    private var state by mutableStateOf(ThreadUiState("c", "Channel", hasMessages = true))
    private var mounted by mutableStateOf(true)
    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var restoration: StateRestorationTester
    private val owner = Owner()

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }

    @Test open fun rootReturnsWhileWaiting_navigatesOnce() {
        mount()
        tapAndRemoveRoot("a")
        restoreRoot("a")
        compose.runOnIdle { assertEquals("msg:a", anchor().first) }
        list().performScrollToIndex(8)
        // Consumption is observable: a subsequent disappearance/rearrival cannot navigate again.
        removeRoot("a")
        assertArrivalKeepsReader("a")
    }

    @Test open fun gestureWhileWaiting_cancelsNavigation() {
        mount()
        tapAndRemoveRoot("a")
        list().performTouchInput {
            swipe(Offset(center.x, height * .4f), Offset(center.x, height * .7f), 300)
        }
        assertArrivalKeepsReader("a")
    }

    @Test open fun accessibilityScrollWhileWaiting_cancelsNavigation() {
        mount()
        tapAndRemoveRoot("a")
        list().performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, -240f)) }
        assertArrivalKeepsReader("a")
    }

    @Test open fun departureAndReturn_doesNotReviveRequest() {
        mount()
        tapAndRemoveRoot("a")
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        assertArrivalKeepsReader("a")
    }

    @Test open fun remount_doesNotReviveRequest() {
        mount()
        tapAndRemoveRoot("a")
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { mounted = true }
        list().performScrollToIndex(8)
        assertArrivalKeepsReader("a")
        tapAndRemoveRoot("a")
        restoration.emulateSavedInstanceStateRestore()
        assertArrivalKeepsReader("a")
    }

    @Test open fun conversationSwitch_doesNotReviveRequest() {
        mount()
        tapAndRemoveRoot("a")
        compose.runOnIdle { state = state.copy(conversationId = "other") }
        compose.runOnIdle { state = state.copy(conversationId = "c") }
        assertArrivalKeepsReader("a")
    }

    @Test open fun freshTap_replacesUnresolvedRequest() {
        mount(second = true)
        tapAndRemoveRoot("a")
        tapAndRemoveRoot("b")
        assertArrivalKeepsReader("a")
        restoreRoot("b")
        compose.runOnIdle { assertEquals("msg:b", anchor().first) }
    }

    @Test open fun freshTapAfterReaderCancellation_navigates() {
        mount()
        tapAndRemoveRoot("a")
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -240f) }
        assertArrivalKeepsReader("a")
        tapAndRemoveRoot("a")
        restoreRoot("a")
        compose.runOnIdle { assertEquals("msg:a", anchor().first) }
    }

    @Test open fun readerInterruptsInProgressNavigation_doesNotRetry() {
        mount()
        list().performScrollToNode(marker("a"))
        val click =
            requireNotNull(
                compose
                    .onNode(marker("a"))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        var reader: Job? = null
        compose.runOnIdle {
            assertTrue(click())
            // Reader input owns the list while the production effect attempts its scroll mutation.
            reader =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    listState.scroll(MutatePriority.UserInput) { awaitCancellation() }
                }
        }
        compose.waitForIdle()
        compose.runOnIdle { reader?.cancel() }
        list().performScrollToIndex(8)
        var before: Pair<Any?, Int>? = null
        compose.runOnIdle {
            before = anchor()
            state = state.copy(items = state.items + user("After interruption"))
        }
        compose.runOnIdle { assertEquals(before, anchor()) }
    }

    private fun mount(second: Boolean = false) {
        owner.registry.currentState = Lifecycle.State.RESUMED
        state =
            state.copy(
                items =
                    (1..8).map { user("Older $it") } + block("a") +
                        (if (second) block("b") else emptyList()) + (1..35).map { user("Reader $it") },
            )
        restoration = StateRestorationTester(compose)
        restoration.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(
                LocalLifecycleOwner provides owner,
                LocalThreadListCompositionObserver provides { listState = it },
            ) {
                PyrycodeMobileTheme {
                    if (mounted) ThreadScreen(state, {}, {}, ConnectionState.Connected, {})
                }
            }
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun marker(id: String) = hasText("Go to agent ↓") and hasText("Agent $id")

    private fun tapAndRemoveRoot(id: String) {
        list().performScrollToNode(marker(id))
        val click =
            requireNotNull(
                compose
                    .onNode(marker(id))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        // Click the production marker while its root exists, then remove it before the effect runs.
        compose.runOnIdle {
            assertTrue(click())
            state = state.copy(items = withoutRoot(id))
        }
        compose.waitForIdle()
    }

    private fun withoutRoot(id: String) = state.items.filterNot { it is ThreadItem.MessageItem && it.message.id == id }

    private fun removeRoot(id: String) {
        compose.runOnIdle { state = state.copy(items = withoutRoot(id)) }
    }

    private fun restoreRoot(id: String) {
        compose.runOnIdle {
            val index = state.items.indexOfFirst { it is ThreadItem.BackgroundTaskLifecycle && it.toolCallId == id }
            state = state.copy(items = state.items.toMutableList().apply { add(index, root(id)) })
        }
        compose.waitForIdle()
    }

    private fun anchor(): Pair<Any?, Int> {
        val item = listState.layoutInfo.visibleItemsInfo.first { it.index == listState.firstVisibleItemIndex }
        return item.key to item.offset
    }

    private fun assertArrivalKeepsReader(id: String) {
        // Choose a stationary history row; root insertion may legitimately change its list index.
        list().performScrollToIndex(8)
        var before: Pair<Any?, Int>? = null
        compose.runOnIdle { before = anchor() }
        restoreRoot(id)
        compose.runOnIdle { assertEquals(before, anchor()) }
    }

    private fun user(id: String) = ThreadItem.MessageItem(Message(id, "s", Role.User, id, ts, false))

    private fun root(id: String) =
        ThreadItem.MessageItem(
            Message(
                id,
                "s",
                Role.Tool,
                "",
                ts,
                false,
                ToolCall("Agent", "input", "output", inputFields = mapOf("run_in_background" to "true")),
            ),
        )

    private fun block(id: String) =
        listOf(
            root(id),
            ThreadItem.BackgroundTaskLifecycle("t$id", ts, id, "Agent $id", "local_agent"),
            ThreadItem.BackgroundTaskLifecycle("t$id", ts, terminal = BackgroundTaskUpdate("", "completed", "", null)),
        )
}
