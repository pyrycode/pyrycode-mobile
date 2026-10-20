package de.pyryco.mobile.di

import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicBoolean

/** A generic connection dependency. Production uses the Noise implementation. */
fun interface RelaySessionPumpFactory {
    fun create(
        transport: RelayTransport,
        sessionFactory: NoiseSessionFactory,
        dispatcher: CoroutineDispatcher,
        onClientMinimum: (String) -> Unit,
    ): ManagedSessionPump

    companion object {
        val noise =
            RelaySessionPumpFactory { transport, session, dispatcher, minimum ->
                NoiseSessionPump(transport, session, dispatcher, onClientMinimum = minimum)
            }
    }
}

/** Builds independent connection owners without resolving any application-wide connection state. */
class RelayConnectionFactory(
    private val deviceStaticKeyStore: DeviceStaticKeyStore,
    private val transportFactory: RelayTransportFactory,
    private val clientInfo: NoiseClientInfo,
    private val pushTokens: Flow<String?> = flowOf(null),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The process-wide message trail (#1564), shared by every host's connections. */
    private val messageTrail: MessageTrail = MessageTrail(),
    private val pumpFactory: RelaySessionPumpFactory = RelaySessionPumpFactory.noise,
) {
    /** The supplied immutable record remains the dial, handshake and re-key identity until disposal. */
    fun create(record: PairedServer): RelayConnectionBundle =
        build(
            object : PairedServerStore {
                override suspend fun load(): PairedServer = record

                override suspend fun save(record: PairedServer): Unit = error("connection pairing is read-only")
            },
            // #2175: the exact saved host owns its negotiated thread route; content needs `thread` in its ack.
            authoritativeHostId = record.serverId,
        )

    /** Temporary single-host entry: preserve unpaired startup and latest-saved selection on redial. */
    fun createCompatibility(store: PairedServerStore): RelayConnectionBundle = build(store)

    private fun build(
        store: PairedServerStore,
        authoritativeHostId: String = "",
    ): RelayConnectionBundle =
        RelayConnectionBundle(
            deviceStaticKeyStore,
            transportFactory,
            clientInfo,
            store,
            pushTokens,
            dispatcher,
            ioDispatcher,
            messageTrail,
            pumpFactory,
            authoritativeHostId,
        )
}

/**
 * Owns one reconnecting connection and its replay cursor. Construction starts collectors, not a dial.
 * Use [supervisor] for resumable background close/reconnect; [close] permanently disposes the owner.
 */
class RelayConnectionBundle internal constructor(
    deviceStaticKeyStore: DeviceStaticKeyStore,
    transportFactory: RelayTransportFactory,
    clientInfo: NoiseClientInfo,
    pairedServerStore: PairedServerStore,
    pushTokens: Flow<String?>,
    dispatcher: CoroutineDispatcher,
    ioDispatcher: CoroutineDispatcher,
    messageTrail: MessageTrail,
    pumpFactory: RelaySessionPumpFactory,
    authoritativeHostId: String = "",
    createCoordinator: ((RelayConnectionSupervisor) -> RelayRepositoryCoordinator)? = null,
) {
    val supervisor = RelayConnectionSupervisor(transportFactory, pairedServerStore, dispatcher)
    val sessionFactory: NoiseSessionFactory =
        NoiseSessionFactory(
            deviceStaticKeyStore,
            pairedServerStore,
            clientInfo,
            ioDispatcher,
            // #2175: the daemon-built thread replaces replay, so no hello carries `last_event_id`.
        )
    val coordinator: RelayRepositoryCoordinator =
        createCoordinator?.invoke(supervisor) ?: RelayRepositoryCoordinator(
            connections = supervisor.currentConnection,
            relayStatus = supervisor.relayStatus,
            // Each pump reports its own transport's app-too-old minimum to the supervisor that dialled it (#1008).
            createPump = { transport ->
                pumpFactory.create(
                    transport,
                    sessionFactory,
                    dispatcher,
                    onClientMinimum = { supervisor.recordClientMinimum(transport, it) },
                )
            },
            dispatcher = dispatcher,
            deviceName = clientInfo.deviceName,
            pushTokens = pushTokens,
            messageTrail = messageTrail,
            authoritativeDeviceId = { host ->
                deviceStaticKeyStore.publicKey(host)?.takeIf { it.size == 32 }?.joinToString("") { byte ->
                    "%02x".format(
                        byte.toInt() and 0xff,
                    )
                }
            },
            authoritativeHostId = authoritativeHostId,
        )
    private val disposed = AtomicBoolean(false)

    init {
        coordinator.start()
        RelayLog.d { "event=relay_bundle_created" }
    }

    /** Stops the socket/retry loop and every coordinator, repository and pump collector. Idempotent. */
    fun close() {
        if (!disposed.compareAndSet(false, true)) return
        supervisor.close()
        coordinator.close()
        RelayLog.d { "event=relay_bundle_disposed" }
    }
}
