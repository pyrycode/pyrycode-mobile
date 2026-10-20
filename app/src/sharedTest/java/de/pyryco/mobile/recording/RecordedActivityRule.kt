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
    ): Statement =
        activity.apply(
            object : Statement() {
                override fun evaluate() {
                    base.evaluate()
                    beforeTeardown()
                }
            },
            description,
        )
}
