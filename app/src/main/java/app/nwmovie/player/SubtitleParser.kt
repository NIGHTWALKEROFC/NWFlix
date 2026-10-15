package app.nwmovie.player

data class Sub(val start: Long, val end: Long, val text: String)

/** Loads external .srt / .vtt / .ass subtitle files so we can show them with an adjustable delay. */
object SubtitleParser {
    private val TIME = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[.,](\\d{1,3})")
    private val TAGS = Regex("<[^>]+>|\\{[^}]*\\}")

    fun parse(raw: String): List<Sub> {
        val t = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val list = when {
            t.contains("-->") -> cues(t)
            t.contains("Dialogue:") -> ass(t)
            else -> emptyList()
        }
        return list.sortedBy { it.start }
    }

    private fun ms(h: String?, m: String, s: String, f: String): Long =
        (h?.toLongOrNull() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()

    private fun cues(t: String): List<Sub> = t.split(Regex("\n\\s*\n")).mapNotNull { block ->
        val lines = block.lines()
        val idx = lines.indexOfFirst { TIME.containsMatchIn(it) }
        if (idx < 0) return@mapNotNull null
        val m = TIME.find(lines[idx])!!.groupValues
        val text = lines.drop(idx + 1).joinToString("\n") { TAGS.replace(it, "") }.trim()
        if (text.isEmpty()) null else Sub(ms(m[1].ifEmpty { null }, m[2], m[3], m[4]), ms(m[5].ifEmpty { null }, m[6], m[7], m[8]), text)
    }

    private fun assTime(s: String): Long {
        val p = s.trim().split(":", ".")
        if (p.size < 4) return 0
        return p[0].toLong() * 3_600_000 + p[1].toLong() * 60_000 + p[2].toLong() * 1000 + p[3].padEnd(2, '0').take(2).toLong() * 10
    }

    private fun ass(t: String): List<Sub> = t.lineSequence().filter { it.startsWith("Dialogue:") }.mapNotNull { line ->
        val p = line.removePrefix("Dialogue:").split(",", limit = 10)
        if (p.size < 10) return@mapNotNull null
        val text = TAGS.replace(p[9], "").replace("\\N", "\n").replace("\\n", "\n").trim()
        try { if (text.isEmpty()) null else Sub(assTime(p[1]), assTime(p[2]), text) } catch (e: Exception) { null }
    }.toList()

    /** Decodes bytes as UTF-8, falling back to Windows-1252 for old subtitle files. */
    fun decode(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        return if (utf.contains('\uFFFD')) String(bytes, charset("windows-1252")) else utf
    }
}
