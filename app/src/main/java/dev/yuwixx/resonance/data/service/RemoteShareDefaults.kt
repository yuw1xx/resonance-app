package dev.yuwixx.resonance.data.service

import dev.yuwixx.resonance.BuildConfig

/**
 * Default relay for Resonance Share. The upload token ships in the APK, so it only keeps out
 * drive-by bots; the server rate-limits per IP. Users can set their own server in Settings.
 */
object RemoteShareDefaults {
    const val SERVER_URL = "https://share.yuwixx.dev"
    val UPLOAD_TOKEN: String = BuildConfig.SHARE_UPLOAD_TOKEN
}
