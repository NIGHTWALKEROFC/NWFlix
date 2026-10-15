package app.nwmovie.data.telegram

/** Works out "is this a movie, and what is it called?" from file names and captions. */
object FilenameParser {
    data class Parsed(val title: String, val year: Int)

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
    private val EPISODE = Regex("(?i)\\bS\\d{1,2}[ ._-]?E\\d{1,3}\\b|\\b\\d{1,2}x\\d{2}\\b|\\bepisode[ ._-]?\\d+\\b")
    private val YEAR = Regex("(?<![0-9])(19[2-9][0-9]|20[0-4][0-9])(?![0-9])")
    private val JUNK = Regex(
        "(?i)\\b(2160p|1080p|720p|576p|480p|4k|uhd|bluray|blu-ray|brrip|bdrip|webrip|web-dl|webdl|web|hdrip|hdtv|dvdrip|dvdscr|" +
            "x264|x265|h264|h265|hevc|avc|10bit|8bit|aac|ac3|eac3|dts|ddp?5?|atmos|hdr10?|hdr|dv|remux|proper|extended|unrated|" +
            "repack|yify|yts|rarbg|dual|multi|esubs?|subs?|hindi|tamil|telugu|malayalam|english|dubbed)\\b.*"
    )

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    /** Movies only: long videos, or big files that clearly are video. Short clips, memes, PDFs, APKs… are ignored. */
    fun looksLikeMovie(r: RawItem): Boolean {
        val isVideoDoc = extension(r.name) in VIDEO_EXT || r.mime.startsWith("video/")
        if (!isVideoDoc) return false
        if (r.durationSec > 0) return r.durationSec >= 40 * 60 || r.size >= 700L * 1024 * 1024
        return r.size >= 350L * 1024 * 1024
    }

    fun parse(fileName: String, caption: String): Parsed? {
        if (EPISODE.containsMatchIn(fileName)) return null // TV episodes are not movies
        fromText(stripExt(fileName))?.let { return it }
        val line = caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isNotEmpty() && !EPISODE.containsMatchIn(line)) fromText(line)?.let { return it }
        val fallback = stripExt(fileName).replace(Regex("[._]+"), " ").trim()
        return if (fallback.isBlank()) null else Parsed(fallback, 0)
    }

    private fun stripExt(n: String): String {
        val ext = extension(n)
        return if (ext in VIDEO_EXT) n.substringBeforeLast('.') else n
    }

    private fun fromText(raw: String): Parsed? {
        var t = raw.replace(Regex("^\\[[^\\]]*\\]\\s*"), "").replace(Regex("[._]+"), " ").replace(Regex("[\\[\\](){}]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isBlank()) return null
        val ym = YEAR.findAll(t).firstOrNull { it.range.first > 0 }
        return if (ym != null) {
            val title = t.substring(0, ym.range.first).trim(' ', '-', '(', '[')
            if (title.isBlank()) null else Parsed(title, ym.value.toInt())
        } else {
            val title = JUNK.replace(t, "").trim(' ', '-')
            if (title.length < 2) null else Parsed(title, 0)
        }
    }
}
