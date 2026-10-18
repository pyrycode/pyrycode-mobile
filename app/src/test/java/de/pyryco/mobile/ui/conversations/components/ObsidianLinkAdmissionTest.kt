package de.pyryco.mobile.ui.conversations.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop externalLink.test.ts's Obsidian matrix plus strict decoding and repeated-key probes. */
class ObsidianLinkAdmissionTest {
    @Test
    fun readOnlyNoteTargets_areAdmittedAndRoutedVerbatim() {
        val targets =
            listOf(
                "obsidian://open?vault=Second%20brain&file=%F0%9F%93%8B%20Projects%2FPlan",
                "obsidian://open?vault=Second%20brain",
                "obsidian://open?path=%2FUsers%2Fme%2Fnote.md",
                "obsidian://open?vault=x&file=Plan&paneType=tab",
                "obsidian://open",
                "obsidian://open?",
                "obsidian://open?&&",
                "obsidian://open?vault=x&file=Plan.md",
                "obsidian://open?file=Plan.MD",
                "obsidian://open?file=A%2FB%2FPlan",
                "OBSIDIAN://open?vault=x",
                "ObSiDiAn://open?vault=x",
                "obsidian://open?vault&file&path&paneType",
                "obsidian://open?vault=&file=&path=&paneType=",
                "obsidian://open?&vault=x&&file=a&file=b.md&",
                "obsidian://open?file=A+B&vault=A+B",
                "obsidian://open?file=Plan%20v1.2.md",
                "obsidian://open?file=folder.with.dot%2FPlan",
                "obsidian://open?file=Plan.md%2F%5C",
                "obsidian://open?vault=a=b&paneType=x=y",
                "obsidian://open?file=%E2%82%AC&vault=%F0%9F%93%8B",
                "obsidian://open?file=日本語",
            )
        targets.forEach { target ->
            assertTrue(target, isSafeLinkScheme(target))
            listOf(false, true).forEach { optIn ->
                val opened = mutableListOf<String>()
                val paths = mutableListOf<String>()
                routeMarkdownLink(target, if (optIn) { path -> paths += path } else null) { opened += it }
                assertEquals(listOf(target), opened)
                assertTrue(paths.isEmpty())
            }
        }
    }

    @Test
    fun unsafeActionsKeysAuthoritiesAndFiles_areInert() {
        val targets =
            listOf(
                "obsidian://new?vault=x&name=y&content=z",
                "obsidian://adv-uri?vault=x&commandid=y",
                "obsidian://search?vault=x&query=y",
                "obsidian://daily?vault=x",
                "obsidian://unique?vault=x",
                "obsidian://open?append=text",
                "obsidian://open?prepend=text",
                "obsidian://open?action=new",
                "obsidian://open?commandid=y",
                "obsidian://open?%61ppend=text",
                "obsidian://open?%66ile=Plan",
                "obsidian://open?File=Plan",
                "obsidian://open?VAULT=x",
                "obsidian://open?=x",
                "obsidian://open?file=x.hta",
                "obsidian://open?file=A%2Fx.jar",
                "obsidian://open?file=x.lnk",
                "obsidian://open?file=x.hta%2F",
                "obsidian://open?file=x.hta%5C",
                "obsidian://open?file=x.hta/",
                "obsidian://open?file=x.hta\\",
                "obsidian://open?file=x.hta.",
                "obsidian://open?file=x.markdown",
                "obsidian://open?path=%2Ftmp%2Fx.command",
                "obsidian://open?file=file%3Ax",
                "obsidian://open?file=A%3AB.md",
                "obsidian://open?path=C:%5CPlan.md",
                "obsidian://open?file=Plan%20v1.2",
                "obsidian://open?file=%zz",
                "obsidian://open?vault=%zz",
                "obsidian://open?file=a%26append%3Dtext",
                "obsidian://open?file=a%23append=text",
                "obsidian://open?vault=%26",
                "obsidian://open?paneType=%23",
                "obsidian://open?file=a#append=x",
                "obsidian://open?vault=x#",
                "obsidian://OPEN?vault=x",
                "obsidian://Open?vault=x",
                "OBSIDIAN://new?vault=x",
                "obsidian://op%65n?vault=x",
                "obsidian://openx?vault=x",
                "obsidian://open.?vault=x",
                "obsidian://x@open?vault=x",
                "obsidian://open@new?vault=x",
                "obsidian://open:80?vault=x",
                "obsidian://open/?vault=x",
                "obsidian:open?vault=x",
                "obsidian:///open?vault=x",
                " obsidian://open?vault=x",
                "\nobsidian://open",
                "obsidian://open\n",
                "obsidian://open?file=x&file=x.hta",
                "obsidian://open?file=x.hta&file=x",
                "obsidian://open?path=x.jar&path=x.md",
                "obsidian://open?paneType=tab&paneType=%zz",
                "javascript:alert(1)",
                "data:text/html,x",
                "file:///tmp/a.md",
                "content://x/a.md",
                "unknown:x",
            )
        targets.forEach { target ->
            assertFalse(target, isSafeLinkScheme(target))
            routeMarkdownLink(target, { throw AssertionError("workspace callback") }) { throw AssertionError("external callback") }
        }
    }

    @Test
    fun everyValue_requiresStrictDecodeURIComponentUtf8() {
        val invalid =
            listOf(
                "%",
                "%2",
                "%gg",
                "%ＦＦ",
                "%١١",
                "%80",
                "%C0%AF",
                "%C1%BF",
                "%C2",
                "%E2%82",
                "%E0%80%AF",
                "%ED%A0%80",
                "%F0%80%80%AF",
                "%F4%90%80%80",
                "%F5%80%80%80",
                "%FF",
                "%E2%28%A1",
            )
        listOf("vault", "file", "path", "paneType").forEach { key ->
            invalid.forEach { value ->
                val target = "obsidian://open?$key=good&$key=$value"
                assertFalse(target, isSafeLinkScheme(target))
            }
        }
    }

    @Test
    fun mobileWebAndMailtoAdmission_isUnchanged() {
        listOf("http:example.com", "HTTP://h", "https://example.com", "mailto:a@b.c").forEach {
            assertTrue(it, isSafeLinkScheme(it))
        }
    }
}
