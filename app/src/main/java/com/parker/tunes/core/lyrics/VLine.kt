package com.parker.tunes.core.lyrics

import java.util.Locale

data class LyricsSyllable(
    val startMs: Long,
    val endMs: Long,
    val startChar: Int,
    val endChar: Int
)

data class LyricsWord(
    val startMs: Long,
    val text: String,
    val endMs: Long? = null,
    val syllables: List<LyricsSyllable> = emptyList()
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricsWord> = emptyList(),
    val background: LyricsLine? = null,
    val opposite: Boolean = false,
    val endMs: Long? = null
)

data class VWord(val text: String, val startMs: Long, val endMs: Long, val syllables: List<LyricsSyllable>)

data class VLine(
    val timeMs: Long,
    val text: String,
    val origin: Int,
    val endMs: Long,
    val words: List<VWord> = emptyList(),
    val wordSpans: List<IntRange> = emptyList(),
    val sweepSpans: List<IntRange> = emptyList(),
    val isWordSynced: Boolean = false,
    val isGap: Boolean = false,
    val opposite: Boolean = false,
    val background: VLine? = null,
    val growingWords: List<Int> = emptyList(),
    private val syllableRanges: List<Pair<IntRange, Pair<Long, Long>>> = emptyList(),
    private val lineStartMs: Long = timeMs
) {
    fun revealedChars(positionMs: Long): Float {
        if (text.isEmpty() || isGap) return 0f
        if (positionMs <= timeMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()
        if (syllableRanges.isNotEmpty()) {
            val syllable = syllableRanges.firstOrNull { positionMs in it.second.first..it.second.second }
            if (syllable != null) {
                val (range, timing) = syllable
                val fraction = ((positionMs - timing.first).toFloat() /
                    (timing.second - timing.first).coerceAtLeast(1L)).coerceIn(0f, 1f)
                return range.first + (range.last - range.first + 1) * fraction
            }
        }
        if (words.isNotEmpty()) {
            for (index in words.indices) {
                val word = words[index]
                if (positionMs < word.startMs) {
                    val previous = words.getOrNull(index - 1) ?: return 0f
                    val previousEnd = previous.endMs
                    val gapStart = previousEnd
                    if (positionMs <= gapStart) return previous.endMsChar(wordSpans.getOrNull(index - 1))
                    val nextStart = word.startMs
                    val span = wordSpans.getOrNull(index - 1) ?: return 0f
                    val nextSpan = wordSpans.getOrNull(index) ?: return span.last + 1f
                    val from = span.last + 1f
                    val to = nextSpan.first.toFloat()
                    val fraction = ((positionMs - gapStart).toFloat() /
                        (nextStart - gapStart).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    return from + (to - from) * fraction
                }
                if (positionMs <= word.endMs) {
                    val span = wordSpans.getOrNull(index) ?: continue
                    val fraction = ((positionMs - word.startMs).toFloat() /
                        (word.endMs - word.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    return span.first + (span.last - span.first + 1) * fraction
                }
            }
            return text.length.toFloat()
        }
        val fraction = ((positionMs - timeMs).toFloat() /
            (endMs - timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
        return text.length * fraction
    }

    private fun VWord.endMsChar(span: IntRange?): Float = (span?.last?.plus(1) ?: 0).toFloat()

    companion object {
        fun of(lines: List<LyricsLine>): List<VLine> {
            if (lines.isEmpty()) return emptyList()
            val sorted = lines.withIndex().sortedBy { it.value.timeMs }
            val result = mutableListOf<VLine>()
            sorted.forEachIndexed { sortedIndex, indexed ->
                val line = indexed.value
                val origin = indexed.index
                val next = sorted.getOrNull(sortedIndex + 1)?.value
                val clean = line.text.trim()
                if (clean.isEmpty()) {
                    val nextSung = sorted.drop(sortedIndex + 1).firstOrNull { it.value.text.isNotBlank() }?.value
                    if (nextSung != null && nextSung.timeMs - line.timeMs >= GAP_THRESHOLD_MS) {
                        result += VLine(line.timeMs, "", origin, nextSung.timeMs, isGap = false)
                    }
                    return@forEachIndexed
                }
                val words = line.words.map { word ->
                    val cleanWord = word.text
                    VWord(cleanWord, word.startMs, word.endMs ?: next?.timeMs?.takeIf { it > word.startMs }
                        ?: line.endMs ?: (word.startMs + 350L), word.syllables)
                }
                val spans = mutableListOf<IntRange>()
                var cursor = 0
                words.forEach { word ->
                    val index = if (clean.contains(word.text, ignoreCase = false))
                        clean.indexOf(word.text, cursor).takeIf { it >= 0 } else null
                    val start = index ?: cursor.coerceAtMost(clean.length)
                    val end = (start + word.text.length).coerceAtMost(clean.length)
                    spans += start until end
                    cursor = end
                    if (cursor < clean.length && clean[cursor].isWhitespace()) cursor++
                }
                val inferredEnd = line.endMs ?: words.maxOfOrNull { it.endMs }
                    ?: next?.timeMs?.takeIf { it > line.timeMs } ?: (line.timeMs + 3_000L)
                val lineEnd = if (line.opposite) maxOf(inferredEnd, line.timeMs + 3_000L) else inferredEnd
                val syllables = words.flatMapIndexed { index, word ->
                    val base = spans.getOrNull(index)?.first ?: 0
                    word.syllables.map { syllable ->
                        (base + syllable.startChar until base + syllable.endChar) to
                            (syllable.startMs to syllable.endMs)
                    }
                }
                val bg = line.background?.let { backing ->
                    val stripped = backing.text.removeSurrounding("(", ")").trim()
                    val offset = if (backing.text.startsWith("(")) 1 else 0
                    val adjusted = backing.words.map { w ->
                        w.copy(text = w.text.removePrefix("(").removeSuffix(")"),
                            syllables = w.syllables.map { it.copy(
                                startChar = (it.startChar - offset).coerceAtLeast(0),
                                endChar = (it.endChar - offset).coerceAtLeast(0)
                            ) })
                    }
                    of(listOf(backing.copy(text = stripped, words = adjusted))).firstOrNull()
                }
                result += VLine(
                    timeMs = line.timeMs, text = clean, origin = origin,
                    endMs = maxOf(lineEnd, bg?.endMs ?: line.timeMs, line.timeMs + 1),
                    words = words, wordSpans = spans, sweepSpans = syllables.map { it.first },
                    isWordSynced = words.isNotEmpty(), opposite = line.opposite, background = bg,
                    growingWords = words.mapIndexedNotNull { i, word ->
                        i.takeIf { word.endMs - word.startMs >= GROW_THRESHOLD_MS &&
                            !containsBlockScript(word.text) }
                    }, syllableRanges = syllables, lineStartMs = line.timeMs
                )
                // An explicit end time lets us represent a genuine vocal pause.
                val knownEnd = line.endMs ?: words.maxOfOrNull { it.endMs }
                if (knownEnd != null && next != null && next.timeMs - knownEnd >= GAP_THRESHOLD_MS) {
                    result += gap(knownEnd, -1)
                } else if (sortedIndex == 0 && line.timeMs >= GAP_THRESHOLD_MS) {
                    result.add(0, gap(0L, -1))
                }
            }
            return result
        }

        fun gloss(text: String, source: VLine): VLine {
            val sourceLength = source.text.length.coerceAtLeast(1)
            val spans = if (text.isEmpty()) emptyList() else listOf(0 until text.length)
            return VLine(source.timeMs, text, source.origin, source.endMs,
                words = if (text.isNotEmpty()) listOf(VWord(text, source.timeMs, source.endMs, emptyList())) else emptyList(),
                wordSpans = spans, isWordSynced = source.isWordSynced,
                growingWords = emptyList(), lineStartMs = source.timeMs)
        }

        private fun gap(time: Long, origin: Int) = VLine(
            timeMs = time, text = "", origin = origin, endMs = time, isGap = true
        )

        private fun containsBlockScript(value: String): Boolean =
            value.any { it.code in 0x2E80..0x9FFF || it.code in 0xAC00..0xD7AF }

        private const val GAP_THRESHOLD_MS = 4_000L
        private const val GROW_THRESHOLD_MS = 1_200L
    }
}

fun activeRows(rows: List<VLine>, positionMs: Long): List<Int> =
    rows.indices.filter { index ->
        val row = rows[index]
        !row.isGap && positionMs >= row.timeMs && positionMs <= row.endMs
    }
