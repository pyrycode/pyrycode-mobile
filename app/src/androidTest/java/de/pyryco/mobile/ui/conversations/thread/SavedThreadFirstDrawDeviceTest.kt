package de.pyryco.mobile.ui.conversations.thread

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.cache.AuthoritativeThreadWindow
import de.pyryco.mobile.data.cache.AuthoritativeUpsertCache
import de.pyryco.mobile.data.cache.AuthoritativeWindowCache
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheDatabase
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.SavedThreadCoverage
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSnapshotSource
import de.pyryco.mobile.di.SavedAuthoritativeDestination
import de.pyryco.mobile.e2e.awaitEmulatorCpuIdle
import de.pyryco.mobile.e2e.readEmulatorCpuTicks
import de.pyryco.mobile.e2e.withEmulatorCpuSample
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** The GL renderer this process draws with, as the guest reports it. */
private val firstDrawRenderer: String by lazy {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    check(EGL14.eglInitialize(display, version, 0, version, 1))
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val wanted =
        intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
    check(EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, count, 0) && count[0] > 0)
    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
    val context =
        EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
    try {
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        checkNotNull(GLES20.glGetString(GLES20.GL_RENDERER))
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglDestroySurface(display, surface)
    }
}

/**
 * #1949's 1000 ms product bound, or 2000 ms when SwiftShader renders in software, as under the SwANGLE
 * emulator launcher. The 18000-row worst first opens there were 555 ms on HP and 1314 ms on Fractal,
 * against 816 ms on Fractal's native renderer (#2222).
 */
private val firstDrawBoundMs: Long by lazy { if (firstDrawRenderer.contains("SwiftShader", ignoreCase = true)) 2000 else 1000 }

private val firstDrawBoundLabel by lazy { "bound $firstDrawBoundMs ms, renderer $firstDrawRenderer" }

/** Real disk, workers, Android frames and drawn message text; no Compose virtual-time measurement. */
@RunWith(AndroidJUnit4::class)
class SavedThreadFirstDrawDeviceTest {
    @get:Rule val composeRule = createComposeRule()

    private data class Opening(
        val vm: ThreadViewModel,
        val probe: Probe,
        val online: Boolean,
    )

    private fun awaitDeviceReadiness(case: String) {
        val windows = ArrayDeque<Long>()
        val samples = ArrayDeque<String>()
        var stage = "acquire"
        var started = SystemClock.elapsedRealtime()
        try {
            runBlocking {
                awaitEmulatorCpuIdle(
                    sample = {
                        stage = "acquire"
                        started = SystemClock.elapsedRealtime()
                        withEmulatorCpuSample(
                            acquire = {
                                val shellStarted = SystemClock.elapsedRealtime()
                                try {
                                    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /proc/stat")
                                } finally {
                                    Log.i(
                                        "SavedThreadEnvironment",
                                        "event=cpu_acquire case=$case shell_ms=${SystemClock.elapsedRealtime() - shellStarted}",
                                    )
                                }
                            },
                            read = { source ->
                                val acquiredMs = SystemClock.elapsedRealtime() - started
                                stage = "read"
                                started = SystemClock.elapsedRealtime()
                                try {
                                    readCpuCounters(source)
                                } finally {
                                    val readingMs = SystemClock.elapsedRealtime() - started
                                    val durations = "acquire_ms=$acquiredMs read_ms=$readingMs"
                                    if (samples.size == 12) samples.removeFirst()
                                    samples.addLast(durations)
                                    Log.i("SavedThreadEnvironment", "event=cpu_sample case=$case $durations")
                                }
                            },
                        )
                    },
                    onWindow = {
                        if (windows.size == 12) windows.removeFirst()
                        windows.addLast(it)
                        stage = "window"
                        Log.i("SavedThreadEnvironment", "event=cpu_window case=$case idle_pct=$it")
                    },
                )
            }
        } catch (error: Exception) {
            val kind = if (error is TimeoutCancellationException) "deadline" else "sample_failure"
            Log.i(
                "SavedThreadEnvironment",
                "event=cpu_readiness_failed case=$case kind=$kind stage=$stage stage_ms=${SystemClock.elapsedRealtime() - started} windows=$windows samples=$samples",
            )
            throw error
        }
        Log.i("SavedThreadEnvironment", "event=cpu_idle case=$case")
    }

    private val selected = mutableStateOf<Opening?>(null)
    private var activeProbe: Probe? = null

    private suspend fun readCpuCounters(source: ParcelFileDescriptor): Pair<Long, Long> {
        val fd = source.fileDescriptor
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        return readEmulatorCpuTicks { bytes, offset, length ->
            try {
                Os.read(fd, bytes, offset, length).let { if (it == 0) -1 else it }
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) throw error
                0
            }
        }
    }

    @Test fun heldCpuPipeCancelsAndClosesItsDescriptor() {
        val pipe = ParcelFileDescriptor.createPipe()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // The original blocking reader needs a bounded release to let a red run finish.
        worker.launch {
            delay(1500)
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                it.write("cpu 1 2 3 4 5 6 7 8\n".encodeToByteArray())
            }
        }
        try {
            val result =
                runBlocking {
                    runCatching {
                        withEmulatorCpuSample(acquire = { pipe[0] }, read = {
                            withTimeout(100) { readCpuCounters(it) }
                        })
                    }
                }
            assertTrue("held CPU pipe must cancel before its writer releases", result.exceptionOrNull() is TimeoutCancellationException)
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            worker.cancel()
            pipe.forEach { it.close() }
        }
    }

    private class Probe(
        val name: String,
        val newestText: String,
    ) {
        val start = SystemClock.elapsedRealtimeNanos()
        val restored = AtomicLong()
        val snapshot = AtomicLong()
        val complete = AtomicLong()
        val drawn = AtomicLong()

        fun elapsed(nanos: Long) = (nanos - start) / 1_000_000

        fun metBound() = drawn.get() > 0 && elapsed(drawn.get()) <= firstDrawBoundMs

        fun assertBound() {
            assertTrue(
                "$name first drawn newest row: ${elapsed(drawn.get())} ms ($firstDrawBoundLabel)",
                metBound(),
            )
        }
    }

    @Test fun savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen() {
        measureSavedThreads()
    }

    @Test fun databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen() {
        installHost()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "database-first-draw-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdirs())

        fun database() = Room.databaseBuilder(context, ConversationCacheDatabase::class.java, File(root, "cache.db").absolutePath).build()
        val facts =
            (1..18_000).map { id ->
                MobileJson
                    .parseToJsonElement(
                        """{
                    "id":$id,"rev":$id,"order":$id,"kind":"assistant_message",
                    "status":"done","active":false,"shown":true,"summary":"",
                    "content":{"text":"Saved $id."},"future":{"clear":null}
                }""",
                    ).jsonObject
            }
        val expected = facts.takeLast(200)
        try {
            database().use { db ->
                runBlocking {
                    MigratingConversationCache(File(root, "files"), db)
                        .writeAuthoritativeThread(
                            "host",
                            "c",
                            SavedAuthoritativeThread("e", facts, 20_000, 19_000, SavedThreadCoverage(1, 18_000, 1, true)),
                        ).getOrThrow()
                }
            }
            for (online in listOf(false, true)) {
                repeat(2) { opening ->
                    awaitDeviceReadiness("database online=$online opening=$opening")
                    // Construction, the first SQLite open, cache restore and the destination are timed.
                    val probe = Probe("database online=$online opening=$opening", "Saved 18000.")
                    activeProbe = probe
                    val db = database()
                    val disk = MigratingConversationCache(File(root, "files"), db)
                    val measured =
                        object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                            override suspend fun newestAuthoritativeWindow(
                                serverId: String,
                                conversationId: String,
                                size: Int,
                            ): AuthoritativeThreadWindow? =
                                disk.newestAuthoritativeWindow(serverId, conversationId, size).also {
                                    probe.restored.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                                }
                        }
                    val owner = ViewModelStore()
                    lateinit var destination: SavedAuthoritativeDestination
                    lateinit var vm: ThreadViewModel
                    composeRule.runOnUiThread {
                        destination =
                            SavedAuthoritativeDestination(measured, decorate = { repository ->
                                val route = requireNotNull(repository.negotiatedThread)
                                object : ConversationRepository by repository {
                                    override val negotiatedThread =
                                        object : NegotiatedThread() {
                                            override val host = route.host
                                            override val support = route.support
                                            override val store = route.store

                                            override fun observe(conversationId: String): Flow<AuthoritativeThreadSnapshot> =
                                                route.observe(conversationId).onEach {
                                                    if (it.items.isNotEmpty()) {
                                                        probe.snapshot.compareAndSet(
                                                            0,
                                                            SystemClock.elapsedRealtimeNanos(),
                                                        )
                                                    }
                                                }

                                            override fun progress(
                                                conversationId: String,
                                                connection: Any,
                                            ) = route.progress(conversationId, connection)

                                            override suspend fun open(conversationId: String) = route.open(conversationId)

                                            override suspend fun acquire(
                                                conversationId: String,
                                                connection: Any,
                                            ) = route.acquire(conversationId, connection)

                                            override fun older(conversationId: String) = route.older(conversationId)

                                            override fun cancel(conversationId: String) = route.cancel(conversationId)
                                        }
                                }
                            })
                        if (online) destination.connect()
                        vm = destination.open()
                        owner.put("thread", vm)
                        selected.value = Opening(vm, probe, online)
                    }
                    try {
                        composeRule.waitUntil(15000) { probe.drawn.get() > 0 }
                        probe.assertBound()
                        composeRule.waitUntil(15000) {
                            destination.requests.count { it.type in setOf("thread_page", "thread_catch_up") } == if (online) 1 else 0
                        }
                        assertTrue(probe.restored.get() > 0 && probe.snapshot.get() > 0 && probe.complete.get() > 0)
                        composeRule.runOnIdle {
                            assertEquals(expected, requireNotNull(vm.state.value.authoritativeThread).sourceSnapshot.items.map { it.raw })
                            assertEquals(
                                200,
                                vm.state.value.authoritativeThread
                                    ?.items
                                    ?.size,
                            )
                            assertEquals(0, disk.authoritativeFullReads)
                            assertEquals(200, disk.authoritativeReadCounts.itemReads)
                            assertEquals(200, disk.authoritativeReadCounts.itemDecodes)
                            assertEquals(4, disk.authoritativeReadCounts.queries)
                            assertEquals(
                                if (online) 1 else 0,
                                destination.requests.count { it.type in setOf("thread_page", "thread_catch_up") },
                            )
                        }
                        val restoredMs = probe.elapsed(probe.restored.get())
                        val snapshotMs = probe.elapsed(probe.snapshot.get())
                        val contentMs = probe.elapsed(probe.complete.get())
                        val drawnMs = probe.elapsed(probe.drawn.get())
                        Log.i(
                            "SavedThreadFirstDraw",
                            "case=${probe.name} rows=18000 reads=${disk.authoritativeReadCounts} " +
                                "restored_ms=$restoredMs snapshot_ms=$snapshotMs complete_ms=$contentMs drawn_ms=$drawnMs",
                        )
                    } finally {
                        composeRule.runOnUiThread {
                            selected.value = null
                            owner.clear()
                            destination.close()
                        }
                        runBlocking { vm.viewModelScope.coroutineContext[Job]?.join() }
                        composeRule.waitForIdle()
                        db.close()
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun delayedOptionalProjection_cannotHoldFirstNewestDraw_firstOpenAndReopen() {
        measureSavedThreads(projectionDelayMs = firstDrawBoundMs + 100)
    }

    private fun measureSavedThreads(projectionDelayMs: Long = 0) {
        installHost()
        val probes = mutableListOf<Probe>()
        for (name in listOf("ordinary", "fragmented")) {
            val (coverage, rows) =
                if (name == "ordinary") {
                    null to
                        (0 until 20).map {
                            ThreadItem.MessageItem(
                                Message("ordinary-$it", "s", Role.User, "Saved $it.", Instant.fromEpochSeconds(it.toLong()), false),
                            )
                        }
                } else {
                    fragmentedHistoryFixture((0 until 18000).toList())
                }
            withFixture(name, rows, coverage) { source ->
                for (online in listOf(false, true)) {
                    withFixtureCopy(source) { root ->
                        val delegate = HeldNewest()
                        var repo: CachingConversationRepository? = null
                        repeat(2) { opening ->
                            awaitDeviceReadiness("$name online=$online opening=$opening projection_hold_ms=$projectionDelayMs")
                            val probe =
                                Probe("$name online=$online opening=$opening", (rows.last() as ThreadItem.MessageItem).message.content)
                            activeProbe = probe
                            probes += probe
                            if (repo == null) repo = repository(root, delegate)
                            openAndMeasure(requireNotNull(repo), delegate, online, probe, rows, coverage, projectionDelayMs)
                        }
                    }
                }
            }
        }
        assertTrue(
            "$firstDrawBoundLabel: " + probes.joinToString("; ") { "${it.name} drawn_ms=${it.elapsed(it.drawn.get())}" },
            probes.size == 8 && probes.all(Probe::metBound),
        )
    }

    @Test fun slowRestore_negativeControlRejectsTheSameFirstDrawBound() {
        installHost()
        val rows = listOf(ThreadItem.MessageItem(Message("slow", "s", Role.User, "Slow saved row.", Instant.fromEpochSeconds(1), false)))
        withFixture("slow", rows, null) { root ->
            val delegate = HeldNewest()
            awaitDeviceReadiness("negative-control")
            val probe = Probe("negative-control", (rows.last() as ThreadItem.MessageItem).message.content)
            activeProbe = probe
            openAndMeasure(repository(root, delegate, 3000), delegate, false, probe, rows, null)
            assertTrue(probe.elapsed(probe.drawn.get()) >= 3000)
            assertThrows(AssertionError::class.java) { probe.assertBound() }
        }
    }

    private class HeldNewest : ConversationRepository by FakeConversationRepository() {
        override fun observeConversations(filter: ConversationFilter) =
            flowOf(listOf(Conversation("c", "Saved channel", "", "s", emptyList(), true, Instant.fromEpochSeconds(1))))

        val asks = mutableListOf<Pair<String, Int>>()
        val response = CompletableDeferred<HistoryPage>()

        override fun observeMessages(conversationId: String) = flowOf(emptyList<ThreadItem>())

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks += cursor to limit
            return response.await()
        }
    }

    private fun repository(
        root: File,
        delegate: HeldNewest,
        delayMs: Long = 0,
    ): CachingConversationRepository {
        fun measuredWorker(
            dispatcher: CoroutineDispatcher,
            worker: String,
        ) = object : CoroutineDispatcher() {
            override fun dispatch(
                context: CoroutineContext,
                block: Runnable,
            ) {
                val queued = SystemClock.elapsedRealtimeNanos()
                val probe = activeProbe
                dispatcher.dispatch(context) {
                    val started = SystemClock.elapsedRealtimeNanos()
                    val cpu = Debug.threadCpuTimeNanos()
                    try {
                        block.run()
                    } finally {
                        Log.i(
                            "SavedThreadCacheWorker",
                            "event=cache_worker_run worker=$worker case=${probe?.name} queue_ms=${(started - queued) / 1_000_000} wall_ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000} cpu_ms=${(Debug.threadCpuTimeNanos() - cpu) / 1_000_000}",
                        )
                    }
                }
            }
        }
        val disk = FileConversationCache(root, measuredWorker(Dispatchers.IO, "io"))
        val measured =
            object : ConversationCache by disk {
                override suspend fun readThread(
                    serverId: String,
                    conversationId: String,
                ): List<ThreadItem> {
                    val probe = activeProbe
                    delay(delayMs)
                    return disk
                        .readThread(
                            serverId,
                            conversationId,
                        ).also { probe?.restored?.compareAndSet(0, SystemClock.elapsedRealtimeNanos()) }
                }
            }
        return CachingConversationRepository(
            delegate,
            measured,
            "host",
            processingDispatcher = measuredWorker(Dispatchers.Default, "default"),
        )
    }

    private fun installHost() {
        Log.i("SavedThreadFirstDraw", "event=first_draw_bound bound_ms=$firstDrawBoundMs renderer=$firstDrawRenderer")
        composeRule.setContent {
            val opening = selected.value
            val vm = opening?.vm
            val view = LocalView.current
            if (vm != null) {
                val probe = opening.probe
                val online = opening.online
                val state by vm.state.collectAsState()
                DisposableEffect(vm) {
                    val observer = view.viewTreeObserver
                    val listener =
                        ViewTreeObserver.OnDrawListener {
                            val nodes =
                                view
                                    .composeRoot()
                                    ?.semanticsOwner
                                    ?.getAllSemanticsNodes(mergingEnabled = false)
                                    .orEmpty()
                            val viewport =
                                nodes
                                    .firstOrNull {
                                        it.config.getOrNull(
                                            SemanticsProperties.TestTag,
                                        ) == "thread-message-region"
                                    }?.boundsInRoot
                            val newest =
                                nodes.firstOrNull { node ->
                                    node.config.getOrNull(SemanticsProperties.Text)?.any {
                                        it.text ==
                                            probe.newestText
                                    } ==
                                        true
                                }
                            if (viewport != null &&
                                newest != null &&
                                newest.layoutInfo.isPlaced &&
                                newest.boundsInRoot.width > 0 &&
                                newest.boundsInRoot.height > 0 &&
                                newest.boundsInRoot.left >= viewport.left &&
                                newest.boundsInRoot.right <= viewport.right &&
                                newest.boundsInRoot.top >= viewport.top &&
                                newest.boundsInRoot.bottom <= viewport.bottom
                            ) {
                                view.viewTreeObserver.registerFrameCommitCallback {
                                    probe.drawn.compareAndSet(
                                        0,
                                        SystemClock.elapsedRealtimeNanos(),
                                    )
                                }
                            }
                        }
                    observer.addOnDrawListener(listener)
                    onDispose { observer.removeOnDrawListener(listener) }
                }
                Box {
                    if ((state.authoritativeThread?.items ?: state.items).isNotEmpty()) {
                        probe.complete.compareAndSet(
                            0,
                            SystemClock.elapsedRealtimeNanos(),
                        )
                    }
                    PyrycodeMobileTheme(darkTheme = true) {
                        ThreadScreen(state, {
                        }, {
                        }, if (online) ConnectionState.Connected else ConnectionState.Offline, {
                        }, onDemandOlderHistory = vm::onDemandOlderHistory, onDemandUnsignedHistoryGap = vm::onDemandUnsignedHistoryGap)
                    }
                }
            }
        }
    }

    private fun openAndMeasure(
        repo: CachingConversationRepository,
        delegate: HeldNewest,
        online: Boolean,
        probe: Probe,
        rows: List<ThreadItem>,
        coverage: HistoryCoverage?,
        projectionDelayMs: Long = 0,
    ) {
        val projectionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val projection =
            if (projectionDelayMs == 0L) {
                Dispatchers.Default
            } else {
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        val queued = SystemClock.elapsedRealtimeNanos()
                        projectionScope.launch {
                            try {
                                delay(projectionDelayMs)
                            } finally {
                                val started = SystemClock.elapsedRealtimeNanos()
                                val cpu = Debug.threadCpuTimeNanos()
                                try {
                                    block.run()
                                } finally {
                                    Log.i(
                                        "SavedThreadCacheWorker",
                                        "event=projection_worker_run case=${probe.name} hold_ms=$projectionDelayMs queue_ms=${(started - queued) / 1_000_000} wall_ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000} cpu_ms=${(Debug.threadCpuTimeNanos() - cpu) / 1_000_000}",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        val store = ViewModelStore()
        val beforeAsks = delegate.asks.size
        val measuredRepo =
            object : ConversationRepository by repo, ThreadSnapshotSource {
                override fun observeThreadSnapshot(conversationId: String) =
                    repo.observeThreadSnapshot(conversationId).onEach {
                        if (it.rows.isNotEmpty()) probe.snapshot.compareAndSet(0, SystemClock.elapsedRealtimeNanos())
                    }
            }
        composeRule.runOnUiThread {
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                    measuredRepo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                    repositoryAvailable = flowOf(online),
                    contentScheduling = ThreadContentScheduling(),
                    projectionDispatcher = projection,
                )
            store.put("thread", vm)
            selected.value = Opening(vm, probe, online)
            Log.i(
                "SavedThreadFirstDraw",
                "event=opening_installed case=${probe.name} elapsed_ms=${probe.elapsed(SystemClock.elapsedRealtimeNanos())}",
            )
        }
        try {
            composeRule.waitUntil(15000) { probe.drawn.get() > 0 }
            // Independent history validation may finish after the recorded committed frame.
            val expectedAnchors =
                coverage
                    ?.unsignedGaps
                    ?.map { it.anchor }
                    ?.let { listOf(0uL) + it }
                    .orEmpty()
            composeRule.waitUntil(15000) {
                val state =
                    selected.value
                        ?.vm
                        ?.state
                        ?.value
                state?.historyMarkers?.map { it.unsignedAnchor } == expectedAnchors &&
                    delegate.asks.size == if (online) beforeAsks + 1 else beforeAsks
            }
            composeRule.runOnIdle {
                val state = requireNotNull(selected.value).vm.state.value
                assertEquals(rows, state.items)
                assertEquals(if (online) beforeAsks + 1 else beforeAsks, delegate.asks.size)
                assertTrue(delegate.asks.all { it == "" to 200 })
                assertFalse(delegate.response.isCompleted)
                assertEquals(
                    coverage
                        ?.unsignedGaps
                        ?.map { it.anchor }
                        ?.let { listOf(0uL) + it }
                        .orEmpty(),
                    state.historyMarkers.map { it.unsignedAnchor },
                )
            }
            Log.i(
                "SavedThreadFirstDraw",
                "case=${probe.name} rows=${rows.size} durable=${coverage?.unsignedSpans?.sumOf {
                    (it.last - it.first + 1u).toLong()
                } ?: rows.size.toLong()} spans=${coverage?.unsignedSpans?.size ?: 0} restored_ms=${probe.elapsed(
                    probe.restored.get(),
                )} snapshot_ms=${probe.elapsed(
                    probe.snapshot.get(),
                )} complete_ms=${probe.elapsed(probe.complete.get())} drawn_ms=${probe.elapsed(probe.drawn.get())}",
            )
        } finally {
            composeRule.runOnUiThread {
                selected.value = null
                store.clear()
                Log.i(
                    "SavedThreadFirstDraw",
                    "event=opening_cleared case=${probe.name} elapsed_ms=${probe.elapsed(SystemClock.elapsedRealtimeNanos())}",
                )
            }
            projectionScope.cancel()
            composeRule.waitForIdle()
        }
    }

    private fun View.composeRoot(): ViewRootForTest? {
        if (this is ViewRootForTest) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).composeRoot()?.let { return it }
        return null
    }

    private fun withFixtureCopy(
        source: File,
        block: (File) -> Unit,
    ) {
        val root = File(source.parentFile, "first-draw-copy-${SystemClock.elapsedRealtimeNanos()}")
        try {
            check(source.copyRecursively(root))
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withFixture(
        name: String,
        rows: List<ThreadItem>,
        coverage: HistoryCoverage?,
        block: (File) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "first-draw-$name-${SystemClock.elapsedRealtimeNanos()}")
        try {
            runBlocking {
                val cache = FileConversationCache(root)
                cache.writeThread("host", "c", rows).getOrThrow()
                if (coverage != null) cache.writeHistoryPosition("host", "c", HistoryPosition("saved", false, coverage)).getOrThrow()
            }
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
