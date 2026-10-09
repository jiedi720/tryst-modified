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

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tryst.R
import app.tryst.core.prefs.BackupPreferences
import app.tryst.core.session.SessionManager
import app.tryst.data.backup.BackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: SessionManager,
    private val backup: BackupManager,
    private val backupPrefs: BackupPreferences,
) : ViewModel() {

    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** True when a preset export password is stored, so the export flow can skip the prompt. */
    val hasPresetPassword: StateFlow<Boolean> = backupPrefs.hasPresetPassword

    /** The default export folder, or null when the user hasn't chosen one. */
    val exportDirUri: StateFlow<Uri?> = backupPrefs.exportDirUri

    /** Keep the app unlocked across the system file-picker handoff (same as the photo picker). */
    fun suppressAutoLock() = session.suppressNextAutoLock()

    /**
     * Runs an export using the preset password, if one is stored. Returns false when there is no
     * usable preset, so the caller knows to fall back to the password dialog rather than silently
     * producing nothing.
     */
    fun exportWithPreset(uri: Uri, includeSettings: Boolean = true): Boolean {
        val password = backupPrefs.exportPassword() ?: return false
        export(uri, password, includeSettings)
        return true
    }

    /**
     * The stored preset export password, or null. Exposed so the export row can run a one-tap export
     * with it. Reading it requires no unlock — that's the point of storing it under its own
     * Keystore key rather than the vault's.
     */
    fun exportPassword(): String? = backupPrefs.exportPassword()

    /** Stores (or with null, clears) the preset export password. */
    fun setPresetPassword(password: String?) = backupPrefs.setExportPassword(password)

    fun setExportDir(uri: Uri?) = backupPrefs.setExportDirUri(uri)

    /**
     * Writes a backup straight into [dir] under [fileName], without prompting for a location.
     *
     * Uses `createDocument` so a same-named file is replaced rather than silently duplicated (a
     * plain `openOutputStream` on an existing document would truncate it, but a provider is free to
     * treat that as a new file instead). Falls back to reporting the failure through [status] like
     * any other export error.
     */
    fun exportInFolder(dir: Uri, fileName: String, password: String, includeSettings: Boolean = true) {
        viewModelScope.launch {
            busy = true
            status = null
            try {
                // DocumentsContract.createDocument takes a *document* (or tree) URI, not a
                // ContentResolver method — hence the explicit buildChildDocumentsUriUsingTree.
                val parent = DocumentsContract.buildChildDocumentsUriUsingTree(dir, DocumentsContract.getTreeDocumentId(dir))
                val doc = DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    "application/octet-stream",
                    fileName,
                ) ?: throw IOException("Couldn't create the backup file in the chosen folder")
                context.contentResolver.openOutputStream(doc)?.use {
                    backup.export(password, it, includeSettings)
                } ?: throw IOException("Couldn't open the destination file")
                status = context.getString(R.string.backup_status_export_done)
            } catch (e: Exception) {
                status = context.getString(R.string.backup_status_export_failed, e.message)
            } finally {
                busy = false
            }
        }
    }

    fun export(uri: Uri, password: String, includeSettings: Boolean = true) {
        viewModelScope.launch {
            busy = true
            status = null
            try {
                context.contentResolver.openOutputStream(uri)?.use { backup.export(password, it, includeSettings) }
                    ?: throw IOException("Couldn't open the destination file")
                status = context.getString(R.string.backup_status_export_done)
            } catch (e: Exception) {
                status = context.getString(R.string.backup_status_export_failed, e.message)
            } finally {
                busy = false
            }
        }
    }

    fun import(uri: Uri, password: String, wipeFirst: Boolean) {
        viewModelScope.launch {
            busy = true
            status = null
            try {
                context.contentResolver.openInputStream(uri)?.use { backup.import(password, it, wipeFirst) }
                    ?: throw IOException("Couldn't open the file")
                status = context.getString(R.string.backup_status_restore_done)
            } catch (e: Exception) {
                Log.e("TRYSTIMPORT", "import failed: ${e.javaClass.name}: ${e.message}", e)
                // Surface the real reason. The old blanket string ("wrong password or not a
                // Tryst backup") hid every other failure mode — a locked session, an unreadable
                // stream, a truncated file — behind an accusation the user can't act on.
                // Export already reports e.message; import now matches it.
                status = context.getString(R.string.backup_status_import_failed, describe(e))
            } finally {
                busy = false
            }
        }
    }

    /**
     * Best-effort human-readable reason for a failed import. Tink's auth failure carries a message
     * that is accurate but technical ("Ciphertext does not match expected value of ...") — map the
     * common cases to plain wording so the user knows whether to retry with a different password,
     * re-pick the file, or unlock the app.
     */
    private fun describe(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            e is IllegalArgumentException && raw.contains("Not a Tryst backup file") ->
                context.getString(R.string.backup_err_not_a_backup)
            e is IllegalArgumentException && raw.contains("Unsupported backup version") ->
                context.getString(R.string.backup_err_unsupported_version)
            e is IllegalStateException && raw.contains("Database accessed while locked") ->
                context.getString(R.string.backup_err_locked)
            raw.contains("Tag mismatch") || raw.contains("does not match expected value") ->
                context.getString(R.string.backup_err_wrong_password)
            raw.contains("Truncated backup") ->
                context.getString(R.string.backup_err_truncated)
            raw.isBlank() -> e.javaClass.simpleName
            else -> raw
        }
    }
}

// ⏣ HW ⏣ · code · tools · homelab · ⏣ HW ⏣
