package de.pyryco.mobile.recording

import de.pyryco.mobile.e2e.LiveSessionCleanupRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class RecordedTerminalFailureTest {
    @Test fun earlyEndAndPreparationFailuresStayPrimaryAndReleaseEveryActivityExactlyOnce() {
        for (stage in listOf("success", "body", "end", "prepare")) {
            val expected = AssertionError(stage)
            val teardown = AssertionError("destroy")
            val calls = mutableListOf<String>()
            val cleanup =
                LiveSessionCleanupRule(begin = { calls += "begin" }, end = {
                    calls += "end"
                    if (stage == "end") throw expected
                })
            val activity =
                TestRule { base, _ ->
                    object : Statement() {
                        override fun evaluate() {
                            try {
                                base.evaluate()
                            } finally {
                                calls += "destroy"
                                if (stage != "success") throw teardown
                            }
                        }
                    }
                }
            val recorded =
                RecordedActivityRule(activity) {
                    cleanup.endWhileActivityAlive()
                    calls += "prepare"
                    if (stage == "prepare") throw expected
                }
            val result =
                runCatching {
                    cleanup
                        .apply(
                            recorded.apply(
                                object : Statement() {
                                    override fun evaluate() {
                                        cleanup.withRestartedActivity(AutoCloseable { calls += "replacement-close" }) {}
                                        calls += "body"
                                        if (stage == "body") throw expected
                                    }
                                },
                                Description.EMPTY,
                            ),
                            Description.EMPTY,
                        ).evaluate()
                }.exceptionOrNull()
            assertSame(stage, if (stage == "success") null else expected, result)
            if (stage != "success") assertTrue(expected.suppressed.contains(teardown))
            assertEquals(stage, 1, calls.count { it == "end" })
            assertEquals(stage, 1, calls.count { it == "destroy" })
            assertEquals(stage, 1, calls.count { it == "replacement-close" })
            if (stage in listOf("success", "prepare", "end")) assertTrue(calls.indexOf("end") < calls.indexOf("destroy"))
            assertTrue(calls.indexOf("end") < calls.indexOf("replacement-close"))
        }
    }

    @Test fun anotherEvaluationCannotReuseThePreviousEarlyEnd() {
        var ends = 0
        val cleanup = LiveSessionCleanupRule(begin = {}, end = { ends++ })
        val statement =
            cleanup.apply(
                object : Statement() {
                    override fun evaluate() {
                        cleanup.endWhileActivityAlive()
                        assertEquals(1, ends)
                    }
                },
                Description.EMPTY,
            )
        statement.evaluate()
        assertEquals(1, ends)
        // A fresh body on the same rule must get its own end action.
        cleanup
            .apply(
                object : Statement() {
                    override fun evaluate() {
                        cleanup.endWhileActivityAlive()
                    }
                },
                Description.EMPTY,
            ).evaluate()
        assertEquals(2, ends)
    }
}
