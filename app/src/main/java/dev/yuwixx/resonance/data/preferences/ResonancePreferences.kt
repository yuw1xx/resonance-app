// DataStore-backed preferences covering every user-configurable setting: playback, audio,
// appearance, library, Navidrome, Last.fm, equalizer, and history thresholds.
package dev.yuwixx.resonance.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.yuwixx.resonance.data.model.MusicSource
import dev.yuwixx.resonance.data.model.RepeatMode
import dev.yuwixx.resonance.data.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resonance_prefs")

@Singleton
class ResonancePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val ds = context.dataStore

    companion object Keys {
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")

        val UPDATE_FREQUENCY = stringPreferencesKey("update_frequency")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")

        val REPEAT_MODE = stringPreferencesKey("repeat_mode")
        val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        val GAPLESS_ENABLED = booleanPreferencesKey("gapless_enabled")
        val CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
        val REPLAY_GAIN_PREAMP_DB = floatPreferencesKey("replay_gain_preamp_db")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PLAYBACK_PITCH = floatPreferencesKey("playback_pitch")
        val RESUME_ON_HEADPHONES = booleanPreferencesKey("resume_on_headphones")
        val PAUSE_ON_HEADPHONES_OUT = booleanPreferencesKey("pause_on_headphones_out")
        val DUCK_AUDIO_ON_FOCUS_LOSS = booleanPreferencesKey("duck_audio_on_focus_loss")
        val SMART_SHUFFLE_ENABLED = booleanPreferencesKey("smart_shuffle_enabled")
        val VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization")

        val MIN_TRACK_DURATION_MS = longPreferencesKey("min_track_duration_ms")
        val ARTIST_DELIMITER = stringPreferencesKey("artist_delimiter")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val INCLUDED_FOLDERS = stringSetPreferencesKey("included_folders")
        val HIDDEN_NAV_TABS  = stringSetPreferencesKey("hidden_nav_tabs")
        val SHOW_ARTWORK_IN_LIST = booleanPreferencesKey("show_artwork_in_list")
        val GROUP_BY_ALBUM_ARTIST = booleanPreferencesKey("group_by_album_artist")
        val SHOW_FILENAME_AS_TITLE = booleanPreferencesKey("show_filename_as_title")
        val IGNORE_ARTICLES = booleanPreferencesKey("ignore_articles")
        val AUTO_SCAN_INTERVAL_HOURS = intPreferencesKey("auto_scan_interval_hours")
        val FETCH_ARTIST_IMAGES = booleanPreferencesKey("fetch_artist_images")
        val FETCH_LYRICS        = booleanPreferencesKey("fetch_lyrics")

        val DYNAMIC_COLOR_ENABLED = booleanPreferencesKey("dynamic_color_enabled")
        val PRESET_COLOR = intPreferencesKey("preset_color")
        val DARK_THEME = stringPreferencesKey("dark_theme")
        val CORNER_RADIUS = intPreferencesKey("corner_radius")
        val SEEKBAR_STYLE = stringPreferencesKey("seekbar_style")
        val BLUR_ARTWORK_BACKGROUND = booleanPreferencesKey("blur_artwork_background")
        val BLUR_STRENGTH = floatPreferencesKey("blur_strength")
        val ARTWORK_ANIMATION = booleanPreferencesKey("artwork_animation")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val SHOW_BITRATE_INFO = booleanPreferencesKey("show_bitrate_info")
        val ALBUM_GRID_COLUMNS = intPreferencesKey("album_grid_columns")
        val MINI_PLAYER_STYLE = stringPreferencesKey("mini_player_style")
        val PLAYER_LAYOUT = stringPreferencesKey("player_layout")
        val SHOW_LYRICS_BUTTON = booleanPreferencesKey("show_lyrics_button")
        val LYRICS_FONT_SCALE = floatPreferencesKey("lyrics_font_scale")

        val LOCKSCREEN_ARTWORK = booleanPreferencesKey("lockscreen_artwork")
        val SHOW_SKIP_BUTTONS = booleanPreferencesKey("show_skip_buttons")

        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val MIN_LISTEN_SECONDS = intPreferencesKey("min_listen_seconds")
        val MIN_LISTEN_PERCENTAGE = floatPreferencesKey("min_listen_percentage")
        val MAX_HISTORY_ITEMS = intPreferencesKey("max_history_items")

        val MUSIC_SOURCE            = stringPreferencesKey("music_source")
        val NAVIDROME_SERVER_URL    = stringPreferencesKey("navidrome_server_url")
        val NAVIDROME_USERNAME      = stringPreferencesKey("navidrome_username")
        val NAVIDROME_PASSWORD      = stringPreferencesKey("navidrome_password")

        val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        val EQ_BAND_LEVELS = stringPreferencesKey("eq_band_levels")
        val EQ_PRESET = intPreferencesKey("eq_preset")

        val PARTY_MODE        = booleanPreferencesKey("party_mode")

        val MALOJA_ENABLED    = booleanPreferencesKey("maloja_enabled")
        val MALOJA_SERVER_URL = stringPreferencesKey("maloja_server_url")
        val MALOJA_API_KEY    = stringPreferencesKey("maloja_api_key")

        val LAST_FM_ENABLED = booleanPreferencesKey("last_fm_enabled")
        val LAST_FM_NOW_PLAYING = booleanPreferencesKey("last_fm_now_playing")
        val LAST_FM_SCROBBLE_PERCENT = floatPreferencesKey("last_fm_scrobble_percent")
        val LAST_FM_SCROBBLE_MIN_SECS = intPreferencesKey("last_fm_scrobble_min_secs")
        val LAST_FM_ONLY_ON_WIFI = booleanPreferencesKey("last_fm_only_on_wifi")
        val LAST_FM_SCROBBLE_OFFLINE = booleanPreferencesKey("last_fm_scrobble_offline")
        val LAST_FM_USERNAME = stringPreferencesKey("last_fm_username")
        val LAST_FM_SESSION_KEY = stringPreferencesKey("last_fm_session_key")

        val AMOLED_BLACK_THEME    = booleanPreferencesKey("amoled_black_theme")
        val COMPACT_LIST_MODE     = booleanPreferencesKey("compact_list_mode")
        val SHOW_DURATION_IN_LIST = booleanPreferencesKey("show_duration_in_list")
        val PLAYER_ARTWORK_SHAPE  = stringPreferencesKey("player_artwork_shape")
        val LYRIC_ALIGNMENT       = stringPreferencesKey("lyric_alignment")
        val SEEKBAR_COLOR         = stringPreferencesKey("seekbar_color")
        val TINTED_NAV_BAR        = booleanPreferencesKey("tinted_nav_bar")

        val FLOATING_NAV_BAR       = booleanPreferencesKey("floating_nav_bar")
        val NAV_LABEL_VISIBILITY   = stringPreferencesKey("nav_label_visibility")
        val PLAYLIST_GRID_COLUMNS  = intPreferencesKey("playlist_grid_columns")
        val HOME_SHOW_MOST_PLAYED  = booleanPreferencesKey("home_show_most_played")
        val HOME_SHOW_SMART_QUEUE  = booleanPreferencesKey("home_show_smart_queue")
        val HOME_SHOW_DAILY_MIXES  = booleanPreferencesKey("home_show_daily_mixes")
        val MIXES_LOCATION         = stringPreferencesKey("mixes_location")

        val SHOW_ALBUM_IN_LIST          = booleanPreferencesKey("show_album_in_list")
        val LIST_ARTWORK_SIZE           = stringPreferencesKey("list_artwork_size")
        val SHOW_REMAINING_TIME         = booleanPreferencesKey("show_remaining_time")
        val SHOW_NEXT_SONG_IN_PLAYER    = booleanPreferencesKey("show_next_song_in_player")
        val LYRICS_LINE_SPACING         = stringPreferencesKey("lyrics_line_spacing")
        val MINI_PLAYER_SHOW_PROGRESS   = booleanPreferencesKey("mini_player_show_progress")
        val MINI_PLAYER_SHOW_SKIP_BTN   = booleanPreferencesKey("mini_player_show_skip_btn")
        val HOME_SHOW_RECENTLY_ADDED    = booleanPreferencesKey("home_show_recently_added")
        val HOME_RECENTLY_ADDED_COUNT   = intPreferencesKey("home_recently_added_count")
        val DEFAULT_SONGS_SORT          = stringPreferencesKey("default_songs_sort")
        val SHOW_EQUALIZER_IN_PLAYER    = booleanPreferencesKey("show_equalizer_in_player")
    }

    val isFirstRun: Flow<Boolean> = ds.data.map { it[IS_FIRST_RUN] ?: true }
    suspend fun setFirstRunCompleted() { ds.edit { it[IS_FIRST_RUN] = false } }

    val updateFrequency: Flow<String> = ds.data.map { it[UPDATE_FREQUENCY] ?: "DAILY" }
    suspend fun setUpdateFrequency(freq: String) { ds.edit { it[UPDATE_FREQUENCY] = freq } }

    val lastUpdateCheck: Flow<Long> = ds.data.map { it[LAST_UPDATE_CHECK] ?: 0L }
    suspend fun setLastUpdateCheck(time: Long) { ds.edit { it[LAST_UPDATE_CHECK] = time } }

    val repeatMode: Flow<RepeatMode> = ds.data.map {
        try { RepeatMode.valueOf(it[REPEAT_MODE] ?: "NONE") } catch (e: Exception) { RepeatMode.NONE }
    }
    suspend fun setRepeatMode(mode: RepeatMode) { ds.edit { it[REPEAT_MODE] = mode.name } }

    val shuffleEnabled: Flow<Boolean> = ds.data.map { it[SHUFFLE_ENABLED] ?: false }
    suspend fun setShuffleEnabled(enabled: Boolean) { ds.edit { it[SHUFFLE_ENABLED] = enabled } }

    val gaplessEnabled: Flow<Boolean> = ds.data.map { it[GAPLESS_ENABLED] ?: true }
    suspend fun setGaplessEnabled(enabled: Boolean) { ds.edit { it[GAPLESS_ENABLED] = enabled } }

    val crossfadeDurationMs: Flow<Int> = ds.data.map { it[CROSSFADE_DURATION_MS] ?: 0 }
    suspend fun setCrossfadeDuration(ms: Int) { ds.edit { it[CROSSFADE_DURATION_MS] = ms } }

    val skipSilence: Flow<Boolean> = ds.data.map { it[SKIP_SILENCE] ?: false }
    suspend fun setSkipSilence(enabled: Boolean) { ds.edit { it[SKIP_SILENCE] = enabled } }

    val replayGainMode: Flow<String> = ds.data.map { it[REPLAY_GAIN_MODE] ?: "TRACK" }
    suspend fun setReplayGainMode(mode: String) { ds.edit { it[REPLAY_GAIN_MODE] = mode } }

    val replayGainPreampDb: Flow<Float> = ds.data.map { it[REPLAY_GAIN_PREAMP_DB] ?: 0f }
    suspend fun setReplayGainPreamp(db: Float) { ds.edit { it[REPLAY_GAIN_PREAMP_DB] = db } }

    val playbackSpeed: Flow<Float> = ds.data.map { it[PLAYBACK_SPEED] ?: 1.0f }
    suspend fun setPlaybackSpeed(speed: Float) { ds.edit { it[PLAYBACK_SPEED] = speed } }

    val playbackPitch: Flow<Float> = ds.data.map { it[PLAYBACK_PITCH] ?: 1.0f }
    suspend fun setPlaybackPitch(pitch: Float) { ds.edit { it[PLAYBACK_PITCH] = pitch } }

    val resumeOnHeadphones: Flow<Boolean> = ds.data.map { it[RESUME_ON_HEADPHONES] ?: true }
    suspend fun setResumeOnHeadphones(enabled: Boolean) { ds.edit { it[RESUME_ON_HEADPHONES] = enabled } }

    val pauseOnHeadphonesOut: Flow<Boolean> = ds.data.map { it[PAUSE_ON_HEADPHONES_OUT] ?: true }
    suspend fun setPauseOnHeadphonesOut(enabled: Boolean) { ds.edit { it[PAUSE_ON_HEADPHONES_OUT] = enabled } }

    val duckAudioOnFocusLoss: Flow<Boolean> = ds.data.map { it[DUCK_AUDIO_ON_FOCUS_LOSS] ?: true }
    suspend fun setDuckAudioOnFocusLoss(enabled: Boolean) { ds.edit { it[DUCK_AUDIO_ON_FOCUS_LOSS] = enabled } }

    val smartShuffleEnabled: Flow<Boolean> = ds.data.map { it[SMART_SHUFFLE_ENABLED] ?: false }
    suspend fun setSmartShuffleEnabled(enabled: Boolean) { ds.edit { it[SMART_SHUFFLE_ENABLED] = enabled } }

    val volumeNormalization: Flow<Boolean> = ds.data.map { it[VOLUME_NORMALIZATION] ?: false }
    suspend fun setVolumeNormalization(enabled: Boolean) { ds.edit { it[VOLUME_NORMALIZATION] = enabled } }

    val minTrackDurationMs: Flow<Long> = ds.data.map { it[MIN_TRACK_DURATION_MS] ?: 30000L }
    suspend fun setMinTrackDuration(ms: Long) { ds.edit { it[MIN_TRACK_DURATION_MS] = ms } }

    val artistDelimiter: Flow<String> = ds.data.map { it[ARTIST_DELIMITER] ?: ",;/&" }
    suspend fun setArtistDelimiter(delim: String) { ds.edit { it[ARTIST_DELIMITER] = delim } }

    val excludedFolders: Flow<Set<String>> = ds.data.map { it[EXCLUDED_FOLDERS] ?: emptySet() }
    suspend fun setExcludedFolders(folders: Set<String>) { ds.edit { it[EXCLUDED_FOLDERS] = folders } }

    val includedFolders: Flow<Set<String>> = ds.data.map { it[INCLUDED_FOLDERS] ?: emptySet() }
    suspend fun setIncludedFolders(folders: Set<String>) { ds.edit { it[INCLUDED_FOLDERS] = folders } }

    val hiddenNavTabs: Flow<Set<String>> = ds.data.map { it[HIDDEN_NAV_TABS] ?: emptySet() }
    suspend fun setHiddenNavTabs(routes: Set<String>) { ds.edit { it[HIDDEN_NAV_TABS] = routes } }

    val showArtworkInList: Flow<Boolean> = ds.data.map { it[SHOW_ARTWORK_IN_LIST] ?: true }
    suspend fun setShowArtworkInList(enabled: Boolean) { ds.edit { it[SHOW_ARTWORK_IN_LIST] = enabled } }

    val groupByAlbumArtist: Flow<Boolean> = ds.data.map { it[GROUP_BY_ALBUM_ARTIST] ?: true }
    suspend fun setGroupByAlbumArtist(enabled: Boolean) { ds.edit { it[GROUP_BY_ALBUM_ARTIST] = enabled } }

    val showFilenameAsTitle: Flow<Boolean> = ds.data.map { it[SHOW_FILENAME_AS_TITLE] ?: false }
    suspend fun setShowFilenameAsTitle(enabled: Boolean) { ds.edit { it[SHOW_FILENAME_AS_TITLE] = enabled } }

    val ignoreArticles: Flow<Boolean> = ds.data.map { it[IGNORE_ARTICLES] ?: true }
    suspend fun setIgnoreArticles(enabled: Boolean) { ds.edit { it[IGNORE_ARTICLES] = enabled } }

    val autoScanIntervalHours: Flow<Int> = ds.data.map { it[AUTO_SCAN_INTERVAL_HOURS] ?: 0 }
    suspend fun setAutoScanIntervalHours(hours: Int) { ds.edit { it[AUTO_SCAN_INTERVAL_HOURS] = hours } }

    val fetchArtistImages: Flow<Boolean> = ds.data.map { it[FETCH_ARTIST_IMAGES] ?: true }
    suspend fun setFetchArtistImages(enabled: Boolean) { ds.edit { it[FETCH_ARTIST_IMAGES] = enabled } }

    val fetchLyrics: Flow<Boolean> = ds.data.map { it[FETCH_LYRICS] ?: true }
    suspend fun setFetchLyrics(enabled: Boolean) { ds.edit { it[FETCH_LYRICS] = enabled } }

    val dynamicColorEnabled: Flow<Boolean> = ds.data.map { it[DYNAMIC_COLOR_ENABLED] ?: true }
    suspend fun setDynamicColorEnabled(enabled: Boolean) { ds.edit { it[DYNAMIC_COLOR_ENABLED] = enabled } }

    val presetColor: Flow<Int?> = ds.data.map { it[PRESET_COLOR] }
    suspend fun setPresetColor(color: Int) { ds.edit { it[PRESET_COLOR] = color } }

    val darkTheme: Flow<String> = ds.data.map { it[DARK_THEME] ?: "SYSTEM" }
    suspend fun setDarkTheme(theme: String) { ds.edit { it[DARK_THEME] = theme } }

    val cornerRadius: Flow<Int> = ds.data.map { it[CORNER_RADIUS] ?: 28 }
    suspend fun setCornerRadius(radius: Int) { ds.edit { it[CORNER_RADIUS] = radius } }

    val seekbarStyle: Flow<String> = ds.data.map { it[SEEKBAR_STYLE] ?: "WAVEFORM" }
    suspend fun setSeekbarStyle(style: String) { ds.edit { it[SEEKBAR_STYLE] = style } }

    val blurArtworkBackground: Flow<Boolean> = ds.data.map { it[BLUR_ARTWORK_BACKGROUND] ?: true }
    suspend fun setBlurArtworkBackground(enabled: Boolean) { ds.edit { it[BLUR_ARTWORK_BACKGROUND] = enabled } }

    val blurStrength: Flow<Float> = ds.data.map { it[BLUR_STRENGTH] ?: 0.3f }
    suspend fun setBlurStrength(strength: Float) { ds.edit { it[BLUR_STRENGTH] = strength } }

    val artworkAnimation: Flow<Boolean> = ds.data.map { it[ARTWORK_ANIMATION] ?: true }
    suspend fun setArtworkAnimation(enabled: Boolean) { ds.edit { it[ARTWORK_ANIMATION] = enabled } }

    val hapticFeedback: Flow<Boolean> = ds.data.map { it[HAPTIC_FEEDBACK] ?: true }
    suspend fun setHapticFeedback(enabled: Boolean) { ds.edit { it[HAPTIC_FEEDBACK] = enabled } }

    val showBitrateInfo: Flow<Boolean> = ds.data.map { it[SHOW_BITRATE_INFO] ?: false }
    suspend fun setShowBitrateInfo(enabled: Boolean) { ds.edit { it[SHOW_BITRATE_INFO] = enabled } }

    val albumGridColumns: Flow<Int> = ds.data.map { it[ALBUM_GRID_COLUMNS] ?: 2 }
    suspend fun setAlbumGridColumns(cols: Int) { ds.edit { it[ALBUM_GRID_COLUMNS] = cols } }

    val miniPlayerStyle: Flow<String> = ds.data.map { it[MINI_PLAYER_STYLE] ?: "CARD" }
    suspend fun setMiniPlayerStyle(style: String) { ds.edit { it[MINI_PLAYER_STYLE] = style } }

    val playerLayout: Flow<String> = ds.data.map { it[PLAYER_LAYOUT] ?: "STANDARD" }
    suspend fun setPlayerLayout(layout: String) { ds.edit { it[PLAYER_LAYOUT] = layout } }

    val showLyricsButton: Flow<Boolean> = ds.data.map { it[SHOW_LYRICS_BUTTON] ?: true }
    suspend fun setShowLyricsButton(enabled: Boolean) { ds.edit { it[SHOW_LYRICS_BUTTON] = enabled } }

    val lyricsFontScale: Flow<Float> = ds.data.map { it[LYRICS_FONT_SCALE] ?: 1.0f }
    suspend fun setLyricsFontScale(scale: Float) { ds.edit { it[LYRICS_FONT_SCALE] = scale } }

    val lockscreenArtwork: Flow<Boolean> = ds.data.map { it[LOCKSCREEN_ARTWORK] ?: true }
    suspend fun setLockscreenArtwork(enabled: Boolean) { ds.edit { it[LOCKSCREEN_ARTWORK] = enabled } }

    val showSkipButtons: Flow<Boolean> = ds.data.map { it[SHOW_SKIP_BUTTONS] ?: true }
    suspend fun setShowSkipButtons(enabled: Boolean) { ds.edit { it[SHOW_SKIP_BUTTONS] = enabled } }

    val historyEnabled: Flow<Boolean> = ds.data.map { it[HISTORY_ENABLED] ?: true }
    suspend fun setHistoryEnabled(enabled: Boolean) { ds.edit { it[HISTORY_ENABLED] = enabled } }

    val minListenSeconds: Flow<Int> = ds.data.map { it[MIN_LISTEN_SECONDS] ?: 30 }
    val minListenPercentage: Flow<Float> = ds.data.map { it[MIN_LISTEN_PERCENTAGE] ?: 0.5f }
    suspend fun setListenThresholds(seconds: Int, pct: Float) {
        ds.edit {
            it[MIN_LISTEN_SECONDS] = seconds
            it[MIN_LISTEN_PERCENTAGE] = pct
        }
    }

    val maxHistoryItems: Flow<Int> = ds.data.map { it[MAX_HISTORY_ITEMS] ?: 1000 }
    suspend fun setMaxHistoryItems(max: Int) { ds.edit { it[MAX_HISTORY_ITEMS] = max } }

    val musicSource: Flow<MusicSource> = ds.data.map {
        try { MusicSource.valueOf(it[MUSIC_SOURCE] ?: "LOCAL") }
        catch (e: Exception) { MusicSource.LOCAL }
    }
    suspend fun setMusicSource(source: MusicSource) { ds.edit { it[MUSIC_SOURCE] = source.name } }

    val navidromeServerUrl: Flow<String?> = ds.data.map { it[NAVIDROME_SERVER_URL] }
    suspend fun setNavidromeServerUrl(url: String) { ds.edit { it[NAVIDROME_SERVER_URL] = url } }

    val navidromeUsername: Flow<String?> = ds.data.map { it[NAVIDROME_USERNAME] }
    suspend fun setNavidromeUsername(username: String) { ds.edit { it[NAVIDROME_USERNAME] = username } }

    val navidromePassword: Flow<String?> = ds.data.map { it[NAVIDROME_PASSWORD] }
    suspend fun setNavidromePassword(password: String) { ds.edit { it[NAVIDROME_PASSWORD] = password } }

    suspend fun setNavidromeCredentials(url: String, username: String, password: String) {
        ds.edit {
            it[NAVIDROME_SERVER_URL] = url
            it[NAVIDROME_USERNAME]   = username
            it[NAVIDROME_PASSWORD]   = password
        }
    }

    suspend fun clearNavidromeCredentials() {
        ds.edit {
            it.remove(NAVIDROME_SERVER_URL)
            it.remove(NAVIDROME_USERNAME)
            it.remove(NAVIDROME_PASSWORD)
            it[MUSIC_SOURCE] = MusicSource.LOCAL.name
        }
    }

    val eqEnabled: Flow<Boolean> = ds.data.map { it[EQ_ENABLED] ?: false }
    suspend fun setEqEnabled(enabled: Boolean) { ds.edit { it[EQ_ENABLED] = enabled } }

    val eqBandLevels: Flow<String> = ds.data.map { it[EQ_BAND_LEVELS] ?: "" }
    suspend fun setEqBandLevels(levels: String) { ds.edit { it[EQ_BAND_LEVELS] = levels } }

    val eqPreset: Flow<Int> = ds.data.map { it[EQ_PRESET] ?: -1 }
    suspend fun setEqPreset(preset: Int) { ds.edit { it[EQ_PRESET] = preset } }

    val lastFmEnabled: Flow<Boolean> = ds.data.map { it[LAST_FM_ENABLED] ?: false }
    suspend fun setLastFmEnabled(enabled: Boolean) { ds.edit { it[LAST_FM_ENABLED] = enabled } }

    val lastFmNowPlaying: Flow<Boolean> = ds.data.map { it[LAST_FM_NOW_PLAYING] ?: true }
    suspend fun setLastFmNowPlaying(enabled: Boolean) { ds.edit { it[LAST_FM_NOW_PLAYING] = enabled } }

    val lastFmScrobblePercent: Flow<Float> = ds.data.map { it[LAST_FM_SCROBBLE_PERCENT] ?: 0.5f }
    suspend fun setLastFmScrobblePercent(percent: Float) { ds.edit { it[LAST_FM_SCROBBLE_PERCENT] = percent } }

    val lastFmScrobbleMinSecs: Flow<Int> = ds.data.map { it[LAST_FM_SCROBBLE_MIN_SECS] ?: 30 }
    suspend fun setLastFmScrobbleMinSecs(seconds: Int) { ds.edit { it[LAST_FM_SCROBBLE_MIN_SECS] = seconds } }

    val lastFmOnlyOnWifi: Flow<Boolean> = ds.data.map { it[LAST_FM_ONLY_ON_WIFI] ?: false }
    suspend fun setLastFmOnlyOnWifi(enabled: Boolean) { ds.edit { it[LAST_FM_ONLY_ON_WIFI] = enabled } }

    val lastFmScrobbleOffline: Flow<Boolean> = ds.data.map { it[LAST_FM_SCROBBLE_OFFLINE] ?: true }
    suspend fun setLastFmScrobbleOffline(enabled: Boolean) { ds.edit { it[LAST_FM_SCROBBLE_OFFLINE] = enabled } }

    val amoledBlackTheme: Flow<Boolean> = ds.data.map { it[AMOLED_BLACK_THEME] ?: false }
    suspend fun setAmoledBlackTheme(enabled: Boolean) { ds.edit { it[AMOLED_BLACK_THEME] = enabled } }

    val compactListMode: Flow<Boolean> = ds.data.map { it[COMPACT_LIST_MODE] ?: false }
    suspend fun setCompactListMode(enabled: Boolean) { ds.edit { it[COMPACT_LIST_MODE] = enabled } }

    val showDurationInList: Flow<Boolean> = ds.data.map { it[SHOW_DURATION_IN_LIST] ?: false }
    suspend fun setShowDurationInList(enabled: Boolean) { ds.edit { it[SHOW_DURATION_IN_LIST] = enabled } }

    val playerArtworkShape: Flow<String> = ds.data.map { it[PLAYER_ARTWORK_SHAPE] ?: "ROUNDED" }
    suspend fun setPlayerArtworkShape(shape: String) { ds.edit { it[PLAYER_ARTWORK_SHAPE] = shape } }

    val lyricAlignment: Flow<String> = ds.data.map { it[LYRIC_ALIGNMENT] ?: "CENTER" }
    suspend fun setLyricAlignment(alignment: String) { ds.edit { it[LYRIC_ALIGNMENT] = alignment } }

    val seekbarColor: Flow<String> = ds.data.map { it[SEEKBAR_COLOR] ?: "PRIMARY" }
    suspend fun setSeekbarColor(color: String) { ds.edit { it[SEEKBAR_COLOR] = color } }

    val tintedNavBar: Flow<Boolean> = ds.data.map { it[TINTED_NAV_BAR] ?: false }
    suspend fun setTintedNavBar(enabled: Boolean) { ds.edit { it[TINTED_NAV_BAR] = enabled } }

    val floatingNavBar: Flow<Boolean> = ds.data.map { it[FLOATING_NAV_BAR] ?: false }
    suspend fun setFloatingNavBar(enabled: Boolean) { ds.edit { it[FLOATING_NAV_BAR] = enabled } }

    val navLabelVisibility: Flow<String> = ds.data.map { it[NAV_LABEL_VISIBILITY] ?: "ALWAYS" }
    suspend fun setNavLabelVisibility(mode: String) { ds.edit { it[NAV_LABEL_VISIBILITY] = mode } }

    val playlistGridColumns: Flow<Int> = ds.data.map { it[PLAYLIST_GRID_COLUMNS] ?: 2 }
    suspend fun setPlaylistGridColumns(cols: Int) { ds.edit { it[PLAYLIST_GRID_COLUMNS] = cols } }

    val homeShowMostPlayed: Flow<Boolean> = ds.data.map { it[HOME_SHOW_MOST_PLAYED] ?: true }
    suspend fun setHomeShowMostPlayed(show: Boolean) { ds.edit { it[HOME_SHOW_MOST_PLAYED] = show } }

    val homeShowSmartQueue: Flow<Boolean> = ds.data.map { it[HOME_SHOW_SMART_QUEUE] ?: true }
    suspend fun setHomeShowSmartQueue(show: Boolean) { ds.edit { it[HOME_SHOW_SMART_QUEUE] = show } }

    val homeShowDailyMixes: Flow<Boolean> = ds.data.map { it[HOME_SHOW_DAILY_MIXES] ?: true }
    suspend fun setHomeShowDailyMixes(show: Boolean) { ds.edit { it[HOME_SHOW_DAILY_MIXES] = show } }

    val mixesLocation: Flow<String> = ds.data.map { it[MIXES_LOCATION] ?: "BOTH" }
    suspend fun setMixesLocation(location: String) { ds.edit { it[MIXES_LOCATION] = location } }

    val showAlbumInList: Flow<Boolean> = ds.data.map { it[SHOW_ALBUM_IN_LIST] ?: false }
    suspend fun setShowAlbumInList(show: Boolean) { ds.edit { it[SHOW_ALBUM_IN_LIST] = show } }

    val listArtworkSize: Flow<String> = ds.data.map { it[LIST_ARTWORK_SIZE] ?: "MEDIUM" }
    suspend fun setListArtworkSize(size: String) { ds.edit { it[LIST_ARTWORK_SIZE] = size } }

    val showRemainingTime: Flow<Boolean> = ds.data.map { it[SHOW_REMAINING_TIME] ?: false }
    suspend fun setShowRemainingTime(show: Boolean) { ds.edit { it[SHOW_REMAINING_TIME] = show } }

    val showNextSongInPlayer: Flow<Boolean> = ds.data.map { it[SHOW_NEXT_SONG_IN_PLAYER] ?: false }
    suspend fun setShowNextSongInPlayer(show: Boolean) { ds.edit { it[SHOW_NEXT_SONG_IN_PLAYER] = show } }

    val lyricsLineSpacing: Flow<String> = ds.data.map { it[LYRICS_LINE_SPACING] ?: "NORMAL" }
    suspend fun setLyricsLineSpacing(spacing: String) { ds.edit { it[LYRICS_LINE_SPACING] = spacing } }

    val miniPlayerShowProgress: Flow<Boolean> = ds.data.map { it[MINI_PLAYER_SHOW_PROGRESS] ?: true }
    suspend fun setMiniPlayerShowProgress(show: Boolean) { ds.edit { it[MINI_PLAYER_SHOW_PROGRESS] = show } }

    val miniPlayerShowSkipBtn: Flow<Boolean> = ds.data.map { it[MINI_PLAYER_SHOW_SKIP_BTN] ?: true }
    suspend fun setMiniPlayerShowSkipBtn(show: Boolean) { ds.edit { it[MINI_PLAYER_SHOW_SKIP_BTN] = show } }

    val homeShowRecentlyAdded: Flow<Boolean> = ds.data.map { it[HOME_SHOW_RECENTLY_ADDED] ?: true }
    suspend fun setHomeShowRecentlyAdded(show: Boolean) { ds.edit { it[HOME_SHOW_RECENTLY_ADDED] = show } }

    val homeRecentlyAddedCount: Flow<Int> = ds.data.map { it[HOME_RECENTLY_ADDED_COUNT] ?: 10 }
    suspend fun setHomeRecentlyAddedCount(count: Int) { ds.edit { it[HOME_RECENTLY_ADDED_COUNT] = count } }

    val defaultSongsSort: Flow<String> = ds.data.map { it[DEFAULT_SONGS_SORT] ?: "TITLE" }
    suspend fun setDefaultSongsSort(sort: String) { ds.edit { it[DEFAULT_SONGS_SORT] = sort } }

    val showEqualizerInPlayer: Flow<Boolean> = ds.data.map { it[SHOW_EQUALIZER_IN_PLAYER] ?: true }
    suspend fun setShowEqualizerInPlayer(show: Boolean) { ds.edit { it[SHOW_EQUALIZER_IN_PLAYER] = show } }

    val partyMode: Flow<Boolean> = ds.data.map { it[PARTY_MODE] ?: false }
    suspend fun setPartyMode(enabled: Boolean) { ds.edit { it[PARTY_MODE] = enabled } }

    val malojaEnabled: Flow<Boolean>   = ds.data.map { it[MALOJA_ENABLED]    ?: false }
    val malojaServerUrl: Flow<String>  = ds.data.map { it[MALOJA_SERVER_URL] ?: "" }
    val malojaApiKey: Flow<String>     = ds.data.map { it[MALOJA_API_KEY]    ?: "" }
    suspend fun setMalojaEnabled(v: Boolean)  { ds.edit { it[MALOJA_ENABLED]    = v } }
    suspend fun setMalojaServerUrl(v: String) { ds.edit { it[MALOJA_SERVER_URL] = v } }
    suspend fun setMalojaApiKey(v: String)    { ds.edit { it[MALOJA_API_KEY]    = v } }

    val lastFmUsername: Flow<String?> = ds.data.map { it[LAST_FM_USERNAME] }
    val lastFmSessionKey: Flow<String?> = ds.data.map { it[LAST_FM_SESSION_KEY] }
    suspend fun setLastFmCredentials(username: String, sessionKey: String) {
        ds.edit {
            it[LAST_FM_USERNAME] = username
            it[LAST_FM_SESSION_KEY] = sessionKey
        }
    }
    suspend fun clearLastFmCredentials() {
        ds.edit {
            it.remove(LAST_FM_USERNAME)
            it.remove(LAST_FM_SESSION_KEY)
            it[LAST_FM_ENABLED] = false
        }
    }
}
