package de.pyryco.mobile.recording

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Outside every original rule, so unsuccessful assertions or cleanup never publish a capture. */
class RecordedSuiteRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                var failure: Throwable? = null
                try {
                    RecordedSuiteNative.begin(description)
                    base.evaluate()
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        RecordedSuiteNative.finish(failure == null)
                    } catch (cleanup: Throwable) {
                        if (failure == null) throw cleanup
                        failure.addSuppressed(cleanup)
                    }
                }
            }
        }
}
