package de.pyryco.mobile.recording

import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.e2e.E2eTestApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.RunWith
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecordedSuiteIsolationDeviceTest {
    @Test fun ordinaryGraphClosurePreservesApplicationPreferences() =
        runBlocking {
            val koin = GlobalContext.get()
            val preferences = koin.get<DataStore<Preferences>>()
            val original = preferences.data.first()
            try {
                // Ordinary navigation tests load this same Module into an isolated graph.
                KoinApplication.init().modules(appModule).close()
                val afterClose = koin.get<DataStore<Preferences>>()
                assertSame("isolated graph closure must not discard the application's file owner", preferences, afterClose)
                assertEquals(original, afterClose.updateData { it })
            } finally {
                // Keep the baseline failure from leaving a second, uninitialized owner behind.
                koin.loadModules(listOf(module { single<DataStore<Preferences>> { preferences } }))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.runOnMainSync {
                    (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph()
                }
            }
        }

    @Test fun incompleteReplayRestoresOrdinaryInstrumentationArgumentsAndGraph() =
        withSeededState {
            repeat(2) {
                KoinApplication.init().modules(appModule).close()
                incompleteReplayRestoresOrdinaryEnvironment()
            }
        }

    @Test fun successfulReplayRestoresPreferencesAndPairings() =
        withSeededState {
            val result =
                JUnitCore().run(
                    Request.method(RecordedMobileSuiteTest::class.java, "interactiveTurn_pingPrompt_streamsPingReplyIntoThread"),
                )
            assertEquals("the original scenario must execute", 1, result.runCount)
            assertEquals("the original scenario must not skip", 0, result.ignoreCount)
            assertTrue("original scenario failed: ${result.failures}", result.wasSuccessful())
        }

    private fun incompleteReplayRestoresOrdinaryEnvironment() {
        val original = Bundle(InstrumentationRegistry.getArguments())
        assertNull("ordinary tests must not inherit relay arguments", original.getString(E2eTestApplication.ARG_RELAY_URL))
        var entered = false
        val failure =
            runCatching {
                try {
                    RecordedSuiteNative.begin(
                        Description.createTestDescription(
                            RecordedMobileSuiteTest::class.java,
                            "interactiveTurn_pingPrompt_streamsPingReplyIntoThread",
                        ),
                    )
                    entered = RecordedSuiteNative.replaying
                } finally {
                    RecordedSuiteNative.finish(success = false)
                }
            }.exceptionOrNull()
        assertTrue("the cleanup probe must enter replay", entered)
        assertTrue("an unexecuted tape cannot pass", failure is IllegalStateException)
        assertFalse(RecordedSuiteNative.active)
        val restored = InstrumentationRegistry.getArguments()
        assertEquals("recorded tests must restore the argument keys", original.keySet(), restored.keySet())
        original.keySet().forEach { key ->
            assertTrue("recorded tests must restore argument $key", original.getString(key) == restored.getString(key))
        }
        val koin = GlobalContext.get()
        assertSame(koin.get<FakeConversationRepository>(), koin.get<ConversationRepository>())
    }

    private fun withSeededState(block: () -> Unit) =
        runBlocking {
            val preferences = GlobalContext.get().get<DataStore<Preferences>>()
            val original = preferences.data.first()
            val pairing =
                PairedServer(
                    UUID.randomUUID().toString(),
                    "synthetic-isolation-token",
                    "wss://recorded.invalid",
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                )
            try {
                val store = GlobalContext.get().get<ObservablePairedServerStore>()
                store.save(pairing)
                store.setDisplayName(pairing.serverId, "Isolation host")
                preferences.updateData {
                    it.toMutablePreferences().apply { this[stringPreferencesKey("isolation-probe")] = "ordinary" }
                }
                val expectedPreferences = preferences.data.first()
                val expectedPairings = store.readSnapshot().getOrThrow()
                block()
                assertFalse(RecordedSuiteNative.active)
                val restored = GlobalContext.get()
                assertSame(preferences, restored.get<DataStore<Preferences>>())
                assertEquals(expectedPreferences, preferences.data.first())
                assertEquals(expectedPairings, restored.get<ObservablePairedServerStore>().readSnapshot().getOrThrow())
                assertSame(restored.get<FakeConversationRepository>(), restored.get<ConversationRepository>())
            } finally {
                preferences.updateData { original }
            }
        }
}
