package dev.yuwixx.resonance.domain.usecase

import dev.yuwixx.resonance.data.model.LyricLine
import dev.yuwixx.resonance.data.model.WordTiming

/**
 * Parses LRC lyrics, including enhanced word timings. Handles several timestamps on one line,
 * [offset:], empty lines for instrumental breaks, and a second line with the same timestamp as
 * a translation.
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val leadingTimeTag = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""", RegexOption.MULTILINE)

    /** True when the text contains at least one line starting with an LRC timestamp. */
    fun isSynced(text: String): Boolean = leadingTimeTag.containsMatchIn(text)

    private fun toMs(min: String, sec: String, frac: String): Long {
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs
    }

    private data class Entry(val timeMs: Long, val body: String, val order: Int)

    fun parse(lrc: String): List<LyricLine> {
        var offsetMs = 0L
        val entries = mutableListOf<Entry>()
        var order = 0

        for (raw in lrc.lines()) {
            val line = raw.trim()
            offsetTag.find(line)?.let { offsetMs = it.groupValues[1].toLongOrNull() ?: 0L; continue }

            // All timestamps at the start of the line apply to the same text.
            val times = mutableListOf<Long>()
            var pos = 0
            while (true) {
                val m = timeTag.matchAt(line, pos) ?: break
                val (min, sec, frac) = m.destructured
                times += toMs(min, sec, frac)
                pos = m.range.last + 1
            }
            if (times.isEmpty()) continue
            val body = line.substring(pos)
            // A positive [offset:] shifts lyrics earlier.
            times.forEach { t -> entries += Entry((t - offsetMs).coerceAtLeast(0L), body, order++) }
        }

        val sorted = entries.sortedWith(compareBy({ it.timeMs }, { it.order }))
        val grouped = sorted.groupBy { it.timeMs }.toSortedMap()
        val times = grouped.keys.toList()

        val result = mutableListOf<LyricLine>()
        times.forEachIndexed { index, time ->
            val bodies = grouped.getValue(time).map { it.body }
            val texts = bodies.filter { stripWordTags(it).isNotBlank() }
            val nextTime = times.getOrNull(index + 1)
            if (texts.isEmpty()) {
                // Instrumental gap: only meaningful between sung lines, and only once.
                if (result.isNotEmpty() && result.last().isGap.not()) result += LyricLine(time, "")
                return@forEachIndexed
            }
            val main = texts[0]
            val translation = texts.getOrNull(1)?.let { stripWordTags(it).trim() }
            val words = parseWords(main, time, nextTime)
            val text = if (words.isEmpty()) main.trim() else stripWordTags(main).trim().replace(Regex("\\s+"), " ")
            result += LyricLine(timeMs = time, text = text, wordTimings = words, translation = translation)
        }
        // A trailing gap is just the end marker.
        if (result.lastOrNull()?.isGap == true) result.removeAt(result.lastIndex)
        return result
    }

    private fun stripWordTags(s: String): String = wordTag.replace(s, "")

    private fun parseWords(body: String, lineTime: Long, nextLineTime: Long?): List<WordTiming> {
        val tags = wordTag.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()
        val words = mutableListOf<WordTiming>()

        val prefix = body.substring(0, tags.first().range.first)
        val firstStart = tags.first().destructured.let { (m, s, f) -> toMs(m, s, f) }
        if (prefix.isNotBlank()) words += WordTiming(lineTime, firstStart, prefix.trim())

        tags.forEachIndexed { i, tag ->
            val (m, s, f) = tag.destructured
            val start = toMs(m, s, f)
            val textEnd = tags.getOrNull(i + 1)?.range?.first ?: body.length
            val word = body.substring(tag.range.last + 1, textEnd)
            val end = tags.getOrNull(i + 1)?.destructured?.let { (nm, ns, nf) -> toMs(nm, ns, nf) }
                ?: nextLineTime?.let { minOf(it, start + 5_000L) }
                ?: (start + 500L)
            // A trailing <time> with no text is the end time of the previous word.
            if (word.isBlank()) {
                if (words.isNotEmpty() && i == tags.lastIndex) {
                    words[words.lastIndex] = words.last().copy(endMs = start)
                }
                return@forEachIndexed
            }
            words += WordTiming(startMs = start, endMs = end, word = word.trim())
        }
        return words
    }
}
