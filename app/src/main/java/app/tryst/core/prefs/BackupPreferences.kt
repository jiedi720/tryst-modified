// SPDX-License-Identifier: GPL-3.0-or-later
// ╭──────────────────────────────────────────────────────────────────╮
// │  ┌●───●───●───●───●───●───●───●───●───●───●───●───●───●───●───┐  │
// │  │                                                            │  │
// │  ●   ██╗  ██╗    ██╗    ██╗                                   ●  │
// │  │   ██║  ██║    ██║    ██║           herbiewalker            │  │
// │  │   ███████║    ██║ █╗ ██║──●──●──┐                       │  │
// │  │   ██╔══██║    ██║███╗██║           │                       │  │
// │  ●   ██║  ██║    ╚███╔███╔╝           ●───⏣  code · tools     ●  │
// │  │   ╚═╝  ╚═╝     ╚══╝╚══╝                    homelab         │  │
// │  │                                                            │  │
// │  └●───●───●───●───●───●───●───●───●───●───●───●───●───●───●───┘  │
// ╰──────────────────────────────────────────────────────────────────╯
package app.tryst.core.prefs

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.tryst.core.security.SecureKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted backup/export settings: an optional preset export password and an optional default
 * export directory.
 *
 * **The password is never stored in the clear.** It is encrypted with [SecureKeyStore] under its own
 * Keystore alias — a non-exportable AES-256-GCM key that never leaves the secure hardware, so a
 * stolen preferences file (or a `adb backup` image) yields only ciphertext. This is deliberately a
 * *separate* key from the vault's KEK: the preset password must stay decryptable while the app is
 * locked, since the whole point is to let a backup run without unlocking, and reusing the vault KEK
 * would either fail that or couple the two secrets. Rotating or wiping the vault therefore does not
 * destroy the preset, and vice versa.
 *
 * Caveat worth stating plainly: a non-exportable key protects the password at rest, but anything
 * running inside this process while the user is present can still call [exportPassword]. That is
 * inherent to "remember my password" — it trades the offline-attack resistance of a purely
 * user-entered secret for convenience. The store is excluded from cloud backup and device transfer
 * (see `data_extraction_rules.xml`), and the value never leaves the app except inside the encrypted
 * `.tryst` container the user already chose to create.
 *
 * Not part of the app's own encrypted backup payload: [PrefsBackup] deliberately covers only the
 * four plain, non-sensitive stores, so a preset password can't ride along inside a backup.
 */
@Singleton
class BackupPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("tryst_backup_prefs", Context.MODE_PRIVATE)

    /**
     * A dedicated alias. Distinct from the vault's `KEK_ALIAS` on purpose (see the class comment) —
     * the preset must be readable without unlocking, and the two secrets have different lifetimes.
     */
    private val passwordKeyStore = SecureKeyStore(PASSWORD_KEY_ALIAS)

    // --- preset export password (encrypted) ---

    private val _hasPresetPassword = MutableStateFlow(prefs.contains(KEY_PASSWORD))
    val hasPresetPassword: StateFlow<Boolean> = _hasPresetPassword.asStateFlow()

    /**
     * Encrypts and stores [password], replacing any previous preset. Passing null clears it.
     *
     * Encryption is best-effort in one direction only: if the keystore refuses (some OEMs throw on
     * key generation after a device-level keystore reset), the password is NOT stored plaintext as
     * a fallback — the save is simply skipped and [hasPresetPassword] stays false. Silently
     * downgrading a secret to plaintext would be far worse than not remembering it.
     */
    fun setExportPassword(password: String?) {
        if (password == null) {
            prefs.edit().remove(KEY_PASSWORD).apply()
            _hasPresetPassword.value = false
            return
        }
        val sealed = try {
            passwordKeyStore.encrypt(password.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            return
        }
        val encoded = Base64.encodeToString(sealed, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PASSWORD, encoded).apply()
        _hasPresetPassword.value = true
    }

    /**
     * The preset export password, or null when none is stored.
     *
     * Returns null (rather than throwing) if the ciphertext can't be read back — e.g. the app data
     * was restored onto a different device, where the hardware key doesn't exist. The caller then
     * falls back to prompting, which is the correct outcome: a backup encrypted with a key we can no
     * longer read would be useless anyway.
     */
    fun exportPassword(): String? {
        val encoded = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            String(passwordKeyStore.decrypt(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key gone (device reset / data transplanted). Drop the unusable ciphertext so the UI
            // stops offering a preset that can never work.
            prefs.edit().remove(KEY_PASSWORD).apply()
            _hasPresetPassword.value = false
            null
        }
    }

    // --- default export directory (SAF tree URI) ---

    private val _exportDirUri = MutableStateFlow(
        prefs.getString(KEY_EXPORT_DIR, null)?.let(Uri::parse),
    )

    /** The user-chosen default export folder, or null when exports should ask each time. */
    val exportDirUri: StateFlow<Uri?> = _exportDirUri.asStateFlow()

    /**
     * Stores the default export folder as a string.
     *
     * The caller is responsible for having already taken a persistable URI permission on [uri] via
     * `takePersistableUriPermission` — without that grant this URI stops resolving after a process
     * death, and the export would fail on the next attempt rather than at pick time. Storing null
     * reverts to asking on every export.
     */
    fun setExportDirUri(uri: Uri?) {
        val editor = prefs.edit()
        if (uri == null) editor.remove(KEY_EXPORT_DIR) else editor.putString(KEY_EXPORT_DIR, uri.toString())
        editor.apply()
        _exportDirUri.value = uri
    }

    private companion object {
        const val PASSWORD_KEY_ALIAS = "tryst_backup_pw"
        const val KEY_PASSWORD = "export_pw_sealed"
        const val KEY_EXPORT_DIR = "export_dir_uri"
    }
}

// ⏣ HW ⏣ · code · tools · homelab · ⏣ HW ⏣
