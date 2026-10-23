package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun replacementClosesAfterEndOnSuccessOrBodyOrEndFailure() {
        for (stage in listOf("success", "body", "end")) {
            val calls = mutableListOf<String>()
            val original = AssertionError(stage)
            val cleanup =
                LiveSessionCleanupRule(begin = {}, end = {
                    calls += "end"
                    if (stage == "end") throw original
                })
            val result =
                runCatching {
                    cleanup
                        .apply(
                            object : Statement() {
                                override fun evaluate() {
                                    cleanup.withRestartedActivity(AutoCloseable { calls += "close" }) {
                                        calls += "proof"
                                        if (stage == "body") throw original
                                    }
                                    calls += "fixture-cleanup"
                                }
                            },
                            Description.EMPTY,
                        ).evaluate()
                }.exceptionOrNull()
            assertSame(if (stage == "success") null else original, result)
            val expected = if (stage == "body") listOf("proof", "end", "close") else listOf("proof", "fixture-cleanup", "end", "close")
            assertEquals(stage, expected, calls)
        }
    }

    @Test fun replacementCloseFailureCannotHideEarlierFailuresOrSkipOtherCloses() {
        for (stage in listOf("success", "body", "end")) {
            val original = AssertionError(stage)
            val closeFailure = AssertionError("close")
            var closes = 0
            val cleanup = LiveSessionCleanupRule(begin = {}, end = { if (stage == "end") throw original })
            val result =
                runCatching {
                    cleanup
                        .apply(
                            object : Statement() {
                                override fun evaluate() {
                                    cleanup.withRestartedActivity(AutoCloseable { closes++ }) {}
                                    cleanup.withRestartedActivity(
                                        AutoCloseable {
                                            closes++
                                            throw closeFailure
                                        },
                                    ) {
                                        if (stage == "body") throw original
                                    }
                                }
                            },
                            Description.EMPTY,
                        ).evaluate()
                }.exceptionOrNull()
            assertSame(if (stage == "success") closeFailure else original, result)
            if (stage != "success") assertTrue(original.suppressed.contains(closeFailure))
            assertEquals("every resource closes once", 2, closes)
        }
    }
}
