package de.pyryco.mobile.di

import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.atomic.AtomicBoolean

/** Builds independent connection owners without resolving any application-wide connection state. */
class RelayConnectionFactory(
    private val deviceStaticKeyStore: DeviceStaticKeyStore,
    private val transportFactory: RelayTransportFactory,
    private val clientInfo: NoiseClientInfo,
    private val pushTokens: Flow<String?> = flowOf(null),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The supplied immutable record remains the dial, handshake and re-key identity until disposal. */
    fun create(record: PairedServer): RelayConnectionBundle =
        build(
            object : PairedServerStore {
                override suspend fun load(): PairedServer = record

                override suspend fun save(record: PairedServer): Unit = error("connection pairing is read-only")
            },
        )

    /** Temporary single-host entry: preserve unpaired startup and latest-saved selection on redial. */
    fun createCompatibility(store: PairedServerStore): RelayConnectionBundle = build(store)

    private fun build(store: PairedServerStore): RelayConnectionBundle =
        RelayConnectionBundle(deviceStaticKeyStore, transportFactory, clientInfo, store, pushTokens, dispatcher, ioDispatcher)
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
) {
    val supervisor = RelayConnectionSupervisor(transportFactory, pairedServerStore, dispatcher)
    val sessionFactory: NoiseSessionFactory =
        NoiseSessionFactory(
            deviceStaticKeyStore,
            pairedServerStore,
            clientInfo,
            ioDispatcher,
            // Invoked at hello-build, after all members exist and the coordinator has started.
            lastEventId = { coordinator.replayCursor.latest },
        )
    val coordinator: RelayRepositoryCoordinator =
        RelayRepositoryCoordinator(
            connections = supervisor.currentConnection,
            relayStatus = supervisor.relayStatus,
            createPump = { transport -> NoiseSessionPump(transport, sessionFactory, dispatcher) },
            dispatcher = dispatcher,
            deviceName = clientInfo.deviceName,
            pushTokens = pushTokens,
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
