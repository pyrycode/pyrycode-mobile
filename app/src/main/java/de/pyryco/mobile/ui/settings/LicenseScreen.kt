package de.pyryco.mobile.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.license_title)) },
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
        val context = LocalContext.current
        val licenseText = remember(LICENSE_ASSET_NAME) {
            context.assets.open(LICENSE_ASSET_NAME).bufferedReader().use { it.readText() }
        }
        Text(
            text = licenseText,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

private const val LICENSE_ASSET_NAME = "LICENSE"

@Preview(name = "License — Light", showBackground = true, widthDp = 412)
@Composable
private fun LicenseScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        LicenseScreen(onBack = {})
    }
}

@Preview(name = "License — Dark", showBackground = true, widthDp = 412)
@Composable
private fun LicenseScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        LicenseScreen(onBack = {})
    }
}
