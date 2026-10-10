package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.e2e.InteractiveStreamE2ETest
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Native camera permission and encrypted pairing storage, using the live method's actual setup helper. */
@RunWith(AndroidJUnit4::class)
class AnswerHostSetupTest {
    private val live = InteractiveStreamE2ETest()
    private val previousId = "previous-1899-${UUID.randomUUID()}"
    private val answerId = "answer-1899-${UUID.randomUUID()}"
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val previous = PairedServer(previousId, "fixture-token", "wss://relay.example", key)
    private val answer = previous.copy(serverId = answerId)
    private val connects = AtomicInteger()
    private val verifications = AtomicInteger()
    private val compatibility = MutableStateFlow<ConnectionState>(ConnectionState.Offline)
    private lateinit var store: PairedServerCollectionStore

    @get:Rule(order = 0)
    val fixture =
        TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    val koin = GlobalContext.get()
                    store = koin.get()
                    val originalSource = koin.get<ConnectionStateSource>()
                    val registry = koin.get<RelayConnectionRegistry>()
                    val preceding = runBlocking { store.list() }
                    try {
                        runBlocking { store.save(previous) }
                        loadKoinModules(
                            module {
                                single<ConnectionStateSource> {
                                    object : ConnectionStateSource {
                                        override fun observe() = compatibility

                                        override suspend fun retry() = error("setup must not retry the preceding host")
                                    }
                                }
                                viewModel {
                                    PairCodeViewModel(
                                        store,
                                        object : RelayConnectionController {
                                            override fun connect() {
                                                connects.incrementAndGet()
                                            }

                                            override fun close() = Unit
                                        },
                                        { record ->
                                            flow {
                                                assertEquals("verified the new host", answer, record)
                                                assertEquals("verification follows persistence", answer, store.loadById(answerId)?.record)
                                                assertEquals(ConnectionState.Offline, compatibility.value)
                                                verifications.incrementAndGet()
                                                emit(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        base.evaluate()
                    } finally {
                        // The inner Compose rule has closed its Activity and cancelled the pairing VM.
                        loadKoinModules(
                            module {
                                single<ConnectionStateSource> { originalSource }
                                viewModel { parameters ->
                                    val target: String? = parameters.getOrNull()
                                    PairCodeViewModel(get(), registry, registry::pairingStatus, target)
                                }
                            },
                        )
                        runBlocking {
                            store.remove(answerId)
                            store.remove(previousId)
                            assertEquals("cleanup preserves original saved entries/order", preceding, store.list())
                        }
                    }
                }
            }
        }

    @get:Rule(order = 1)
    val compose = live.composeTestRule

    @Test
    fun offlinePrecedingHostDoesNotBlockAnswerHostPairing() {
        assertEquals(previous, runBlocking { store.load() })
        val code =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"server":"$answerId","token":"fixture-token","relay":"wss://relay.example","server_static_pubkey":"$key"}"""
                    .toByteArray(),
            )
        live.pairAnswerHost(code)
        compose.onNode(hasTestTag(CHANNEL_LIST_TEST_TAG)).assertIsDisplayed()
        assertEquals("one connect after confirmation", 1, connects.get())
        assertEquals("exact-target readiness is still required", 1, verifications.get())
        assertEquals(ConnectionState.Offline, compatibility.value)
        runBlocking {
            assertEquals(previous, store.loadById(previousId)?.record)
            assertEquals(answer, store.loadById(answerId)?.record)
            assertTrue("new host name is saved", !store.loadById(answerId)?.displayName.isNullOrBlank())
        }
    }
}
