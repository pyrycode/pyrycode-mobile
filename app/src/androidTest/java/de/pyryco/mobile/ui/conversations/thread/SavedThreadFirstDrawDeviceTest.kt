package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import de.pyryco.mobile.data.cache.AuthoritativeDisplayCursor
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
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
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.SavedThreadCoverage
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.di.SavedAuthoritativeDestination
import de.pyryco.mobile.e2e.awaitEmulatorCpuIdle
import de.pyryco.mobile.e2e.readEmulatorCpuTicks
import de.pyryco.mobile.e2e.withEmulatorCpuSample
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

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
        val observed = AtomicLong()

        fun elapsed(nanos: Long) = (nanos - start) / 1_000_000

        fun metBound() = drawn.get() > 0 && elapsed(drawn.get()) <= firstDrawBoundMs

        fun assertBound() {
            assertTrue(
                "$name first drawn newest row: ${elapsed(drawn.get())} ms ($firstDrawBoundLabel)",
                metBound(),
            )
        }
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
            val seedDatabase = database()
            try {
                runBlocking {
                    MigratingConversationCache(File(root, "files"), seedDatabase)
                        .writeAuthoritativeThread(
                            "host",
                            "c",
                            SavedAuthoritativeThread("e", facts, 20_000, 19_000, SavedThreadCoverage(1, 18_000, 1, true)),
                        ).getOrThrow()
                }
            } finally {
                seedDatabase.close()
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
                            assertEquals(5, disk.authoritativeReadCounts.queries)
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

    @Test fun databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen() {
        installHost()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.noBackupFilesDir, "database-pages-${SystemClock.elapsedRealtimeNanos()}")
        check(root.mkdirs())

        fun database() = Room.databaseBuilder(context, ConversationCacheDatabase::class.java, File(root, "cache.db").absolutePath).build()
        val facts =
            (1..18_000).map { id ->
                MobileJson
                    .parseToJsonElement(
                        """{"id":$id,"rev":$id,"order":$id,"kind":"assistant_message",
                "status":"done","active":false,"shown":true,"summary":"","content":{"text":"Saved $id."}}""",
                    ).jsonObject
            }
        try {
            val seed = database()
            try {
                runBlocking {
                    MigratingConversationCache(File(root, "files"), seed)
                        .writeAuthoritativeThread(
                            "host",
                            "c",
                            SavedAuthoritativeThread("e", facts, 20_000, 19_000, SavedThreadCoverage(17_900, 18_000, 17_900, true)),
                        ).getOrThrow()
                }
            } finally {
                seed.close()
            }
            for (online in listOf(false, true)) {
                repeat(2) { opening ->
                    awaitDeviceReadiness("database-pages online=$online opening=$opening")
                    val probe = Probe("database-pages online=$online opening=$opening", "Saved 18000.")
                    activeProbe = probe
                    val db = database()
                    val disk = MigratingConversationCache(File(root, "files"), db)
                    val attempts = AtomicInteger()
                    val release = CompletableDeferred<Unit>()
                    var fail = true
                    val cache =
                        object : ConversationCache by disk, AuthoritativeWindowCache by disk, AuthoritativeUpsertCache by disk {
                            override suspend fun beforeAuthoritativeWindow(
                                serverId: String,
                                conversationId: String,
                                cursor: AuthoritativeDisplayCursor,
                                admitted: Set<Pair<Long, Boolean>>,
                                size: Int,
                            ): AuthoritativeThreadWindow? {
                                attempts.incrementAndGet()
                                val page = disk.beforeAuthoritativeWindow(serverId, conversationId, cursor, admitted, size)
                                release.await()
                                return if (fail) null else page
                            }
                        }
                    val owner = ViewModelStore()
                    lateinit var destination: SavedAuthoritativeDestination
                    lateinit var vm: ThreadViewModel
                    composeRule.runOnUiThread {
                        destination = SavedAuthoritativeDestination(cache)
                        if (online) destination.connect()
                        vm = destination.open()
                        owner.put("thread", vm)
                        selected.value = Opening(vm, probe, online)
                    }
                    try {
                        composeRule.waitUntil(15_000) { probe.drawn.get() > 0 }
                        probe.assertBound()
                        composeRule.waitUntil(15_000) {
                            vm.state.value.authoritativeThread
                                ?.rows
                                ?.size == 200
                        }
                        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(199)
                        composeRule.waitForIdle()
                        assertEquals(0, attempts.get())
                        val list = composeRule.onNode(hasScrollToIndexAction())
                        val listBounds = list.fetchSemanticsNode().boundsInRoot
                        val header = composeRule.onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot
                        val composer = composeRule.onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot
                        val readingHeight = composer.top - header.bottom
                        val start = header.bottom - listBounds.top + readingHeight * 0.2f
                        val distance = readingHeight * 0.4f
                        assertTrue(start + distance < composer.top - listBounds.top)
                        Log.i(
                            "SavedThreadPaging",
                            "case=${probe.name} list=$listBounds header=$header composer=$composer start=$start distance=$distance",
                        )
                        list.performTouchInput {
                            down(Offset(center.x, start))
                            moveBy(Offset(0f, distance), delayMillis = 100)
                            advanceEventTime(250)
                            up()
                        }
                        composeRule.waitUntil(15_000) { attempts.get() == 1 }
                        assertEquals(
                            200,
                            vm.state.value.authoritativeThread
                                ?.rows
                                ?.size,
                        )
                        release.complete(Unit)
                        composeRule.waitUntil(15_000) { vm.state.value.historyTail == ThreadHistoryTail.Retry }
                        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(200)
                        composeRule.waitForIdle()
                        assertEquals(1, attempts.get())
                        fail = false
                        val retry =
                            composeRule.onNodeWithContentDescription(
                                context.getString(de.pyryco.mobile.R.string.cd_thread_history_retry),
                            )
                        retry.assertIsDisplayed()
                        val retryBounds = retry.fetchSemanticsNode().boundsInRoot
                        assertTrue(retryBounds.top >= header.bottom && retryBounds.bottom <= composer.top)
                        Log.i("SavedThreadPaging", "case=${probe.name} retry=$retryBounds attempts=${attempts.get()}")
                        retry.performClick()
                        composeRule.waitUntil(15_000) {
                            vm.state.value.authoritativeThread
                                ?.rows
                                ?.size == 400
                        }
                        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(399)
                        composeRule.onNodeWithText("Saved 17601.").assertIsDisplayed()
                        val olderBounds = composeRule.onNodeWithText("Saved 17601.").fetchSemanticsNode().boundsInRoot
                        val viewport = composeRule.onNodeWithTag("thread-message-region").fetchSemanticsNode().boundsInRoot
                        assertTrue(
                            olderBounds.left >= viewport.left &&
                                olderBounds.right <= viewport.right &&
                                olderBounds.top >= viewport.top &&
                                olderBounds.bottom <= viewport.bottom,
                        )
                        runBlocking { delay(300) }
                        composeRule.runOnIdle {
                            assertEquals(2, attempts.get())
                            assertEquals(
                                400,
                                vm.state.value.authoritativeThread
                                    ?.rows
                                    ?.size,
                            )
                            assertEquals(
                                if (online) 1 else 0,
                                destination.requests.count {
                                    it.type in
                                        setOf("thread_page", "thread_catch_up")
                                },
                            )
                            assertEquals(
                                facts.takeLast(400),
                                vm.state.value.authoritativeThread
                                    ?.sourceSnapshot
                                    ?.items
                                    ?.map { it.raw },
                            )
                            assertEquals(0, disk.authoritativeFullReads)
                            assertEquals(200, disk.authoritativeReadCounts.itemDecodes)
                        }
                        Log.i(
                            "SavedThreadPaging",
                            "case=${probe.name} attempts=${attempts.get()} rows=400 " +
                                "counts=${disk.authoritativeReadCounts} drawn_ms=${probe.elapsed(probe.drawn.get())} " +
                                "older=$olderBounds viewport=$viewport",
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
                            if (probe.complete.get() > 0 && probe.observed.compareAndSet(0, 1)) {
                                Log.i(
                                    "SavedThreadFirstDraw",
                                    "event=frame_observed case=${probe.name} viewport=$viewport newest=${newest?.boundsInRoot} " +
                                        "placed=${newest?.layoutInfo?.isPlaced} nodes=${nodes.size}",
                                )
                            }
                            if (probe.drawn.get() == 0L &&
                                viewport != null &&
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
                                // OnDraw can run after this traversal captured commit callbacks.
                                // Require a subsequent frame, still containing the exact visible text.
                                view.postInvalidateOnAnimation()
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
                        }, onDemandOlderHistory = vm::onDemandOlderHistory, onRetryOlderHistory = vm::onRetryOlderHistory)
                    }
                }
            }
        }
    }

    private fun View.composeRoot(): ViewRootForTest? {
        if (this is ViewRootForTest) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).composeRoot()?.let { return it }
        return null
    }
}
