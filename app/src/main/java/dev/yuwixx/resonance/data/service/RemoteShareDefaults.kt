// Default public relay for long-distance Resonance Share, so the feature works with zero setup
// out of the box. This token is embedded in the app and is not a secret — anyone can extract it
// from the APK — it only exists to filter out driveby bots; real abuse protection is server-side
// per-IP rate limiting. Users who want their own server can override both in Settings.
package dev.yuwixx.resonance.data.service

object RemoteShareDefaults {
    const val SERVER_URL = "https://share.yuwixx.dev"
    const val UPLOAD_TOKEN = "68fa11a42c40cc9a4ae01389109e8a59c5ce18e6346f38ef"
}
