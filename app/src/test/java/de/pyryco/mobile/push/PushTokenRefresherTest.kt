package de.pyryco.mobile.push

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The app asks FCM for its current token while none is stored (#1102), instead of waiting only for
 * `onNewToken`. The refresher, the sink and a temp-folder DataStore share the test scheduler, so a write
 * has finished before a plain `first()` reads it (#953).
 */
class PushTokenRefresherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val owner = Owner()
    private val registry get() = owner.registry
    private var previousEnabled = RelayLog.enabled
    private var previousSink = RelayLog.sink

    @Before
    fun setUp() {
        previousEnabled = RelayLog.enabled
        previousSink = RelayLog.sink
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
        // CREATED, never STARTED yet: a process launched by a push.
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @After
    fun tearDown() {
        RelayLog.enabled = previousEnabled
        RelayLog.sink = previousSink
    }

    @Test
    fun start_requestsTheToken_andStoresIt_withoutLoggingIt() =
        harness(FakeSource(listOf(Result.success(TOKEN)))) { source, preferences, refresher ->
            refresher.start()
            advanceUntilIdle()

            assertEquals(1, source.calls)
            assertEquals(TOKEN, preferences.pushToken.first())
            assertTrue("a request logs its outcome", logs.any { it.contains("event=push_token_requested outcome=success") })
            assertTrue("the token is never logged", logs.none { it.contains(TOKEN) })
        }

    @Test
    fun failure_storesNothing_andTheNextForegroundRetries() =
        harness(
            FakeSource(listOf(Result.failure(IOException("SERVICE_NOT_AVAILABLE")), Result.success(TOKEN))),
        ) { source, preferences, refresher ->
            refresher.start()
            advanceUntilIdle()

            assertEquals(1, source.calls)
            assertNull(preferences.pushToken.first())
            assertTrue(logs.any { it.contains("event=push_token_requested outcome=failure error=IOException") })

            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            advanceUntilIdle()

            assertEquals(2, source.calls)
            assertEquals(TOKEN, preferences.pushToken.first())
        }

    @Test
    fun storedToken_makesNoRequest_atStartOrOnForeground() =
        harness(FakeSource(listOf(Result.success("fcm-other")))) { source, preferences, refresher ->
            preferences.setPushToken(TOKEN)
            advanceUntilIdle()

            refresher.start()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            advanceUntilIdle()

            assertEquals(0, source.calls)
            assertEquals(TOKEN, preferences.pushToken.first())
        }

    @Test
    fun noFirebaseApp_makesNoRequest() =
        harness(FakeSource(listOf(Result.success(TOKEN)), available = false)) { source, preferences, refresher ->
            refresher.start()
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            advanceUntilIdle()

            assertEquals(0, source.calls)
            assertNull(preferences.pushToken.first())
        }

    @Test
    fun foregroundWhileARequestIsPending_asksOnce() =
        runTest {
            val pending = CompletableDeferred<String>()
            val source =
                object : PushTokenSource {
                    var calls = 0

                    override fun isAvailable() = true

                    override suspend fun currentToken(): String {
                        calls++
                        return pending.await()
                    }
                }
            withRefresher(source) { preferences, refresher ->
                // runCurrent, not advanceUntilIdle: the latter would run the clock past the request timeout.
                refresher.start()
                runCurrent()
                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                runCurrent()

                assertEquals(1, source.calls)

                pending.complete(TOKEN)
                advanceUntilIdle()
                assertEquals(TOKEN, preferences.pushToken.first())
            }
        }

    @Test
    fun requestThatNeverCompletes_timesOut_andTheNextForegroundRetries() =
        runTest {
            val source =
                object : PushTokenSource {
                    var calls = 0

                    override fun isAvailable() = true

                    override suspend fun currentToken(): String {
                        calls++
                        return CompletableDeferred<String>().await()
                    }
                }
            withRefresher(source) { preferences, refresher ->
                refresher.start()
                advanceUntilIdle()

                assertTrue(logs.any { it.contains("event=push_token_requested outcome=timeout") })
                assertNull(preferences.pushToken.first())

                registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                runCurrent()
                assertEquals(2, source.calls)
            }
        }

    private fun harness(
        source: FakeSource,
        body: suspend TestScope.(FakeSource, AppPreferences, PushTokenRefresher) -> Unit,
    ) = runTest {
        withRefresher(source) { preferences, refresher -> body(source, preferences, refresher) }
    }

    private suspend fun TestScope.withRefresher(
        source: PushTokenSource,
        body: suspend TestScope.(AppPreferences, PushTokenRefresher) -> Unit,
    ) {
        // Its own scope, not backgroundScope: advanceUntilIdle leaves background work unrun (#824).
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val preferences =
            AppPreferences(
                PreferenceDataStoreFactory.create(
                    scope = storeScope,
                    produceFile = { tmp.newFile("push_token.preferences_pb") },
                ),
            )
        val sink = PushTokenSink(preferences, StandardTestDispatcher(testScheduler))
        val refresher =
            PushTokenRefresher(
                storedToken = preferences.pushToken,
                source = source,
                sink = sink,
                lifecycle = registry,
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        try {
            body(preferences, refresher)
        } finally {
            refresher.dispose()
            sink.dispose()
            storeScope.cancel()
        }
    }

    /** Returns [results] in order, the last one repeating. */
    private class FakeSource(
        private val results: List<Result<String>>,
        private val available: Boolean = true,
    ) : PushTokenSource {
        var calls = 0

        override fun isAvailable() = available

        override suspend fun currentToken(): String = results[minOf(calls++, results.lastIndex)].getOrThrow()
    }

    /** Held for the whole test: the registry keeps only a weak reference to its owner. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val TOKEN = "fcm-current-token"
    }
}
