package app.nwmovie.data.telegram

/** Works out "what is this file?" (movie, TV episode, part of a multi-part movie) from file names and captions. */
object FilenameParser {
    data class Parsed(val title: String, val year: Int, val season: Int = 0, val episode: Int = 0, val part: Int = 0) {
        val isSeries: Boolean get() = season > 0 || episode > 0
    }

    private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "webm", "m4v", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
    private val SXXEYY = Regex("(?i)\\bS(\\d{1,2})[ ._-]?E(\\d{1,3})(?:[ ._-]?E\\d{1,3})*\\b")
    private val NXNN = Regex("(?i)\\b(\\d{1,2})x(\\d{2,3})\\b")
    private val SEASON_EP = Regex("(?i)\\bseason[ ._-]?(\\d{1,2})[ ._-]+(?:episode|ep)[ ._-]?(\\d{1,3})\\b")
    private val PART = Regex("(?i)(?<![a-z0-9])(cd|disc|disk|part|pt)[ ._-]?(\\d)(?![0-9a-z])")
    private val DISC_ONLY = Regex("(?i)(?<![a-z0-9])(cd|disc|disk)[ ._-]?(\\d)(?![0-9a-z])")
    private val YEAR = Regex("(?<![0-9])(19[2-9][0-9]|20[0-4][0-9])(?![0-9])")
    private val JUNK = Regex(
        "(?i)\\b(2160p|1080p|720p|576p|480p|4k|uhd|bluray|blu-ray|brrip|bdrip|webrip|web-dl|webdl|web|hdrip|hdtv|dvdrip|dvdscr|" +
            "x264|x265|h264|h265|hevc|avc|10bit|8bit|aac|ac3|eac3|dts|ddp?5?|atmos|hdr10?|hdr|dv|remux|proper|extended|unrated|" +
            "repack|yify|yts|rarbg|dual|multi|esubs?|subs?|hindi|tamil|telugu|malayalam|english|dubbed|hdcam|camrip|hdts|hdtc|predvd|" +
            "amzn|nf|dsnp|zee5|hotstar|sonyliv|atvp|hmax|tamilmv|tamilblasters|tamilrockers|mkvcinemas|hdhub4u|vegamovies|filmyzilla|" +
            "bolly4u|katmoviehd|moviesmod|mlwbd|jiorockers)\\b.*"
    )

    // Site or channel names stuck in front of the real title.
    private val WWW_PREFIX = Regex("(?i)^\\s*(?:https?://)?www\\.[a-z0-9-]+\\.[a-z]{2,6}\\b[\\s._-]*")
    private val DIGIT_SITE_PREFIX = Regex("(?i)^\\s*[a-z0-9-]*\\d[a-z0-9-]*\\.(?:com|net|org|xyz|cc|ws|lol|club|site|vip|link|ink|io)\\b[\\s._-]*")
    private val SITE_DASH_PREFIX = Regex("(?i)^\\s*[a-z0-9-]+\\.(?:com|net|org|xyz|cc|ws|co|in|me|tv)\\s+[-–]\\s+")
    private val HANDLE_SPACED = Regex("^\\s*@[A-Za-z0-9_]+(?:\\s+[-–:|]\\s*|\\s+)")
    private val TOKEN = Regex("[^_\\s.\\-]+")

    // Channel tags such as "@Trending_Tamizhaas_" in front of file names. A tag can contain underscores itself, so it is
    // learned from the library: the shortest start shared by several different files that then continue differently.
    @Volatile private var handles: List<List<String>> = emptyList()

    fun learnHandles(names: Collection<String>) {
        val stems = names.map { stripExt(it).trim() }.filter { it.startsWith("@") }.distinct()
            .map { s -> TOKEN.findAll(s).map { it.value.lowercase() }.take(7).toList() }.filter { it.size >= 2 }
        val count = HashMap<String, Int>()
        val next = HashMap<String, MutableSet<String>>()
        for (t in stems) for (k in 1..minOf(5, t.size - 1)) {
            val key = t.take(k).joinToString("_")
            count[key] = (count[key] ?: 0) + 1
            next.getOrPut(key) { mutableSetOf() }.add(t[k])
        }
        val found = HashSet<List<String>>()
        for (t in stems) for (k in 1..minOf(5, t.size - 1)) {
            val key = t.take(k).joinToString("_")
            if ((count[key] ?: 0) >= 3 && (next[key]?.size ?: 0) >= 3) { found += t.take(k); break }
        }
        handles = found.sortedByDescending { it.size }
    }

    private fun stripHandle(raw: String): String {
        val t = raw.trimStart()
        if (!t.startsWith("@")) return raw
        val toks = TOKEN.findAll(t).take(6).toList()
        for (h in handles) {
            if (toks.size > h.size && h.indices.all { toks[it].value.equals(h[it], ignoreCase = true) })
                return t.substring(toks[h.size - 1].range.last + 1).trimStart('_', ' ', '.', '-')
        }
        return t.replace(HANDLE_SPACED, "")
    }
    private val LEADING_BRACKETS = Regex("^\\s*(?:\\[[^\\]]*\\]|\\{[^}]*\\})[\\s._-]*")
    private val TAG_WORD = Regex("^[A-Z]{2,5}[\\s_-]+(?=\\S)")
    private val TAG_GLUED = Regex("^[A-Z]{1,4}(?=[A-Z][a-z]{2,})")

    private fun stripPrefixes(raw: String): String {
        var t = raw
        repeat(4) {
            val before = t
            t = t.replace(WWW_PREFIX, "").replace(DIGIT_SITE_PREFIX, "").replace(SITE_DASH_PREFIX, "")
                .let { stripHandle(it) }.replace(LEADING_BRACKETS, "")
            if (t == before) return t
        }
        return t
    }

    /** The title as parsed, then a few cleaned-up guesses (leading tag like "TM", first word dropped…) to try on TMDB one by one. */
    fun titleVariants(title: String): List<String> {
        val t = title.trim()
        val words = t.split(' ').filter { it.isNotBlank() }
        val out = linkedSetOf<String>()
        if (t.startsWith("@")) {
            // unknown channel tag: try the title with 1, 2, 3 or 4 leading words removed
            for (k in 1..4) if (words.size > k) out += words.drop(k).joinToString(" ")
            out += t.trimStart('@')
        } else {
            out += t
            out += t.replace(TAG_WORD, "").trim()
            out += t.replace(TAG_GLUED, "").trim()
            if (words.size >= 3) out += words.dropLast(1).joinToString(" ")
            if (words.size >= 4) out += words.dropLast(2).joinToString(" ")
            if (words.size >= 3 && words[0].length <= 6) out += words.drop(1).joinToString(" ")
            if (words.size >= 4 && words[0].length <= 6 && words[1].length <= 6) out += words.drop(2).joinToString(" ")
        }
        return out.filter { it.length >= 2 }
    }

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    /** season, episode, range of the marker in the text (or null when it is not an episode). */
    private fun episodeOf(text: String): Triple<Int, Int, IntRange>? {
        SXXEYY.find(text)?.let { return Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range) }
        SEASON_EP.find(text)?.let { return Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range) }
        NXNN.find(text)?.let { return Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range) }
        return null
    }

    /** Movies and TV episodes only: long videos or big video files. Short clips, memes, PDFs, APKs… are ignored. */
    fun looksLikeMovie(r: RawItem): Boolean {
        val isVideoDoc = extension(r.name) in VIDEO_EXT || r.mime.startsWith("video/")
        if (!isVideoDoc) return false
        val ep = episodeOf(stripExt(r.name)) != null
        val mb = 1024L * 1024
        if (r.durationSec > 0) return r.durationSec >= (if (ep) 10 * 60 else 40 * 60) || r.size >= (if (ep) 120 * mb else 700 * mb)
        return r.size >= (if (ep) 60 * mb else 350 * mb)
    }

    fun parse(fileName: String, caption: String): Parsed? {
        val base = stripExt(fileName)
        val ep = episodeOf(base)
        if (ep != null) {
            val p = fromText(base.substring(0, ep.third.first))
                ?: caption.lineSequence().firstOrNull { it.isNotBlank() }?.let { line -> episodeOf(line)?.let { e -> fromText(line.substring(0, e.third.first)) } }
                ?: return null
            return Parsed(p.title, p.year, ep.first, ep.second, 0)
        }
        val part = PART.find(base)?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val cleaned = base.replace(DISC_ONLY, " ")          // explicit CD1/Disc2 markers do not belong in the title
        fromText(cleaned)?.let { return Parsed(it.title, it.year, 0, 0, part) }
        val line = caption.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isNotEmpty() && episodeOf(line) == null) fromText(line)?.let { return Parsed(it.title, it.year, 0, 0, part) }
        val fallback = cleaned.replace(Regex("[._]+"), " ").trim()
        return if (fallback.isBlank()) null else Parsed(fallback, 0, 0, 0, part)
    }

    private fun stripExt(n: String): String {
        val ext = extension(n)
        return if (ext in VIDEO_EXT) n.substringBeforeLast('.') else n
    }

    private fun fromText(raw: String): Parsed? {
        var t = stripPrefixes(raw).replace(Regex("[._]+"), " ").replace(Regex("[\\[\\](){}]"), " ")
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
