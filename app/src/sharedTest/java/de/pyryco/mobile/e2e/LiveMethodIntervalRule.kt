package de.pyryco.mobile.e2e

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Records completion inside the test statement, before UTP stops the per-method logcat collector. */
class LiveMethodIntervalRule(
    private val emit: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                val start = runCatching(nowMillis).getOrNull()
                try {
                    base.evaluate()
                } finally {
                    runCatching {
                        val end = runCatching(nowMillis).getOrNull()
                        emit(
                            "method=${description.className}#${description.methodName} stage=interval " +
                                "start_ms=${start ?: "unknown"} finish_ms=${end ?: "unknown"}",
                        )
                    }
                }
            }
        }
}
