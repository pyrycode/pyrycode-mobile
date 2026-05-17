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
import androidx.compose.ui.graphics.Color
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
import de.pyryco.mobile.ui.theme.warning

@Composable
fun ThreadStatusRow(
    model: String,
    effort: String,
    tokenPercent: Int,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val percentColor = tokenPercentColor(tokenPercent)
    val annotated: AnnotatedString =
        buildAnnotatedString {
            withStyle(SpanStyle(color = onSurface)) { append(model) }
            withStyle(SpanStyle(color = onSurfaceVariant)) { append(" · $effort · ") }
            withStyle(SpanStyle(color = percentColor)) { append("$tokenPercent% used") }
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

@Composable
private fun tokenPercentColor(tokenPercent: Int): Color {
    val clamped = tokenPercent.coerceIn(0, 100)
    return when {
        clamped < 50 -> MaterialTheme.colorScheme.onSurfaceVariant
        clamped < 95 -> MaterialTheme.colorScheme.warning
        else -> MaterialTheme.colorScheme.error
    }
}

@Preview(name = "StatusRow — Light, 20%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowLight20Preview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 20, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Light, 60%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowLight60Preview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 60, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Light, 88%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowLight88Preview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 88, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Light, 97%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowLight97Preview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 97, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Dark, 20%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowDark20Preview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 20, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Dark, 60%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowDark60Preview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 60, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Dark, 88%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowDark88Preview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 88, onExpandClick = {})
    }
}

@Preview(name = "StatusRow — Dark, 97%", showBackground = true, widthDp = 412)
@Composable
private fun ThreadStatusRowDark97Preview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadStatusRow(model = "Opus 4.7", effort = "high", tokenPercent = 97, onExpandClick = {})
    }
}
