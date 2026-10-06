package de.pyryco.mobile.ui.conversations.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.AttachmentRead
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.OwnedPasteCopy
import de.pyryco.mobile.ui.conversations.thread.PasteCopyCapture
import de.pyryco.mobile.ui.conversations.thread.PickedAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareIntakeTest {
    @get:Rule val copies = TemporaryFolder()

    @After fun resetDispatcher() = Dispatchers.resetMain()

    @Test fun parsesTextAndOrderedUniqueStreamsWithClipFallbackOnlyWhenAbsent() {
        val a = Uri.parse("content://foreign/a")
        val b = Uri.parse("content://foreign/b")
        val clip = ClipData.newRawUri("ignored", b).apply { addItem(ClipData.Item(a)) }
        val single =
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hello").putExtra(Intent.EXTRA_STREAM, a).apply {
                clipData =
                    clip
            }
        assertEquals(listOf(a), SharePayload.from(single)?.uris)
        assertEquals("hello", SharePayload.from(single)?.text)
        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(b, a, b))
        assertEquals(listOf(b, a), SharePayload.from(multiple)?.uris)
        single.removeExtra(Intent.EXTRA_STREAM)
        assertEquals(listOf(b, a), SharePayload.from(single)?.uris)
        assertFalse(requireNotNull(SharePayload.from(single)).toString().contains("hello"))
    }

    @Test fun rejectsMalformedAndUnsupportedExtrasAndBoundsText() {
        assertNull(SharePayload.from(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "hello")))
        assertNull(SharePayload.from(Intent(Intent.ACTION_SEND)))
        assertNull(SharePayload.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "not a uri")))
        assertNull(SharePayload.from(Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, Uri.parse("content://foreign/a"))))
        assertNull(SharePayload.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, 8)))
        val parsed = requireNotNull(SharePayload.from(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "a".repeat(100_000))))
        assertTrue(parsed.text.length < 100_000)
    }

    @Test fun selectionMergesOnlyTheExactDraftAndConsumesOnce() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ComposerDraftStore()
            val x = HostConversationTarget("host-b", "same-id")
            store.setDraft("host-a", "same-id", "other host")
            store.setDraft(x.serverId, x.conversationId, "existing")
            store.addAttachment(x.serverId, x.conversationId, "old", "old", "text/plain", 1)
            val vm = ShareIntakeViewModel(store, { uri, _ -> captured(uri, dispatcher) }, dispatcher)
            vm.accept(SharePayload("shared", listOf(Uri.parse("content://foreign/a"), Uri.parse("content://foreign/b"))))
            assertFalse(vm.select(x))
            advanceUntilIdle()
            assertEquals("existing", store.draftFor(x.serverId, x.conversationId))
            assertEquals(1, store.attachmentsFor(x.serverId, x.conversationId).size)
            assertTrue(vm.select(x))
            assertFalse(vm.select(x))
            assertEquals("existing\nshared", store.draftFor(x.serverId, x.conversationId))
            assertEquals("other host", store.draftFor("host-a", "same-id"))
            assertEquals(listOf("old", "a", "b"), store.attachmentsFor(x.serverId, x.conversationId).map { it.displayName })
            assertTrue(store.attachmentsFor("host-a", "same-id").isEmpty())
            store.clearHost(x.serverId)
            assertEquals(0, copies.root.listFiles()?.size)
        }

    @Test fun fileOnlyPreservesTextAndBlankDraftIsPrefilled() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ComposerDraftStore()
            val x = HostConversationTarget("host", "x")
            store.setDraft("host", "x", "  ")
            val vm = ShareIntakeViewModel(store, { uri, _ -> captured(uri, dispatcher) }, dispatcher)
            vm.accept(SharePayload("", listOf(Uri.parse("content://foreign/a"))))
            advanceUntilIdle()
            assertTrue(vm.select(x))
            assertEquals("  ", store.draftFor("host", "x"))
            vm.accept(SharePayload("text", emptyList()))
            advanceUntilIdle()
            assertTrue(vm.select(x))
            assertEquals("text", store.draftFor("host", "x"))
            store.clearHost("host")
        }

    @Test fun limitsCaptureAndExistingDraftAndReleasesRefusals() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ComposerDraftStore()
            val x = HostConversationTarget("host", "x")
            repeat(MessageAttachmentIds.MAX - 1) { store.addAttachment("host", "x", "$it", "$it", "text/plain", 1) }
            var count = 0
            val notices = mutableListOf<Pair<Int, Int>>()
            val vm =
                ShareIntakeViewModel(store, { uri, _ ->
                    count++
                    captured(uri, dispatcher)
                }, dispatcher)
            val collecting = launch { vm.notices.collect { notices += it } }
            vm.accept(SharePayload("", (0..MessageAttachmentIds.MAX + 2).map { Uri.parse("content://foreign/$it") }))
            advanceUntilIdle()
            assertEquals(MessageAttachmentIds.MAX, count)
            assertEquals(MessageAttachmentIds.MAX, copies.root.listFiles()?.size)
            assertTrue(vm.select(x))
            runCurrent()
            assertEquals(MessageAttachmentIds.MAX, store.attachmentsFor("host", "x").size)
            assertEquals(1, copies.root.listFiles()?.size)
            assertTrue(notices.contains(R.plurals.thread_attachments_too_many to 3))
            collecting.cancel()
            store.clearHost("host")
        }

    @Test fun replacementCancellationAndViewModelClearingReleaseUnselectedCopies() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val store = ComposerDraftStore()
            val gate = CompletableDeferred<Unit>()
            val vm =
                ShareIntakeViewModel(store, { uri, _ ->
                    if (uri.lastPathSegment == "wait") gate.await()
                    captured(uri, dispatcher)
                }, dispatcher)
            vm.accept(SharePayload("old", listOf(Uri.parse("content://foreign/a"), Uri.parse("content://foreign/wait"))))
            runCurrent()
            assertEquals(1, copies.root.listFiles()?.size)
            vm.accept(SharePayload("new", listOf(Uri.parse("content://foreign/b"))))
            advanceUntilIdle()
            assertEquals(1, copies.root.listFiles()?.size)
            assertEquals("new", vm.state.value?.text)
            vm.cancel()
            assertEquals(0, copies.root.listFiles()?.size)
            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.state.value)
            assertTrue(store.drafts.value.isEmpty())
            vm.accept(SharePayload("", listOf(Uri.parse("content://foreign/b"))))
            advanceUntilIdle()
            ViewModelStore().apply {
                put("share", vm)
                clear()
            }
            assertEquals(0, copies.root.listFiles()?.size)
        }

    @Test fun cancellationAsIoReturnsACompletedCopyCannotPublishOrLeakIt() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val release = CompletableDeferred<Unit>()
            val vm =
                ShareIntakeViewModel(ComposerDraftStore(), { uri, _ ->
                    withContext(NonCancellable) {
                        val file = captured(uri, dispatcher)
                        release.await()
                        file
                    }
                }, dispatcher)
            vm.accept(SharePayload("", listOf(Uri.parse("content://foreign/a"))))
            runCurrent()
            assertEquals(1, copies.root.listFiles()?.size)
            vm.cancel()
            release.complete(Unit)
            advanceUntilIdle()
            assertNull(vm.state.value)
            assertEquals(0, copies.root.listFiles()?.size)
        }

    @Test fun shortcutExtrasAreUntrustedButNeverDiscardAnOtherwiseValidBatch() {
        fun share(id: Any?) =
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "keep me").apply {
                when (id) {
                    is String -> putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                    is Int -> putExtra(Intent.EXTRA_SHORTCUT_ID, id)
                }
            }
        assertEquals("published-id", SharePayload.from(share("published-id"))?.shortcutId)
        for (bad in listOf(null, 42, " ", "x".repeat(257))) {
            val payload = requireNotNull(SharePayload.from(share(bad)))
            assertEquals("keep me", payload.text)
            assertNull(payload.shortcutId)
        }
    }

    @Test fun lateLookupCannotSelectAReplacedOrCancelledBatch() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val drafts = ComposerDraftStore()
            val vm = ShareIntakeViewModel(drafts, { _, _ -> null }, dispatcher)
            val target = HostConversationTarget("host", "x")
            vm.accept(SharePayload("old", emptyList(), "old-id"))
            advanceUntilIdle()
            val old = requireNotNull(vm.state.value).generation
            vm.accept(SharePayload("new", emptyList(), "new-id"))
            advanceUntilIdle()
            assertFalse(vm.select(target, old))
            assertEquals("", drafts.draftFor("host", "x"))
            val current = requireNotNull(vm.state.value).generation
            vm.fallback(old)
            assertEquals("new-id", vm.state.value?.shortcutId)
            vm.cancel()
            assertFalse(vm.select(target, current))
            assertEquals("", drafts.draftFor("host", "x"))
        }

    private suspend fun captured(
        uri: Uri,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): PickedAttachment {
        val copy =
            OwnedPasteCopy.capture(
                copies.root,
                AttachmentReader {
                    AttachmentRead.Bytes(byteArrayOf(1, 2, 3))
                },
                uri.toString(),
                dispatcher,
            ) as PasteCopyCapture.Captured
        return PickedAttachment(uri.toString(), uri.lastPathSegment.orEmpty(), "application/octet-stream", copy.size, copy.copy)
    }
}
