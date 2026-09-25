package de.pyryco.mobile.e2e

import android.app.Instrumentation
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Answers the activities the app under test starts for a given intent action, in place of the system UI
 * (#1016): the document pickers and whichever viewer `ACTION_VIEW` would open. Built on the platform's
 * [Instrumentation.ActivityMonitor] no-arg constructor, whose [onStartActivity] runs for every start that goes
 * through the instrumentation — a plain `startActivity` and an activity-result launcher alike — so no
 * Espresso-Intents dependency is needed. An action with no answer starts as usual.
 *
 * Register it with [Instrumentation.addMonitor] and remove it with [Instrumentation.removeMonitor] in the
 * scenario's `finally`. Every answered intent is recorded in [answered], in start order.
 */
class ActivityIntentStub : Instrumentation.ActivityMonitor() {
    private val answers = ConcurrentHashMap<String, (Intent) -> Instrumentation.ActivityResult>()

    /** The intents answered so far, oldest first. Written on the main thread, read by the test. */
    val answered: MutableList<Intent> = CopyOnWriteArrayList()

    /** Answer every later start of [action] with [result], computed from the intent the app started. */
    fun answer(
        action: String,
        result: (Intent) -> Instrumentation.ActivityResult,
    ) {
        answers[action] = result
    }

    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        val result = intent.action?.let { answers[it] } ?: return null
        answered += intent
        return result(intent)
    }
}
