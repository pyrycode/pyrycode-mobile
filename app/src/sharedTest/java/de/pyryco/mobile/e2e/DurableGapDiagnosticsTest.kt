package de.pyryco.mobile.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

internal enum class DurableGapCheck(
    val code: String,
) {
    Baseline("baseline"),
    Terminal("terminal"),
    RetainedRow("retained_row"),
    RetainedRows("retained_rows"),
}

/** Primitive-only input keeps routing identities, epochs and row content out of diagnostics. */
internal fun durableGapDiagnostic(
    check: DurableGapCheck,
    sameEpoch: Boolean,
    generation: Long,
    checkpoint: Long,
    completedVersion: Long,
    expected: Int,
    matches: Int,
    row: Int = -1,
): String =
    "event=durable_gap_check stage=${check.code} epoch_same=$sameEpoch generation=$generation " +
        "checkpoint=$checkpoint completed=$completedVersion row=$row expected=$expected matches=$matches"

@RunWith(AndroidJUnit4::class)
class DurableGapDiagnosticsTest {
    @Test
    fun omittedAndExplicitRootParentsMatchWithoutErasingOwnedParents() {
        assertTrue(durableParentMatches(null, 0L))
        assertTrue(durableParentMatches(0L, null))
        assertTrue(durableParentMatches(null, null))
        assertTrue(durableParentMatches(0L, 0L))
        assertTrue(durableParentMatches(23L, 23L))
        assertFalse(durableParentMatches(null, 23L))
        assertFalse(durableParentMatches(23L, null))
        assertFalse(durableParentMatches(23L, 24L))
    }

    @Test
    fun terminalMatchesCarryTheirExactCertificationBoundary() {
        assertEquals(
            "event=durable_gap_check stage=terminal epoch_same=true generation=2 " +
                "checkpoint=17 completed=15 row=-1 expected=1 matches=0",
            durableGapDiagnostic(DurableGapCheck.Terminal, true, 2, 17, 15, 1, 0),
        )
    }

    @Test
    fun missingAndDuplicateRetainedRowsRemainDistinctFromTerminalFailure() {
        for (matches in listOf(0, 2)) {
            assertEquals(
                "event=durable_gap_check stage=retained_row epoch_same=false generation=3 " +
                    "checkpoint=40 completed=39 row=5 expected=1 matches=$matches",
                durableGapDiagnostic(DurableGapCheck.RetainedRow, false, 3, 40, 39, 1, matches, 5),
            )
        }
    }

    @Test
    fun everyStageUsesOnlyBoundedStaticLabelsAndPrimitiveValues() {
        val fields =
            Regex(
                "event=durable_gap_check stage=(baseline|terminal|retained_row|retained_rows) " +
                    "epoch_same=false generation=[0-9]+ checkpoint=[0-9]+ completed=[0-9]+ " +
                    "row=[0-9]+ expected=[0-9]+ matches=[0-9]+",
            )
        for (check in DurableGapCheck.entries) {
            val line =
                durableGapDiagnostic(
                    check,
                    false,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    Int.MAX_VALUE,
                    Int.MAX_VALUE,
                    Int.MAX_VALUE,
                )
            assertTrue(fields.matches(line))
            assertTrue(line.length <= 320)
        }
    }
}

internal fun durableParentMatches(
    left: Long?,
    right: Long?,
): Boolean = (left ?: 0L) == (right ?: 0L)
