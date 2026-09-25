package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Test

/** The footer buttons' shared width cap (#1032): the widest labels give up space first. */
class ThreadComposerFooterLayoutTest {
    private fun fitted(
        widths: List<Int>,
        cap: Int,
    ) = widths.sumOf { minOf(it, cap) }

    @Test
    fun everythingFits_leavesEveryButtonUncapped() {
        assertEquals(Int.MAX_VALUE, footerShrinkCap(listOf(60, 106, 60), available = 226))
    }

    @Test
    fun oneWideLabel_isTheOnlyOneCapped() {
        val widths = listOf(60, 106, 60, 60)
        val cap = footerShrinkCap(widths, available = 250)

        assertEquals(70, cap)
        assertEquals(250, fitted(widths, cap))
    }

    @Test
    fun everythingOverBudget_sharesTheWidthEqually() {
        val widths = listOf(60, 106, 60, 60)

        assertEquals(45, footerShrinkCap(widths, available = 180))
    }

    @Test
    fun noWidth_capsEveryButtonToNothing() {
        assertEquals(0, footerShrinkCap(listOf(60, 106), available = 0))
    }
}
