package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.ManagedSessionPump
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Application-only connections shared by the real phone graph and its independent peer. */
internal class SuiteConnections private constructor(
    parent: CoroutineScope,
    private val tape: RecordedSuiteTape?,
    private val capture: SuiteCapture?,
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val transports = mutableMapOf<String, RecordedTransport>()
    private val pumps = mutableMapOf<String, ReplayPump>()
    private val waiting = mutableMapOf<String, MutableList<SuiteStep>>()
    private val pending = AtomicInteger()
    private val failure = AtomicReference<Throwable?>()
    private val controls = AtomicLong()
    private val deliveries = Channel<Delivery>(Channel.UNLIMITED)
    private val readers = CopyOnWriteArrayList<Job>()

    private sealed interface Delivery {
        data class Steps(
            val values: List<SuiteStep>,
        ) : Delivery

        data class Barrier(
            val done: CompletableDeferred<Unit>,
        ) : Delivery
    }

    init {
        scope.launch {
            for (delivery in deliveries) {
                when (delivery) {
                    is Delivery.Barrier -> delivery.done.complete(Unit)
                    is Delivery.Steps -> for (step in delivery.values) {
                        try {
                            delay(step.delayMs)
                            route(step)
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                        }
                    }
                }
            }
        }
        tape?.initial()?.let(::enqueue)
    }

    @Synchronized fun transport(
        owner: String,
        live: () -> RelayTransport,
    ): RelayTransport {
        check(owner !in transports) { "Duplicate recorded suite connection owner" }
        return RecordedTransport(owner, if (capture != null) live() else null).also { transports[owner] = it }
    }

    fun pump(
        transport: RelayTransport,
        live: () -> ManagedSessionPump,
    ): ManagedSessionPump {
        val connection = transport as RecordedTransport
        return if (capture != null) ObservedPump(connection, live()) else ReplayPump(connection)
    }

    @Synchronized private fun enqueue(values: List<SuiteStep>) {
        if (values.isEmpty()) return
        pending.addAndGet(values.size)
        check(deliveries.trySend(Delivery.Steps(values)).isSuccess) { "Recorded suite delivery closed" }
    }

    @Synchronized private fun request(
        owner: String,
        frame: Envelope,
    ): Boolean {
        val result = checkNotNull(tape).send(owner, frame)
        enqueue(result.deliveries)
        return result.accepted
    }

    @Synchronized private fun lifecycle(
        direction: String,
        owner: String,
    ) {
        if (capture != null) {
            capture.observe(SuiteStep(direction, owner))
        } else {
            enqueue(checkNotNull(tape).lifecycle(direction, owner))
        }
    }

    @Synchronized private fun route(step: SuiteStep) {
        if (step.direction == "transport_down") {
            val code = step.detail["code"]?.jsonPrimitive?.intOrNull
            val reason = step.detail["reason"]?.jsonPrimitive?.contentOrNull
            checkNotNull(transports[step.owner]).remoteDown(code, reason)
            pending.decrementAndGet()
            return
        }
        val pump = pumps[step.owner]
        if (pump == null) {
            waiting.getOrPut(step.owner) { mutableListOf() } += step
        } else {
            pump.deliver(step)
            pending.decrementAndGet()
        }
    }

    @Synchronized private fun register(pump: ReplayPump) {
        check(pumps.put(pump.connection.owner, pump) == null) { "Duplicate suite pump" }
        waiting.remove(pump.connection.owner).orEmpty().forEach {
            pump.deliver(it)
            pending.decrementAndGet()
        }
    }

    fun control(
        action: String,
        body: String,
        live: () -> Unit,
    ) {
        val frame =
            Envelope(
                controls.incrementAndGet(),
                "test-control:$action",
                java.time.Instant
                    .now()
                    .toString(),
                buildJsonObject { put("body", body) },
            )
        if (capture != null) {
            capture.observe(SuiteStep("client", "control", frame))
            live()
        } else {
            check(request("control", frame)) { "Recorded suite control rejected" }
        }
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        deliveries.send(Delivery.Barrier(done))
        done.await()
        failure.get()?.let { throw IllegalStateException("Recorded suite delivery failed", it) }
    }

    suspend fun awaitReaders() {
        val caller = currentCoroutineContext()[Job]
        readers.filter { it != caller }.forEach { it.join() }
    }

    fun assertComplete() {
        failure.get()?.let { throw IllegalStateException("Recorded suite delivery failed", it) }
        check(pending.get() == 0) { "Recorded suite deliveries remain pending" }
        checkNotNull(tape).assertComplete()
    }

    fun completedCapture(success: Boolean): List<SuiteStep> = checkNotNull(capture).completed(success)

    override fun close() {
        deliveries.close()
        pumps.values.forEach { it.frames.close() }
        scope.cancel()
    }

    private inner class RecordedTransport(
        val owner: String,
        val live: RelayTransport?,
    ) : RelayTransport {
        val localClose = AtomicBoolean()
        private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
        var observePumpState: () -> Unit = {}
        override val inbound = live?.inbound ?: emptyFlow<InnerFrameV2>()
        override val events =
            live?.events?.onStart { currentCoroutineContext()[Job]?.let(readers::add) }?.onEach { event ->
                if (event is TransportEvent.Down) {
                    capture!!.observe(
                        SuiteStep(
                            "transport_down",
                            owner,
                            detail =
                                buildJsonObject {
                                    event.code?.let { put("code", it) }
                                    event.reason?.let { put("reason", it) }
                                },
                        ),
                    )
                }
            } ?: eventsChannel.receiveAsFlow()

        override fun connect() {
            lifecycle("connect", owner)
            if (live != null) {
                live.connect()
            } else {
                check(eventsChannel.trySend(TransportEvent.Up).isSuccess)
            }
        }

        override fun send(frame: InnerFrameV2): Boolean = live?.send(frame) ?: error("Raw transport use during application replay")

        override fun close() {
            if (localClose.get()) return
            observePumpState()
            if (!localClose.compareAndSet(false, true)) return
            lifecycle("close", owner)
            if (live != null) {
                live.close()
            } else {
                pumps[owner]?.localClosed()
            }
        }

        fun remoteDown(
            code: Int?,
            reason: String?,
        ) {
            check(eventsChannel.trySend(TransportEvent.Down(code, reason, null)).isSuccess)
            eventsChannel.close()
        }
    }

    private inner class ObservedPump(
        val connection: RecordedTransport,
        val delegate: ManagedSessionPump,
    ) : ManagedSessionPump {
        private val opened = AtomicBoolean()
        private val ended = AtomicBoolean()
        private val explicitClose = AtomicBoolean()

        init {
            connection.observePumpState = { observeState(delegate.state.value) }
        }

        override val state = delegate.state.onEach(::observeState).stateIn(scope, SharingStarted.Eagerly, delegate.state.value)
        override val inbound =
            delegate.inbound
                .onStart { currentCoroutineContext()[Job]?.let(readers::add) }
                .onEach {
                    observeState(delegate.state.value)
                    capture!!.observe(SuiteStep("daemon", connection.owner, it))
                }.onCompletion { capture!!.observe(SuiteStep("inbound_end", connection.owner)) }

        private fun observeState(value: PumpState) {
            when (value) {
                is PumpState.Open ->
                    if (opened.compareAndSet(false, true)) {
                        capture!!.observe(SuiteStep("open", connection.owner, capabilities = value.capabilities))
                    }
                is PumpState.Closed ->
                    if (!explicitClose.get() && !connection.localClose.get() && ended.compareAndSet(false, true)) {
                        capture!!.observe(SuiteStep("down", connection.owner))
                    }
                else -> Unit
            }
        }

        override fun start() = delegate.start()

        override fun send(envelope: Envelope): Boolean =
            synchronized(this@SuiteConnections) {
                observeState(delegate.state.value)
                capture!!.send(connection.owner, envelope) { delegate.send(envelope) }
            }

        override fun close() {
            explicitClose.set(true)
            delegate.close()
        }
    }

    private inner class ReplayPump(
        val connection: RecordedTransport,
    ) : ManagedSessionPump {
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val frames = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = frames.receiveAsFlow()
        private val started = AtomicBoolean()

        override fun start() {
            if (started.compareAndSet(false, true)) register(this)
        }

        override fun send(envelope: Envelope): Boolean = request(connection.owner, envelope)

        override fun close() = connection.close()

        fun localClosed() {
            state.value = PumpState.Closed(null)
        }

        fun deliver(step: SuiteStep) {
            when (step.direction) {
                "open" -> state.value = PumpState.Open(connection.owner, step.capabilities)
                "down" -> state.value = PumpState.Closed(null)
                "daemon" -> check(frames.trySend(checkNotNull(step.message)).isSuccess)
                "inbound_end" -> frames.close()
                else -> error("Unsupported recorded suite delivery")
            }
        }
    }

    companion object {
        fun replaying(
            scope: CoroutineScope,
            tape: RecordedSuiteTape,
        ) = SuiteConnections(scope, tape, null)

        fun recording(
            scope: CoroutineScope,
            capture: SuiteCapture = SuiteCapture(),
        ) = SuiteConnections(scope, null, capture)
    }
}
