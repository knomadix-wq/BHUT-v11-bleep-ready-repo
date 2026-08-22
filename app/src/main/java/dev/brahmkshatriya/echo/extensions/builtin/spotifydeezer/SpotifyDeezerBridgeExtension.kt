package dev.brahmkshatriya.echo.extensions.builtin.spotifydeezer

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
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
    RadioClient,
    SaveClient,
    ShareClient {

    companion object {
        const val ID = "spotify-deezer"
        val metadata = Metadata(
            className = "dev.brahmkshatriya.echo.extensions.builtin.spotifydeezer.SpotifyDeezerBridgeExtension",
            path = "",
            importType = ImportType.BuiltIn,
            type = ExtensionType.MUSIC,
            id = ID,
            name = "Spotify → Deezer MP3",
            version = "v26",
            description = "Spotify browsing with Deezer MP3 playback, curated editorial and experimental-label picks, and the latest NTS archives.",
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
        private const val BANDCAMP_FEED_URL =
            "https://raw.githubusercontent.com/knomadix-wq/BHUT-v12-bleep-ready-repo/main/data/bandcamp-electronic.json"
        private const val LABEL_WATCHLIST_FEED_URL =
            "https://raw.githubusercontent.com/knomadix-wq/BHUT-v12-bleep-ready-repo/main/data/label-watchlist.json"
        private const val AU_FRESH_FINDS_ID = "spotify:playlist:37i9dQZF1DX8pdK1PVpBQz"
        private val bleepHttp = OkHttpClient()
    }

    override val requiredMusicExtensions = listOf("spotify", "deezer")
    private var extensions: List<MusicExtension> = emptyList()
    private val bleepFeedMutex = Mutex()
    private var cachedBleepReleases: List<BleepRelease>? = null
    private var cachedBoomkatReleases: List<BleepRelease>? = null
    private var cachedBandcampReleases: List<BleepRelease>? = null
    private var cachedLabelReleases: List<BleepRelease>? = null
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
            val localShelf = runCatching { withTimeoutOrNull(8_000) { buildAustralianLocalShelf() } }
                .onFailure { println("BHUT Australian local releases: ${it.message}") }
                .getOrNull()
            val ntsShelf = runCatching { withTimeoutOrNull(8_000) { buildNtsShelf() } }
                .onFailure { println("BHUT NTS: ${it.message}") }
                .getOrNull()
            spotifyData.copy(
                pagedData = PagedData.Single {
                    val spotifyShelves = spotifyData.pagedData.loadAll()
                        .filterNot(::removeSpotifyHomeShelf)
                        .mapNotNull(::withoutSpotifyRadio)
                    val (releaseRadar, withoutRadar) = takeStandaloneShelf(
                        spotifyShelves, "release radar", "RELEASE RADAR",
                    )
                    val (newReleases, withoutNewReleases) = takeStandaloneShelf(
                        withoutRadar, "new releases for you", "NEW RELEASES FOR YOU",
                    )
                    val (recommendedToday, remaining) = takeStandaloneShelf(
                        withoutNewReleases, "recommended for today", "Recommended for today",
                    )
                    listOfNotNull(
                        ntsShelf,
                        curatedShelf,
                        releaseRadar,
                        newReleases,
                        recommendedToday,
                        localShelf,
                    ) + remaining
                },
            )
        }
    }

    private fun removeSpotifyHomeShelf(shelf: Shelf): Boolean {
        val title = norm(shelf.title)
        return title.startsWith("made for ") ||
            title == "best of artists" ||
            title == "your top mixes" ||
            title == "your favourite artists" ||
            title == "your favorite artists" ||
            title.startsWith("more like ")
    }

    private fun takeStandaloneShelf(
        shelves: List<Shelf>,
        target: String,
        displayTitle: String,
    ): Pair<Shelf?, List<Shelf>> {
        var selected: Shelf? = null
        val remaining = buildList {
            shelves.forEach { shelf ->
                if (norm(shelf.title) == target) {
                    if (selected == null) selected = shelf
                    return@forEach
                }
                when (shelf) {
                    is Shelf.Item -> {
                        if (norm(shelf.media.title) == target && selected == null) {
                            selected = Shelf.Lists.Items(
                                id = "bhut-${target.replace(' ', '-')}",
                                title = displayTitle,
                                list = listOf(shelf.media),
                            )
                        } else add(shelf)
                    }
                    is Shelf.Lists.Items -> {
                        val matches = shelf.list.filter { norm(it.title) == target }
                        if (matches.isNotEmpty() && selected == null) {
                            selected = shelf.copy(
                                id = "bhut-${target.replace(' ', '-')}",
                                title = displayTitle,
                                list = matches,
                            )
                            val leftovers = shelf.list.filterNot { norm(it.title) == target }
                            if (leftovers.isNotEmpty()) add(shelf.copy(list = leftovers))
                        } else add(shelf)
                    }
                    else -> add(shelf)
                }
            }
        }
        return selected to remaining
    }

    private fun withoutSpotifyRadio(shelf: Shelf): Shelf? = when (shelf) {
        is Shelf.Item -> shelf.takeUnless { isSpotifyRadioItem(it.media) }
        is Shelf.Lists.Items -> shelf.copy(list = shelf.list.filterNot(::isSpotifyRadioItem))
            .takeIf { it.list.isNotEmpty() }
        else -> shelf
    }

    private fun isSpotifyRadioItem(item: EchoMediaItem): Boolean =
        item is Radio || (item is Playlist && isSpotifyGenerated(item) && (
            norm(item.title).endsWith(" radio") || norm(item.title).endsWith(" mix")
        ))

    private fun isSpotifyGenerated(playlist: Playlist): Boolean =
        norm(playlist.subtitle.orEmpty()) == "spotify" ||
            playlist.authors.any { norm(it.name) == "spotify" }

    private suspend fun buildAustralianLocalShelf(): Shelf.Lists.Tracks {
        val playlist = Playlist(
            id = AU_FRESH_FINDS_ID,
            title = "Fresh Finds AU & NZ",
            subtitle = "Spotify",
            isEditable = false,
        )
        val tracks = client<PlaylistClient>("spotify").loadTracks(playlist).loadAll().take(12)
        return Shelf.Lists.Tracks(
            id = "bhut-local-releases-au",
            title = "LOCAL RELEASES • AUSTRALIA",
            subtitle = "New independent Australian and New Zealand music",
            list = tracks,
            more = PagedData.Single<Shelf> { listOf(playlist.toShelf()) }.toFeed(),
        )
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
            .onFailure { println("BHUT Bleep live feed: ${it.message}; using V26 fallback") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackBleepReleases() }
        val boomkat = runCatching { withTimeoutOrNull(5_000) { fetchBoomkatWeeklyReleases() } }
            .onFailure { println("BHUT Boomkat feed: ${it.message}; using V26 fallback") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackBoomkatReleases() }
        val bandcamp = runCatching { withTimeoutOrNull(5_000) { fetchBandcampReleases() } }
            .onFailure { println("BHUT Bandcamp Daily feed: ${it.message}; using V26 fallback") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackBandcampReleases() }
        val labels = runCatching { withTimeoutOrNull(5_000) { fetchLabelWatchlistReleases() } }
            .onFailure { println("BHUT label watchlist feed: ${it.message}; using V26 baseline") }
            .getOrNull()
            .orEmpty()
            .ifEmpty { fallbackLabelReleases() }
        val releases = (bleep + boomkat + bandcamp + labels).fold(linkedMapOf<String, BleepRelease>()) { merged, release ->
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
            title = "CURATED • V26",
            list = albums,
            subtitle = "Bleep · Boomkat · Bandcamp Daily · Shelter Press · Latency · Editions Mego · Raster",
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
            Request.Builder().url(BOOMKAT_FEED_URL).header("User-Agent", "BHUT/26").build()
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

    private suspend fun fetchBandcampReleases(): List<BleepRelease> = bleepFeedMutex.withLock {
        cachedBandcampReleases?.let { return@withLock it }
        val json = bleepHttp.newCall(
            Request.Builder().url(BANDCAMP_FEED_URL).header("User-Agent", "BHUT/26").build()
        ).await().use { response ->
            if (!response.isSuccessful) error("Bandcamp feed HTTP ${response.code}")
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
                    add(BleepRelease(artist, title, spotifyId, cover, setOf("BANDCAMP DAILY")))
                }
            }
        }.distinctBy { it.spotifyId }.take(12)
        cachedBandcampReleases = releases
        releases
    }

    private suspend fun fetchLabelWatchlistReleases(): List<BleepRelease> = bleepFeedMutex.withLock {
        cachedLabelReleases?.let { return@withLock it }
        val json = bleepHttp.newCall(
            Request.Builder().url(LABEL_WATCHLIST_FEED_URL).header("User-Agent", "BHUT/26").build()
        ).await().use { response ->
            if (!response.isSuccessful) error("Label watchlist feed HTTP ${response.code}")
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
                val source = item.optString("source").trim().takeIf(String::isNotBlank) ?: continue
                if (artist.isNotBlank() && title.isNotBlank() && spotifyId.isNotBlank()) {
                    add(BleepRelease(artist, title, spotifyId, cover, setOf(source)))
                }
            }
        }.distinctBy { it.spotifyId + "|" + it.sources.first() }.take(32)
        cachedLabelReleases = releases
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
        BleepRelease(
            "Cate Kennan",
            "Shadows",
            "5QjofTmQcJeoTeGCTavLtb",
            "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02a181b0590223934290e3fe4a",
            setOf("BOOMKAT"),
        ),
        BleepRelease(
            "Charanjit Singh",
            "Synthesizing - Ten Ragas to a Disco Beat",
            "1xXm4sanFkm9tVZglqTq60",
            "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e027443f8328a2f0d3f3141bef5",
            setOf("BOOMKAT"),
        ),
        BleepRelease(
            "YL Hooi",
            "Untitled",
            "1aVKHYdh9Qqv0lKulUturf",
            "https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e02550205050f35b877cdb525c2",
            setOf("BOOMKAT"),
        ),
        BleepRelease(
            "Nondi_",
            "Nondi...",
            "5gTVBk1Ny9S6Ki5cCkyOz2",
            "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e02bb8a389e22e85014278629e6",
            setOf("BOOMKAT"),
        ),
    )

    private fun fallbackBandcampReleases() = listOf(
        BleepRelease(
            "The Bug / Dis Fig",
            "Ladybug 1",
            "0U171DRtrbqXZONDIPd14F",
            "https://i.scdn.co/image/ab67616d00004851842a568d786d43452824045d",
            setOf("BANDCAMP DAILY"),
        ),
        BleepRelease(
            "HVL",
            "Formation",
            "70S5dpPzjDZJ6WzftyGjL4",
            "https://i.scdn.co/image/ab67616d00004851b26169b6c04dba0024ffd5ba",
            setOf("BANDCAMP DAILY"),
        ),
        BleepRelease(
            "ReKab",
            "Subtle Beginnings",
            "6Pc0xUcMdQHcITAGRWYyRy",
            "https://i.scdn.co/image/ab67616d00004851658bcb57cf188a6065057957",
            setOf("BANDCAMP DAILY"),
        ),
    )

    private fun fallbackLabelReleases() = listOf(
        BleepRelease(
            "Kassel Jaeger",
            "Sub Re",
            "1kDaNdOJtTm68CcbNJJPXV",
            "https://image-cdn-ak.spotifycdn.com/image/ab67616d00001e0261c3e7bda596f7a0473ed3f5",
            setOf("SHELTER PRESS"),
        ),
    )

    private suspend fun buildNtsShelf(): Shelf.Lists.Items? {
        val episodes = fetchNtsEpisodes()
        if (episodes.isEmpty()) return null
        return Shelf.Lists.Items(
            id = "bhut-nts-latest",
            title = "NTS Latest Archives • V26",
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
        client<AlbumClient>("spotify").loadAlbum(album).copy(
            isSaveable = true,
            isShareable = true,
        )

    override suspend fun loadTracks(album: Album): Feed<Track>? =
        client<AlbumClient>("spotify").loadTracks(album)

    override suspend fun loadFeed(album: Album): Feed<Shelf>? =
        client<AlbumClient>("spotify").loadFeed(album)

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) =
        client<SaveClient>("spotify").saveToLibrary(item.spotifyFacing(), shouldSave)

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean =
        client<SaveClient>("spotify").isItemSaved(item.spotifyFacing())

    override suspend fun onShare(item: EchoMediaItem): String =
        client<ShareClient>("spotify").onShare(item.spotifyFacing())

    private fun EchoMediaItem.spotifyFacing(): EchoMediaItem {
        val spotifyId = extras["bridge_spotify_id"] ?: id
        require(spotifyId.startsWith("spotify:")) { "This item has no Spotify catalogue link" }
        return copyMediaItem(id = spotifyId)
    }

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
            isSaveable = !isDirectDeezer,
            isShareable = !isDirectDeezer,
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
