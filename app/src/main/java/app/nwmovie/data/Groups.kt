package app.nwmovie.data

import app.nwmovie.data.db.Movie

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")
private val PART_RE = Regex("(?i)(?<![a-z0-9])(cd|disc|disk|part|pt)[ ._-]?(\\d)(?![0-9a-z])")

/** Files of the same movie / series share this key (same TMDB id, or same cleaned title (+ year for movies) when not matched). */
fun Movie.groupKey(): String = when {
    tmdbId > 0 -> if (isSeries) "s$tmdbId" else "t$tmdbId"
    isSeries -> "ns${norm(parsedTitle.ifBlank { fileName })}"
    else -> "n${norm(parsedTitle.ifBlank { fileName })}_$parsedYear"
}

/** Parts of one multi-part movie (CD1/CD2, Part 1/Part 2) share this key. */
fun partKey(f: Movie): String? = if (f.part > 0) norm(f.fileName.replace(PART_RE, " ")) else null

val RES_ORDER = listOf("4K", "1440p", "1080p", "720p", "576p", "480p", "360p", "Other")
val LANGUAGE_TAGS = setOf("Hindi", "English", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Punjabi", "Marathi",
    "Korean", "Japanese", "Spanish", "French", "German", "Arabic")

fun qualityOf(m: Movie): String = fileTags(m).firstOrNull { it in RES_ORDER } ?: "Other"

/** One playable thing: a single file, or all parts of a multi-part movie. */
class FileEntry(val files: List<Movie>) {
    val lead: Movie get() = files.first()
    val size: Long get() = files.sumOf { it.size }
    val isMulti: Boolean get() = files.size > 1
}

class QualityInfo(val label: String, val files: List<Movie>) {
    val episodes: Int get() = files.map { it.season to it.episode }.distinct().size
    val size: Long get() = files.sumOf { it.size }
}

/** The same file forwarded into several chats has the same size, length and episode: keep one copy of it. */
private fun dropCopies(all: List<Movie>): List<Movie> {
    if (all.size < 2) return all
    fun used(m: Movie) = (if (m.lastPlayedAt > 0) 4 else 0) + (if (m.watched) 2 else 0) + (if (m.watchlist || m.favorite) 1 else 0)
    return all.groupBy { "${it.size}|${it.durationSec}|${it.season}|${it.episode}|${it.part}" }.values.flatMap { same ->
        if (same.size == 1 || same.first().size <= 0) same
        else listOf(same.sortedWith(compareByDescending<Movie> { used(it) }.thenBy { it.addedAt }).first())
    }
}

/** One movie or series that may exist as several Telegram files (1080p, 720p, different languages, parts…). */
class MovieGroup(allFiles: List<Movie>) {
    val files: List<Movie> = dropCopies(allFiles)

    private val rep: Movie = files.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }
        ?: files.maxByOrNull { it.size } ?: files.first()

    val id: String get() = rep.id
    val isSeries: Boolean = files.any { it.isSeries }
    val episodeCount: Int get() = files.map { it.season to it.episode }.distinct().size

    /** What posters/lists show: the details with lists/history merged across all files. */
    val view: Movie = rep.copy(
        watchlist = allFiles.any { it.watchlist }, favorite = allFiles.any { it.favorite },
        lastPlayedAt = allFiles.maxOf { it.lastPlayedAt }, addedAt = allFiles.maxOf { it.addedAt }
    )

    /** Files, with the parts of multi-part movies joined into one entry. */
    val entries: List<FileEntry> = run {
        val multi = files.filter { it.part > 0 }.groupBy { partKey(it) }
            .filter { (_, v) -> v.map { it.part }.distinct().size >= 2 }
        val used = multi.values.flatten().map { it.id }.toSet()
        multi.values.map { FileEntry(it.sortedBy { f -> f.part }) } + files.filter { it.id !in used }.map { FileEntry(listOf(it)) }
    }
    val fileCount: Int get() = entries.size

    val badge: String? get() = if (isSeries) "$episodeCount eps" else if (entries.size > 1) "${entries.size} files" else null

    /** A file that was started but not finished. */
    val resumeFile: Movie? = files.filter { it.lastPlayedAt > 0 && !it.watched && it.positionMs > 5000 }.maxByOrNull { it.lastPlayedAt }

    val bestFile: Movie = entries.maxByOrNull { it.size }?.lead ?: files.first()
    val smallestFile: Movie = entries.minByOrNull { if (it.size > 0) it.size else Long.MAX_VALUE }?.lead ?: files.first()
    val playFile: Movie get() = resumeFile ?: bestFile

    /** Smart choice: best quality on Wi-Fi, smallest on mobile data / Data saver. */
    fun pick(saver: Boolean, metered: Boolean, smart: Boolean): Movie =
        resumeFile ?: if (saver || (smart && metered)) smallestFile else bestFile

    /** Directors / creators and cast (name to role), read from the saved details. */
    val people: List<Pair<String, String>> = run {
        try {
            val a = org.json.JSONArray(view.cast)
            (0 until a.length()).map { val o = a.getJSONObject(it); o.optString("n") to o.optString("c") }.filter { it.first.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    /** Lower-case text used by the search box (title, file names, cast), built once. */
    val searchText: String = (view.displayTitle + " " + view.cast + " " + files.joinToString(" ") { it.fileName }).lowercase()

    val languages: Set<String> = files.flatMap { fileTags(it) }.filter { it in LANGUAGE_TAGS }.toSet()

    // ----- series -----
    val qualities: List<QualityInfo> by lazy {
        files.groupBy { qualityOf(it) }.map { QualityInfo(it.key, it.value) }
            .sortedBy { RES_ORDER.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
    }
    /** Episodes in correct order (season, then episode). q = quality, null = all. */
    fun episodesFor(q: String?): List<Movie> = files.filter { q == null || qualityOf(it) == q }
        .sortedWith(compareBy<Movie>({ it.season }, { it.episode }).thenByDescending { it.size })
}

fun List<Movie>.toGroups(): List<MovieGroup> = groupBy { it.groupKey() }.values.map { MovieGroup(it) }

enum class SortOption(val label: String, val short: String) {
    POPULAR("Most popular", "Popular"),
    RATING_HIGH("Rating: high to low", "Rating ↓"),
    RATING_LOW("Rating: low to high", "Rating ↑"),
    NEWEST("Release date: newest first", "Newest"),
    OLDEST("Release date: oldest first", "Oldest"),
    TITLE_AZ("Title: A to Z", "A–Z"),
    TITLE_ZA("Title: Z to A", "Z–A"),
    ADDED("Recently added", "Recently added"),
    LONGEST("Runtime: longest first", "Longest"),
    SHORTEST("Runtime: shortest first", "Shortest"),
    LARGEST("File size: largest first", "Largest file")
}

fun List<MovieGroup>.sortedByOption(o: SortOption): List<MovieGroup> = when (o) {
    SortOption.POPULAR -> sortedByDescending { it.view.popularity }
    SortOption.RATING_HIGH -> sortedByDescending { it.view.rating }
    SortOption.RATING_LOW -> sortedBy { if (it.view.rating > 0) it.view.rating else Double.MAX_VALUE }
    SortOption.NEWEST -> sortedByDescending { it.view.displayYear }
    SortOption.OLDEST -> sortedBy { if (it.view.displayYear > 0) it.view.displayYear else Int.MAX_VALUE }
    SortOption.TITLE_AZ -> sortedBy { it.view.displayTitle.lowercase() }
    SortOption.TITLE_ZA -> sortedByDescending { it.view.displayTitle.lowercase() }
    SortOption.ADDED -> sortedByDescending { it.view.addedAt }
    SortOption.LONGEST -> sortedByDescending { it.view.runtime }
    SortOption.SHORTEST -> sortedBy { if (it.view.runtime > 0) it.view.runtime else Int.MAX_VALUE }
    SortOption.LARGEST -> sortedByDescending { g -> g.files.maxOf { it.size } }
}

private fun rx(vararg pairs: Pair<String, String>) = pairs.map { Regex(it.first) to it.second }
private val TAG_RES = rx("\\b(2160p|4k|uhd)\\b" to "4K", "\\b1440p\\b" to "1440p", "\\b1080p\\b" to "1080p", "\\b720p\\b" to "720p",
    "\\b576p\\b" to "576p", "\\b480p\\b" to "480p", "\\b360p\\b" to "360p")
private val TAG_SRC = rx("\\b(hd ?cam|cam ?rip|cam|telesync|hdts)\\b" to "CAM", "\\b(blu ?ray|bdrip|brrip|bd ?rip)\\b" to "BluRay",
    "\\bweb ?-?dl\\b" to "WEB-DL", "\\bweb ?-?rip\\b" to "WEBRip", "\\bhd ?rip\\b" to "HDRip", "\\bdvd ?rip\\b" to "DVDRip", "\\bhdtv\\b" to "HDTV")
private val TAG_CODEC = rx("\\b(x265|hevc|h ?265)\\b" to "HEVC", "\\b(x264|h ?264|avc)\\b" to "x264", "\\bav1\\b" to "AV1")
private val TAG_AUDIO = rx("\\batmos\\b" to "Atmos", "\\b(ddp|eac3|e ac3)\\b" to "DD+", "\\bdts\\b" to "DTS", "\\b(ac3|dd)\\b" to "DD", "\\baac\\b" to "AAC")
private val TAG_LANG = listOf("hindi" to "Hindi", "english" to "English", "tamil" to "Tamil", "telugu" to "Telugu", "malayalam" to "Malayalam",
    "kannada" to "Kannada", "bengali" to "Bengali", "punjabi" to "Punjabi", "marathi" to "Marathi", "korean" to "Korean",
    "japanese" to "Japanese", "spanish" to "Spanish", "french" to "French", "german" to "German", "arabic" to "Arabic")
    .map { Regex("\\b${it.first}\\b") to it.second }
private val R_SEPARATORS = Regex("[._\\[\\](){}+,|]")
private val R_10BIT = Regex("\\b10 ?bit\\b")
private val R_HDR = Regex("\\b(hdr10 ?|hdr)\\b")
private val R_DV = Regex("\\b(dolby vision|dv)\\b")
private val R_51 = Regex("(?<!\\d)5[. ]1(?!\\d)")
private val R_DUAL = Regex("\\b(dual audio|dual)\\b")
private val R_MULTI = Regex("\\bmulti\\b")
private val R_SUBS = Regex("\\b(esubs?|subs?|subtitles?)\\b")
private val tagCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

/** Quality / language / audio tags read from a file's name and caption. Empty list = nothing recognised. Cached: cheap to call often. */
fun fileTags(m: Movie): List<String> = tagCache.getOrPut(m.id + "|" + m.fileName.hashCode() + "|" + m.caption.hashCode()) { computeTags(m) }

private fun computeTags(m: Movie): List<String> {
    val raw = (m.fileName + " " + m.caption).lowercase()
    val t = R_SEPARATORS.replace(raw, " ")
    val out = linkedSetOf<String>()
    TAG_RES.firstOrNull { it.first.containsMatchIn(t) }?.let { out += it.second }
    TAG_SRC.firstOrNull { it.first.containsMatchIn(t) }?.let { out += it.second }
    TAG_CODEC.firstOrNull { it.first.containsMatchIn(t) }?.let { out += it.second }
    if (R_10BIT.containsMatchIn(t)) out += "10-bit"
    if (R_HDR.containsMatchIn(t)) out += "HDR"
    if (R_DV.containsMatchIn(t)) out += "Dolby Vision"
    TAG_AUDIO.firstOrNull { it.first.containsMatchIn(t) }?.let { out += it.second }
    if (R_51.containsMatchIn(raw)) out += "5.1"
    if (R_DUAL.containsMatchIn(t)) out += "Dual Audio" else if (R_MULTI.containsMatchIn(t)) out += "Multi Audio"
    TAG_LANG.forEach { (r, label) -> if (r.containsMatchIn(t)) out += label }
    if (R_SUBS.containsMatchIn(t)) out += "Subs"
    return out.toList()
}
