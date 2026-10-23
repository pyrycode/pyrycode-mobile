package de.pyryco.mobile.e2e

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Cleanup is mandatory after both successful and failed test bodies. */
class LiveSessionCleanupRule(
    private val begin: () -> Unit,
    private val end: () -> Unit,
) : TestRule {
    private val restartedActivities = mutableListOf<AutoCloseable>()
    private var endAttempted = false

    /** Recorded terminal work can need the Activity after end; outer cleanup still owns release. */
    fun endWhileActivityAlive() {
        if (endAttempted) return
        endAttempted = true
        end()
    }

    /** Transfer ownership before the proof runs; fixture cleanup and test-end still need this Activity. */
    fun withRestartedActivity(
        activity: AutoCloseable,
        proof: () -> Unit,
    ) {
        restartedActivities += activity
        proof()
    }

    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                endAttempted = false
                begin()
                var failure: Throwable? = null

                fun preserve(error: Throwable) {
                    val original = failure
                    if (original == null) failure = error else original.addSuppressed(error)
                }

                try {
                    base.evaluate()
                } catch (error: Throwable) {
                    preserve(error)
                }
                try {
                    endWhileActivityAlive()
                } catch (error: Throwable) {
                    preserve(error)
                }
                val activities = restartedActivities.toList()
                restartedActivities.clear()
                for (activity in activities.asReversed()) {
                    try {
                        activity.close()
                    } catch (error: Throwable) {
                        preserve(error)
                    }
                }
                failure?.let { throw it }
            }
        }
}
