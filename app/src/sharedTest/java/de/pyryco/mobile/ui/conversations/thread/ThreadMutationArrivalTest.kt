package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Forces the owner-repository gap before the live scenario's menu tap (#1998). */
@RunWith(AndroidJUnit4::class)
class ThreadMutationArrivalTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private val currentA = MutableStateFlow<ConversationRepository?>(null)
    private val currentB = MutableStateFlow<ConversationRepository?>(null)
    private val events = mutableListOf<ThreadEvent>()

    @After fun close() {
        compose.runOnIdle { owner.clear() }
    }

    @Test fun owningHostArrivalEnablesEditAfterComposerWasAlreadyDrawn() {
        val vm = mountDisconnectedOwner()
        // Composer presence cannot establish that a newly constructed ViewModel saw a live owner.
        compose.onNodeWithContentDescription(string(R.string.cd_send_message)).assertIsDisplayed()
        compose.runOnIdle { assertFalse(vm.state.value.mutationsSupported) }
        compose.runOnIdle { currentA.value = FakeConversationRepository() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Personal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        // This common row proves the tap did open the menu; the regression is missing Edit.
        compose.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.thread_overflow_edit)).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(ThreadEvent.EditChannel), events) }
    }

    @Test fun otherHostArrivalDoesNotEnableOwnersEdit() {
        val vm = mountDisconnectedOwner()
        compose.runOnIdle {
            currentB.value = FakeConversationRepository()
            val other = createViewModel("host-b", currentB)
            assertEquals(vm.state.value.conversationId, other.state.value.conversationId)
            assertTrue("the other host supports mutations for the colliding ID", other.state.value.mutationsSupported)
        }
        compose.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        compose.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.thread_overflow_edit)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.thread_overflow_rename)).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(vm.state.value.mutationsSupported)
            assertEquals(emptyList<ThreadEvent>(), events)
            assertEquals("seed-channel-personal", vm.state.value.conversationId)
        }
    }

    private fun mountDisconnectedOwner(): ThreadViewModel {
        lateinit var vm: ThreadViewModel
        compose.runOnIdle {
            vm = createViewModel("host-a", currentA)
        }
        compose.setContent {
            val state by vm.state.collectAsState()
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = if (state.hostAvailable) ConnectionState.Connected else ConnectionState.Offline,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }
        return vm
    }

    private fun createViewModel(
        serverId: String,
        current: MutableStateFlow<ConversationRepository?>,
    ): ThreadViewModel =
        ThreadViewModel(
            SavedStateHandle(mapOf("serverId" to serverId, "conversationId" to "seed-channel-personal")),
            StableConversationRepository(current),
            FakeConnectionStateSource(),
            ComposerDraftStore(),
            repositoryAvailable = current.map { it != null },
            projectionDispatcher = Dispatchers.Main.immediate,
        ).also { owner.put(serverId, it) }

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
