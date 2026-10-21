package de.pyryco.mobile.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.ui.conversations.thread.AttachmentRead
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.ThreadContentScheduling
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.jsonObject
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled transport, real production host registry, negotiated cache binding and destination. */
internal class SavedAuthoritativeDestination(
    cache: ConversationCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    worker: CoroutineDispatcher = Dispatchers.Default,
    awaitFrame: (suspend () -> Long)? = null,
    decorate: (ConversationRepository) -> ConversationRepository = { it },
) : Closeable {
    private val record = PairedServer("host", "fixture-token", "wss://fixture.invalid", "fixture-key")
    private val paired =
        object : PairedServerCollectionStore {
            override suspend fun load() = record

            override suspend fun save(record: PairedServer) = Unit

            override suspend fun list() = listOf(PairedServerEntry(record))

            override suspend fun loadById(serverId: String) = list().firstOrNull { it.record.serverId == serverId }

            override suspend fun setDisplayName(
                serverId: String,
                displayName: String?,
            ) = Unit

            override suspend fun remove(serverId: String) = Unit
        }
    private val saved = ObservablePairedServerStore(paired) {}
    private val connections = MutableStateFlow<RelayTransport?>(null)
    private val relay = MutableStateFlow<RelayLinkStatus>(RelayLinkStatus.Offline)
    private val pumps = CopyOnWriteArrayList<Pump>()
    val coordinator =
        RelayRepositoryCoordinator(connections, relay, {
            Pump().also {
                it.state.value = PumpState.Open("fixture-connection", setOf("interactive", "thread"))
                pumps += it
            }
        }, dispatcher = dispatcher, authoritativeHostId = "host")
    private val keys =
        object : DeviceStaticKeyStore {
            override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair = error("fixture must not perform a Noise handshake")

            override suspend fun publicKey(serverId: String) = ByteArray(32)
        }
    private val bundle =
        RelayConnectionBundle(
            keys,
            RelayTransportFactory { Transport() },
            NoiseClientInfo("fixture", "1"),
            saved,
            flowOf(null),
            dispatcher,
            worker,
            MessageTrail(),
            RelaySessionPumpFactory.noise,
            authoritativeHostId = "host",
            createCoordinator = { coordinator },
        )
    private val registry = RelayConnectionRegistry(saved, { bundle }, dispatcher)
    private val preferences =
        AppPreferences(
            object : DataStore<Preferences> {
                override val data = flowOf(emptyPreferences())

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
            },
        )
    private val factory =
        ThreadDestinationFactory(
            true,
            registry,
            FakeConversationRepository(),
            saved,
            decorate,
            cache = cache,
            attachmentReader = lazy { AttachmentReader { AttachmentRead.Unreadable } },
            contentScheduling = if (awaitFrame == null) ThreadContentScheduling(worker) else ThreadContentScheduling(worker, awaitFrame),
        )

    val requests get() = pumps.flatMap { it.sent }

    val hostReady get() = registry.connectionFor("host") != null

    fun push(envelope: Envelope) {
        pumps.last().push(envelope)
    }

    fun reply(
        request: Envelope,
        payload: String,
    ) {
        pumps.last().reply(request, payload)
    }

    fun resetNewest() {
        val request = requests.last { it.type in setOf("thread_catch_up", "thread_page") }
        pumps.last().reset(request)
    }

    fun connect() {
        relay.value = RelayLinkStatus.Connected
        connections.value = Transport()
    }

    fun disconnect() {
        connections.value = null
        relay.value = RelayLinkStatus.Offline
    }

    fun open() =
        factory.thread(
            SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
            ComposerDraftStore(),
            preferences,
        )

    override fun close() {
        registry.dispose()
        bundle.close()
    }

    private class Pump : ManagedSessionPump {
        private val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = frames.receiveAsFlow()
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val sent = CopyOnWriteArrayList<Envelope>()

        fun push(envelope: Envelope) {
            frames.trySend(envelope).getOrThrow()
        }

        fun reset(request: Envelope) {
            frames
                .trySend(
                    Envelope(
                        request.id + 1000,
                        "thread_reset",
                        "2026-10-20T00:00:00Z",
                        MobileJson.parseToJsonElement("""{"conversation_id":"c","epoch":"e","version":1000}""").jsonObject,
                        inReplyTo = request.id,
                    ),
                ).getOrThrow()
        }

        fun reply(
            request: Envelope,
            payload: String,
        ) {
            frames
                .trySend(
                    Envelope(
                        request.id + 1000,
                        "thread_items",
                        "2026-10-20T00:00:00Z",
                        MobileJson.parseToJsonElement(payload).jsonObject,
                        inReplyTo = request.id,
                    ),
                ).getOrThrow()
        }

        override fun start() = Unit

        override fun close() {
            frames.close()
        }

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            if (envelope.type == "list_conversations") {
                frames.trySend(
                    Envelope(
                        id = envelope.id + 1000,
                        type = "conversations",
                        ts = "2026-10-20T00:00:00Z",
                        payload =
                            MobileJson
                                .parseToJsonElement(
                                    """{"conversations":[{"id":"c","name":"Saved channel","is_promoted":true,
                                "cwd":"","last_message_ts":"2026-10-20T00:00:00Z",
                                "last_used_at":"2026-10-20T00:00:00Z"}]}""",
                                ).jsonObject,
                        inReplyTo = envelope.id,
                    ),
                )
            }
            return true
        }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }
}
