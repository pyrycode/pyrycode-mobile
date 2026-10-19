package de.pyryco.mobile.verification

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Per-test time limit for every JVM unit test, Robolectric and sharedTest classes included. A hung test once
 * blocked the unit-test gate silently until the dispatcher killed it an hour later, leaving no trace (#2161).
 *
 * Registered through META-INF/services; app/build.gradle.kts sets the limit. When a test runs past it, this
 * prints a full thread dump naming the test, then interrupts the thread running it. Robolectric forwards the
 * interrupt to its main thread, and a blocking call such as runBlocking or a parked lock throws, so the test
 * fails and the run continues. A test that is still running after the grace period, or that passes despite
 * the interrupt, or a second timeout in the same JVM, halts the test JVM so Gradle fails the run at once.
 */
class TestTimeoutWatchdog internal constructor(
    private val limitSeconds: Long?,
    private val timer: ScheduledExecutorService,
    private val interrupt: (Thread) -> Unit = { it.interrupt() },
    private val terminate: (Int) -> Unit = { Runtime.getRuntime().halt(it) },
    private val graceSeconds: Long = GRACE_SECONDS,
    private val diagnostic: ((String) -> Unit)? = null,
) : TestExecutionListener {
    constructor() : this(
        System.getProperty(LIMIT_PROPERTY)?.toLongOrNull()?.takeIf { it > 0 },
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "test-timeout-watchdog").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true },
    )

    private class Execution(
        val test: TestIdentifier,
        val runner: Thread,
    ) {
        val done = CountDownLatch(1)
        var deadline: ScheduledFuture<*>? = null
        var timedOut = false
    }

    private val running = mutableMapOf<String, Execution>()
    private var timeoutCount = 0

    private fun trace(
        event: String,
        test: TestIdentifier? = null,
        detail: String = "",
    ) {
        if (System.getenv("PYRY_TEST_WATCHDOG_TRACE") != "1") return
        report(
            "WATCHDOG event=$event id=${test?.uniqueId} thread=${Thread.currentThread().name} " +
                "threadId=${Thread.currentThread().threadId()} running=${running.keys} $detail",
        )
    }

    @Synchronized
    override fun executionStarted(testIdentifier: TestIdentifier) {
        trace("start", testIdentifier)
        val limit = limitSeconds ?: return
        if (!testIdentifier.isTest) return
        if (testIdentifier.uniqueId in running) return
        val execution = Execution(testIdentifier, Thread.currentThread())
        running[testIdentifier.uniqueId] = execution
        execution.deadline = timer.schedule({ expire(execution, limit) }, limit, TimeUnit.SECONDS)
        trace("armed", testIdentifier, "owner=${execution.runner.threadId()} limit=$limit")
    }

    @Synchronized
    override fun executionSkipped(
        testIdentifier: TestIdentifier,
        reason: String,
    ) {
        trace("skipped", testIdentifier)
        val ended = retireSubtree(testIdentifier)
        if (ended.any { it.timedOut }) halt("${name(testIdentifier)} was skipped after its timeout interrupt.")
    }

    @Synchronized
    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        trace("plan_finished")
        val unfinished = running.values.toList()
        unfinished.forEach(::retire)
        if (unfinished.isNotEmpty()) halt("Test plan ended with unfinished executions: ${unfinished.map { it.test.uniqueId }}.")
    }

    @Synchronized
    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        trace(
            "finish",
            testIdentifier,
            "status=${testExecutionResult.status} exception=${testExecutionResult.throwable.orElse(null)?.javaClass?.name}",
        )
        val ended = retireSubtree(testIdentifier)
        val unfinishedChildren = ended.filter { it.test.uniqueId != testIdentifier.uniqueId }
        if (unfinishedChildren.isNotEmpty()) {
            halt(
                "Container ${testIdentifier.uniqueId} ended status=${testExecutionResult.status} " +
                    "exception=${testExecutionResult.throwable.orElse(null)?.javaClass?.name} " +
                    "with unfinished executions: ${unfinishedChildren.map { it.test.uniqueId }}.",
            )
        } else if (ended.any { it.timedOut } && testExecutionResult.status != TestExecutionResult.Status.FAILED) {
            halt("${name(testIdentifier)} returned ${testExecutionResult.status} after its timeout interrupt; the hang must fail the run.")
        }
    }

    private fun retireSubtree(test: TestIdentifier): List<Execution> =
        running.values.filter { it.test.uniqueIdObject.hasPrefix(test.uniqueIdObject) }.also { it.forEach(::retire) }

    private fun retire(execution: Execution) {
        running.remove(execution.test.uniqueId)
        execution.deadline?.cancel(false)
        execution.done.countDown()
        trace("retired", execution.test, "owner=${execution.runner.threadId()}")
    }

    private fun expire(
        execution: Execution,
        limit: Long,
    ) {
        // Terminal callbacks and expiry share ownership: even an already-dequeued deadline cannot
        // interrupt a finished execution or a later execution that reuses its identifier.
        synchronized(this) {
            if (running[execution.test.uniqueId] !== execution) return
            execution.timedOut = true
            timeoutCount++
            trace("deadline", execution.test, "owner=${execution.runner.threadId()}")
            report(
                "TEST TIMEOUT: ${name(execution.test)} ran longer than the ${limit}s per-test limit. Full thread dump:\n\n${threadDump()}",
            )
            if (timeoutCount > 1) {
                halt("A second test timed out in this JVM; an earlier hung thread may still block it.")
                return
            }
            report("TEST TIMEOUT: interrupting \"${execution.runner.name}\" to fail ${name(execution.test)}.")
            interrupt(execution.runner)
        }
        if (!execution.done.await(graceSeconds, TimeUnit.SECONDS)) {
            halt("${name(execution.test)} did not stop within ${graceSeconds}s of its interrupt.")
        }
    }

    private fun name(test: TestIdentifier): String =
        when (val source = test.source.orElse(null)) {
            is MethodSource -> "${source.className}#${source.methodName}"
            is ClassSource -> "${source.className}#${test.legacyReportingName}"
            else -> test.uniqueId
        }

    private fun threadDump(): String =
        buildString {
            for (info in ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
                append('"').append(info.threadName).append("\" #").append(info.threadId)
                if (info.isDaemon) append(" daemon")
                append(' ').append(info.threadState)
                info.lockName?.let { append(" on ").append(it) }
                info.lockOwnerName?.let { append(" owned by \"").append(it).append("\" #").append(info.lockOwnerId) }
                append('\n')
                info.stackTrace.forEachIndexed { depth, frame ->
                    append("\tat ").append(frame).append('\n')
                    info.lockedMonitors.filter { it.lockedStackDepth == depth }.forEach { append("\t- locked ").append(it).append('\n') }
                }
                if (info.lockedSynchronizers.isNotEmpty()) {
                    append("\tLocked synchronizers: ").append(info.lockedSynchronizers.joinToString()).append('\n')
                }
                append('\n')
            }
        }

    // Gradle captures System.err into the test report only once the test finishes. The process's own error
    // stream reaches the build log at once, so the dump survives a halt.
    private fun report(message: String) {
        diagnostic?.let {
            it(message)
            return
        }
        System.err.println(message)
        System.err.flush()
        processErr.println(message)
        processErr.flush()
    }

    private fun halt(reason: String) {
        report("TEST WATCHDOG: $reason Halting the test JVM so Gradle fails the run now.")
        terminate(HALT_STATUS)
    }

    private companion object {
        const val LIMIT_PROPERTY = "pyry.testTimeoutSeconds"
        const val GRACE_SECONDS = 30L
        const val HALT_STATUS = 124
        val processErr = PrintStream(FileOutputStream(FileDescriptor.err), true)
    }
}
