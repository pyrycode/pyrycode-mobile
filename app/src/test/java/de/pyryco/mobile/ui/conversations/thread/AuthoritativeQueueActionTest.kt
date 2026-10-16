package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeQueueTarget
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.StableConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeQueueActionTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    @Test fun capturedActionsStayBoundToPresentationAndCurrentSessionEligibility() =
        runTest {
            val fake = FakeConversationRepository()
            val calls = mutableListOf<ThreadEvent.AuthoritativeQueueAction>()
            val repo =
                object : ConversationRepository by fake {
                    override suspend fun actOnAuthoritativeQueue(
                        target: AuthoritativeQueueTarget,
                        sendNow: Boolean,
                    ): Result<Unit> {
                        calls += ThreadEvent.AuthoritativeQueueAction(target, sendNow)
                        return Result.failure(IllegalStateException("offline"))
                    }
                }
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { vm.state.collect {} }
            val fact =
                authoritativeItem(
                    1,
                    "user_message",
                    order = null,
                    status = "queued",
                    active = true,
                    content = """{"text":"queue text","queued_msg_id":7}""",
                )
            val presentation = projectAuthoritativeThread("host", "c", authoritativeSnapshot(fact))
            val target = requireNotNull((presentation.rows.single() as ThreadRow.Queued).target)
            val drop = ThreadEvent.AuthoritativeQueueAction(target, false)
            val send = drop.copy(sendNow = true)
            vm.onOverflowEvent(drop)
            runCurrent()
            assertTrue(calls.isEmpty())
            val binding = Any()
            vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadPresented(presentation, binding))
            vm.onOverflowEvent(send)
            vm.onOverflowEvent(drop)
            runCurrent()
            assertEquals(listOf(drop), calls)
            fake.setSessionSettingsReading(
                "c",
                SessionSettings(
                    effectiveEffort = EffectiveEffort.Unavailable,
                    sessionId = "s",
                    model = "opus",
                    effort = "high",
                    permissionMode = "default",
                    yolo = false,
                    usedTokens = 0,
                    windowTokens = 0,
                    capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = true),
                ),
            )
            runCurrent()
            assertTrue(vm.state.value.runConfig.midTurnInputSupported)
            vm.onOverflowEvent(send)
            runCurrent()
            assertEquals(listOf(drop, send), calls)
            assertEquals(target, (presentation.rows.single() as ThreadRow.Queued).target)
            vm.onOverflowEvent(
                ThreadEvent.AuthoritativeThreadPresented(
                    projectAuthoritativeThread("host", "c", authoritativeSnapshot(fact, epoch = "new")),
                    binding,
                ),
            )
            vm.onOverflowEvent(drop)
            runCurrent()
            assertEquals(2, calls.size)
            vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadRetired(binding))
            vm.onOverflowEvent(drop)
            runCurrent()
            assertEquals(2, calls.size)
        }

    @Test fun stableFacadeForwardsBoundTargetAcrossReconnectWithoutUnboundFallback() =
        runTest {
            val fake = FakeConversationRepository()
            val calls = mutableListOf<Pair<AuthoritativeQueueTarget, Boolean>>()
            val connection =
                object : ConversationRepository by fake {
                    override suspend fun actOnAuthoritativeQueue(
                        target: AuthoritativeQueueTarget,
                        sendNow: Boolean,
                    ): Result<Unit> {
                        calls += target to sendNow
                        return Result.success(Unit)
                    }
                }
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)
            val target = AuthoritativeQueueTarget("host", "c", "epoch", 0, 1, ULong.MAX_VALUE)
            assertTrue(facade.actOnAuthoritativeQueue(target, false).isFailure)
            current.value = connection
            assertTrue(facade.actOnAuthoritativeQueue(target, false).isSuccess)
            current.value = null
            assertTrue(facade.actOnAuthoritativeQueue(target, true).isFailure)
            current.value = connection
            assertTrue(facade.actOnAuthoritativeQueue(target, true).isSuccess)
            assertEquals(listOf(target to false, target to true), calls)
        }
}
