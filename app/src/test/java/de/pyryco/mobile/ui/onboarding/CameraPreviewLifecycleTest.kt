package de.pyryco.mobile.ui.onboarding

import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class CameraPreviewLifecycleTest {
    private val future = PendingProviderFuture()
    private val callbacks = ArrayDeque<Runnable>()
    private var binds = 0
    private var unbinds = 0
    private var errors = 0

    private fun binding() =
        CameraPreviewBinding(
            future = future,
            executor = Executor { callbacks.add(it) },
            bind = { binds++ },
            unbind = { unbinds++ },
            onError = { errors++ },
        )

    @Test
    fun leavingScannerBeforeInitialization_doesNotWaitForProvider() {
        val binding = binding()
        binding.dispose()

        // On main, a real pending future would block; this fake records that read without hanging.
        assertEquals("disposal must not read the pending provider", 0, future.pendingReads)
        assertEquals(0, unbinds)
        assertEquals(0, errors)
    }

    @Test
    fun initializationCompletingAfterExit_doesNotBindOrReportError() {
        val binding = binding()
        binding.dispose()
        future.complete()
        callbacks.removeFirst().run()

        assertEquals("late initialization must not bind a departed scanner", 0, binds)
        assertEquals(0, unbinds)
        assertEquals(0, errors)
    }

    @Test
    fun completionQueuedBeforeExit_doesNotBindAfterDisposal() {
        val binding = binding()
        future.complete()
        binding.dispose()
        callbacks.removeFirst().run()

        assertEquals(0, binds)
        assertEquals(0, unbinds)
        assertEquals(0, errors)
    }

    @Test
    fun failedBinding_stillUnbindsTheInitializedProvider() {
        val binding =
            CameraPreviewBinding(
                future = future,
                executor = Executor { callbacks.add(it) },
                bind = { error("camera binding failed") },
                unbind = { unbinds++ },
                onError = { errors++ },
            )
        future.complete()
        callbacks.removeFirst().run()
        binding.dispose()

        assertEquals(1, unbinds)
        assertEquals(1, errors)
    }

    @Test
    fun initializedCamera_isUnboundWhenScannerLeaves() {
        val binding = binding()
        future.complete()
        callbacks.removeFirst().run()
        assertEquals(1, binds)
        binding.dispose()

        assertEquals(1, unbinds)
        assertEquals(0, errors)
    }

    @Test
    fun failedInitializationAfterExit_doesNotReportErrorToDepartedScanner() {
        val binding = binding()
        binding.dispose()
        future.complete(failed = true)
        callbacks.removeFirst().run()

        assertEquals(0, binds)
        assertEquals(0, unbinds)
        assertEquals(0, errors)
    }

    @Test
    fun failedInitializationWhileMounted_reportsCameraError() {
        val binding = binding()
        future.complete(failed = true)
        callbacks.removeFirst().run()
        binding.dispose()

        assertEquals(0, binds)
        assertEquals(0, unbinds)
        assertEquals(1, errors)
    }

    private class PendingProviderFuture : ListenableFuture<Unit> {
        private var done = false
        private var failed = false
        private lateinit var listener: Runnable
        private lateinit var executor: Executor
        var pendingReads = 0
            private set

        override fun addListener(
            listener: Runnable,
            executor: Executor,
        ) {
            this.listener = listener
            this.executor = executor
        }

        fun complete(failed: Boolean = false) {
            done = true
            this.failed = failed
            executor.execute(listener)
        }

        override fun get() {
            if (!done) {
                pendingReads++
                error("provider initialization is pending")
            }
            check(!failed) { "provider initialization failed" }
        }

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ) = get()

        override fun isDone() = done

        override fun cancel(mayInterruptIfRunning: Boolean) = false

        override fun isCancelled() = false
    }
}
