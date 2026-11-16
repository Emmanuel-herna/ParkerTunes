package com.parker.tunes.core.lyrics

data class ParsedLyrics(val lines: List<LyricsLine>, val plainText: String? = null)

object LyricsParser {
    private val lineStamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val inlineStamp = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    fun parse(lrc: String): List<LyricsLine> {
        val parsed = mutableListOf<LyricsLine>()
        lrc.lineSequence().forEach { raw ->
            val stamps = lineStamp.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val text = raw.substring(stamps.last().range.last + 1)
            val enhanced = parseEnhancedText(text, stamps.last().let(::toMs))
            stamps.forEach { stamp ->
                val time = toMs(stamp)
                val shiftedWords = enhanced.first.map { word ->
                    val delta = time - stamps.last().let(::toMs)
                    word.copy(startMs = word.startMs + delta, endMs = word.endMs?.plus(delta),
                        syllables = word.syllables.map { it.copy(startMs = it.startMs + delta, endMs = it.endMs + delta) })
                }
                parsed += LyricsLine(time, enhanced.second, shiftedWords,
                    background = extractBackground(text, time), endMs = shiftedWords.maxOfOrNull { it.endMs ?: it.startMs })
            }
        }
        return parsed.sortedBy { it.timeMs }
    }

    fun parsePlain(text: String, lineDurationMs: Long = 4_000L): List<LyricsLine> =
        text.lineSequence().mapIndexedNotNull { index, line ->
            line.trim().takeIf { it.isNotEmpty() }?.let {
                LyricsLine(index * lineDurationMs, it, endMs = (index + 1) * lineDurationMs)
            }
        }.toList()

    private fun parseEnhancedText(raw: String, baseMs: Long): Pair<List<LyricsWord>, String> {
        val matches = inlineStamp.findAll(raw).toList()
        if (matches.isEmpty()) return emptyList<LyricsWord>() to cleanVocalText(raw)
        val words = mutableListOf<LyricsWord>()
        val visible = StringBuilder()
        var cursor = 0
        var currentStart = baseMs
        val chunks = mutableListOf<Triple<Long, Int, String>>()
        matches.forEach { stamp ->
            val segment = raw.substring(cursor, stamp.range.first)
            if (segment.isNotEmpty()) {
                val start = visible.length
                visible.append(segment)
                chunks += Triple(currentStart, start, segment)
            }
            currentStart = toMs(stamp)
            cursor = stamp.range.last + 1
        }
        val tail = raw.substring(cursor)
        if (tail.isNotEmpty()) {
            val start = visible.length
            visible.append(tail)
            chunks += Triple(currentStart, start, tail)
        }
        val clean = cleanVocalText(visible.toString())
        chunks.forEachIndexed { index, chunk ->
            val next = chunks.getOrNull(index + 1)?.first ?: (chunk.first + 500L)
            val rawWords = chunk.third.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            var charCursor = chunk.second
            rawWords.forEach { token ->
                val tokenIndex = visible.indexOf(token, charCursor).takeIf { it >= 0 } ?: charCursor
                words += LyricsWord(chunk.first, token, next, emptyList())
                charCursor = tokenIndex + token.length
            }
        }
        return words to clean
    }

    private fun extractBackground(raw: String, timeMs: Long): LyricsLine? {
        val bracket = Regex("""\(([^()]+)\)""").find(raw) ?: return null
        val text = bracket.groupValues[1].trim()
        if (text.isBlank()) return null
        return LyricsLine(timeMs, text, words = listOf(LyricsWord(timeMs, text, timeMs + 700L)), endMs = timeMs + 700L)
    }

    private fun cleanVocalText(raw: String): String =
        raw.replace(Regex("""\([^()]*\)"""), "").replace(Regex("""<[^>]*>"""), "")
            .replace(Regex("""\s+"""), " ").trim()

    private fun toMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues.getOrNull(3).orEmpty().padEnd(3, '0').take(3)
        return (minutes * 60 + seconds) * 1000 + fraction.toLongOrNull().orZero()
    }

    private fun Long?.orZero() = this ?: 0L
}
