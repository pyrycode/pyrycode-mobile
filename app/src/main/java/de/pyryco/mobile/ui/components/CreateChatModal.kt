package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/** A fieldless, caller-owned confirmation for a chat on one host. */
@Composable
internal fun CreateChatModal(
    hostName: String,
    onSubmit: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    hostAvailable: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
) {
    MobileGateModal(
        title = stringResource(R.string.create_chat_title),
        cancelLabel = stringResource(android.R.string.cancel),
        submitLabel = stringResource(R.string.create_chat_action),
        onCancel = onDismissRequest,
        onSubmit = onSubmit,
        submissionEnabled = hostAvailable,
        sending = loading,
        error = error,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.create_chat_host, hostName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(name = "Create chat — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Create chat — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun CreateChatModalPreview() {
    PyrycodeMobileTheme {
        CreateChatModal(hostName = "Pyry", onSubmit = {}, onDismissRequest = {})
    }
}
