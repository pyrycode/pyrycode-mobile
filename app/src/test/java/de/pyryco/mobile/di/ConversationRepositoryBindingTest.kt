package de.pyryco.mobile.di

import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * JVM unit tests for the #350 flag-gated `ConversationRepository` binding selector. The selector
 * ([conversationRepositoryModule]) is the only module that binds `ConversationRepository`; it picks
 * the bound implementation from a boolean (defaulting to `BuildConfig.USE_RELAY_REPOSITORY`) without
 * constructing either repository itself — it resolves the two already-registered concrete singletons
 * by type. These tests assert the choice purely through DI resolution; no relay connection is opened.
 *
 * Each case loads an isolated [koinApplication] (never `startKoin`/`GlobalContext`, so there is no
 * cross-test bleed and no global teardown) holding only the selector plus a tiny stub-deps module,
 * and `close()`s it in a `finally`. The stub deps are pure-JVM constructions — `StableConversationRepository`
 * wraps a `MutableStateFlow(null)` (no live connection, no network), and the Fake has a no-arg
 * constructor — so nothing here touches Android.
 */
class ConversationRepositoryBindingTest {
    private fun stubDeps(): Module =
        module {
            single { FakeConversationRepository() }
            single { StableConversationRepository(MutableStateFlow<ConversationRepository?>(null)) }
        }

    private fun withSelector(
        selector: Module,
        block: (Koin) -> Unit,
    ) {
        val app = koinApplication { modules(stubDeps(), selector) }
        try {
            block(app.koin)
        } finally {
            app.close()
        }
    }

    /** AC #2: flag off binds the Fake — fake-mode behaviour is unchanged for previews and tests. */
    @Test
    fun flagOff_bindsFakeRepository() =
        withSelector(conversationRepositoryModule(useRelay = false)) { koin ->
            assertTrue(koin.get<ConversationRepository>() is FakeConversationRepository)
        }

    /** AC #3: flag on binds the relay-backed facade — asserted via DI resolution, no live network. */
    @Test
    fun flagOn_bindsRelayFacade() =
        withSelector(conversationRepositoryModule(useRelay = true)) { koin ->
            assertTrue(koin.get<ConversationRepository>() is StableConversationRepository)
        }

    /** Gradle supplies the requested mode independently of the generated BuildConfig field. */
    @Test
    fun defaultParam_selectsRequestedBuildRepository() {
        val expectedRelay = System.getProperty("expectedUseRelayRepository")?.toBooleanStrict() ?: true
        assertEquals("generated repository flag", expectedRelay, BuildConfig.USE_RELAY_REPOSITORY)
        withSelector(conversationRepositoryModule()) { koin ->
            val expected =
                if (expectedRelay) koin.get<StableConversationRepository>() else koin.get<FakeConversationRepository>()
            assertSame(expected, koin.get<ConversationRepository>())
        }
    }

    /**
     * AC #4: tests and `@Preview`s can force fake mode regardless of the build flag by passing
     * `useRelay = false` explicitly. This parameter is the preferred force-fake mechanism — it pins
     * the Fake without depending on Koin module-override semantics.
     */
    @Test
    fun forceFakeForTestsAndPreviews_viaParameter() =
        withSelector(conversationRepositoryModule(useRelay = false)) { koin ->
            assertTrue(koin.get<ConversationRepository>() is FakeConversationRepository)
        }

    /** Koin `single`: resolving the binding twice returns the same instance. */
    @Test
    fun binding_isSingleton() =
        withSelector(conversationRepositoryModule(useRelay = false)) { koin ->
            assertSame(koin.get<ConversationRepository>(), koin.get<ConversationRepository>())
        }
}
