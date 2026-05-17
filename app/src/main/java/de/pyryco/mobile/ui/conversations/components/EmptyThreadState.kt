package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@Composable
fun EmptyThreadState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.thread_empty_state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "EmptyThreadState — Light", showBackground = true, widthDp = 412)
@Composable
private fun EmptyThreadStateLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            EmptyThreadState(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(horizontal = 24.dp),
            )
        }
    }
}

@Preview(
    name = "EmptyThreadState — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyThreadStateDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            EmptyThreadState(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                        .padding(horizontal = 24.dp),
            )
        }
    }
}
