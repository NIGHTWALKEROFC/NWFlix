package app.nwmovie.data

import app.nwmovie.data.db.Movie

private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

/** Files of the same movie share this key (same TMDB id, or same cleaned title + year when not matched). */
fun Movie.groupKey(): String = if (tmdbId > 0) "t$tmdbId" else "n${norm(parsedTitle.ifBlank { fileName })}_$parsedYear"

/** One movie that may exist as several Telegram files (1080p, 720p, different languages…). */
class MovieGroup(val files: List<Movie>) {
    private val rep: Movie = files.filter { it.lastPlayedAt > 0 }.maxByOrNull { it.lastPlayedAt }
        ?: files.maxByOrNull { it.size } ?: files.first()

    val id: String get() = rep.id
    val fileCount: Int get() = files.size

    /** What posters/lists show: the movie's details with lists/history merged across all its files. */
    val view: Movie = rep.copy(
        watchlist = files.any { it.watchlist }, favorite = files.any { it.favorite },
        lastPlayedAt = files.maxOf { it.lastPlayedAt }, addedAt = files.maxOf { it.addedAt }
    )

    /** A file that was started but not finished. */
    val resumeFile: Movie? = files.filter { it.lastPlayedAt > 0 && !it.watched && it.positionMs > 5000 }.maxByOrNull { it.lastPlayedAt }

    /** What the big Play button plays: the unfinished file, otherwise the largest (best quality) one. */
    val playFile: Movie = resumeFile ?: files.maxByOrNull { it.size } ?: files.first()

    /** Used by Data saver: the smallest file uses the least data. */
    val smallestFile: Movie = files.minByOrNull { if (it.size > 0) it.size else Long.MAX_VALUE } ?: files.first()
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

/** Quality / language / audio tags read from a file's name and caption. Empty list = nothing recognised. */
fun fileTags(m: Movie): List<String> {
    val raw = (m.fileName + " " + m.caption).lowercase()
    val t = raw.replace(Regex("[._\\[\\](){}+,|]"), " ")
    fun has(r: String) = Regex(r).containsMatchIn(t)
    val out = linkedSetOf<String>()
    when {
        has("\\b(2160p|4k|uhd)\\b") -> out += "4K"
        has("\\b1440p\\b") -> out += "1440p"
        has("\\b1080p\\b") -> out += "1080p"
        has("\\b720p\\b") -> out += "720p"
        has("\\b576p\\b") -> out += "576p"
        has("\\b480p\\b") -> out += "480p"
        has("\\b360p\\b") -> out += "360p"
    }
    when {
        has("\\b(hd ?cam|cam ?rip|cam|telesync|hdts)\\b") -> out += "CAM"
        has("\\b(blu ?ray|bdrip|brrip|bd ?rip)\\b") -> out += "BluRay"
        has("\\bweb ?-?dl\\b") -> out += "WEB-DL"
        has("\\bweb ?-?rip\\b") -> out += "WEBRip"
        has("\\bhd ?rip\\b") -> out += "HDRip"
        has("\\bdvd ?rip\\b") -> out += "DVDRip"
        has("\\bhdtv\\b") -> out += "HDTV"
    }
    when {
        has("\\b(x265|hevc|h ?265)\\b") -> out += "HEVC"
        has("\\b(x264|h ?264|avc)\\b") -> out += "x264"
        has("\\bav1\\b") -> out += "AV1"
    }
    if (has("\\b10 ?bit\\b")) out += "10-bit"
    if (has("\\b(hdr10 ?|hdr)\\b")) out += "HDR"
    if (has("\\b(dolby vision|dv)\\b")) out += "Dolby Vision"
    if (has("\\batmos\\b")) out += "Atmos"
    else if (has("\\b(ddp|eac3|e ac3)\\b")) out += "DD+"
    else if (has("\\bdts\\b")) out += "DTS"
    else if (has("\\b(ac3|dd)\\b")) out += "DD"
    else if (has("\\baac\\b")) out += "AAC"
    if (Regex("(?<!\\d)5[. ]1(?!\\d)").containsMatchIn(raw)) out += "5.1"
    if (has("\\b(dual audio|dual)\\b")) out += "Dual Audio" else if (has("\\bmulti\\b")) out += "Multi Audio"
    listOf("hindi" to "Hindi", "english" to "English", "tamil" to "Tamil", "telugu" to "Telugu", "malayalam" to "Malayalam",
        "kannada" to "Kannada", "bengali" to "Bengali", "punjabi" to "Punjabi", "marathi" to "Marathi", "korean" to "Korean",
        "japanese" to "Japanese", "spanish" to "Spanish", "french" to "French", "german" to "German", "arabic" to "Arabic")
        .forEach { (w, label) -> if (has("\\b$w\\b")) out += label }
    if (has("\\b(esubs?|subs?|subtitles?)\\b")) out += "Subs"
    return out.toList()
}
