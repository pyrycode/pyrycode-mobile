package de.pyryco.mobile.verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import java.util.concurrent.Delayed
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TestTimeoutWatchdogTest {
    private val timer = ManualTimer()
    private val interrupted = mutableListOf<Thread>()
    private val halted = mutableListOf<Int>()
    private val messages = mutableListOf<String>()
    private val watchdog = TestTimeoutWatchdog(180, timer, interrupted::add, halted::add, 0, messages::add)
    private val root = EngineDescriptor(UniqueId.forEngine("junit-vintage"), "fixture")
    private val test =
        TestIdentifier.from(
            object : AbstractTestDescriptor(root.uniqueId.append("test", "method"), "method") {
                override fun getType() = TestDescriptor.Type.TEST
            }.also(root::addChild),
        )

    @Test fun completedExecutionCancelsItsDeadline() {
        watchdog.executionStarted(test)
        watchdog.executionFinished(test, TestExecutionResult.successful())
        assertTrue("completed execution must release its timer", timer.tasks.single().isCancelled)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
    }

    @Test fun skippedExecutionCancelsItsDeadline() {
        watchdog.executionStarted(test)
        watchdog.executionSkipped(test, "controlled skip")
        assertTrue("skipped execution must release its timer", timer.tasks.single().isCancelled)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
    }

    @Test fun vintageRunnerExceptionRetiresItsUnfinishedChild() {
        val events = mutableListOf<String>()
        val launcher = LauncherFactory.create(LauncherConfig.builder().enableTestExecutionListenerAutoRegistration(false).build())
        launcher.execute(
            LauncherDiscoveryRequestBuilder.request().selectors(selectClass(EscapingFixture::class.java)).build(),
            watchdog,
            object : TestExecutionListener {
                override fun executionStarted(testIdentifier: TestIdentifier) {
                    events += "start ${testIdentifier.uniqueId}"
                }

                override fun executionFinished(
                    testIdentifier: TestIdentifier,
                    testExecutionResult: TestExecutionResult,
                ) {
                    events +=
                        "finish ${testIdentifier.uniqueId} ${testExecutionResult.status} ${testExecutionResult.throwable.orElse(
                            null,
                        )?.javaClass?.simpleName}"
                }
            },
        )
        assertTrue(events.toString(), events.any { "FAILED IllegalStateException" in it })
        assertEquals(events.toString(), 1, timer.tasks.size)
        assertTrue("runner returned with an unfinished child: $events", timer.tasks.single().isCancelled)
        assertEquals("an incomplete result stream must fail immediately", listOf(124), halted)
        assertTrue(messages.any { "exception=java.lang.IllegalStateException" in it })
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
    }

    @Test fun genuineHangStillInterruptsAndHaltsAfterGrace() {
        watchdog.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        assertEquals(listOf(Thread.currentThread()), interrupted)
        assertEquals(listOf(124), halted)
        assertTrue(messages.any { "180s per-test limit" in it })
    }

    @Test fun abortedExecutionCannotLeaveAStaleDeadline() {
        watchdog.executionStarted(test)
        watchdog.executionFinished(test, TestExecutionResult.aborted(IllegalStateException()))
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
        assertTrue(halted.isEmpty())
        assertTrue(timer.tasks.single().isCancelled)
    }

    @Test fun reusedIdentityIgnoresItsEarlierDeadline() {
        watchdog.executionStarted(test)
        watchdog.executionFinished(test, TestExecutionResult.successful())
        watchdog.executionStarted(test)
        timer.tasks
            .first()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
        timer.tasks
            .last()
            .command
            .run()
        assertEquals(1, interrupted.size)
        assertEquals(listOf(124), halted)
    }

    @Test fun duplicateStartCannotOrphanTheFirstDeadline() {
        watchdog.executionStarted(test)
        watchdog.executionStarted(test)
        watchdog.executionFinished(test, TestExecutionResult.successful())
        timer.tasks.forEach { it.command.run() }
        assertTrue(interrupted.isEmpty())
        assertTrue(halted.isEmpty())
    }

    @Test fun timeoutFailureCanFinishWithoutHaltingTheWorker() {
        // The interrupt callback completes the same listener's active execution.
        lateinit var listener: TestTimeoutWatchdog
        listener =
            TestTimeoutWatchdog(180, timer, {
                listener.executionFinished(test, TestExecutionResult.failed(InterruptedException()))
            }, halted::add, 0, messages::add)
        listener.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(halted.isEmpty())
    }

    @Test fun successfulReturnAfterTimeoutStillFailsTheGate() {
        lateinit var listener: TestTimeoutWatchdog
        listener =
            TestTimeoutWatchdog(
                180,
                timer,
                { listener.executionFinished(test, TestExecutionResult.successful()) },
                halted::add,
                0,
                messages::add,
            )
        listener.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        assertEquals(listOf(124), halted)
    }

    @Test fun abortedReturnAfterTimeoutStillFailsTheGate() {
        lateinit var listener: TestTimeoutWatchdog
        listener =
            TestTimeoutWatchdog(180, timer, {
                listener.executionFinished(test, TestExecutionResult.aborted(InterruptedException()))
            }, halted::add, 0, messages::add)
        listener.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        assertEquals(listOf(124), halted)
    }

    @Test fun completedRetryDoesNotInheritThePreviousTimeout() {
        lateinit var listener: TestTimeoutWatchdog
        listener =
            TestTimeoutWatchdog(180, timer, {
                listener.executionFinished(test, TestExecutionResult.failed(InterruptedException()))
            }, halted::add, 0, messages::add)
        listener.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        listener.executionStarted(test)
        listener.executionFinished(test, TestExecutionResult.successful())
        assertTrue(halted.isEmpty())
    }

    @Test fun secondTimeoutStillHaltsWhenIdentityIsReused() {
        lateinit var listener: TestTimeoutWatchdog
        listener =
            TestTimeoutWatchdog(180, timer, {
                listener.executionFinished(test, TestExecutionResult.failed(InterruptedException()))
            }, halted::add, 0, messages::add)
        listener.executionStarted(test)
        timer.tasks
            .single()
            .command
            .run()
        listener.executionStarted(test)
        timer.tasks
            .last()
            .command
            .run()
        assertEquals(listOf(124), halted)
    }

    @Test fun successfulContainerCannotHideAnUnfinishedTest() {
        watchdog.executionStarted(test)
        watchdog.executionFinished(TestIdentifier.from(root), TestExecutionResult.successful())
        assertEquals(listOf(124), halted)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
    }

    @Test fun skippedContainerRetiresOnlyItsDescendants() {
        watchdog.executionStarted(test)
        watchdog.executionSkipped(TestIdentifier.from(EngineDescriptor(UniqueId.forEngine("other"), "other")), "unrelated")
        assertTrue(!timer.tasks.single().isCancelled)
        watchdog.executionSkipped(TestIdentifier.from(root), "controlled container skip")
        assertTrue(timer.tasks.single().isCancelled)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
        assertTrue(halted.isEmpty())
    }

    @Test fun planTeardownRejectsAndCancelsUnfinishedExecutions() {
        val plan =
            LauncherFactory.create().discover(
                LauncherDiscoveryRequestBuilder.request().selectors(selectClass(EscapingFixture::class.java)).build(),
            )
        watchdog.executionStarted(test)
        watchdog.testPlanExecutionFinished(plan)
        assertTrue(timer.tasks.single().isCancelled)
        assertEquals(listOf(124), halted)
        timer.tasks
            .single()
            .command
            .run()
        assertTrue(interrupted.isEmpty())
    }

    @RunWith(EscapingRunner::class)
    class EscapingFixture

    class EscapingRunner(
        private val fixture: Class<*>,
    ) : Runner() {
        private val method = Description.createTestDescription(fixture, "unfinished")

        override fun getDescription(): Description = Description.createSuiteDescription(fixture).also { it.addChild(method) }

        override fun run(notifier: RunNotifier) {
            val executor = Executors.newSingleThreadExecutor { Thread(it, "fixture SDK Main Thread") }
            try {
                executor.submit { notifier.fireTestStarted(method) }.get(5, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
            throw IllegalStateException("controlled runner exception")
        }
    }

    private class ManualTimer : ScheduledThreadPoolExecutor(1) {
        val tasks = mutableListOf<Deadline>()

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = Deadline(command).also(tasks::add)
    }

    private class Deadline(
        val command: Runnable,
    ) : FutureTask<Unit>(command, Unit),
        ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit) = 0L

        override fun compareTo(other: Delayed) = 0
    }
}
