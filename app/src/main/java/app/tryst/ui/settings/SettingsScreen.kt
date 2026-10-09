// SPDX-License-Identifier: GPL-3.0-or-later
// ╭──────────────────────────────────────────────────────────────────╮
// │  ┌●───●───●───●───●───●───●───●───●───●───●───●───●───●───●───┐  │
// │  │                                                            │  │
// │  ●   ██╗  ██╗    ██╗    ██╗                                   ●  │
// │  │   ██║  ██║    ██║    ██║           herbiewalker            │  │
// │  │   ███████║    ██║ █╗ ██║──●──●──●──┐                       │  │
// │  │   ██╔══██║    ██║███╗██║           │                       │  │
// │  ●   ██║  ██║    ╚███╔███╔╝           ●───⏣  code · tools     ●  │
// │  │   ╚═╝  ╚═╝     ╚══╝╚══╝                    homelab         │  │
// │  │                                                            │  │
// │  └●───●───●───●───●───●───●───●───●───●───●───●───●───●───●───┘  │
// ╰──────────────────────────────────────────────────────────────────╯
package app.tryst.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tryst.R
import app.tryst.core.prefs.ThemeMode
import app.tryst.core.prefs.WeekStart
import app.tryst.ui.common.SingleSelectChips
import app.tryst.ui.common.adaptiveContentWidth
import app.tryst.ui.common.rememberHaptics
import app.tryst.ui.lock.BiometricPromptHelper
import app.tryst.ui.lock.LockViewModel
import app.tryst.ui.lock.findFragmentActivity

// Auto-lock delay options (ms). 0 = lock immediately on background (default, strongest privacy).
private const val AUTO_LOCK_30S = 30_000L
private const val AUTO_LOCK_1M = 60_000L
private const val AUTO_LOCK_5M = 300_000L
private val AUTO_LOCK_OPTIONS = listOf(0L, AUTO_LOCK_30S, AUTO_LOCK_1M, AUTO_LOCK_5M)

/**
 * A borderless settings row (leading icon, title, optional one-line description) — replaces the
 * old full-width bordered [androidx.compose.material3.OutlinedButton] per row, which gave every
 * navigation/action row the same heavy bordered-rectangle treatment regardless of what it did.
 * Matches the stock-Android/Signal/Gmail settings pattern: a plain tappable row, dividers between
 * sections instead of a border per row. A plain [Row] rather than M3's own `ListItem` — `ListItem`
 * carries its own ~16dp horizontal inset, which would double up against this screen's existing
 * outer padding.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Column {
            Text(title, color = tint, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Default name for a new backup file. The time is included because the one-tap export path writes
 * straight into a remembered folder with no save dialog to let the user rename or confirm: a
 * date-only name would silently overwrite that same day's earlier backup on every export.
 */
private fun backupFileName(): String {
    val now = java.time.LocalDateTime.now()
    val d = "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth)
    val t = "%02d%02d".format(now.hour, now.minute)
    return "tryst-backup-$d-$t.tryst"
}

/** The toggle counterpart of [SettingsRow] — same row shape, a trailing [Switch] instead of an icon. */
@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onCustomizeInsights: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onChangePin: () -> Unit = {},
    onOpenReset: () -> Unit = {},
    onOpenWhatsNew: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onManageCategory: (String) -> Unit = {},
    onCustomizeGallery: () -> Unit = {},
    onOpenCsvImport: () -> Unit = {},
    viewModel: LockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val activity = remember(context) { context.findFragmentActivity() }
    val biometricAvailable = remember { viewModel.canUseBiometrics() }
    var biometricEnabled by remember { mutableStateOf(viewModel.isBiometricEnabled()) }
    val appearanceViewModel: AppearanceViewModel = hiltViewModel()
    val themeMode by appearanceViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by appearanceViewModel.dynamicColor.collectAsStateWithLifecycle()
    val generalViewModel: GeneralSettingsViewModel = hiltViewModel()
    val hapticsEnabled by generalViewModel.hapticsEnabled.collectAsStateWithLifecycle()
    val weekStart by generalViewModel.weekStart.collectAsStateWithLifecycle()
    val defaultToCalendar by generalViewModel.defaultToCalendar.collectAsStateWithLifecycle()
    val autoLockMs by generalViewModel.autoLockTimeoutMs.collectAsStateWithLifecycle()
    val backupViewModel: BackupViewModel = hiltViewModel()
    var showExportPw by remember { mutableStateOf(false) }
    var showImportPw by remember { mutableStateOf(false) }
    var showPresetPw by remember { mutableStateOf(false) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingExportIncludeSettings by remember { mutableStateOf(true) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val hasPresetPassword by backupViewModel.hasPresetPassword.collectAsStateWithLifecycle()
    val exportDirUri by backupViewModel.exportDirUri.collectAsStateWithLifecycle()

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let { backupViewModel.export(it, pendingExportPassword, pendingExportIncludeSettings) }
        pendingExportPassword = ""
    }

    // Picking a default folder grants a persistable tree permission; without taking it the URI would
    // stop resolving after a process death and the next export would fail.
    val pickExportDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            backupViewModel.setExportDir(uri)
        }
    }

    // Where an export should land. A remembered folder wins: build the name ourselves and write
    // straight into the tree, skipping the system save dialog entirely. A SAF tree grant covers
    // every child document, so a name we generate is always writable inside it.
    fun startExport(password: String, includeSettings: Boolean) {
        val dir = exportDirUri
        if (dir == null) {
            pendingExportPassword = password
            pendingExportIncludeSettings = includeSettings
            createBackup.launch(backupFileName())
        } else {
            backupViewModel.exportInFolder(dir, backupFileName(), password, includeSettings)
        }
    }
    // Built by hand rather than via ActivityResultContracts.OpenDocument so we can seed
    // EXTRA_INITIAL_URI — the contract's launch(Array<String>) overload gives no way to say where
    // the picker should open. Backups live in the folder the user exports to, so starting the
    // picker there saves hunting through Downloads. Providers that don't honour the extra simply
    // open at their default location, which is the old behaviour.
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null && result.resultCode == android.app.Activity.RESULT_OK) {
            pendingImportUri = uri
            showImportPw = true
            // The picker's own backgrounding already CONSUMED the one-shot suppression armed on
            // tap (SessionManager clears the flag on first use). Re-arm it here so the password
            // dialog is survivable: with the default 0 ms auto-lock, looking the password up in
            // another app would otherwise lock the session and the restore would then die on
            // "Database accessed while locked" — a failure the old message mislabelled as a
            // wrong password.
            backupViewModel.suppressAutoLock()
        }
    }

    val biometricEnableTitle = stringResource(R.string.settings_biometric_enable)
    val biometricEnableSubtitle = stringResource(R.string.settings_biometric_enable_subtitle)
    val biometricErrorFmt = stringResource(R.string.settings_biometric_error)
    val themeSystemLabel = stringResource(R.string.settings_theme_system)
    val themeLightLabel = stringResource(R.string.settings_theme_light)
    val themeDarkLabel = stringResource(R.string.settings_theme_dark)
    val weekStartSundayLabel = stringResource(R.string.settings_week_start_sunday)
    val weekStartMondayLabel = stringResource(R.string.settings_week_start_monday)
    val autoLockImmediateLabel = stringResource(R.string.settings_autolock_immediate)
    val autoLock30sLabel = stringResource(R.string.settings_autolock_30s)
    val autoLock1mLabel = stringResource(R.string.settings_autolock_1m)
    val autoLock5mLabel = stringResource(R.string.settings_autolock_5m)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Cap + centre on wide windows so settings rows don't stretch (Pass 5); no-op on phones.
                .wrapContentWidth()
                .adaptiveContentWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Top-of-Settings privacy preamble — sits above every section since it describes the whole app.
            Text(
                stringResource(R.string.settings_about_app_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ─── You ──────────────────────────────────────────────────────────────
            Text(stringResource(R.string.settings_you), style = MaterialTheme.typography.titleMedium)
            SettingsRow(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.settings_profile),
                description = stringResource(R.string.settings_profile_desc),
                onClick = onOpenProfile,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ─── Security ─────────────────────────────────────────────────────────
            Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleMedium)

            SettingsRow(icon = Icons.Filled.Password, title = stringResource(R.string.settings_change_pin), onClick = onChangePin)

            Text(stringResource(R.string.settings_autolock), style = MaterialTheme.typography.labelLarge)
            SingleSelectChips(
                options = AUTO_LOCK_OPTIONS,
                selected = autoLockMs,
                label = {
                    when (it) {
                        AUTO_LOCK_30S -> autoLock30sLabel
                        AUTO_LOCK_1M -> autoLock1mLabel
                        AUTO_LOCK_5M -> autoLock5mLabel
                        else -> autoLockImmediateLabel
                    }
                },
                onSelect = { generalViewModel.setAutoLockTimeoutMs(it) },
            )
            Text(
                stringResource(R.string.settings_autolock_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !biometricAvailable -> Text(
                    stringResource(R.string.settings_biometric_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                biometricEnabled -> SettingsRow(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.settings_biometric_disable),
                    onClick = {
                        viewModel.disableBiometric()
                        biometricEnabled = false
                    },
                )

                else -> SettingsRow(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.settings_biometric_enable),
                    onClick = {
                        val cipher = try {
                            viewModel.biometricEncryptCipher()
                        } catch (e: Exception) {
                            viewModel.reportError(biometricErrorFmt.format(e.message))
                            return@SettingsRow
                        }
                        BiometricPromptHelper.authenticate(
                            activity = activity,
                            cipher = cipher,
                            title = biometricEnableTitle,
                            subtitle = biometricEnableSubtitle,
                            onSuccess = { authed -> if (viewModel.enableBiometric(authed)) biometricEnabled = true },
                            onError = { viewModel.reportError(it) },
                            onCancel = { },
                        )
                    },
                )
            }

            SettingsRow(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.settings_lock_now),
                onClick = {
                    haptics.tick()
                    viewModel.lock()
                },
            )

            AnimatedVisibility(visible = viewModel.error != null) {
                viewModel.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ─── Appearance ───────────────────────────────────────────────────────
            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge)
            SingleSelectChips(
                options = ThemeMode.entries,
                selected = themeMode,
                label = {
                    when (it) {
                        ThemeMode.SYSTEM -> themeSystemLabel
                        ThemeMode.LIGHT -> themeLightLabel
                        ThemeMode.DARK -> themeDarkLabel
                    }
                },
                onSelect = { appearanceViewModel.setThemeMode(it) },
            )
            SettingsToggleRow(
                title = stringResource(R.string.settings_material_you),
                description = stringResource(R.string.settings_material_you_desc),
                checked = dynamicColor,
                onCheckedChange = { appearanceViewModel.setDynamicColor(it) },
            )

            SettingsToggleRow(
                title = stringResource(R.string.settings_haptics),
                checked = hapticsEnabled,
                onCheckedChange = { generalViewModel.setHapticsEnabled(it) },
            )

            Text(stringResource(R.string.settings_week_start), style = MaterialTheme.typography.labelLarge)
            SingleSelectChips(
                options = WeekStart.entries,
                selected = weekStart,
                label = {
                    when (it) {
                        WeekStart.SUNDAY -> weekStartSundayLabel
                        WeekStart.MONDAY -> weekStartMondayLabel
                    }
                },
                onSelect = { generalViewModel.setWeekStart(it) },
            )

            SettingsToggleRow(
                title = stringResource(R.string.settings_default_calendar),
                checked = defaultToCalendar,
                onCheckedChange = { generalViewModel.setDefaultToCalendar(it) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ─── Customize tabs ───────────────────────────────────────────────────
            Text(stringResource(R.string.settings_customize), style = MaterialTheme.typography.titleMedium)
            SettingsRow(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.settings_customize_insights),
                description = stringResource(R.string.settings_insights_desc),
                onClick = onCustomizeInsights,
            )
            SettingsRow(
                icon = Icons.Filled.GridView,
                title = stringResource(R.string.settings_customize_gallery),
                description = stringResource(R.string.settings_gallery_desc),
                onClick = onCustomizeGallery,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.settings_categories), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_categories_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val categoryRows = listOf(
                R.string.settings_manage_acts to CatalogCategory.ACTS,
                R.string.settings_manage_kinks to CatalogCategory.KINKS,
                R.string.settings_manage_positions to CatalogCategory.POSITIONS,
                R.string.settings_manage_toys to CatalogCategory.TOYS,
                R.string.settings_manage_occasions to CatalogCategory.OCCASIONS,
                R.string.settings_manage_ejaculation to CatalogCategory.EJACULATION,
            )
            for ((label, category) in categoryRows) {
                SettingsRow(icon = Icons.Filled.Category, title = stringResource(label), onClick = { onManageCategory(category) })
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.settings_backup), style = MaterialTheme.typography.titleMedium)
            SettingsRow(
                icon = Icons.Filled.FileUpload,
                title = stringResource(R.string.settings_export),
                enabled = !backupViewModel.busy,
                // With a preset password there's nothing to ask, so the first tap does the export.
                // Otherwise fall through to the dialog as before.
                onClick = {
                    val preset = if (hasPresetPassword) backupViewModel.exportPassword() else null
                    if (preset != null) startExport(preset, includeSettings = true) else showExportPw = true
                },
            )
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.settings_export_folder),
                description = exportDirUri?.let { stringResource(R.string.settings_export_folder_set) }
                    ?: stringResource(R.string.settings_export_folder_unset),
                enabled = !backupViewModel.busy,
                onClick = { pickExportDir.launch(null) },
            )
            if (exportDirUri != null) {
                SettingsRow(
                    icon = Icons.Filled.FolderOff,
                    title = stringResource(R.string.settings_export_folder_clear),
                    enabled = !backupViewModel.busy,
                    onClick = { backupViewModel.setExportDir(null) },
                )
            }
            SettingsRow(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.settings_export_pw_preset),
                description = if (hasPresetPassword) {
                    stringResource(R.string.settings_export_pw_preset_set)
                } else {
                    stringResource(R.string.settings_export_pw_preset_unset)
                },
                enabled = !backupViewModel.busy,
                onClick = { showPresetPw = true },
            )
            SettingsRow(
                icon = Icons.Filled.FileDownload,
                title = stringResource(R.string.settings_import),
                description = stringResource(R.string.settings_backup_desc),
                enabled = !backupViewModel.busy,
                onClick = {
                    backupViewModel.suppressAutoLock()
                    // If no folder is set, or the provider rejects the intent outright, fall back to
                    // a plain ACTION_OPEN_DOCUMENT with no seed.
                    val intent = runCatching {
                        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            // Accept any type: a .tryst file's MIME is provider-defined and often
                            // octet-stream, so filtering would hide it in some pickers.
                            type = "*/*"
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            // Open where the backups actually are. EXTRA_INITIAL_URI wants the
                            // tree's *document* uri, not the tree uri we stored.
                            exportDirUri?.let { dir ->
                                val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                    dir,
                                    android.provider.DocumentsContract.getTreeDocumentId(dir),
                                )
                                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, doc)
                            }
                        }
                    }.getOrElse {
                        android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(android.content.Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                    }
                    openBackup.launch(intent)
                },
            )
            AnimatedVisibility(visible = backupViewModel.status != null) {
                backupViewModel.status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            SettingsRow(
                icon = Icons.Filled.TableChart,
                title = stringResource(R.string.settings_import_csv),
                description = stringResource(R.string.settings_csv_desc),
                onClick = onOpenCsvImport,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.settings_danger_zone), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            SettingsRow(
                icon = Icons.Filled.DeleteForever,
                title = stringResource(R.string.settings_delete_all),
                description = stringResource(R.string.settings_delete_all_desc),
                tint = MaterialTheme.colorScheme.error,
                onClick = onOpenReset,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleMedium)
            SettingsRow(
                icon = Icons.Filled.NewReleases,
                title = stringResource(R.string.settings_whats_new),
                description = stringResource(R.string.settings_whats_new_desc),
                onClick = onOpenWhatsNew,
            )
            SettingsRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_about_button),
                description = stringResource(R.string.settings_about_desc),
                onClick = onOpenAbout,
            )
        }
    }

    if (showExportPw) {
        BackupPasswordDialog(
            title = stringResource(R.string.settings_backup_pw_set_title),
            requireConfirm = true,
            includeSettingsInitial = true,
            savePresetInitial = true,
            onConfirm = { pw, _, includeSettings, savePreset ->
                showExportPw = false
                if (savePreset) backupViewModel.setPresetPassword(pw)
                backupViewModel.suppressAutoLock()
                startExport(pw, includeSettings)
            },
            onDismiss = { showExportPw = false },
        )
    }

    if (showPresetPw) {
        // Editing the preset: the field starts empty rather than pre-filled with the stored secret —
        // showing it would put the password on screen (and in any screenshot the user takes).
        var preset by remember { mutableStateOf("") }
        var presetConfirm by remember { mutableStateOf("") }
        val presetValid = preset.length >= 6 && preset == presetConfirm
        AlertDialog(
            onDismissRequest = { showPresetPw = false },
            title = { Text(stringResource(R.string.settings_export_pw_preset)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_export_pw_preset_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = preset,
                        onValueChange = { preset = it },
                        label = { Text(stringResource(R.string.settings_password_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = presetConfirm,
                        onValueChange = { presetConfirm = it },
                        label = { Text(stringResource(R.string.settings_confirm_password_label)) },
                        singleLine = true,
                        isError = presetConfirm.isNotEmpty() && preset != presetConfirm,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        backupViewModel.setPresetPassword(preset)
                        showPresetPw = false
                    },
                    enabled = presetValid,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                Row {
                    if (hasPresetPassword) {
                        TextButton(onClick = {
                            backupViewModel.setPresetPassword(null)
                            showPresetPw = false
                        }) { Text(stringResource(R.string.settings_export_pw_preset_clear)) }
                    }
                    TextButton(onClick = { showPresetPw = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }

    if (showImportPw) {
        BackupPasswordDialog(
            title = stringResource(R.string.settings_backup_pw_enter_title),
            requireConfirm = false,
            wipeFirstInitial = true,
            onConfirm = { pw, wipeFirst, _, _ ->
                showImportPw = false
                pendingImportUri?.let { backupViewModel.import(it, pw, wipeFirst) }
                pendingImportUri = null
            },
            onDismiss = {
                showImportPw = false
                pendingImportUri = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupPasswordDialog(
    title: String,
    requireConfirm: Boolean,
    onConfirm: (password: String, wipeFirst: Boolean, includeSettings: Boolean, savePreset: Boolean) -> Unit,
    onDismiss: () -> Unit,
    // Non-null on the import path (Bundle-E Q1): renders a wipe-first checkbox above the password
    // field so users can pick "replace" (default) vs "merge on top" semantics.
    wipeFirstInitial: Boolean? = null,
    // Non-null on the export path (QOL-5): renders an "include my settings" checkbox.
    includeSettingsInitial: Boolean? = null,
    // Non-null on the export path: renders a "remember this password" checkbox so the next export
    // can skip the prompt. Null on import — remembering a password you only need to READ a backup
    // with would be storing a secret for no benefit.
    savePresetInitial: Boolean? = null,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var wipeFirst by remember { mutableStateOf(wipeFirstInitial ?: false) }
    var includeSettings by remember { mutableStateOf(includeSettingsInitial ?: true) }
    var savePreset by remember { mutableStateOf(savePresetInitial ?: false) }
    val mismatch = requireConfirm && confirm.isNotEmpty() && password != confirm
    val valid = password.length >= 6 && (!requireConfirm || password == confirm)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (wipeFirstInitial != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = wipeFirst, role = Role.Switch, onValueChange = { wipeFirst = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = wipeFirst, onCheckedChange = null)
                        Text(
                            stringResource(R.string.settings_backup_wipe_first),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.settings_backup_wipe_first_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (includeSettingsInitial != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = includeSettings, role = Role.Switch, onValueChange = { includeSettings = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = includeSettings, onCheckedChange = null)
                        Text(
                            stringResource(R.string.settings_backup_include_settings),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.settings_backup_include_settings_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.settings_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirm) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.settings_confirm_password_label)) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(if (mismatch) R.string.settings_password_mismatch else R.string.settings_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (savePresetInitial != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = savePreset, role = Role.Switch, onValueChange = { savePreset = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(checked = savePreset, onCheckedChange = null)
                        Text(
                            stringResource(R.string.settings_export_pw_remember),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.settings_export_pw_remember_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password, wipeFirst, includeSettings, savePreset) },
                enabled = valid,
            ) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ⏣ HW ⏣ · code · tools · homelab · ⏣ HW ⏣
