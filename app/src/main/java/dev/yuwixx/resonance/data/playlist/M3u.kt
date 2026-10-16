// M3U / M3U8 playlist parsing and writing. Pure Kotlin (no Android types) so it's unit-testable.
package dev.yuwixx.resonance.data.playlist

import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class M3uEntry(
    /** The location line as written, with `\` normalised to `/` and `file://` URLs decoded. */
    val location: String,
    val durationSec: Int? = null,
    val artist: String? = null,
    val title: String? = null,
) {
    val isRemote: Boolean
        get() = location.startsWith("http://", ignoreCase = true) ||
            location.startsWith("https://", ignoreCase = true) ||
            location.startsWith("rtsp://", ignoreCase = true)
}

data class M3uPlaylist(val name: String?, val entries: List<M3uEntry>)

object M3uParser {

    /** Decodes raw file bytes. `.m3u8` is UTF-8 by definition; plain `.m3u` files written by
     *  older Windows players are often Windows-1252, so anything that isn't valid UTF-8 falls
     *  back to that instead of turning accented names into replacement characters. */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }.removePrefix("\uFEFF")
    }

    fun parse(text: String): M3uPlaylist {
        var name: String? = null
        val entries = mutableListOf<M3uEntry>()
        var pendingInfo: Triple<Int?, String?, String?>? = null

        for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> pendingInfo = parseExtInf(line.substring(8))
                line.startsWith("#PLAYLIST:", ignoreCase = true) ->
                    name = line.substring(10).trim().takeIf { it.isNotEmpty() }
                line.startsWith("#") -> Unit // #EXTM3U, #EXTGRP, #EXTALB, comments…
                else -> {
                    val (duration, artist, title) = pendingInfo ?: Triple(null, null, null)
                    entries += M3uEntry(normaliseLocation(line), duration, artist, title)
                    pendingInfo = null
                }
            }
        }
        return M3uPlaylist(name, entries)
    }

    // "#EXTINF:<duration> [key="value" …],<display>" — display is usually "Artist - Title".
    private fun parseExtInf(body: String): Triple<Int?, String?, String?> {
        val comma = indexOfDisplayComma(body)
        val head = if (comma >= 0) body.substring(0, comma) else body
        val display = if (comma >= 0) body.substring(comma + 1).trim() else ""
        val duration = head.trim().substringBefore(' ').toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
        if (display.isEmpty()) return Triple(duration, null, null)
        val sep = display.indexOf(" - ")
        return if (sep > 0) {
            Triple(duration, display.substring(0, sep).trim(), display.substring(sep + 3).trim())
        } else {
            Triple(duration, null, display)
        }
    }

    // The first comma outside double quotes (attribute values may contain commas).
    private fun indexOfDisplayComma(body: String): Int {
        var inQuotes = false
        body.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return i
            }
        }
        return -1
    }

    private fun normaliseLocation(raw: String): String {
        if (raw.startsWith("file:", ignoreCase = true)) {
            // file:///storage/… and file://localhost/…; also file:/C:/… from some Windows apps.
            val path = raw.substringAfter(':').let {
                when {
                    it.startsWith("//localhost/") -> it.removePrefix("//localhost")
                    it.startsWith("///") -> it.removePrefix("//")
                    it.startsWith("//") -> it.removePrefix("/")
                    else -> it
                }
            }
            return decodePercent(path).replace('\\', '/').let { p ->
                // "/C:/Music/…" → "C:/Music/…"
                if (p.length > 3 && p[0] == '/' && p[2] == ':') p.substring(1) else p
            }
        }
        if (raw.contains("://")) return raw
        return raw.replace('\\', '/')
    }

    private fun decodePercent(s: String): String =
        try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            s
        }
}

object M3uWriter {

    data class Track(val path: String, val durationMs: Long, val artist: String, val title: String)

    /** Writes an extended M3U. With [relativeToDir] set (the folder the file will live in),
     *  locations under that folder are written relative to it — portable when the music and
     *  playlists are copied together to another device or PC. */
    fun write(name: String, tracks: List<Track>, relativeToDir: String? = null): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#PLAYLIST:$name")
        tracks.forEach { t ->
            appendLine("#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}")
            appendLine(relativeToDir?.let { relativize(t.path, it) } ?: t.path)
        }
    }

    internal fun relativize(path: String, dir: String): String {
        val base = dir.trimEnd('/').split('/').filter { it.isNotEmpty() }
        val target = path.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < base.size && common < target.size - 1 && base[common] == target[common]) common++
        // Nothing in common beyond the root: an absolute path is clearer than "../../../…".
        if (common == 0) return path
        val ups = List(base.size - common) { ".." }
        return (ups + target.drop(common)).joinToString("/")
    }
}
