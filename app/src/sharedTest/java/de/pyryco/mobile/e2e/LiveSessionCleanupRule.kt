package de.pyryco.mobile.e2e

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Cleanup is mandatory after both successful and failed test bodies. */
class LiveSessionCleanupRule(
    private val begin: () -> Unit,
    private val end: () -> Unit,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                begin()
                var failure: Throwable? = null
                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        end()
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup
                        failure.addSuppressed(cleanup)
                    }
                }
            }
        }
}
