package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SessionBoundaryDelimiterTest {
    private val occurredAt = Instant.parse("2026-05-17T14:32:00Z")
    private val berlin = TimeZone.of("Europe/Berlin")

    @Test
    fun `boundaryLabel formats Clear reason with short time`() {
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s0",
                newSessionId = "s1",
                reason = BoundaryReason.Clear,
                occurredAt = occurredAt,
                workspaceCwd = null,
            )

        val label = boundaryLabel(boundary, berlin, Locale.GERMANY)

        assertEquals("New session — 16:32", label)
    }

    @Test
    fun `boundaryLabel formats WorkspaceChange as a new session without the cwd`() {
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s0",
                newSessionId = "s1",
                reason = BoundaryReason.WorkspaceChange,
                occurredAt = occurredAt,
                workspaceCwd = "~/Workspace/Projects/KitchenClaw",
            )

        val label = boundaryLabel(boundary, berlin, Locale.GERMANY)

        assertEquals("New session — 16:32", label)
    }

    @Test
    fun `boundaryLabel formats IdleEvict reason with short time`() {
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s0",
                newSessionId = "s1",
                reason = BoundaryReason.IdleEvict,
                occurredAt = occurredAt,
                workspaceCwd = null,
            )

        val label = boundaryLabel(boundary, berlin, Locale.GERMANY)

        assertEquals("Idle session ended — 16:32", label)
    }

    @Test
    fun `boundaryLabel formats WorkspaceChange with null cwd as a new session`() {
        val boundary =
            ThreadItem.SessionBoundary(
                previousSessionId = "s0",
                newSessionId = "s1",
                reason = BoundaryReason.WorkspaceChange,
                occurredAt = occurredAt,
                workspaceCwd = null,
            )

        assertEquals("New session — 16:32", boundaryLabel(boundary, berlin, Locale.GERMANY))
    }

    @Test
    fun `formatShortTime honours the locale parameter`() {
        // Berlin TZ shifts 14:32 UTC to 16:32 local. en_US FormatStyle.SHORT
        // renders that as "4:32 PM"; JDK versions differ on the separator
        // (regular space vs. narrow no-break space  ), so assert the
        // digits and the AM/PM marker separately rather than chasing the glyph.
        val result = formatShortTime(occurredAt, berlin, Locale.US)

        assertTrue("expected '4:32' in '$result'", result.contains("4:32"))
        assertTrue("expected 'PM' in '$result'", result.contains("PM"))
    }
}
