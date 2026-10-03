package de.pyryco.mobile.ui.conversations.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1519: the usage pill's text is desktop's client-owned copy, picked by exact-equality lookup on claude's
 * `status` and `limit_type`. The expected texts are literals, so a resource that drifts from the frame fails here.
 */
@RunWith(AndroidJUnit4::class)
class UsageLimitTextTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val now = Instant.parse("2026-09-23T10:00:00Z")
    private val laterToday = Instant.parse("2026-09-23T15:30:00Z").epochSeconds
    private val anotherDay = Instant.parse("2026-09-29T08:30:00Z").epochSeconds

    private fun text(
        status: String,
        limitType: String = "",
        resetsAt: Long = 0L,
    ): String =
        usageLimitText(
            UsageLimitReading(status, limitType, resetsAt, utilization = 0.94, truncatedFields = null),
            now,
            TimeZone.UTC,
            resources,
        )

    @Test
    fun theLead_isReachedOnlyForRejected() {
        assertEquals("Usage limit reached", text("rejected"))
        assertEquals("Nearly at usage limit", text("allowed_warning"))
        assertEquals("Nearly at usage limit", text("something_new"))
    }

    @Test
    fun theWindow_namesOnlyTheTwoKnownLimitTypes() {
        for ((status, lead) in LEADS) {
            assertEquals("$lead - 5-hour window", text(status, "five_hour"))
            assertEquals("$lead - 7-day window", text(status, "seven_day"))
            assertEquals(lead, text(status, "seven_day_opus"))
            assertEquals(lead, text(status, ""))
        }
    }

    @Test
    fun theReset_isATimeToday_aDateAndTimeOnAnotherDay_andAbsentForZero() {
        for ((status, lead) in LEADS) {
            assertEquals("$lead - 7-day window, resets 15:30", text(status, "seven_day", laterToday))
            assertEquals("$lead - 5-hour window, resets 29.09.2026 at 08:30", text(status, "five_hour", anotherDay))
            assertEquals("$lead - 7-day window", text(status, "seven_day", 0L))
            assertEquals("$lead, resets 15:30", text(status, "", laterToday))
            assertEquals("$lead, resets 29.09.2026 at 08:30", text(status, "unknown", anotherDay))
        }
    }

    @Test
    fun hostileOrNearMissValues_neverAppear_andTakeTheFallbackArm() {
        for (status in listOf("<b>x</b>", "rejected ", " rejected", "Rejected", "REJECTED", "rejected_new", "rejected\n")) {
            val text = text(status, "seven_day", laterToday)
            assertEquals("Nearly at usage limit - 7-day window, resets 15:30", text)
            assertFalse("status '$status' must not be drawn in '$text'", status.isNotBlank() && text.contains(status))
        }
        for (limitType in listOf("<b>x</b>", "seven_day ", "SEVEN_DAY", "Seven_Day", "five_hour\n", "constructor")) {
            val text = text("rejected", limitType)
            assertEquals("Usage limit reached", text)
            assertFalse("limit type '$limitType' must not be drawn in '$text'", text.contains(limitType))
        }
        assertFalse(text("<b>x</b>", "<b>x</b>").contains("<"))
    }

    private companion object {
        val LEADS =
            listOf(
                "rejected" to "Usage limit reached",
                "allowed_warning" to "Nearly at usage limit",
                "something_new" to "Nearly at usage limit",
            )
    }
}
