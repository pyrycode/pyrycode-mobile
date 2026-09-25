package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.attachmentDisplayName
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.ui.conversations.components.MAX_CLIPBOARD_CHARS
import de.pyryco.mobile.ui.conversations.components.MarkdownText
import de.pyryco.mobile.ui.conversations.components.boundClipHtml
import de.pyryco.mobile.ui.conversations.components.boundClipText
import de.pyryco.mobile.ui.conversations.components.markdownHtml
import de.pyryco.mobile.ui.conversations.components.markdownPlainText
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The reader's own bound on a file's size (#1027), far below retrieval's 23 MB. `MarkdownText` parses in
 * composition and lays every block out in one non-lazy column, so the bytes an attacker-chosen file may
 * hold have to be bounded before any of it reaches Compose. 256 KiB is far above any workspace note.
 */
internal const val MAX_MARKDOWN_READER_BYTES = 262_144

// Figma 553:2574: the markdown body opens 12dp below the bar (whose rule already carries 16dp beneath it)
// and keeps the bar's 20dp gutter.
private val ReaderBodyTopGap = 12.dp
private val ReaderBodyBottomGap = 16.dp

/** A markdown attachment ready to read (#1027). [toString] prints lengths only, never the name or text. */
class MarkdownDocument(
    val name: String,
    val text: String,
) {
    override fun toString(): String = "MarkdownDocument(name=${name.length}, text=${text.length})"
}

/**
 * A linked note as the thread hands it to the reader (#1067): the [path] it was read from, so Refresh can read
 * it again, and the [document] read. Memory only, never in the route or saved state. [toString] prints lengths.
 */
class LinkedMarkdown(
    val path: String,
    val document: MarkdownDocument,
) {
    override fun toString(): String = "LinkedMarkdown(path=${path.length}, document=$document)"
}

/** Whether a tapped file opens in the reader (#1027): its name ends in `.md` or `.markdown`, in any case. */
internal fun isMarkdownAttachmentName(name: String?): Boolean =
    name != null && (name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true))

/** [bytes] as UTF-8, or `null` when they are not valid UTF-8. Never substitutes U+FFFD for a bad byte. */
internal fun decodeUtf8Strictly(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

/**
 * The markdown attachment [attachmentId] of [conversationId], read from the file [repository] keeps for its
 * host (#1027), or `null` when it cannot be shown: a failed retrieval, an unreadable file, more than
 * [MAX_MARKDOWN_READER_BYTES], or bytes that are not UTF-8. The name is the retrieval's sanitised one.
 * Exceptions other than cancellation are dropped unread: their messages can carry the path.
 */
internal suspend fun readMarkdownAttachment(
    repository: ConversationRepository,
    conversationId: String,
    attachmentId: String,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): MarkdownDocument? =
    try {
        val retrieved = repository.retrieveAttachment(conversationId, attachmentId) as? AttachmentRetrievalResult.Retrieved
        retrieved?.let { result ->
            withContext(ioDispatcher) { readBoundedFile(result.file) }
                ?.let(::decodeUtf8Strictly)
                ?.let { text -> MarkdownDocument(result.displayName, text) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** All of [file], or `null` once it holds more than [MAX_MARKDOWN_READER_BYTES]; counted here, not trusted from its length. */
private fun readBoundedFile(file: File): ByteArray? =
    file.inputStream().use { input ->
        val bytes = input.readNBytes(MAX_MARKDOWN_READER_BYTES + 1)
        bytes.takeIf { it.size <= MAX_MARKDOWN_READER_BYTES }
    }

/**
 * The workspace note [path] of [conversationId], read live through [repository] (#1050), or `null` when it
 * cannot be shown: any failed read, more than [MAX_MARKDOWN_READER_BYTES], or bytes that are not UTF-8. One
 * call is one `read_workspace_file`; the bytes stay in memory. [path] is sent exactly as the link wrote it.
 * Exceptions other than cancellation are dropped unread, as [readMarkdownAttachment] drops them.
 */
internal suspend fun readLinkedMarkdown(
    repository: ConversationRepository,
    conversationId: String,
    path: String,
): MarkdownDocument? =
    try {
        (repository.readWorkspaceFile(conversationId, path) as? AttachmentFetchResult.Fetched)
            ?.content
            ?.takeIf { it.size <= MAX_MARKDOWN_READER_BYTES }
            ?.let { content -> ByteArrayOutputStream(content.size.toInt()).also(content::writeTo).toByteArray() }
            ?.let(::decodeUtf8Strictly)
            ?.let { text -> MarkdownDocument(linkedMarkdownName(path), text) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

/** The top bar's name for a linked note (#1050): the path's last component, sanitised, since the assistant wrote it. */
internal fun linkedMarkdownName(path: String): String = attachmentDisplayName(path.substringAfterLast('/'))

/**
 * The reader for a linked workspace note (#1050). The thread read [note] live just before navigating and
 * hands it over in memory, so nothing is fetched on opening; Refresh (#1067) reads its path again through
 * [reread]. `null` means there is no note to show, for example after the process was restored with the
 * reader on top, and goes [onBack] once rather than drawing an empty reader.
 */
@Composable
fun LinkedMarkdownReaderDestination(
    note: LinkedMarkdown?,
    reread: suspend (path: String) -> MarkdownDocument?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    if (note != null) {
        RefreshableMarkdownReader(
            initial = note.document,
            reread = { reread(note.path) },
            onBack = onBack,
            modifier = modifier,
        )
    } else {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        LaunchedEffect(Unit) { currentOnBack() }
    }
}

/**
 * The reader's destination (#1027): reads [attachmentId] through the host's [repository] and draws it. The
 * thread decoded this same kept file before navigating, so the read is local. A read that fails here (the
 * file went in between) goes [onBack] rather than drawing empty or garbled content. The document lives in
 * composition only, never in saved state.
 */
@Composable
fun MarkdownReaderDestination(
    repository: ConversationRepository,
    conversationId: String,
    attachmentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnBack by rememberUpdatedState(onBack)
    val load by produceState<ReaderLoad>(ReaderLoad.Loading, repository, conversationId, attachmentId) {
        value = readMarkdownAttachment(repository, conversationId, attachmentId)?.let(ReaderLoad::Loaded) ?: ReaderLoad.Failed
    }
    when (val current = load) {
        ReaderLoad.Loading -> Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
        is ReaderLoad.Loaded ->
            RefreshableMarkdownReader(
                initial = current.document,
                reread = { readMarkdownAttachment(repository, conversationId, attachmentId) },
                onBack = onBack,
                modifier = modifier,
            )
        ReaderLoad.Failed -> LaunchedEffect(Unit) { currentOnBack() }
    }
}

private sealed interface ReaderLoad {
    data object Loading : ReaderLoad

    class Loaded(
        val document: MarkdownDocument,
    ) : ReaderLoad

    data object Failed : ReaderLoad
}

/**
 * The reader with Refresh (#1067): shows [initial] until a Refresh reads a newer version through [reread].
 * One read at a time; a Refresh while one is reading does nothing. A failed read keeps the content on screen
 * and says the file could not be opened. The document lives in composition only, never in saved state, and
 * leaving the reader cancels a read in flight.
 */
@Composable
fun RefreshableMarkdownReader(
    initial: MarkdownDocument,
    reread: suspend () -> MarkdownDocument?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var document by remember(initial) { mutableStateOf(initial) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentReread by rememberUpdatedState(reread)
    val openFailed = stringResource(AttachmentNotice.OPEN_FAILED.message)
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    MarkdownReaderScreen(
        document = document,
        onBack = onBack,
        modifier = modifier,
        onRefresh = {
            if (refreshJob?.isActive != true) {
                refreshJob =
                    scope.launch {
                        val next = currentReread()
                        RelayLog.d { "event=markdown_reader_refresh outcome=${if (next != null) "loaded" else "failed"}" }
                        if (next != null) {
                            document = next
                        } else {
                            // Its own job: the in-flight guard covers the read, not the notice, so a retry works.
                            scope.launch { snackbarHostState.showSnackbar(openFailed) }
                        }
                    }
            }
        },
        snackbarHostState = snackbarHostState,
    )
}

/** The reader's copy formats (#1067), in menu order. */
private enum class MarkdownCopyFormat(
    val logName: String,
) {
    MARKDOWN("markdown"),
    PLAIN_TEXT("plain"),
    HTML("html"),
}

/**
 * [format]'s clip of [markdown], each field within [MAX_CLIPBOARD_CHARS]. [label] is static, never the note's
 * name. The HTML clip is one item: the HTML with the plain text as its fallback.
 */
private fun markdownClip(
    markdown: String,
    format: MarkdownCopyFormat,
    label: String,
): ClipData =
    when (format) {
        MarkdownCopyFormat.MARKDOWN -> ClipData.newPlainText(label, boundClipText(markdown))
        MarkdownCopyFormat.PLAIN_TEXT -> ClipData.newPlainText(label, boundClipText(markdownPlainText(markdown)))
        MarkdownCopyFormat.HTML ->
            ClipData.newHtmlText(label, boundClipText(markdownPlainText(markdown)), boundClipHtml(markdownHtml(markdown)))
    }

/**
 * A markdown file rendered in-app (#1027), Figma `Markdown Reader Screen` (553:2574): the thread's bar with
 * the file name and the overflow menu (#1067), fixed, over a scrolling [MarkdownText] body. Copies act on
 * [document] and show no notice of their own, since the system confirms a copy; Open in another app (#1068)
 * hands [document] on and reports a failure in [snackbarHostState]. Refresh is the caller's, and so is
 * [snackbarHostState], where a failed refresh is reported.
 */
@Composable
fun MarkdownReaderScreen(
    document: MarkdownDocument,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val clipboard = LocalClipboardManager.current
    val clipLabel = stringResource(R.string.markdown_reader_clip_label)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooserTitle = stringResource(R.string.markdown_reader_open_in_app)
    val notices = AttachmentNotice.entries.associateWith { stringResource(it.message) }
    val onCopy: (MarkdownCopyFormat) -> Unit = { format ->
        val clip = markdownClip(document.text, format, clipLabel)
        clipboard.setClip(ClipEntry(clip))
        RelayLog.d { "event=markdown_reader_copy format=${format.logName} chars=${document.text.length}" }
    }
    val onOpenInApp: () -> Unit = {
        val shown = document
        scope.launch {
            val notice = openNoteInAnotherApp(context, shown, chooserTitle)
            val outcome =
                when (notice) {
                    null -> "opened"
                    AttachmentNotice.NO_APP -> "no_app"
                    else -> "failed"
                }
            RelayLog.d { "event=markdown_reader_open_in_app outcome=$outcome chars=${shown.text.length}" }
            notice?.let { snackbarHostState.showSnackbar(notices.getValue(it)) }
        }
    }
    // A Surface, not a bare background: it also sets `onSurface` as the content colour MarkdownText's text uses.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box {
            Column {
                MarkdownReaderTopBar(
                    name = document.name,
                    onBack = onBack,
                    onCopy = onCopy,
                    onRefresh = onRefresh,
                    onOpenInApp = onOpenInApp,
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(
                        markdown = document.text,
                        modifier =
                            Modifier.padding(
                                start = BarGutter,
                                end = BarGutter,
                                top = ReaderBodyTopGap,
                                bottom = ReaderBodyBottomGap,
                            ),
                    )
                }
            }
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** [ThreadTopAppBar]'s back control, title, overflow and rule, without its title tap. */
@Composable
private fun MarkdownReaderTopBar(
    name: String,
    onBack: () -> Unit,
    onCopy: (MarkdownCopyFormat) -> Unit,
    onRefresh: () -> Unit,
    onOpenInApp: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = BarGutter - BarTouchSlack, end = BarGutter - BarTouchSlack, top = BarTopGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(BarTouchSize)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    modifier = Modifier.size(BarGlyphSize),
                )
            }
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(BarTouchSize)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.cd_more_actions),
                        modifier = Modifier.size(BarGlyphSize),
                    )
                }
                MarkdownReaderMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onCopy = onCopy,
                    onRefresh = onRefresh,
                    onOpenInApp = onOpenInApp,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = BarGutter, end = BarGutter, top = BarRuleGap, bottom = BarBottomGap),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = BAR_RULE_ALPHA),
        )
    }
}

/**
 * The reader's overflow (#1067), built as [ThreadOverflowMenu] is: each item dismisses, then acts. Open in
 * another app (#1068) comes last, after Refresh.
 */
@Composable
private fun MarkdownReaderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: (MarkdownCopyFormat) -> Unit,
    onRefresh: () -> Unit,
    onOpenInApp: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            MarkdownCopyFormat.MARKDOWN to R.string.markdown_reader_copy_markdown,
            MarkdownCopyFormat.PLAIN_TEXT to R.string.markdown_reader_copy_plain_text,
            MarkdownCopyFormat.HTML to R.string.markdown_reader_copy_html,
        ).forEach { (format, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                onClick = {
                    onDismiss()
                    onCopy(format)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.markdown_reader_refresh)) },
            onClick = {
                onDismiss()
                onRefresh()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.markdown_reader_open_in_app)) },
            onClick = {
                onDismiss()
                onOpenInApp()
            },
        )
    }
}

private const val PREVIEW_MARKDOWN =
    "# Builder Pipeline Plan\n\nThe main board runs four roles since 2026-09-01. Each ticket moves from " +
        "[refiner](https://example.com) to builder, then through review and the merge gate.\n\n## Next steps\n\n" +
        "- Pin all five agents repos to one dispatcher version\n- Measure token spend per role\n" +
        "- Retire the old single-role runner\n\n```\npyry release --both\npyry status\n```\n\n" +
        "> Tokens first, running time second."

@Preview(name = "MarkdownReaderScreen — Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun MarkdownReaderScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        MarkdownReaderScreen(document = MarkdownDocument("Builder Pipeline - Plan.md", PREVIEW_MARKDOWN), onBack = {})
    }
}

@Preview(name = "MarkdownReaderScreen — Dark", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun MarkdownReaderScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        MarkdownReaderScreen(document = MarkdownDocument("Builder Pipeline - Plan.md", PREVIEW_MARKDOWN), onBack = {})
    }
}
