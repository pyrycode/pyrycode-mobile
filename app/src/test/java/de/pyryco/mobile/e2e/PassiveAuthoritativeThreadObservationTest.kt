package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.AuthoritativeThreadStoreTest.Companion.item
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PassiveAuthoritativeThreadObservationTest {
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun teardown() {
        RelayLog.sink = oldSink
    }

    @Test fun observesOnlyTheDestinationCollectionAndRequiresAnActualReopen() =
        runTest {
            val source = Route()
            val tapped = PassiveThreadProbe.negotiated(source)
            val fact =
                item(
                    1,
                    1,
                    1,
                    kind = "user_message",
                    status = "delivered",
                    text = "peer prompt",
                    extra = "\"session\":\"session\",\"turn\":\"turn\",",
                )
            source.store.establishComplete("host", "chat", "epoch", 1, listOf(fact), 0)
            PassiveThreadProbe.open().use { probe ->
                assertNull(probe.reading("host", "chat"))
                assertEquals(0, source.collections)
                var delivered: AuthoritativeThreadSnapshot? = null
                val app = launch { tapped.observe("chat").collect { delivered = it } }
                runCurrent()
                val first = checkNotNull(probe.reading("host", "chat"))
                val captured = checkNotNull(probe.evidence(first)).records.single()
                repeat(5) { assertNotNull(probe.evidence(first)) }
                assertSame(source.store.current("host", "chat"), delivered)
                assertEquals(1, source.collections)
                assertEquals(0, source.opens)
                assertNull(probe.reading("other", "chat"))
                assertNull(probe.reading("host", "other"))
                assertNull(probe.reading("host", "chat", after = first.subscription))
                app.cancel()
                runCurrent()
                assertNull(probe.evidence(first))
                val reopened = launch { tapped.observe("chat").collect {} }
                runCurrent()
                val second = checkNotNull(probe.reading("host", "chat", after = first.subscription))
                assertNotNull(checkNotNull(probe.evidence(second)).retainedPeerUser(captured, second.owner, "session"))
                assertEquals(2, source.collections)
                assertEquals(0, source.opens)
                reopened.cancel()
            }
        }

    @Test fun ownerChangesCannotInventAReopenOrReviveARetiredReading() =
        runTest {
            val source = Route()
            source.store.establishComplete("host", "chat", "epoch", 1, emptyList(), 0)
            val tapped = PassiveThreadProbe.negotiated(source)
            PassiveThreadProbe.open().use { probe ->
                val app = launch { tapped.observe("chat").collect {} }
                runCurrent()
                val first = checkNotNull(probe.reading("host", "chat"))
                source.store.reset("host", "chat", "epoch")
                source.store.establishComplete("host", "chat", "epoch", 2, emptyList(), 1)
                runCurrent()
                val current = checkNotNull(probe.reading("host", "chat"))
                assertSame(first.subscription, current.subscription)
                assertNull(probe.reading("host", "chat", after = first.subscription))
                assertNull(probe.evidence(first))
                source.support.value = ThreadSupport.Unverified
                assertNull(probe.evidence(current))
                source.support.value = ThreadSupport.Supported(Any())
                assertNull(probe.evidence(current))
                assertNull(probe.reading("host", "chat"))
                app.cancel()
            }
        }

    @Test fun queuedRetiredSnapshotCannotDisableTheSameActualSubscription() =
        runTest {
            val source = Route()
            source.store.establishComplete("host", "chat", "epoch", 1, emptyList(), 0)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            source.beforeEmit = {
                if (first) {
                    first = false
                    entered.complete(Unit)
                    release.await()
                }
            }
            PassiveThreadProbe.open().use { probe ->
                val tapped = PassiveThreadProbe.negotiated(source)
                val app = launch { tapped.observe("chat").collect {} }
                runCurrent()
                entered.await()
                source.store.reset("host", "chat", "epoch")
                source.store.establishComplete("host", "chat", "epoch", 2, emptyList(), 1)
                release.complete(Unit)
                runCurrent()
                val reading = checkNotNull(probe.reading("host", "chat"))
                assertEquals(1L, reading.owner.generation)
                assertEquals(2L, checkNotNull(probe.evidence(reading)).sourceSnapshot?.checkpoint)
                assertEquals(1, source.collections)
                assertEquals(0, source.opens)
                app.cancel()
            }
        }

    private class Route : NegotiatedThread() {
        override val host = "host"
        override val support = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(Any()))
        override val store = AuthoritativeThreadStore()
        var collections = 0
        var opens = 0
        var beforeEmit: suspend (AuthoritativeThreadSnapshot) -> Unit = {}

        override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
            flow {
                collections++
                store.observe(host, conversationId).collect {
                    beforeEmit(it)
                    emit(it)
                }
            }

        override suspend fun open(conversationId: String): Boolean {
            opens++
            return true
        }

        override fun older(conversationId: String) = false

        override fun cancel(conversationId: String) = Unit
    }
}
