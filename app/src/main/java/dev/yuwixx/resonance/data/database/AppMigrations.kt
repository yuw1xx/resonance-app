package dev.yuwixx.resonance.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Migration from schema v1 or v2 to v3.
// Both recreate all tables from scratch: these were pre-release schemas with no
// users to protect, so a clean slate is acceptable and safer than partial ALTER TABLEs
// that could leave the schema in an inconsistent state.

private val CREATE_ALL_TABLES: Array<String> = arrayOf(
    """CREATE TABLE IF NOT EXISTS `songs` (
        `id` INTEGER NOT NULL,
        `uri` TEXT NOT NULL,
        `title` TEXT NOT NULL,
        `artist` TEXT NOT NULL,
        `albumArtist` TEXT NOT NULL,
        `album` TEXT NOT NULL,
        `albumId` INTEGER NOT NULL,
        `genre` TEXT NOT NULL,
        `duration` INTEGER NOT NULL,
        `size` INTEGER NOT NULL,
        `bitrate` INTEGER NOT NULL,
        `sampleRate` INTEGER NOT NULL,
        `trackNumber` INTEGER NOT NULL,
        `discNumber` INTEGER NOT NULL,
        `year` INTEGER NOT NULL,
        `dateAdded` INTEGER NOT NULL,
        `dateModified` INTEGER NOT NULL,
        `path` TEXT NOT NULL,
        `folder` TEXT NOT NULL,
        `mimeType` TEXT NOT NULL,
        `replayGainTrack` REAL,
        `replayGainAlbum` REAL,
        `listenCount` INTEGER NOT NULL DEFAULT 0,
        `lastListened` INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY(`id`)
    )""",
    """CREATE TABLE IF NOT EXISTS `playlists` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `isReadOnly` INTEGER NOT NULL,
        `artworkUri` TEXT,
        `dateCreated` INTEGER NOT NULL,
        `dateModified` INTEGER NOT NULL
    )""",
    """CREATE TABLE IF NOT EXISTS `playlist_songs` (
        `playlistId` INTEGER NOT NULL,
        `songId` INTEGER NOT NULL,
        `position` INTEGER NOT NULL,
        PRIMARY KEY(`playlistId`, `songId`)
    )""",
    """CREATE TABLE IF NOT EXISTS `history` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `songId` INTEGER NOT NULL,
        `listenedAt` INTEGER NOT NULL,
        `durationListened` INTEGER NOT NULL,
        `percentageListened` REAL NOT NULL
    )""",
    """CREATE TABLE IF NOT EXISTS `artist_artwork` (
        `artistName` TEXT NOT NULL,
        `artworkUrl` TEXT,
        `fetchedAt` INTEGER NOT NULL,
        PRIMARY KEY(`artistName`)
    )""",
    """CREATE TABLE IF NOT EXISTS `lyrics` (
        `songId` INTEGER NOT NULL,
        `synced` TEXT,
        `plain` TEXT,
        `source` TEXT NOT NULL,
        `fetchedAt` INTEGER NOT NULL,
        PRIMARY KEY(`songId`)
    )""",
    """CREATE TABLE IF NOT EXISTS `queues` (
        `id` INTEGER NOT NULL,
        `songIds` TEXT NOT NULL,
        `currentIndex` INTEGER NOT NULL,
        `shuffleEnabled` INTEGER NOT NULL,
        `repeatMode` TEXT NOT NULL,
        `originalOrder` TEXT NOT NULL,
        `savedAt` INTEGER NOT NULL,
        PRIMARY KEY(`id`)
    )""",
    """CREATE TABLE IF NOT EXISTS `liked_songs` (
        `songId` INTEGER NOT NULL,
        `likedAt` INTEGER NOT NULL,
        PRIMARY KEY(`songId`)
    )""",
    """CREATE TABLE IF NOT EXISTS `navidrome_songs` (
        `navidromeId` TEXT NOT NULL,
        `numericId` INTEGER NOT NULL,
        `streamUrl` TEXT NOT NULL,
        `title` TEXT NOT NULL,
        `artist` TEXT NOT NULL,
        `albumArtist` TEXT NOT NULL,
        `album` TEXT NOT NULL,
        `albumId` TEXT NOT NULL,
        `coverArtUrl` TEXT,
        `genre` TEXT NOT NULL,
        `durationMs` INTEGER NOT NULL,
        `trackNumber` INTEGER NOT NULL,
        `discNumber` INTEGER NOT NULL,
        `year` INTEGER NOT NULL,
        `path` TEXT NOT NULL,
        `mimeType` TEXT NOT NULL,
        `bitrate` INTEGER NOT NULL,
        `size` INTEGER NOT NULL,
        `playCount` INTEGER NOT NULL,
        `cachedAt` INTEGER NOT NULL,
        PRIMARY KEY(`navidromeId`)
    )""",
    """CREATE TABLE IF NOT EXISTS `navidrome_albums` (
        `navidromeId` TEXT NOT NULL,
        `numericId` INTEGER NOT NULL,
        `name` TEXT NOT NULL,
        `artist` TEXT NOT NULL,
        `artistId` TEXT,
        `coverArtUrl` TEXT,
        `songCount` INTEGER NOT NULL,
        `durationSec` INTEGER NOT NULL,
        `year` INTEGER NOT NULL,
        `cachedAt` INTEGER NOT NULL,
        PRIMARY KEY(`navidromeId`)
    )""",
)

private val DROP_ALL_TABLES: Array<String> = arrayOf(
    "DROP TABLE IF EXISTS `songs`",
    "DROP TABLE IF EXISTS `playlists`",
    "DROP TABLE IF EXISTS `playlist_songs`",
    "DROP TABLE IF EXISTS `history`",
    "DROP TABLE IF EXISTS `artist_artwork`",
    "DROP TABLE IF EXISTS `lyrics`",
    "DROP TABLE IF EXISTS `queues`",
    "DROP TABLE IF EXISTS `liked_songs`",
    "DROP TABLE IF EXISTS `navidrome_songs`",
    "DROP TABLE IF EXISTS `navidrome_albums`",
)

val MIGRATION_1_3 = object : Migration(1, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DROP_ALL_TABLES.forEach { db.execSQL(it) }
        CREATE_ALL_TABLES.forEach { db.execSQL(it) }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DROP_ALL_TABLES.forEach { db.execSQL(it) }
        CREATE_ALL_TABLES.forEach { db.execSQL(it) }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN isMix INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE playlists ADD COLUMN mixSource TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN mixType TEXT")
        db.execSQL("ALTER TABLE playlists ADD COLUMN lastMixGenerated INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `mix_navidrome_songs` (
                `playlistId` INTEGER NOT NULL,
                `navidromeSongId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                PRIMARY KEY(`playlistId`, `navidromeSongId`)
            )
        """.trimIndent())
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `album_artwork` (
                `albumId` INTEGER NOT NULL,
                `artworkUrl` TEXT,
                `fetchedAt` INTEGER NOT NULL,
                PRIMARY KEY(`albumId`)
            )
        """.trimIndent())
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pending_scrobbles` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `service` TEXT NOT NULL,
                `artists` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `album` TEXT,
                `durationSec` INTEGER,
                `trackNumber` INTEGER,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `song_downloads` (
                `songId` INTEGER NOT NULL,
                `navidromeId` TEXT NOT NULL,
                `localFilePath` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `fileSizeBytes` INTEGER NOT NULL DEFAULT 0,
                `requestedAt` INTEGER NOT NULL,
                `downloadedAt` INTEGER NOT NULL DEFAULT 0,
                `errorMessage` TEXT,
                PRIMARY KEY(`songId`)
            )
        """.trimIndent())
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlists ADD COLUMN navidromePlaylistId TEXT")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `pending_star_actions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `songId` INTEGER NOT NULL,
                `navidromeId` TEXT NOT NULL,
                `action` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
