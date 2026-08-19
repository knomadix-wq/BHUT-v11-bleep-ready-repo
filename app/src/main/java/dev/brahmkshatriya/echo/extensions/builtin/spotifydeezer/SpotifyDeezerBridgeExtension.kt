package dev.brahmkshatriya.echo.extensions.builtin.spotifydeezer

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.DeezerExtension
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs

/**
 * Gladix-native bridge:
 * Spotify extension supplies browsing/account metadata; bundled Deezer supplies audio.
 * No YouTube fallback is implemented here.
 */
class SpotifyDeezerBridgeExtension :
    ExtensionClient,
    MusicExtensionsProvider,
    HomeFeedClient,
    SearchFeedClient,
    LibraryFeedClient,
    PlaylistClient,
    AlbumClient,
    ArtistClient,
    TrackClient,
    RadioClient {

    companion object {
        const val ID = "spotify-deezer"
        val metadata = Metadata(
            className = "dev.brahmkshatriya.echo.extensions.builtin.spotifydeezer.SpotifyDeezerBridgeExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.MUSIC,
            id = ID,
            name = "Spotify → Deezer MP3",
            version = "v18",
            description = "Spotify browsing with Deezer MP3 playback, curated Bleep and Boomkat picks, and the latest NTS archives.",
            author = "BHUT",
            isEnabled = true,
        )

        /** Session cache modelled on Meld's provider-match cache pattern. */
        private val spotifyToDeezer = ConcurrentHashMap<String, String>()
        private const val DIRECT_DEEZER_ITEM = "bridge_direct_deezer"
        private const val DEEZER_RADIO = "bridge_deezer_radio"
        private const val NTS_ITEM = "bridge_nts_archive"
        private const val NTS_TOKEN = "bridge_nts_token"
        private const val NTS_LATEST_URL = "https://www.nts.live/latest"
        private const val BLEEP_FEED_URL =
            "https://raw.githubusercontent.com/knomadix-wq/BHUT-v12-bleep-ready-repo/main/data/bleep-weekly.json"
        private const val BOOMKAT_FEED_URL =
            "https://raw.githubusercontent.com/knomadix-wq/BHUT-v12-bleep-ready-repo/main/data/boomkat-weekly.json"
        private val bleepHttp = OkHttpClient()
    }

    override val requiredMusicExtensions = listOf("spotify", "deezer")
    private var extensions: List<MusicExtension> = emptyList()
    private val bleepFeedMutex = Mutex()
    private var cachedBleepReleases: List<BleepRelease>? = null
    private var cachedBoomkatReleases: List<BleepRelease>? = null
    private val ntsFeedMutex = Mutex()
    private var cachedNtsEpisodes: List<Track>? = null

    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        this.extensions = extensions
    }

    override suspend fun getSettingItems(): List<Setting> = emptyList()
    override fun setSettings(settings: Settings) = Unit

    private fun extension(id: String): MusicExtension =
        extensions.firstOrNull { it.id == id }
            ?: error("Required extension '$id' is not installed/enabled")

    private suspend inline fun <reified C> client(id: String): C {
        val instance = extension(id).instance.value().getOrThrow()
        return instance as? C
            ?: error("Extension '$id' does not implement ${C::class.simpleName}")
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val spotifyFeed = client<HomeFeedClient>("spotify").loadHomeFeed()
        return Feed(spotifyFeed.tabs) { tab ->
            val spotifyData = spotifyFeed.getPagedData(tab)
            val curatedShelf = buildCuratedShelf()
            val ntsShelf = runCatching { withTimeoutOrNull(8_000) { buildNtsShelf() } }
                .onFailure { println("BHUT NTS: ${it.message}") }
                .getOrNull()
            spotifyData.copy(
                pagedData = PagedData.Concat(
                    PagedData.Single { listOfNotNull(ntsShelf, curatedShelf) },
                    spotifyData.pagedData,
                ),
            )
        }
    }

    private data class BleepRelease(
        val artist: String,
        val title: String,
        val spotifyId: String,
        val cover: String?,
        val sources: Set<String> = setOf("BLEEP"),
    )

    private suspend fun buildCuratedShelf(): Shelf.Lists.Items? {
        val bleep = runCatching { withTimeoutOrNull(5_000) { fetchBleepWeeklyReleases() } }
            .onFailure { println("BHUT Bleep live feed: ${it.message}; using V18 fallback") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackBleepReleases() }
        val boomkat = runCatching { withTimeoutOrNull(5_000) { fetchBoomkatWeeklyReleases() } }
            .onFailure { println("BHUT Boomkat feed: ${it.message}; using V18 fallback") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackBoomkatReleases() }
        val releases = (bleep + boomkat).fold(linkedMapOf<String, BleepRelease>()) { merged, release ->
            val key = release.spotifyId.ifBlank { norm(release.artist) + "|" + norm(release.title) }
            val existing = merged[key]
            merged[key] = existing?.copy(sources = existing.sources + release.sources) ?: release
            merged
        }.values.toList()
        val albums = releases.map { release ->
            Album(
                id = "spotify:album:${release.spotifyId}",
                title = release.title,
                subtitle = release.sources.sorted().joinToString(" · "),
                cover = release.cover?.takeIf { it.isNotBlank() }?.toImageHolder(),
                artists = listOf(
                    Artist(
                        id = "curated:${norm(release.artist)}",
                        name = release.artist,
                        isRadioSupported = false,
                        isFollowable = false,
                        isSaveable = false,
                        isShareable = false,
                    ),
                ),
            )
        }
        if (albums.isEmpty()) return null
        return Shelf.Lists.Items(
            id = "bhut-curated-weekly",
            title = "CURATED • V18",
            list = albums,
            subtitle = "Weekly picks from Bleep and Boomkat",
        )
    }

    private suspend fun fetchBleepWeeklyReleases(): List<BleepRelease> {
        return bleepFeedMutex.withLock {
            cachedBleepReleases?.let { return@withLock it }
            val request = Request.Builder()
                .url(BLEEP_FEED_URL)
                .header("User-Agent", "BHUT/15")
                .build()
            val json = bleepHttp.newCall(request).await().use { response ->
                if (!response.isSuccessful) error("Bleep feed HTTP ${response.code}")
                response.body.string()
            }
            val items = JSONObject(json).optJSONArray("releases")
            val releases = buildList {
                if (items == null) return@buildList
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val artist = item.optString("artist").trim()
                    val title = item.optString("title").trim()
                    val spotifyId = item.optString("spotifyId").trim()
                    val cover = item.optString("cover").trim().takeIf { it.isNotBlank() }
                    if (artist.isNotBlank() && title.isNotBlank() && spotifyId.isNotBlank()) {
                        add(BleepRelease(artist, title, spotifyId, cover))
                    }
                }
            }
                .distinctBy { norm(it.artist) + "|" + norm(it.title) }
                .take(12)
            cachedBleepReleases = releases
            releases
        }
    }

    private suspend fun fetchBoomkatWeeklyReleases(): List<BleepRelease> = bleepFeedMutex.withLock {
        cachedBoomkatReleases?.let { return@withLock it }
        val json = bleepHttp.newCall(
            Request.Builder().url(BOOMKAT_FEED_URL).header("User-Agent", "BHUT/18").build()
        ).await().use { response ->
            if (!response.isSuccessful) error("Boomkat feed HTTP ${response.code}")
            response.body.string()
        }
        val items = JSONObject(json).optJSONArray("releases")
        val releases = buildList {
            if (items == null) return@buildList
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val artist = item.optString("artist").trim()
                val title = item.optString("title").trim()
                val spotifyId = item.optString("spotifyId").trim()
                val cover = item.optString("cover").trim().takeIf(String::isNotBlank)
                if (artist.isNotBlank() && title.isNotBlank() && spotifyId.isNotBlank()) {
                    add(BleepRelease(artist, title, spotifyId, cover, setOf("BOOMKAT")))
                }
            }
        }.distinctBy { it.spotifyId }.take(12)
        cachedBoomkatReleases = releases
        releases
    }

    private fun fallbackBleepReleases() = listOf(
        BleepRelease("Topdown Dialectic", "False LP A", "1R570SkqASVYyKJJQAzV5v", "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02f62e019a91013abe13fbc838"),
        BleepRelease("Phoebe Bridgers", "Lost Weekend", "2NSzwyYvQvdOQAoEjrlw9c", "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e0225a647ace83ba32770ab5d0f"),
    )

    private fun fallbackBoomkatReleases() = listOf(
        BleepRelease(
            "Nueen",
            "Swerved",
            "2sFUiugQ90JwZhOkJIrZiP",
            "https://i.scdn.co/image/ab67616d00004851f335151666ed660f1a711be8",
            setOf("BOOMKAT"),
        ),
    )

    private suspend fun buildNtsShelf(): Shelf.Lists.Items? {
        val episodes = fetchNtsEpisodes()
        if (episodes.isEmpty()) return null
        return Shelf.Lists.Items(
            id = "bhut-nts-latest",
            title = "NTS Latest Archives • V18",
            list = episodes,
            subtitle = "Newest playable mixes from the NTS archive",
        )
    }

    private suspend fun fetchNtsEpisodes(): List<Track> = ntsFeedMutex.withLock {
        cachedNtsEpisodes?.let { return@withLock it }
        val html = bleepHttp.newCall(
            Request.Builder().url(NTS_LATEST_URL).header("User-Agent", "BHUT/16").build()
        ).await().use { response ->
            if (!response.isSuccessful) error("NTS latest HTTP ${response.code}")
            response.body.string()
        }
        val token = Regex("""NTS_API_TOKEN\"\s*:\s*\"([^\"]+)""")
            .find(html)?.groupValues?.getOrNull(1)
            ?: error("NTS stream token missing")
        val marker = "window._REACT_STATE_ = "
        val stateStart = html.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length)
            ?: error("NTS archive state missing")
        val stateEnd = html.indexOf(";</script>", stateStart).takeIf { it > stateStart }
            ?: error("NTS archive state incomplete")
        val episodes = JSONObject(html.substring(stateStart, stateEnd))
            .getJSONObject("recentlyAdded")
            .getJSONArray("episodes")
        val tracks = buildList {
            for (i in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(i) ?: continue
                val sources = episode.optJSONArray("audio_sources") ?: continue
                val source = (0 until sources.length()).firstNotNullOfOrNull { index ->
                    sources.optJSONObject(index)
                        ?.takeIf { it.optString("source") == "soundcloud" }
                        ?.optString("url")
                        ?.takeIf { it.isNotBlank() }
                } ?: continue
                val show = episode.optString("show_alias").trim()
                val alias = episode.optString("episode_alias").trim()
                val title = episode.optString("name").trim()
                if (show.isBlank() || alias.isBlank() || title.isBlank()) continue
                val media = episode.optJSONObject("media")
                val cover = media?.optString("picture_medium_large")
                    ?.takeIf { it.isNotBlank() }
                    ?.toImageHolder()
                val date = episode.optString("broadcastDateFormatted").trim()
                val location = episode.optString("location_long").trim()
                val streamExtras = mapOf(NTS_ITEM to "true", NTS_TOKEN to token)
                add(
                    Track(
                        id = "nts:$show/$alias",
                        title = title,
                        type = Track.Type.Podcast,
                        cover = cover,
                        artists = listOf(
                            Artist(
                                id = "nts-radio",
                                name = "NTS Radio",
                                isRadioSupported = false,
                                isFollowable = false,
                                isSaveable = false,
                                isShareable = false,
                            )
                        ),
                        subtitle = listOf(date, location).filter { it.isNotBlank() }.joinToString(" • "),
                        genres = episode.optJSONArray("genres")?.let { genres ->
                            (0 until genres.length()).mapNotNull { index ->
                                genres.optJSONObject(index)?.optString("value")?.takeIf { it.isNotBlank() }
                            }
                        }.orEmpty(),
                        extras = streamExtras,
                        streamables = listOf(Streamable.server(source, 2, "NTS Archive", streamExtras)),
                        isRadioSupported = false,
                        isSaveable = false,
                        isLikeable = false,
                        isHideable = false,
                        isShareable = false,
                    )
                )
                if (size >= 12) break
            }
        }
        cachedNtsEpisodes = tracks
        tracks
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> =
        client<SearchFeedClient>("spotify").loadSearchFeed(query)

    override suspend fun loadLibraryFeed(): Feed<Shelf> =
        client<LibraryFeedClient>("spotify").loadLibraryFeed()

    override suspend fun loadPlaylist(playlist: Playlist): Playlist =
        client<PlaylistClient>("spotify").loadPlaylist(playlist)

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> =
        client<PlaylistClient>("spotify").loadTracks(playlist)

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? =
        client<PlaylistClient>("spotify").loadFeed(playlist)

    override suspend fun loadAlbum(album: Album): Album =
        client<AlbumClient>("spotify").loadAlbum(album)

    override suspend fun loadTracks(album: Album): Feed<Track>? =
        client<AlbumClient>("spotify").loadTracks(album)

    override suspend fun loadFeed(album: Album): Feed<Shelf>? =
        client<AlbumClient>("spotify").loadFeed(album)

    override suspend fun loadArtist(artist: Artist): Artist =
        client<ArtistClient>("spotify").loadArtist(artist)

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> =
        client<ArtistClient>("spotify").loadFeed(artist)

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        if (track.extras[NTS_ITEM] == "true") return null
        val spotifyId = track.extras["bridge_spotify_id"] ?: track.id
        return client<TrackClient>("spotify").loadFeed(track.copy(id = spotifyId))
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        if (track.extras[NTS_ITEM] == "true") return track
        val deezer = client<DeezerExtension>("deezer")
        val isDirectDeezer = track.extras[DIRECT_DEEZER_ITEM] == "true"
        val spotifyId = track.extras["bridge_spotify_id"] ?: track.id

        if (!isDirectDeezer) {
            println("SpotifyDeezerBridge: resolve start spotify=$spotifyId title=${track.title}")
        }

        // 1) Reuse a successful match for this app session.
        val cachedId = if (isDirectDeezer) null else spotifyToDeezer[spotifyId]
        val matchedFromCache = cachedId?.let { id ->
            println("SpotifyDeezerBridge: cache hit spotify=$spotifyId deezer=$id")
            Track(id = id, title = track.title)
        }

        // 2) Exact/public catalogue match, preferring Track.isrc directly.
        val albumPosition = if (isDirectDeezer) null else track.albumOrderNumber ?: runCatching {
            track.album?.let { album ->
                client<AlbumClient>("spotify").loadTracks(album)?.loadAll()
                    ?.indexOfFirst { it.id == spotifyId }
                    ?.takeIf { it >= 0 }
                    ?.plus(1)?.toLong()
            }
        }.getOrNull()
        val publicId = if (matchedFromCache == null) {
            DeezerCatalogueMatcher.resolve(track, albumPosition)?.toString()?.also { id ->
                println("SpotifyDeezerBridge: catalogue match spotify=$spotifyId deezer=$id")
            }
        } else null

        // 3) Authenticated Deezer search fallback using multiple query variants.
        val matched = if (isDirectDeezer) track else matchedFromCache
            ?: publicId?.let { Track(id = it, title = track.title) }
            ?: resolveViaDeezer(track, deezer)
            ?: error(
                "Deezer match unavailable: ${track.title} — " +
                    track.artists.firstOrNull()?.name.orEmpty()
            )

        if (!isDirectDeezer) {
            spotifyToDeezer[spotifyId] = matched.id
            println("SpotifyDeezerBridge: matched spotify=$spotifyId deezer=${matched.id}")
        }

        // Let Gladix's bundled Deezer client hydrate the track/token itself.
        val loaded = runCatching { deezer.loadTrack(matched, isDownload) }
            .getOrElse { cause ->
                if (!isDirectDeezer) spotifyToDeezer.remove(spotifyId, matched.id)
                throw IllegalStateException(
                    "Deezer track load failed for ${matched.id}: ${cause.message}",
                    cause,
                )
            }

        val mp3_320 = loaded.streamables.filter {
            it.quality == 6 || it.title.equals("320kbps", ignoreCase = true)
        }
        val mp3Misc = loaded.streamables.filter {
            it.title.equals("MP3", ignoreCase = true) ||
                (it.quality == 0 && loaded.extras["FILESIZE_MP3_MISC"]?.let { size -> size != "0" } == true)
        }

        // Deezer normally exposes its highest lossy tier as MP3_320. Some
        // catalogue entries instead use the authenticated MP3_MISC route.
        // Prefer an explicit 320 stream when available; otherwise accept the
        // provider's MP3_MISC representation as the highest MP3 available for
        // that specific track.
        val preferredMp3 = if (mp3_320.isNotEmpty()) mp3_320 else mp3Misc
        if (preferredMp3.isEmpty()) {
            val offered = loaded.streamables.joinToString { "${it.title}/${it.quality}" }
            val miscSize = loaded.extras["FILESIZE_MP3_MISC"].orEmpty()
            error(
                "Deezer MP3 unavailable after match ${loaded.id} " +
                    "(offered: $offered, FILESIZE_MP3_MISC=$miscSize)"
            )
        }

        val selectedLabel = if (mp3_320.isNotEmpty()) "320kbps" else "MP3_MISC"
        println("SpotifyDeezerBridge: $selectedLabel ready deezer=${loaded.id}")

        // Preserve Spotify-facing artwork/artist/album metadata while routing
        // all playable streamables to the Deezer client.
        return track.copy(
            // Gladix's StreamableLoader requires loadTrack() to preserve the
            // logical item identity it was asked to load. Keep the Spotify ID
            // here; Deezer's real track ID stays in extras and inside the
            // Deezer-generated streamables used for playback.
            id = if (isDirectDeezer) track.id else spotifyId,
            streamables = preferredMp3,
            extras = track.extras + loaded.extras + mapOf(
                "bridge_spotify_id" to spotifyId,
                "bridge_deezer_id" to loaded.id,
            ) + if (isDirectDeezer) mapOf(DIRECT_DEEZER_ITEM to "true") else emptyMap(),
        )
    }

    /**
     * Continue a Spotify-origin queue through Gladix's bundled Deezer radio implementation.
     * The final Spotify item is translated only to its already-successful Deezer match; the
     * recommendations returned from Deezer remain native Deezer items for playback.
     */
    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val track = item as? Track ?: error("BHUT radio requires a track seed")
        val spotifyId = track.extras["bridge_spotify_id"] ?: track.id
        val deezerId = track.extras["bridge_deezer_id"]
            ?: spotifyToDeezer[spotifyId]
            ?: error("No successful Deezer match is available for ${track.title}")
        val deezerSeed = track.copy(
            id = deezerId,
            extras = track.extras + mapOf(
                DIRECT_DEEZER_ITEM to "true",
                "bridge_deezer_id" to deezerId,
            ),
        )
        val deezerRadio = client<DeezerExtension>("deezer").radio(deezerSeed, null)
        return deezerRadio.copy(
            extras = deezerRadio.extras + mapOf(DEEZER_RADIO to "true"),
        )
    }

    override suspend fun loadRadio(radio: Radio): Radio = radio

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        require(radio.extras[DEEZER_RADIO] == "true") { "Unknown BHUT radio" }
        val feed = client<DeezerExtension>("deezer").loadTracks(radio)
        return Feed(feed.tabs) { tab ->
            val data = feed.getPagedData(tab)
            data.copy(
                pagedData = data.pagedData.map { result ->
                    result.getOrThrow().map { track ->
                        track.copy(
                            extras = track.extras + mapOf(
                                DIRECT_DEEZER_ITEM to "true",
                                "bridge_deezer_id" to track.id,
                            ),
                        )
                    }
                },
            )
        }
    }

    private suspend fun resolveViaDeezer(source: Track, deezer: DeezerExtension): Track? {
        val artists = source.artists.map { it.name.trim() }.filter { it.isNotBlank() }
        val titles = titleVariants(source.title)
        val albumTitles = source.album?.title?.let { listOf(it, baseAlbumTitle(it)) }.orEmpty()
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val queries = linkedSetOf<String>().apply {
            for (title in titles) {
                for (artist in artists) {
                    add("$artist $title")
                    add("$title $artist")
                }
                add(title)
            }
            for (album in albumTitles) {
                for (artist in artists) add("$artist $album")
                add(album)
            }
            for (artist in artists) add(artist)
        }

        var best: Track? = null
        var bestScore = Int.MIN_VALUE

        for (query in queries) {
            val candidates = runCatching { deezer.bridgeSearchTracks(query) }
                .onFailure {
                    println("SpotifyDeezerBridge: Deezer search failed query='$query': ${it.message}")
                }
                .getOrDefault(emptyList())

            println("SpotifyDeezerBridge: Deezer search query='$query' candidates=${candidates.size}")

            for (candidate in candidates) {
                val score = bridgeScore(source, candidate)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
            }

            // Strong exact metadata match; no need to fan out further.
            if (bestScore >= 55) break
        }

        println("SpotifyDeezerBridge: best authenticated score=$bestScore deezer=${best?.id}")
        return best?.takeIf { bestScore >= 40 }
    }

    private fun bridgeScore(source: Track, candidate: Track): Int {
        var score = 0

        val sourceIsrc = source.isrc?.trim()?.uppercase()
        val candidateIsrc = candidate.isrc?.trim()?.uppercase()
        if (!sourceIsrc.isNullOrBlank() && !candidateIsrc.isNullOrBlank() && sourceIsrc == candidateIsrc) {
            score += 70
        }

        val sourceTitles = titleVariants(source.title).map(::norm).filter { it.isNotBlank() }
        val candidateTitle = norm(candidate.title)
        if (candidateTitle.isNotBlank()) {
            when {
                sourceTitles.any { it == candidateTitle } -> score += 25
                sourceTitles.any { it.contains(candidateTitle) || candidateTitle.contains(it) } -> score += 12
            }
        }

        val sourceArtists = source.artists.map { norm(it.name) }.filter { it.isNotBlank() }
        val candidateArtists = candidate.artists.map { norm(it.name) }.filter { it.isNotBlank() }
        if (sourceArtists.isNotEmpty() && candidateArtists.isNotEmpty()) {
            when {
                candidateArtists.any { da -> sourceArtists.any { sa -> sa == da } } -> score += 20
                candidateArtists.any { da -> sourceArtists.any { sa -> sa.contains(da) || da.contains(sa) } } -> score += 10
            }
        }

        val sourceDuration = source.duration
        val candidateDuration = candidate.duration
        if (sourceDuration != null && candidateDuration != null) {
            val delta = abs(sourceDuration - candidateDuration)
            when {
                delta <= 2_500 -> score += 10
                delta <= 5_000 -> score += 5
                delta > 15_000 -> score -= 20
            }
        }

        return score
    }

    private fun titleVariants(title: String): List<String> = linkedSetOf<String>().apply {
        val clean = title.trim()
        if (clean.isNotBlank()) add(clean)
        clean.substringBefore(" - ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.substringBefore(" – ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.substringBefore(" — ").trim().takeIf { it.isNotBlank() }?.let(::add)
        clean.replace(Regex("""\s*[-–—]\s*(reprise|remix|edit|version|remaster(?:ed)?)\b.*$""", RegexOption.IGNORE_CASE), "")
            .trim().takeIf { it.isNotBlank() }?.let(::add)
    }.toList()

    private fun baseAlbumTitle(album: String): String = album
        .replace(Regex("""\s*\((revision|reissue|remaster(?:ed)?|deluxe).*?\)\s*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun norm(value: String) = value.lowercase()
        .replace(Regex("""\([^)]*(remaster|remastered|live|edit|version)[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\[[^]]*(remaster|remastered|live|edit|version)[^]]*]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean,
    ): Streamable.Media {
        if (streamable.extras[NTS_ITEM] == "true") {
            val token = streamable.extras[NTS_TOKEN] ?: error("NTS stream token unavailable")
            val encoded = URLEncoder.encode(streamable.id, "UTF-8")
            val request = Request.Builder()
                .url("https://www.nts.live/api/v2/resolve-stream?url=$encoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic $token")
                .build()
            val hls = bleepHttp.newCall(request).await().use { response ->
                if (!response.isSuccessful) error("NTS stream HTTP ${response.code}")
                JSONObject(response.body.string()).getString("hls")
            }
            return hls.toServerMedia(type = Streamable.SourceType.HLS)
        }
        return runCatching {
            client<TrackClient>("deezer").loadStreamableMedia(streamable, isDownload)
        }.getOrElse { cause ->
            throw IllegalStateException(
                "Deezer MP3 media resolution failed: ${cause.message}",
                cause,
            )
        }
    }
}
