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

/**
 * Keystore-backed storage for the Navidrome password and the Last.fm session key. All other
 * settings stay in DataStore.
 */
@Singleton
class SecureCredentialStore @Inject constructor(@ApplicationContext context: Context) {

    // If the file can't be decrypted (its key is gone, e.g. after a restore onto another device
    // or some OEM updates), the stored values are unrecoverable: start over with an empty store
    // so new credentials can be saved. Null only if even that fails.
    private val prefs: SharedPreferences? = open(context) ?: run {
        context.deleteSharedPreferences(PREFS_NAME)
        open(context)
    }

    private fun open(context: Context): SharedPreferences? = try {
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
    /** Returns whether the value was stored. */
    fun setNavidromePassword(value: String): Boolean = safeSet(KEY_NAVIDROME_PASSWORD, value)
    fun clearNavidromePassword() = safeRemove(KEY_NAVIDROME_PASSWORD)

    fun getLastFmSessionKey(): String? = safeGet(KEY_LASTFM_SESSION_KEY)
    fun setLastFmSessionKey(value: String): Boolean = safeSet(KEY_LASTFM_SESSION_KEY, value)
    fun clearLastFmSessionKey() = safeRemove(KEY_LASTFM_SESSION_KEY)

    private fun safeGet(key: String): String? =
        try { prefs?.getString(key, null) } catch (e: Exception) {
            Log.e(TAG, "Failed to read $key", e); null
        }

    private fun safeSet(key: String, value: String): Boolean =
        try {
            prefs?.edit()?.putString(key, value)?.commit() == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write $key", e)
            false
        }

    private fun safeRemove(key: String) {
        try { prefs?.edit()?.remove(key)?.apply() } catch (e: Exception) {
            Log.e(TAG, "Failed to remove $key", e)
        }
    }
}
