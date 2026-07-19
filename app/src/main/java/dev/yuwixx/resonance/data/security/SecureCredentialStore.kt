// Keystore-backed storage for the two real secrets the app persists: the Navidrome account
// password and the Last.fm session key. Everything else (server URLs, usernames, tokens the
// codebase already documents as non-secret) stays in the regular DataStore-backed preferences.
package dev.yuwixx.resonance.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SecureCredentialStore"
private const val PREFS_NAME = "resonance_secure_prefs"
private const val KEY_NAVIDROME_PASSWORD = "navidrome_password"
private const val KEY_LASTFM_SESSION_KEY = "lastfm_session_key"

@Singleton
class SecureCredentialStore @Inject constructor(@ApplicationContext context: Context) {

    // Null if Keystore/EncryptedSharedPreferences construction fails (rare, but seen on some
    // OEM ROMs after OS updates) — every accessor below degrades to "no stored credential"
    // rather than crashing, so the app just prompts the user to re-enter it.
    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open encrypted credential store", e)
        null
    }

    fun getNavidromePassword(): String? = safeGet(KEY_NAVIDROME_PASSWORD)
    fun setNavidromePassword(value: String) = safeSet(KEY_NAVIDROME_PASSWORD, value)
    fun clearNavidromePassword() = safeRemove(KEY_NAVIDROME_PASSWORD)

    fun getLastFmSessionKey(): String? = safeGet(KEY_LASTFM_SESSION_KEY)
    fun setLastFmSessionKey(value: String) = safeSet(KEY_LASTFM_SESSION_KEY, value)
    fun clearLastFmSessionKey() = safeRemove(KEY_LASTFM_SESSION_KEY)

    private fun safeGet(key: String): String? =
        try { prefs?.getString(key, null) } catch (e: Exception) {
            Log.e(TAG, "Failed to read $key", e); null
        }

    private fun safeSet(key: String, value: String) {
        try { prefs?.edit()?.putString(key, value)?.apply() } catch (e: Exception) {
            Log.e(TAG, "Failed to write $key", e)
        }
    }

    private fun safeRemove(key: String) {
        try { prefs?.edit()?.remove(key)?.apply() } catch (e: Exception) {
            Log.e(TAG, "Failed to remove $key", e)
        }
    }
}
