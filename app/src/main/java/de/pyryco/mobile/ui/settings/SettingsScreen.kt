package de.pyryco.mobile.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicense: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
        var materialYou by remember { mutableStateOf(true) }
        var defaultYolo by remember { mutableStateOf(false) }
        var pushNotifications by remember { mutableStateOf(true) }

        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
        ) {
            SettingsSectionHeader("Connection")
            SettingsRow(
                headline = "Server",
                supporting = "juhana-mac-2026",
                trailing = { ChevronIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "Pair another server",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("Appearance")
            SettingsRow(
                headline = "Theme",
                supporting = "System",
                trailing = { ChevronIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "Use Material You dynamic color",
                trailing = {
                    Switch(checked = materialYou, onCheckedChange = { materialYou = it })
                },
            )

            SettingsSectionHeader("Defaults for new conversations")
            SettingsRow(
                headline = "Default model",
                supporting = "Opus 4.7",
                trailing = { ChevronIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "Default effort",
                supporting = "high",
                trailing = { ChevronIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "Default YOLO",
                supporting = "off",
                trailing = {
                    Switch(checked = defaultYolo, onCheckedChange = { defaultYolo = it })
                },
            )
            SettingsRow(
                headline = "Default workspace",
                supporting = "scratch",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("Notifications")
            SettingsRow(
                headline = "Push notifications when claude responds",
                trailing = {
                    Switch(
                        checked = pushNotifications,
                        onCheckedChange = { pushNotifications = it },
                    )
                },
            )
            SettingsRow(
                headline = "Notification sound",
                supporting = "Default",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("Memory")
            SettingsRow(
                headline = "Installed memory plugins",
                supporting = "0 plugins",
                trailing = { AddPill(onClick = {}) },
            )
            SettingsRow(
                headline = "Manage per-channel memory",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("Storage")
            SettingsRow(
                headline = "Archived conversations",
                supporting = "11 archived",
                trailing = { ChevronIcon() },
                onClick = {},
            )
            SettingsRow(
                headline = "Clear cache",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("About")
            SettingsRow(
                headline = "Version ${BuildConfig.VERSION_NAME}",
                supporting = "build ${BuildConfig.VERSION_CODE}",
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
                trailing = { ChevronIcon() },
                onClick = onOpenLicense,
            )
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    headline: String,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ListItem(
        modifier = rowModifier,
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ChevronIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun ExternalLinkIcon() {
    Icon(
        painter = painterResource(R.drawable.ic_open_in_new),
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun AddPill(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_add_memory_plugin),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = "Add", style = MaterialTheme.typography.labelLarge)
    }
}

private const val SOURCE_REPO_URL = "https://github.com/pyrycode/pyrycode-mobile"

@Preview(name = "Settings — Light", showBackground = true, widthDp = 412)
@Composable
private fun SettingsScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        SettingsScreen(onBack = {}, onOpenLicense = {})
    }
}

@Preview(name = "Settings — Dark", showBackground = true, widthDp = 412)
@Composable
private fun SettingsScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        SettingsScreen(onBack = {}, onOpenLicense = {})
    }
}
