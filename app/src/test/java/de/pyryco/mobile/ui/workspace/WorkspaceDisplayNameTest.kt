package de.pyryco.mobile.ui.workspace

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDisplayNameTest {
    @Test
    fun nonBlankLabel_winsOverCwdBasename() {
        assertEquals("Design system", workspaceDisplayName(cwd = "pyry-workspace/my-app", label = "Design system"))
    }

    @Test
    fun label_isRenderedVerbatim_includingWhitespaceUnicodeAndMarkupLookingText() {
        // Opaque daemon-authored text: not trimmed, not escaped, not interpreted. The angle brackets
        // survive as literal characters precisely because the sink is a plain Text, never markup.
        assertEquals("  Työ 🛠 <b>A</b>  ", workspaceDisplayName(cwd = "/work/A", label = "  Työ 🛠 <b>A</b>  "))
    }

    @Test
    fun label_winsOverScratchSentinelToo() {
        // Arm 1 is unconditional — the protocol keys labels by workspace, and scratch is nameable.
        assertEquals("Named scratch", workspaceDisplayName(cwd = DEFAULT_SCRATCH_CWD, label = "Named scratch"))
        assertEquals("Named scratch", workspaceDisplayName(cwd = "", label = "Named scratch"))
    }

    @Test
    fun nullEmptyAndWhitespaceLabels_fallBackToCwdDerivedText() {
        for (blank in listOf(null, "", "   ", "\t\n")) {
            assertEquals("my-app", workspaceDisplayName(cwd = "pyry-workspace/my-app", label = blank))
            assertEquals("scratch", workspaceDisplayName(cwd = DEFAULT_SCRATCH_CWD, label = blank))
        }
    }

    @Test
    fun unlabelledCwd_keepsTheThreeExistingFallbackCases() {
        // The #137 behaviour table, moved unchanged: empty and sentinel cwds collapse to "scratch",
        // any other cwd yields its last segment, and a segment-less cwd yields the whole string.
        assertEquals("scratch", workspaceDisplayName(cwd = "", label = null))
        assertEquals("scratch", workspaceDisplayName(cwd = DEFAULT_SCRATCH_CWD, label = null))
        assertEquals("my-app", workspaceDisplayName(cwd = "pyry-workspace/my-app", label = null))
        assertEquals("X", workspaceDisplayName(cwd = "~/Workspace/Projects/X", label = null))
        assertEquals("X", workspaceDisplayName(cwd = "X", label = null))
        // Documented degenerate case: a trailing slash yields the whole cwd, not "".
        assertEquals("foo/", workspaceDisplayName(cwd = "foo/", label = null))
    }

    @Test
    fun conformantMaxLengthLabel_passesThroughUntruncated() {
        val conformant = "w".repeat(MAX_WORKSPACE_LABEL_CHARS)
        assertEquals(conformant, workspaceDisplayName(cwd = "/work/A", label = conformant))
    }

    @Test
    fun overlongLabel_isBoundedBeforeReachingTextLayout() {
        // The daemon's 128-byte bound is a size limit, not a safety property: an in-session hostile or
        // buggy daemon can send an arbitrarily long label, and Compose measures the whole paragraph.
        val hostile = "w".repeat(5_000)
        val rendered = workspaceDisplayName(cwd = "/work/A", label = hostile)
        assertEquals(MAX_WORKSPACE_LABEL_CHARS, rendered.length)
        assertTrue(hostile.startsWith(rendered))
    }
}
