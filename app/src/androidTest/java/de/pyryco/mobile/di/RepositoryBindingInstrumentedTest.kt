package de.pyryco.mobile.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.e2e.E2eTestApplication
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class RepositoryBindingInstrumentedTest {
    @Test
    fun ordinaryInstrumentation_explicitlyBindsFakeRepository() {
        assumeTrue(
            "Relay runs use the separate tapped repository binding",
            InstrumentationRegistry.getArguments().getString(E2eTestApplication.ARG_RELAY_URL) == null,
        )
        val koin = GlobalContext.get()
        assertSame(koin.get<FakeConversationRepository>(), koin.get<ConversationRepository>())
    }
}
