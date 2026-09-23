package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The render-boundary cleaning a refusal's claude-authored model identifier owes (#875): the banner's
 * stripping set, plus the line breaks an identifier has no business carrying.
 */
class ModelRefusalDisplayTest {
    @Test
    fun plainIdentifier_passesThroughUnchanged() {
        assertEquals("claude-opus-5-5[1m]", refusalModelDisplay("claude-opus-5-5[1m]"))
    }

    @Test
    fun escapesAndControls_areStripped() {
        assertEquals("opus", refusalModelDisplay("\u001b[31mop\u0007us\u001b]0;title\u0007\u009b2J"))
    }

    @Test
    fun tabsAndLineBreaks_areStripped() {
        assertEquals("opus, continued on haiku", refusalModelDisplay("opus,\n continued\t on haiku\r"))
    }

    @Test
    fun emptyOrBlankOrEscapeOnly_readsAsNoModel() {
        assertNull(refusalModelDisplay(""))
        assertNull(refusalModelDisplay("   "))
        assertNull(refusalModelDisplay("\u001b[0m\n"))
    }
}
