// Custom MediaSession commands and session-extras keys shared by MusicService and its
// controllers (PlayerViewModel). State that must outlive the UI — the sleep timer — lives in
// the service and is mirrored to controllers through session extras.
package dev.yuwixx.resonance.data.service

object SessionProtocol {
    const val CMD_SLEEP_SET = "dev.yuwixx.resonance.SLEEP_SET"
    const val CMD_SLEEP_CANCEL = "dev.yuwixx.resonance.SLEEP_CANCEL"

    // CMD_SLEEP_SET args: either ARG_MINUTES or ARG_TRACKS, plus ARG_FADE.
    const val ARG_MINUTES = "minutes"
    const val ARG_TRACKS = "tracks"
    const val ARG_FADE = "fade"

    // Session extras describing the active sleep timer.
    const val EXTRA_SLEEP_TYPE = "sleep_type"          // "off" | "time" | "tracks"
    const val EXTRA_SLEEP_ENDS_AT = "sleep_ends_at"    // epoch ms, time timers
    const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
    const val EXTRA_SLEEP_TRACKS = "sleep_tracks_left"
    const val EXTRA_SLEEP_FADE = "sleep_fade"

    const val SLEEP_FADE_MS = 30_000L
}
