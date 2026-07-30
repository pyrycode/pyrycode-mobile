package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * The compact monospace status line pinned above the input bar: `Opus 4.7 · high`.
 *
 * **Deliberate, spec'd divergence from Figma `16:58`** (#602). That node is a single text layer
 * literally named `Opus 4.7 · high · 73% used` and specifies a third, severity-coloured
 * context-usage segment. The percentage backing it was never measured — it was
 * `ThreadViewModel.STUB_TOKEN_PERCENT`, a constant — so this row renders two segments rather than
 * editorialising about a fabricated number. The honest "Context usage unavailable" explanation
 * lives one tap away in the Status sheet (#601); #591 restores the Figma-matching populated render
 * once the daemon serves real figures. Everything else about the node — typography, alpha, padding,
 * the two-tone span split and the trailing expand affordance — is as designed.
 */
@Composable
fun ThreadStatusRow(
    model: String,
    effort: String,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated: AnnotatedString =
        buildAnnotatedString {
            withStyle(SpanStyle(color = onSurface)) { append(model) }
            withStyle(SpanStyle(color = onSurfaceVariant)) { append(" · $effort") }
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onExpandClick)
                .semantics { role = Role.Button }
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .alpha(0.85f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = annotated,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(R.string.cd_thread_status_expand),
            tint = onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Preview(name = "StatusRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Dark", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", onExpandClick = {})
    }
}
