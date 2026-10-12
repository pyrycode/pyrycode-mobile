package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LiveSessionCleanupRuleTest {
    private fun runRule(
        begin: () -> Unit = {},
        end: () -> Unit,
        body: () -> Unit = {},
    ): Throwable? =
        runCatching {
            LiveSessionCleanupRule(begin, end)
                .apply(
                    object : Statement() {
                        override fun evaluate() = body()
                    },
                    Description.EMPTY,
                ).evaluate()
        }.exceptionOrNull()

    @Test
    fun cleansAfterSuccessAndFailure() {
        for (bodyFailure in listOf(null, AssertionError("body"))) {
            val calls = mutableListOf<String>()
            val result =
                runRule(
                    begin = { calls += "begin" },
                    end = { calls += "end" },
                    body = {
                        calls += "body"
                        bodyFailure?.let { throw it }
                    },
                )
            assertSame(bodyFailure, result)
            assertEquals(listOf("begin", "body", "end"), calls)
        }
    }

    @Test
    fun cleanupFailureCannotPassAndDoesNotHideBodyFailure() {
        val cleanup = AssertionError("cleanup")
        assertSame(cleanup, runRule(end = { throw cleanup }))
        val body = AssertionError("body")
        assertSame(body, runRule(end = { throw cleanup }, body = { throw body }))
        assertEquals(listOf(cleanup), body.suppressed.toList())
    }

    @Test
    fun failedOwnershipCaptureNeverRunsBody() {
        val calls = mutableListOf<String>()
        val failure = AssertionError("begin")
        assertSame(
            failure,
            runRule(
                begin = { throw failure },
                end = { calls += "end" },
                body = { calls += "body" },
            ),
        )
        assertEquals(emptyList<String>(), calls)
    }
}
