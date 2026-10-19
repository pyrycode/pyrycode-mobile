package de.pyryco.mobile.ui.conversations.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.network.RelayLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObsidianLinkLaunchTest {
    private val target = "ObSiDiAn://open?vault=Second%20brain&file=A%2fPlan.MD&paneType=tab"

    @Test
    fun nonActivityLaunch_hasExactActionTargetAndNewTaskOnly() {
        val intents = mutableListOf<Intent>()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
                override fun startActivity(intent: Intent) {
                    intents += intent
                }
            }
        assertNull(openObsidianLink(context, target))
        assertEquals(1, intents.size)
        assertEquals(Intent.ACTION_VIEW, intents.single().action)
        assertEquals(target, intents.single().dataString)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intents.single().flags)
        assertNull(intents.single().component)
        assertNull(intents.single().`package`)
    }

    @Test
    fun activityLaunch_hasNoNewTaskFlag() {
        val intents = mutableListOf<Intent>()
        val activity =
            object : Activity() {
                override fun startActivity(intent: Intent) {
                    intents += intent
                }
            }
        assertNull(openObsidianLink(activity, target))
        assertEquals(target, intents.single().dataString)
        assertEquals(0, intents.single().flags)
    }

    @Test
    fun deniedTargets_startNothing() {
        val intents = mutableListOf<Intent>()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
                override fun startActivity(intent: Intent) {
                    intents += intent
                }
            }
        listOf("obsidian://new", "obsidian://open?append=x", "obsidian://open?file=x.hta", "https://example.com").forEach {
            assertNull(openObsidianLink(context, it))
        }
        assertTrue(intents.isEmpty())
    }

    @Test
    fun launchFailures_areClassifiedWithoutReadingExceptionMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failures =
            listOf(
                object : ActivityNotFoundException() {
                    override val message: String get() = error("must never read exception message")
                } to MarkdownLinkNotice.OBSIDIAN_NOT_INSTALLED,
                object : SecurityException() {
                    override val message: String get() = error("must never read exception message")
                } to MarkdownLinkNotice.OPEN_FAILED,
            )
        val logs = mutableListOf<String>()
        val oldSink = RelayLog.sink
        val oldEnabled = RelayLog.enabled
        try {
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> logs += message }
            failures.forEach { (failure, expected) ->
                val throwing =
                    object : ContextWrapper(context) {
                        override fun startActivity(intent: Intent): Unit = throw failure
                    }
                assertEquals(expected, openObsidianLink(throwing, target))
            }
            assertEquals(
                listOf("event=markdown_link_open outcome=OBSIDIAN_NOT_INSTALLED", "event=markdown_link_open outcome=OPEN_FAILED"),
                logs,
            )
        } finally {
            RelayLog.sink = oldSink
            RelayLog.enabled = oldEnabled
        }
    }
}
