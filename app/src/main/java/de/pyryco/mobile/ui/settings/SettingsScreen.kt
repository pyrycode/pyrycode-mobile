package de.pyryco.mobile.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.data.preferences.label
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.host.HostEditorModal
import de.pyryco.mobile.ui.host.HostEditorState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.workspaceDisplayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connection: SettingsConnectionState,
    themeMode: ThemeMode,
    useWallpaperColors: Boolean,
    archivedDiscussionCount: Int,
    defaultModel: Model,
    defaultEffort: Effort,
    defaultYolo: Boolean,
    pushNotifications: Boolean,
    defaultWorkspace: String,
    /**
     * The owning host's chosen name for [defaultWorkspace]'s directory, or null when nothing there
     * names it (#723). Display text only: [defaultWorkspace] stays the stored path, and it is the
     * path — never this — that the picker writes and that a new discussion is created in.
     */
    defaultWorkspaceLabel: String?,
    workspacePickerVisible: Boolean,
    onSelectTheme: (ThemeMode) -> Unit,
    onToggleUseWallpaperColors: (Boolean) -> Unit,
    onSelectDefaultModel: (Model) -> Unit,
    onSelectDefaultEffort: (Effort) -> Unit,
    onToggleDefaultYolo: (Boolean) -> Unit,
    onTogglePushNotifications: (Boolean) -> Unit,
    onDefaultWorkspaceTapped: () -> Unit,
    onSelectDefaultWorkspace: (String) -> Unit,
    onWorkspacePickerDismissed: () -> Unit,
    onOpenHost: (String) -> Unit,
    /**
     * The open Edit host modal, or null when none is (#751). Non-null only ever describes this
     * destination's own host: [onEditHost] is reachable from the owner's row alone.
     */
    hostEditor: HostEditorState?,
    /**
     * Opens the editor on this destination's own host. Wired to the owner's row, so a destination
     * owning no host — or one whose host is no longer paired — never reaches it: no row carries
     * `isOwner`, so there is no chevron to tap and no editor to draw.
     */
    onEditHost: () -> Unit,
    onEditHostNameSubmitted: (String) -> Unit,
    onHostUnpairRequested: () -> Unit,
    onHostUnpairConfirmed: () -> Unit,
    onHostUnpairDeclined: () -> Unit,
    onEditHostDismissed: () -> Unit,
    onPairServer: () -> Unit,
    onBack: () -> Unit,
    /**
     * Opens this destination's own host's archive, or null when it owns none (#715). Null draws the
     * row inert through [SettingsRow]'s own nullable-click affordance — an entry that cannot lead
     * anywhere should not offer the tap, and a destination with no owner has no archive to open.
     */
    onOpenArchivedDiscussions: (() -> Unit)?,
    /**
     * Opens the Log data modal on this destination's own host, or null when it owns none (#683).
     * Null draws the row inert through [SettingsRow]'s nullable-click affordance, the way #715 draws
     * the Archive row: a destination with no host has no archive to download.
     */
    onOpenLogData: (() -> Unit)?,
    /** The open Log data modal, or null when none is (#683). */
    logData: DebugBundleDownloadState?,
    onLogDataRequested: () -> Unit,
    onLogDataSaveRequested: () -> Unit,
    onLogDataDismissed: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
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
        var showThemeDialog by remember { mutableStateOf(false) }
        var showModelDialog by remember { mutableStateOf(false) }
        var showEffortDialog by remember { mutableStateOf(false) }

        if (showThemeDialog) {
            ThemePickerDialog(
                selected = themeMode,
                onConfirm = { mode ->
                    onSelectTheme(mode)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false },
            )
        }

        if (showModelDialog) {
            ModelPickerDialog(
                selected = defaultModel,
                onConfirm = { model ->
                    onSelectDefaultModel(model)
                    showModelDialog = false
                },
                onDismiss = { showModelDialog = false },
            )
        }

        if (showEffortDialog) {
            EffortPickerDialog(
                selected = defaultEffort,
                onConfirm = { effort ->
                    onSelectDefaultEffort(effort)
                    showEffortDialog = false
                },
                onDismiss = { showEffortDialog = false },
            )
        }

        WorkspacePicker(
            visible = workspacePickerVisible,
            onPicked = onSelectDefaultWorkspace,
            onDismiss = onWorkspacePickerDismissed,
        )

        // The same binding the channel list draws its editor through (#751), so the presence rule,
        // the loading flag and the generic-failure strings are resolved in one place for both.
        HostEditorModal(
            state = hostEditor,
            onSubmit = onEditHostNameSubmitted,
            onUnpairRequested = onHostUnpairRequested,
            onUnpairConfirmed = onHostUnpairConfirmed,
            onUnpairDeclined = onHostUnpairDeclined,
            onDismissRequest = onEditHostDismissed,
        )

        // The Storage section's own modal (#683), drawn through the same shell and the same
        // presence rule as the editor above it.
        DebugBundleModal(
            state = logData,
            onRequest = onLogDataRequested,
            onSave = onLogDataSaveRequested,
            onDismissRequest = onLogDataDismissed,
        )

        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
        ) {
            SettingsSectionHeader("Connection")
            // One row per saved host, each carrying its own identity and its own status (#750). The
            // destination's own host is marked rather than singled out by position, and it is the
            // only row that does not navigate — it is already here. Neither copy state hides the
            // pairing row below: an unpaired phone reaches the scanner from exactly here (#749).
            when (connection) {
                // Nothing, deliberately: a placeholder here would flash wrong copy for one frame.
                SettingsConnectionState.Resolving -> Unit
                is SettingsConnectionState.Loaded -> {
                    connection.hosts.forEach { row ->
                        // Slots are matched by call order in a plain Column, so without this a
                        // reorder hands one host's composition to another (#750).
                        key(row.serverId) {
                            HostIdentityRow(
                                name = row.name,
                                serverId = row.serverId,
                                relayUrl = row.relayUrl,
                                status = row.status,
                                // The owner's row is the one free affordance in this section, so it
                                // is the one that edits (#751); every other row keeps #750's
                                // host-to-host navigation. A host is edited from its own Settings,
                                // which is why no row opens the editor on some other host's id.
                                onClick = if (row.isOwner) ({ onEditHost() }) else ({ onOpenHost(row.serverId) }),
                                trailing = if (row.isOwner) ({ HostOwnerTrailing() }) else ({ ChevronIcon() }),
                            )
                        }
                    }
                    if (connection.hosts.isEmpty()) {
                        // Subsumes the vanished-owner copy: with nothing paired at all, "none is
                        // paired" is both true and the one that names the way out.
                        SettingsRow(headline = stringResource(R.string.settings_host_none))
                    } else if (connection.ownerMissing) {
                        SettingsRow(headline = stringResource(R.string.settings_host_unknown))
                    }
                }
            }
            SettingsRow(
                headline = "Pair another server",
                trailing = { ChevronIcon() },
                onClick = onPairServer,
            )

            SettingsSectionHeader("Appearance")
            SettingsRow(
                headline = "Theme",
                supporting = themeMode.label(),
                trailing = { ChevronIcon() },
                onClick = { showThemeDialog = true },
            )
            SettingsRow(
                headline = stringResource(R.string.settings_use_wallpaper_colors),
                supporting =
                    if (!dynamicColorSupported) {
                        stringResource(R.string.settings_use_wallpaper_colors_unsupported)
                    } else {
                        null
                    },
                trailing = {
                    Switch(
                        checked = useWallpaperColors,
                        onCheckedChange = onToggleUseWallpaperColors,
                        enabled = dynamicColorSupported,
                    )
                },
            )

            SettingsSectionHeader("Defaults for new conversations")
            SettingsRow(
                headline = "Default model",
                supporting = defaultModel.label(),
                trailing = { ChevronIcon() },
                onClick = { showModelDialog = true },
            )
            SettingsRow(
                headline = "Default effort",
                supporting = defaultEffort.label(),
                trailing = { ChevronIcon() },
                onClick = { showEffortDialog = true },
            )
            SettingsRow(
                headline = "Default YOLO",
                supporting = "off",
                trailing = {
                    Switch(checked = defaultYolo, onCheckedChange = onToggleDefaultYolo)
                },
            )
            SettingsRow(
                headline = "Default workspace",
                // The one shared display rule (#722), which also bounds what daemon-authored text
                // reaches layout — this row resolves nothing of its own.
                supporting = workspaceDisplayName(cwd = defaultWorkspace, label = defaultWorkspaceLabel),
                trailing = { ChevronIcon() },
                onClick = onDefaultWorkspaceTapped,
            )

            SettingsSectionHeader("Notifications")
            SettingsRow(
                headline = "Push notifications when claude responds",
                trailing = {
                    Switch(
                        checked = pushNotifications,
                        onCheckedChange = onTogglePushNotifications,
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
                headline = stringResource(R.string.archived_discussions_settings_row),
                supporting =
                    stringResource(
                        R.string.archived_discussions_count_supporting,
                        archivedDiscussionCount,
                    ),
                trailing = { ChevronIcon() },
                onClick = onOpenArchivedDiscussions,
            )
            // One more Storage row in Clear cache's own language — headline plus chevron, no
            // supporting line and no new section header. The locked frame has no Log data row, so
            // the entry borrows the shape the section already draws rather than inventing one (#683).
            SettingsRow(
                headline = stringResource(R.string.log_data_settings_row),
                trailing = { ChevronIcon() },
                onClick = onOpenLogData,
            )
            SettingsRow(
                headline = "Clear cache",
                trailing = { ChevronIcon() },
                onClick = {},
            )

            SettingsSectionHeader("About")
            SettingsRow(
                headline = stringResource(R.string.about_settings_row),
                trailing = { ChevronIcon() },
                onClick = onOpenAbout,
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
internal fun SettingsRow(
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

/**
 * Which of the listed hosts this screen belongs to (#750). Static copy with no format argument, so
 * no host identity can ride into it and be announced; the row's own lines already carry those.
 */
@Composable
private fun HostOwnerBadge() {
    Text(
        text = stringResource(R.string.settings_host_current),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * The owner row's trailing pair (#751): #750's "This server" badge and the design frame's chevron,
 * alongside each other rather than one replacing the other.
 *
 * The badge says which host this Settings belongs to; the chevron says the row opens something. The
 * frame draws the Server row with a chevron and the marking is ours, so the row needs both — and a
 * row that only badged would offer no visible affordance for the editor it now opens.
 */
@Composable
private fun HostOwnerTrailing() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HostOwnerBadge()
        Spacer(Modifier.width(8.dp))
        ChevronIcon()
    }
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

internal fun ThemeMode.label(): String =
    when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }

/**
 * Two saved hosts with different names and different statuses, the first of them this screen's own
 * — the shape AC1 names, and the one worth eyeballing against the design frame.
 */
private val PREVIEW_CONNECTION =
    SettingsConnectionState.Loaded(
        hosts =
            listOf(
                SettingsHostRow(
                    serverId = "pyrybox-2026-0f3a",
                    displayName = "Pyrybox",
                    relayUrl = "wss://relay.pyryco.de",
                    status = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down),
                    isOwner = true,
                ),
                // Unnamed, so the preview also shows the name line falling back to the server id.
                SettingsHostRow(
                    serverId = "juhana-mac-2026-8c41",
                    displayName = null,
                    relayUrl = "wss://relay.pyryco.de",
                    status = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                    isOwner = false,
                ),
            ),
        ownerMissing = false,
    )

@Preview(name = "Settings — Light", showBackground = true, widthDp = 412)
@Composable
private fun SettingsScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        SettingsScreen(
            connection = PREVIEW_CONNECTION,
            themeMode = ThemeMode.SYSTEM,
            useWallpaperColors = false,
            archivedDiscussionCount = 11,
            defaultModel = Model.OPUS_4_7,
            defaultEffort = Effort.HIGH,
            defaultYolo = false,
            pushNotifications = true,
            // The frame's own unbound state, so this preview stays comparable to it.
            defaultWorkspace = DEFAULT_SCRATCH_CWD,
            defaultWorkspaceLabel = null,
            workspacePickerVisible = false,
            onSelectTheme = {},
            onToggleUseWallpaperColors = {},
            onSelectDefaultModel = {},
            onSelectDefaultEffort = {},
            onToggleDefaultYolo = {},
            onTogglePushNotifications = {},
            onDefaultWorkspaceTapped = {},
            onSelectDefaultWorkspace = {},
            onWorkspacePickerDismissed = {},
            onOpenHost = {},
            // Closed, which is the frame's own state; the modal has its own two previews (#743).
            hostEditor = null,
            onEditHost = {},
            onEditHostNameSubmitted = {},
            onHostUnpairRequested = {},
            onHostUnpairConfirmed = {},
            onHostUnpairDeclined = {},
            onEditHostDismissed = {},
            onPairServer = {},
            onBack = {},
            onOpenArchivedDiscussions = {},
            // Closed, as the editor above is: the frame's own state (#683).
            onOpenLogData = {},
            logData = null,
            onLogDataRequested = {},
            onLogDataSaveRequested = {},
            onLogDataDismissed = {},
            onOpenAbout = {},
        )
    }
}

@Preview(name = "Settings — Dark", showBackground = true, widthDp = 412)
@Composable
private fun SettingsScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        SettingsScreen(
            connection = PREVIEW_CONNECTION,
            themeMode = ThemeMode.SYSTEM,
            useWallpaperColors = false,
            archivedDiscussionCount = 11,
            defaultModel = Model.OPUS_4_7,
            defaultEffort = Effort.HIGH,
            defaultYolo = false,
            pushNotifications = true,
            // The named state the light preview above cannot show: a bound path whose host named it.
            defaultWorkspace = "~/Workspace/Projects/pyrycode-mobile",
            defaultWorkspaceLabel = "Pyrycode Mobile",
            workspacePickerVisible = false,
            onSelectTheme = {},
            onToggleUseWallpaperColors = {},
            onSelectDefaultModel = {},
            onSelectDefaultEffort = {},
            onToggleDefaultYolo = {},
            onTogglePushNotifications = {},
            onDefaultWorkspaceTapped = {},
            onSelectDefaultWorkspace = {},
            onWorkspacePickerDismissed = {},
            onOpenHost = {},
            // Closed, which is the frame's own state; the modal has its own two previews (#743).
            hostEditor = null,
            onEditHost = {},
            onEditHostNameSubmitted = {},
            onHostUnpairRequested = {},
            onHostUnpairConfirmed = {},
            onHostUnpairDeclined = {},
            onEditHostDismissed = {},
            onPairServer = {},
            onBack = {},
            onOpenArchivedDiscussions = {},
            // Closed, as the editor above is: the frame's own state (#683).
            onOpenLogData = {},
            logData = null,
            onLogDataRequested = {},
            onLogDataSaveRequested = {},
            onLogDataDismissed = {},
            onOpenAbout = {},
        )
    }
}
