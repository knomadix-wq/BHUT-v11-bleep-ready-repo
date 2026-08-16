package dev.brahmkshatriya.echo.extensions.builtin.spotifydeezer

import dev.brahmkshatriya.echo.common.models.Track
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.abs

/** Metadata-only Spotify -> Deezer recording matcher. */
internal object DeezerCatalogueMatcher {
    fun resolve(track: Track, albumPosition: Long? = track.albumOrderNumber): Long? {
        val sourceIsrc = track.isrc?.trim()?.takeIf { it.isNotBlank() }
        if (sourceIsrc != null) {
            resolveByIsrc(sourceIsrc)?.let { candidate ->
                if (score(track, candidate) >= 85) return candidate.id
            }
        }

        val direct = search(track)
            .maxByOrNull { score(track, it) }
            ?.takeIf { candidate -> score(track, candidate) >= 70 }
            ?.id
        if (direct != null) return direct

        return searchAlbumTracks(track)
            .maxByOrNull { score(track, it, albumPosition) }
            ?.takeIf { candidate -> score(track, candidate, albumPosition) >= 55 }
            ?.id
    }

    private fun resolveByIsrc(isrc: String): Candidate? =
        getJson("https://api.deezer.com/track/isrc:${isrc.trim().uppercase()}")
            ?.takeUnless { it.has("error") }
            ?.let(::parse)

    private fun search(track: Track): List<Candidate> {
        val artists = track.artists.map { it.name.trim() }.filter { it.isNotBlank() }
        val titles = titleVariants(track.title)
        val queries = linkedSetOf<String>().apply {
            for (title in titles) {
                for (artist in artists) {
                    add("artist:\"$artist\" track:\"$title\"")
                    add("$artist $title")
                    add("$title $artist")
                }
                add(title)
            }
        }

        val results = linkedMapOf<Long, Candidate>()
        for (query in queries) {
            val q = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val json = getJson("https://api.deezer.com/search?q=$q&limit=10") ?: continue
            val data = json.optJSONArray("data") ?: continue
            for (i in 0 until data.length()) {
                parse(data.optJSONObject(i) ?: continue)?.let { results[it.id] = it }
            }
        }
        return results.values.toList()
    }

    private fun searchAlbumTracks(track: Track): List<Candidate> {
        val album = track.album?.title?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val q = URLEncoder.encode("$album $artist", StandardCharsets.UTF_8.toString())
        val albums = getJson("https://api.deezer.com/search/album?q=$q&limit=8")
            ?.optJSONArray("data") ?: return emptyList()
        val results = linkedMapOf<Long, Candidate>()
        for (i in 0 until albums.length()) {
            val albumId = albums.optJSONObject(i)?.optLong("id", -1L) ?: continue
            if (albumId <= 0) continue
            val tracks = getJson("https://api.deezer.com/album/$albumId/tracks?limit=500")
                ?.optJSONArray("data") ?: continue
            for (index in 0 until tracks.length()) {
                parse(tracks.optJSONObject(index) ?: continue, (index + 1).toLong())
                    ?.let { results[it.id] = it }
            }
        }
        return results.values.toList()
    }

    private fun parse(json: JSONObject, position: Long? = null): Candidate? {
        val id = json.optLong("id", -1L)
        if (id <= 0) return null
        return Candidate(
            id = id,
            title = json.optString("title"),
            artist = json.optJSONObject("artist")?.optString("name").orEmpty(),
            durationMs = json.optLong("duration", 0L) * 1000L,
            isrc = json.optString("isrc").takeIf { it.isNotBlank() },
            albumPosition = position ?: json.optLong("track_position", 0L).takeIf { it > 0 },
        )
    }

    private fun score(source: Track, candidate: Candidate, expectedPosition: Long? = null): Int {
        var result = 0
        val sourceIsrc = source.isrc?.trim()?.uppercase()
        val candidateIsrc = candidate.isrc?.trim()?.uppercase()
        if (!sourceIsrc.isNullOrBlank() && !candidateIsrc.isNullOrBlank() && sourceIsrc == candidateIsrc) {
            result += 70
        }

        val sourceTitles = titleVariants(source.title).map(::norm).filter { it.isNotBlank() }
        val candidateTitle = norm(candidate.title)
        if (candidateTitle.isNotBlank()) {
            when {
                sourceTitles.any { title -> title == candidateTitle } -> result += 20
                sourceTitles.any { title -> title.contains(candidateTitle) || candidateTitle.contains(title) } -> result += 10
            }
        }

        val sourceArtists = source.artists.map { norm(it.name) }.filter { it.isNotBlank() }
        val candidateArtist = norm(candidate.artist)
        if (candidateArtist.isNotBlank()) {
            when {
                sourceArtists.any { artist -> artist == candidateArtist } -> result += 15
                sourceArtists.any { artist -> artist.contains(candidateArtist) || candidateArtist.contains(artist) } -> result += 8
            }
        }

        val sourceDuration = source.duration
        if (sourceDuration != null && candidate.durationMs > 0) {
            val delta = abs(sourceDuration - candidate.durationMs)
            when {
                delta <= 2_500 -> result += 10
                delta <= 5_000 -> result += 5
                delta > 15_000 -> result -= 20
            }
        }
        if (expectedPosition != null && candidate.albumPosition == expectedPosition) result += 15
        return result
    }

    private fun titleVariants(title: String): List<String> = linkedSetOf<String>().apply {
        val clean = title.trim()
        if (clean.isNotBlank()) add(clean)
        clean.substringBefore(" - ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.substringBefore(" – ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.substringBefore(" — ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.replace(
            Regex("""\s*[-–—]\s*(reprise|remix|edit|version|remaster(?:ed)?)\b.*$""", RegexOption.IGNORE_CASE),
            "",
        ).trim().takeIf { it.isNotBlank() }?.let(::add)
    }.toList()

    private fun norm(value: String): String = value.lowercase()
        .replace(
            Regex("""\([^)]*(remaster|remastered|live|edit|version)[^)]*\)""", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(
            Regex("""\[[^]]*(remaster|remastered|live|edit|version)[^]]*]""", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun getJson(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private data class Candidate(
        val id: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val isrc: String?,
        val albumPosition: Long?,
    )
}
