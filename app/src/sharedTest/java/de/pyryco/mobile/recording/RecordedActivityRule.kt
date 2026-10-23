package de.pyryco.mobile.recording

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Preserve the foreground activity until the captured pre-teardown work has completed. */
internal class RecordedActivityRule(
    private val activity: TestRule,
    private val beforeTeardown: () -> Unit,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement {
        var primary: Throwable? = null
        val wrapped =
            activity.apply(
                object : Statement() {
                    override fun evaluate() {
                        try {
                            base.evaluate()
                            beforeTeardown()
                        } catch (error: Throwable) {
                            primary = error
                            throw error
                        }
                    }
                },
                description,
            )
        return object : Statement() {
            override fun evaluate() {
                primary = null
                try {
                    wrapped.evaluate()
                } catch (error: Throwable) {
                    val original = primary
                    if (original != null && original !== error) {
                        original.addSuppressed(error)
                        throw original
                    }
                    throw error
                }
            }
        }
    }
}
