package de.pyryco.mobile.ui.conversations.thread

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.ui.conversations.components.MarkdownText
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        is ReaderLoad.Loaded -> MarkdownReaderScreen(document = current.document, onBack = onBack, modifier = modifier)
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
 * A markdown file rendered in-app (#1027), Figma `Markdown Reader Screen` (553:2574): the thread's bar with
 * the file name and no overflow, fixed, over a scrolling [MarkdownText] body.
 */
@Composable
fun MarkdownReaderScreen(
    document: MarkdownDocument,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A Surface, not a bare background: it also sets `onSurface` as the content colour MarkdownText's text uses.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            MarkdownReaderTopBar(name = document.name, onBack = onBack)
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
    }
}

/** [ThreadTopAppBar]'s back control, title and rule, without its overflow or title tap. */
@Composable
private fun MarkdownReaderTopBar(
    name: String,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = BarGutter - BarTouchSlack, end = BarGutter, top = BarTopGap),
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
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = BarGutter, end = BarGutter, top = BarRuleGap, bottom = BarBottomGap),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = BAR_RULE_ALPHA),
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
