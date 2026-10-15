// Parses LRC-format time-synced lyrics (optionally with word-level <mm:ss.xx> karaoke timing)
// into LyricLine models. Pulled out as a standalone, dependency-free object (rather than a
// LyricsRepository method) so it's directly unit-testable without needing to construct
// LyricsRepository's full DI graph.
package dev.yuwixx.resonance.domain.usecase

import dev.yuwixx.resonance.data.model.LyricLine
import dev.yuwixx.resonance.data.model.WordTiming

object LrcParser {
    private val timePattern = Regex("""^\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)$""")
    private val wordPattern = Regex("""<(\d{2}):(\d{2})\.(\d{2,3})>([^<]*)""")

    fun parse(lrc: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        for (raw in lrc.lines()) {
            val match = timePattern.matchEntire(raw.trim()) ?: continue
            val (min, sec, centis, rest) = match.destructured
            val timeMs = min.toLong() * 60_000 +
                         sec.toLong() * 1_000 +
                         centis.toLong() * if (centis.length == 2) 10L else 1L

            val wordMatches = wordPattern.findAll(rest).toList()
            val wordTimings = if (wordMatches.isNotEmpty()) {
                wordMatches.mapIndexed { i, wm ->
                    val (wMin, wSec, wCs, word) = wm.destructured
                    val startMs = wMin.toLong() * 60_000 +
                                  wSec.toLong() * 1_000 +
                                  wCs.toLong() * if (wCs.length == 2) 10L else 1L
                    val endMs = wordMatches.getOrNull(i + 1)?.let { next ->
                        val (nm, ns, nc, _) = next.destructured
                        nm.toLong() * 60_000 + ns.toLong() * 1_000 +
                                nc.toLong() * if (nc.length == 2) 10L else 1L
                    } ?: (startMs + 500L)
                    WordTiming(startMs = startMs, endMs = endMs, word = word.trim())
                }
            } else emptyList()

            val text = if (wordTimings.isEmpty()) {
                rest.trim()
            } else {
                wordTimings.joinToString(" ") { it.word }
            }

            if (text.isNotBlank() || wordTimings.isNotEmpty()) {
                lines.add(LyricLine(timeMs = timeMs, text = text, wordTimings = wordTimings))
            }
        }

        return lines.sortedBy { it.timeMs }
    }
}
