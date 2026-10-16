package de.pyryco.mobile.recording

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.e2e.E2eTestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class RecordedSuiteIsolationDeviceTest {
    @Test fun incompleteReplayRestoresOrdinaryInstrumentationArgumentsAndGraph() {
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
}
