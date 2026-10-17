package de.pyryco.mobile.verification

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
class TestTimeoutWatchdog : TestExecutionListener {
    private val limitSeconds = System.getProperty(LIMIT_PROPERTY)?.toLongOrNull()?.takeIf { it > 0 }
    private val timer =
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "test-timeout-watchdog").apply { isDaemon = true } }
    private val running = ConcurrentHashMap<String, CountDownLatch>()
    private val expired = ConcurrentHashMap.newKeySet<String>()

    override fun executionStarted(testIdentifier: TestIdentifier) {
        val limit = limitSeconds ?: return
        if (!testIdentifier.isTest) return
        val runner = Thread.currentThread()
        val done = CountDownLatch(1)
        running[testIdentifier.uniqueId] = done
        timer.schedule({ expire(testIdentifier, runner, done, limit) }, limit, TimeUnit.SECONDS)
    }

    override fun executionFinished(
        testIdentifier: TestIdentifier,
        testExecutionResult: TestExecutionResult,
    ) {
        running.remove(testIdentifier.uniqueId)?.countDown()
        if (testIdentifier.uniqueId in expired && testExecutionResult.status == TestExecutionResult.Status.SUCCESSFUL) {
            halt("${name(testIdentifier)} passed after its timeout interrupt; halting so the hang still fails the run.")
        }
    }

    private fun expire(
        test: TestIdentifier,
        runner: Thread,
        done: CountDownLatch,
        limit: Long,
    ) {
        if (done.count == 0L) return
        expired += test.uniqueId
        val name = name(test)
        report("TEST TIMEOUT: $name ran longer than the ${limit}s per-test limit. Full thread dump:\n\n${threadDump()}")
        if (expired.size > 1) halt("A second test timed out in this JVM; an earlier hung thread may still block it.")
        report("TEST TIMEOUT: interrupting \"${runner.name}\" to fail $name.")
        runner.interrupt()
        if (!done.await(GRACE_SECONDS, TimeUnit.SECONDS)) {
            halt("$name did not stop within ${GRACE_SECONDS}s of its interrupt.")
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
        System.err.println(message)
        System.err.flush()
        processErr.println(message)
        processErr.flush()
    }

    private fun halt(reason: String): Nothing {
        report("TEST TIMEOUT: $reason Halting the test JVM so Gradle fails the run now.")
        Runtime.getRuntime().halt(HALT_STATUS)
        error("unreachable")
    }

    private companion object {
        const val LIMIT_PROPERTY = "pyry.testTimeoutSeconds"
        const val GRACE_SECONDS = 30L
        const val HALT_STATUS = 124
        val processErr = PrintStream(FileOutputStream(FileDescriptor.err), true)
    }
}
