package de.pyryco.mobile.ui.workspace

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun overlongLabel_clampNeverSplitsASurrogatePair() {
        // take() alone would end on the lone high half of the emoji, which renders as a replacement glyph.
        val kept = "n".repeat(MAX_WORKSPACE_LABEL_CHARS - 1)
        assertEquals(kept, workspaceDisplayName(cwd = "/work/A", label = kept + "😀" + "tail"))
    }

    @Test
    fun labelRule_blankInputClearsTheLabel() {
        for (blank in listOf("", "   ", "\t\n")) {
            assertNull(workspaceLabelFor(blank, folderName = "my-app"))
        }
    }

    @Test
    fun labelRule_theFoldersOwnNameClearsTheLabel_evenWithSurroundingSpace() {
        assertNull(workspaceLabelFor("my-app", folderName = workspaceDisplayName("pyry-workspace/my-app", label = null)))
        assertNull(workspaceLabelFor("  my-app  ", folderName = "my-app"))
        // The scratch workspace's own name is the rule's "scratch", not its path.
        assertNull(workspaceLabelFor("scratch", folderName = workspaceDisplayName(DEFAULT_SCRATCH_CWD, label = null)))
        // Compared exactly: a case variant is a real label.
        assertEquals("My-App", workspaceLabelFor("My-App", folderName = "my-app"))
    }

    @Test
    fun labelRule_anythingElseIsSentTrimmed() {
        assertEquals("Design system", workspaceLabelFor("  Design system \n", folderName = "my-app"))
        assertEquals("Työ 🛠", workspaceLabelFor("Työ 🛠", folderName = "my-app"))
    }

    @Test
    fun labelBound_countsUtf8BytesNotCharacters() {
        assertFalse(isWorkspaceLabelTooLong(null))
        assertFalse(isWorkspaceLabelTooLong("w".repeat(MAX_WORKSPACE_LABEL_BYTES)))
        assertTrue(isWorkspaceLabelTooLong("w".repeat(MAX_WORKSPACE_LABEL_BYTES + 1)))
        // 64 two-byte characters sit exactly on the bound; a 65th is over it though it is far under 128 chars.
        assertFalse(isWorkspaceLabelTooLong("ö".repeat(64)))
        assertTrue(isWorkspaceLabelTooLong("ö".repeat(65)))
        assertEquals(4, workspaceLabelByteCount("😀"))
    }
}
