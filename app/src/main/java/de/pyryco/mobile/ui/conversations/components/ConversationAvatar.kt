package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock

private const val FALLBACK_CHAR = '?'
private const val INITIALS_LENGTH = 2

@Composable
fun ConversationAvatar(
    conversation: Conversation,
    modifier: Modifier = Modifier,
) {
    val palette = avatarPaletteFor(conversation)
    val initials = remember(conversation.name, conversation.id) {
        deriveInitials(conversation.name, fallback = conversation.id)
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(palette.container),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelLarge,
            color = palette.onContainer,
        )
    }
}

internal data class AvatarPalette(
    val container: Color,
    val onContainer: Color,
)

@Composable
internal fun avatarPaletteFor(conversation: Conversation): AvatarPalette {
    val key = conversation.name ?: conversation.id
    val colorScheme = MaterialTheme.colorScheme
    return when (paletteIndexFor(key)) {
        0 -> AvatarPalette(colorScheme.primaryContainer, colorScheme.onPrimaryContainer)
        1 -> AvatarPalette(colorScheme.secondaryContainer, colorScheme.onSecondaryContainer)
        else -> AvatarPalette(colorScheme.tertiaryContainer, colorScheme.onTertiaryContainer)
    }
}

internal fun paletteIndexFor(key: String): Int = Math.floorMod(key.hashCode(), 3)

private val wordBoundary = Regex("[^\\p{L}\\p{N}]+")

internal fun deriveInitials(name: String?, fallback: String): String {
    val words = name
        ?.takeIf { it.isNotBlank() }
        ?.split(wordBoundary)
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".lowercase()
        words.size == 1 -> words[0].padInitials()
        else -> fallback.padInitials()
    }
}

private fun String.padInitials(): String {
    val taken = take(INITIALS_LENGTH).lowercase()
    return if (taken.length == INITIALS_LENGTH) {
        taken
    } else {
        taken + FALLBACK_CHAR.toString().repeat(INITIALS_LENGTH - taken.length)
    }
}

@Preview(name = "Avatar palette — Light", showBackground = true, widthDp = 240)
@Composable
private fun ConversationAvatarLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { AvatarPalettePreviewColumn() }
    }
}

@Preview(
    name = "Avatar palette — Dark",
    showBackground = true,
    widthDp = 240,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConversationAvatarDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { AvatarPalettePreviewColumn() }
    }
}

@Composable
private fun AvatarPalettePreviewColumn() {
    // Names chosen so paletteIndexFor lands on 0, 1, and 2 — sample renders all three M3 *-container pairs.
    val previews = listOf(
        previewAvatarConversation(id = "c0", name = "kitchenclaw refactor"),
        previewAvatarConversation(id = "c1", name = "pyrycode discord integration"),
        previewAvatarConversation(id = "c2", name = "rocd-thinking"),
    )
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        previews.forEach { ConversationAvatar(it) }
    }
}

private fun previewAvatarConversation(id: String, name: String): Conversation =
    Conversation(
        id = id,
        name = name,
        cwd = DefaultScratchCwd,
        currentSessionId = "session-$id",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = Clock.System.now(),
    )
