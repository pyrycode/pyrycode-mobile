package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.usageLimitIsWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1002: the process-scoped memory of which usage readings the operator hid, and which readings may be hidden. */
class UsageLimitDismissalsTest {
    private val warning =
        UsageLimitReading(
            status = "allowed_warning",
            limitType = "seven_day",
            resetsAt = 1_790_000_000L,
            utilization = 0.8,
            truncatedFields = null,
        )

    @Test
    fun startsEmpty() {
        assertEquals(emptySet<UsageLimitDismissals.Key>(), UsageLimitDismissals().dismissed.value)
    }

    @Test
    fun dismissing_remembersTheReadingsKey() {
        val dismissals = UsageLimitDismissals()

        dismissals.dismiss(warning)

        assertTrue(warning.dismissalKey() in dismissals.dismissed.value)
    }

    @Test
    fun aChangedStatusLimitTypeOrResetTime_isNotDismissed() {
        val dismissals = UsageLimitDismissals()
        dismissals.dismiss(warning)
        val dismissed = dismissals.dismissed.value

        assertFalse(warning.copy(status = "rejected").dismissalKey() in dismissed)
        assertFalse(warning.copy(limitType = "five_hour").dismissalKey() in dismissed)
        assertFalse(warning.copy(resetsAt = warning.resetsAt + 1).dismissalKey() in dismissed)
    }

    @Test
    fun aChangedUtilizationAlone_staysDismissed() {
        val dismissals = UsageLimitDismissals()
        dismissals.dismiss(warning)

        assertTrue(warning.copy(utilization = 0.9, truncatedFields = listOf("status")).dismissalKey() in dismissals.dismissed.value)
    }

    @Test
    fun onlyExactlyAllowedWarning_isAWarning() {
        assertTrue(usageLimitIsWarning(warning))
        listOf("rejected", "allowed", "Allowed_Warning", "allowed_warning ", "", "something_new").forEach {
            assertFalse(it, usageLimitIsWarning(warning.copy(status = it)))
        }
    }
}
