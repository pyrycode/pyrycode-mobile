package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConversationAgent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAgentDelegationTest {
    @Test
    fun stableDelegatesToCurrentHostWithoutChangingArgumentsOrFailure() =
        runTest {
            val calls = mutableListOf<SwitchAgentCall>()
            val failure = SwitchAgentFailure(SwitchAgentFailure.Category.BinaryOffline, true)
            val first =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun switchAgent(
                        conversationId: String,
                        agent: ConversationAgent,
                        model: String,
                        effort: String?,
                    ): Result<Unit> {
                        calls += SwitchAgentCall(conversationId, agent, model, effort)
                        return Result.failure(failure)
                    }
                }
            val current = MutableStateFlow<ConversationRepository?>(first)
            val stable = StableConversationRepository(current)
            assertSame(failure, stable.switchAgent("opaque\n", ConversationAgent.Codex, " model ", "").exceptionOrNull())
            assertEquals(listOf(SwitchAgentCall("opaque\n", ConversationAgent.Codex, " model ", "")), calls)
            val next = FakeConversationRepository()
            current.value = next
            assertTrue(stable.switchAgent("seed-channel-personal", ConversationAgent.Claude, "", null).isSuccess)
            assertEquals(listOf(SwitchAgentCall("seed-channel-personal", ConversationAgent.Claude, "", null)), next.switchAgentCalls)
            assertEquals(1, calls.size)
            current.value = null
            val disconnected = stable.switchAgent("secret", ConversationAgent.Codex, "secret").exceptionOrNull() as SwitchAgentFailure
            assertEquals(SwitchAgentFailure.Category.Unavailable, disconnected.category)
            assertFalse(disconnected.toString().contains("secret"))
        }

    @Test
    fun stablePropagatesCallerCancellation() =
        runTest {
            val live =
                object : ConversationRepository by FakeConversationRepository() {
                    override suspend fun switchAgent(
                        conversationId: String,
                        agent: ConversationAgent,
                        model: String,
                        effort: String?,
                    ): Result<Unit> = throw CancellationException("cancelled")
                }
            val stable = StableConversationRepository(MutableStateFlow(live))
            assertTrue(runCatching { stable.switchAgent("c1", ConversationAgent.Codex, "") }.exceptionOrNull() is CancellationException)
        }

    @Test
    fun fakeRecordsVerbatimAndSuccessChangesOnlyNamedAgentInBothDirections() =
        runTest {
            val fake = FakeConversationRepository()
            val before = fake.observeConversations(ConversationFilter.All).first()
            val id = "seed-channel-personal"
            assertTrue(fake.switchAgent(id, ConversationAgent.Codex, "", "").isSuccess)
            val switched = fake.observeConversations(ConversationFilter.All).first()
            assertEquals(before.map { if (it.id == id) it.copy(agent = ConversationAgent.Codex) else it }, switched)
            assertTrue(fake.switchAgent(id, ConversationAgent.Claude, " model-secret ", null).isSuccess)
            assertEquals(before, fake.observeConversations(ConversationFilter.All).first())
            assertEquals(
                listOf(
                    SwitchAgentCall(id, ConversationAgent.Codex, "", ""),
                    SwitchAgentCall(id, ConversationAgent.Claude, " model-secret ", null),
                ),
                fake.switchAgentCalls,
            )
            assertFalse(fake.switchAgentCalls.toString().contains("secret"))
        }

    @Test
    fun fakeFailureAndUnknownIdLeaveRowsUnchanged() =
        runTest {
            val fake = FakeConversationRepository()
            val before = fake.observeConversations(ConversationFilter.All).first()
            val failure = SwitchAgentFailure(SwitchAgentFailure.Category.BinaryOffline, true)
            fake.switchAgentFailure = failure
            assertSame(failure, fake.switchAgent("seed-channel-personal", ConversationAgent.Codex, "", "high").exceptionOrNull())
            assertEquals(before, fake.observeConversations(ConversationFilter.All).first())
            fake.switchAgentFailure = null
            val unknown = fake.switchAgent("unknown-secret", ConversationAgent.Codex, "").exceptionOrNull() as SwitchAgentFailure
            assertEquals(SwitchAgentFailure.Category.ConversationNotFound, unknown.category)
            assertEquals(before, fake.observeConversations(ConversationFilter.All).first())
            assertEquals(2, fake.switchAgentCalls.size)
            assertFalse(unknown.toString().contains("secret"))
        }
}
