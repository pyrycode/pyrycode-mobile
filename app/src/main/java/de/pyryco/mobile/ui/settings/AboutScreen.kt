package de.pyryco.mobile.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                headline = "Version ${BuildConfig.VERSION_NAME}",
                supporting = "build ${BuildConfig.GIT_SHA}",
            )
            SettingsRow(
                headline = "Open source · github.com/pyrycode/pyrycode-mobile",
                trailing = { ExternalLinkIcon() },
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_REPO_URL)))
                },
            )
            SettingsRow(
                headline = "Privacy policy",
                trailing = { ExternalLinkIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "License: MIT",
            )
        }
    }
}

@Composable
private fun ExternalLinkIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_open_in_new),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

private const val SOURCE_REPO_URL = "https://github.com/pyrycode/pyrycode-mobile"

@Preview(name = "About — Light", showBackground = true, widthDp = 412)
@Composable
private fun AboutScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        AboutScreen(onBack = {})
    }
}

@Preview(name = "About — Dark", showBackground = true, widthDp = 412)
@Composable
private fun AboutScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        AboutScreen(onBack = {})
    }
}
