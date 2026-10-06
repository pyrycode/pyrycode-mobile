package de.pyryco.mobile.ui.conversations.share

import android.content.pm.ShortcutManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.grantNotificationPermission
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real OS ShortcutManager publication and launching its persisted Intent require a device. */
@RunWith(AndroidJUnit4::class)
class SharingShortcutsDeviceTest {
    init {
        grantNotificationPermission()
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dynamicTargetHasShareContractAndLauncherIntentOpensWithoutChangingDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = HostConversationTarget("demo", "seed-channel-personal")
        val publisher = GlobalContext.get().get<SharingShortcuts>()
        val source = GlobalContext.get().get<de.pyryco.mobile.di.HostConversationSource>()
        compose.waitUntil(10_000) {
            source.snapshots.value.any {
                it.serverId == "demo" &&
                    it.channels.any { row -> row.id == target.conversationId }
            }
        }
        runBlocking { publisher.opened(target) }
        val manager = context.getSystemService(ShortcutManager::class.java)
        val shortcut = manager.dynamicShortcuts.single { NotificationTap.target(it.intent) == target }
        assertTrue(shortcut.isDynamic)
        assertFalse(shortcut.isDeclaredInManifest)
        assertFalse(shortcut.isPinned)
        assertEquals(setOf(SHARE_CATEGORY), shortcut.categories)
        assertEquals("Personal", shortcut.shortLabel.toString())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.ACCESS_SHORTCUTS")
        try {
            val icon =
                requireNotNull(context.getSystemService(android.content.pm.LauncherApps::class.java).getShortcutIconDrawable(shortcut, 0))
            val expected =
                requireNotNull(
                    androidx.core.content.ContextCompat
                        .getDrawable(context, R.mipmap.ic_launcher),
                )

            fun render(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
                val bitmap = android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 108, 108)
                drawable.draw(android.graphics.Canvas(bitmap))
                return bitmap
            }
            assertTrue("published icon is the launcher artwork", render(expected).sameAs(render(icon)))
        } finally {
            automation.dropShellPermissionIdentity()
        }
        assertTrue(manager.manifestShortcuts.isEmpty())
        assertTrue(manager.pinnedShortcuts.isEmpty())
        val xml = context.resources.getXml(R.xml.shortcuts)
        val mimes = mutableSetOf<String>()
        var category: String? = null
        var static = 0
        xml.use {
            while (it.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (it.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    when (it.name) {
                        "data" -> mimes += it.getAttributeValue("http://schemas.android.com/apk/res/android", "mimeType")
                        "category" -> category = it.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                        "shortcut" -> static++
                    }
                }
                it.next()
            }
        }
        assertEquals(setOf("text/plain", "image/*", "*/*"), mimes)
        assertEquals(SHARE_CATEGORY, category)
        assertEquals(0, static)
        val repository = requireNotNull(source.repositoryFor(target.serverId))
        runBlocking { repository.rename(target.conversationId, "\u0000" + "😀".repeat(81)) }
        compose.waitUntil(10_000) {
            manager.dynamicShortcuts
                .single { it.id == shortcut.id }
                .shortLabel
                .toString() == "😀".repeat(80)
        }
        runBlocking { repository.rename(target.conversationId, " \u0000 ") }
        compose.waitUntil(10_000) {
            manager.dynamicShortcuts
                .single { it.id == shortcut.id }
                .shortLabel
                .toString() ==
                context.getString(R.string.app_name)
        }
        runBlocking { repository.rename(target.conversationId, "Personal") }
        val drafts = GlobalContext.get().get<ComposerDraftStore>()
        drafts.setDraft(target.serverId, target.conversationId, "unchanged")
        val store = GlobalContext.get().get<de.pyryco.mobile.data.crypto.PairedServerCollectionStore>()
        runBlocking {
            store
                .save(
                    de.pyryco.mobile.data.crypto
                        .PairedServer(
                            "demo",
                            "unused",
                            "wss://unused.example",
                            de.pyryco.mobile.data.network.base64StdEncode(
                                ByteArray(
                                    32,
                                ) {
                                    1
                                },
                            ),
                        ),
                )
        }
        compose.runOnUiThread { context.startActivity(requireNotNull(shortcut.intent)) }
        // CLEAR_TASK replaces the activity; an empty root set is expected until the new UI composes.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        assertEquals("unchanged", drafts.draftFor(target.serverId, target.conversationId))
        runBlocking { store.remove("demo") }
    }
}
