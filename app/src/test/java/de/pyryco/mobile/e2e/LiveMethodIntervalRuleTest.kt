package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LiveMethodIntervalRuleTest {
    private val description = Description.createTestDescription(javaClass, "scenario")

    @Test
    fun recordsIntervalBeforeTheOuterRunnerReceivesCompletion() {
        val events = mutableListOf<String>()
        val times = ArrayDeque(listOf(10L, 20L))
        val rule = LiveMethodIntervalRule({ events += it }, { times.removeFirst() })
        rule.apply(statement { events += "body" }, description).evaluate()
        events += "runner_finished"
        assertEquals("body", events.first())
        assertEquals("method=${javaClass.name}#scenario stage=interval start_ms=10 finish_ms=20", events[1])
        assertEquals("runner_finished", events.last())
    }

    @Test
    fun observerFailurePreservesTheOriginalAssertion() {
        val original = AssertionError("original")
        val rule = LiveMethodIntervalRule({ throw IllegalStateException("observer") }, { 1L })
        val caught = runCatching { rule.apply(statement { throw original }, description).evaluate() }.exceptionOrNull()
        assertSame(original, caught)
    }

    @Test
    fun unavailableClockIsUnknownAndDoesNotFailSuccessfulBody() {
        val events = mutableListOf<String>()
        val rule = LiveMethodIntervalRule({ events += it }, { throw IllegalStateException("clock") })
        rule.apply(statement {}, description).evaluate()
        assertEquals("method=${javaClass.name}#scenario stage=interval start_ms=unknown finish_ms=unknown", events.single())
    }

    @Test
    fun reusedMethodIdentityReceivesAFreshInterval() {
        val events = mutableListOf<String>()
        val times = ArrayDeque(listOf(1L, 2L, 3L, 4L))
        val rule = LiveMethodIntervalRule({ events += it }, { times.removeFirst() })
        repeat(2) { rule.apply(statement {}, description).evaluate() }
        assertEquals("method=${javaClass.name}#scenario stage=interval start_ms=1 finish_ms=2", events[0])
        assertEquals("method=${javaClass.name}#scenario stage=interval start_ms=3 finish_ms=4", events[1])
    }

    private fun statement(body: () -> Unit): Statement =
        object : Statement() {
            override fun evaluate() = body()
        }
}
