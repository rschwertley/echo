package dev.brahmkshatriya.echo.extensions.builtin.unified

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.room3.Room
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditCoverClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.clients.TrackerClient
import dev.brahmkshatriya.echo.common.clients.TrackerMarkClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.TrackDetails
import dev.brahmkshatriya.echo.common.providers.MusicExtensionsProvider
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import dev.brahmkshatriya.echo.extensions.cache.Cached
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.playback.MediaItemUtils.toKey
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.io.File

@OptIn(UnstableApi::class)
class UnifiedExtension(
    private val app: App,
    private val cache: SimpleCache,
) : ExtensionClient, MusicExtensionsProvider,
    HomeFeedClient, SearchFeedClient, LibraryFeedClient,
    PlaylistClient, AlbumClient, ArtistClient, TrackClient,
    FollowClient, RadioClient, LikeClient, SaveClient, HideClient, ShareClient,
    PlaylistEditClient, PlaylistEditCoverClient, LyricsClient, TrackerMarkClient {

    companion object {
        const val UNIFIED_ID = "unified"
        const val EXTENSION_ID = "extension_id"
        val metadata = Metadata(
            "UnifiedExtension",
            "",
            ImportType.BuiltIn,
            ExtensionType.MUSIC,
            UNIFIED_ID,
            "Unified Extension",
            version = "v${BuildConfig.VERSION_CODE}",
            "All your extensions in one place!",
            "Gladix",
            isEnabled = true
        )

        fun Context.getFeed(items: List<EchoMediaItem>): Feed<Shelf> {
            if (items.isEmpty()) return listOf<Shelf>().toFeed()
            val types = items.groupBy {
                when (it) {
                    is Track -> getString(R.string.track)
                    is Album -> getString(R.string.album)
                    is Artist -> getString(R.string.artists)
                    is Playlist -> getString(R.string.playlist)
                    is Radio -> getString(R.string.radio)
                }
            }
            val tabs = if (types.keys.size == 1) listOf()
            else getString(R.string.all).let { listOf(Tab(it, it)) } +
                    types.keys.map { Tab(it, it, true) }

            return Feed(tabs) { tab ->
                val items = types[tab?.id]?.toList() ?: items
                items.map { it.toShelf() }.toFeedData()
            }
        }

        suspend inline fun <reified C, T> Extension<*>.client(block: C.() -> T): T = runCatching {
            val client = instance.value().getOrThrow() as? C
                ?: throw ClientException.NotSupported(C::class.run { simpleName ?: java.name })
            client.block()
        }.getOrElse { throw it.toAppException(this) }

        suspend inline fun <reified C, T> Extension<*>.clientOrNull(block: C.() -> T): T? =
            runCatching {
                val client = instance.value().getOrThrow() as? C
                client?.block()
            }.getOrElse { throw it.toAppException(this) }

        private fun List<Extension<*>>.get(id: String?) =
            find { it.id == id } ?: throw Exception("Extension $id not found")

        private fun List<Extension<*>>.getOrNull(id: String?) = find { it.id == id }

        /**
         * Non-throwing sibling of [extensionId], for callers where a missing stamp is not an error.
         *
         * ⚠️ PATTERN, LEARNED THE EXPENSIVE WAY: AN ELVIS AGAINST A THROWING ACCESSOR IS NOT A GUARD.
         * The tracker callbacks below read `details?.track?.extras?.extensionId ?: return` for six weeks.
         * That LOOKS like "no stamp, skip quietly" and BEHAVES like "no stamp, throw": [extensionId]
         * raises ExtensionNotFoundException rather than returning null, so the elvis is unreachable on the
         * only branch that matters. Anyone auditing the file saw a guarded call site and moved on — which
         * is exactly why Unified tracking was silently dead for every restored queue from 92af04f5
         * (2026-07-26, when Track.toSlim started stripping extras on save) until 2026-09-07.
         * If you write `?: return` after an accessor, check that the accessor can actually return null.
         */
        val Map<String, String>.extensionIdOrNull get() = this[EXTENSION_ID]

        val Map<String, String>.extensionId
            // Typed, not a bare Exception: the skip reports group on the class, and a restored queue
            // referencing an uninstalled sub-extension is a normal config condition that must be
            // classifiable as Unavailable rather than landing in the residual bucket.
            get() = this[EXTENSION_ID] ?: throw ExtensionNotFoundException(null)

        fun Track.withExtensionId(
            id: String, client: Any?, cached: Boolean = false,
        ) = copy(
            extras = extras + mapOf(EXTENSION_ID to id, "cached" to cached.toString()),
            album = album?.withExtensionId(id, client),
            artists = artists.map { it.withExtensionId(id, client) },
            streamables = streamables.map {
                it.copy(extras = it.extras + mapOf(EXTENSION_ID to id))
            },
            isSaveable = true,
            isLikeable = true,
            isHideable = client is HideClient && isHideable,
            isRadioSupported = client is RadioClient && isRadioSupported,
            isFollowable = client is FollowClient && isFollowable,
            isShareable = client is ShareClient && isShareable
        )

        private fun Album.withExtensionId(id: String, client: Any?) = copy(
            artists = artists.map { it.withExtensionId(id, client) },
            extras = extras + mapOf(EXTENSION_ID to id),
            isSaveable = true,
            isLikeable = false,
            isHideable = client is HideClient && isHideable,
            isRadioSupported = client is RadioClient && isRadioSupported,
            isFollowable = client is FollowClient && isFollowable,
            isShareable = client is ShareClient && isShareable
        )

        private fun Artist.withExtensionId(id: String, client: Any?) = copy(
            extras = extras + mapOf(EXTENSION_ID to id),
            isSaveable = true,
            isLikeable = false,
            isHideable = client is HideClient && isHideable,
            isRadioSupported = client is RadioClient && isRadioSupported,
            isFollowable = client is FollowClient && isFollowable,
            isShareable = client is ShareClient && isShareable
        )

        private fun Playlist.withExtensionId(id: String, client: Any?) = copy(
            isEditable = false,
            authors = authors.map { it.withExtensionId(id, client) },
            extras = extras + mapOf(EXTENSION_ID to id),
            isSaveable = true,
            isLikeable = false,
            isHideable = client is HideClient && isHideable,
            isRadioSupported = client is RadioClient && isRadioSupported,
            isFollowable = client is FollowClient && isFollowable,
            isShareable = client is ShareClient && isShareable
        )

        private fun Radio.withExtensionId(id: String, client: Any?) = copy(
            extras = extras + mapOf(EXTENSION_ID to id),
            isSaveable = true,
            isLikeable = false,
            isHideable = client is HideClient && isHideable,
            isFollowable = client is FollowClient && isFollowable,
            isShareable = client is ShareClient && isShareable
        )

        private fun EchoMediaItem.withExtensionId(
            id: String, client: Any?,
        ) = when (this) {
            is Artist -> this.withExtensionId(id, client)
            is Album -> this.withExtensionId(id, client)
            is Playlist -> this.withExtensionId(id, client)
            is Radio -> this.withExtensionId(id, client)
            is Track -> this.withExtensionId(id, client)
        }

        private fun Lyrics.withExtensionId(id: String) = copy(
            extras = extras + mapOf(EXTENSION_ID to id)
        )

        fun Shelf.Item.withExtensionId(id: String, client: Any?) = copy(
            media = media.withExtensionId(id, client)
        )

        fun Feed.Buttons.withExtensionId(extension: Extension<*>) = copy(
            customTrackList = customTrackList?.map {
                it.withExtensionId(extension.id, extension.instance.value)
            }
        )

        private fun Feed<Shelf>.injectExtensionId(extension: Extension<*>) = copy(
            tabs = tabs.map { it.injectId(extension.id) },
            getPagedData = { tab ->
                val (data, buttons, bg) = runCatching { getPagedData(tab) }.getOrElse {
                    throw it.toAppException(extension)
                }
                data.injectExtensionId(extension)
                    .toFeedData(buttons?.withExtensionId(extension), bg)
            }
        )

        private fun Feed<Track>.injectExtension(extension: Extension<*>) = copy(
            tabs = tabs.map { it.injectId(extension.id) },
            getPagedData = { tab ->
                val id = extension.id
                val (data, buttons, bg) = runCatching { getPagedData(tab) }.getOrElse {
                    throw it.toAppException(extension)
                }
                data.map { result ->
                    val list = result.getOrElse { throw it.toAppException(extension) }
                    list.map { it.withExtensionId(id, extension.instance.value) }
                }.toFeedData(buttons?.withExtensionId(extension), bg)
            }
        )

        fun Feed<Lyrics>.injectLyricsExtId(extension: Extension<*>) = copy(
            tabs = tabs.map { it.injectId(extension.id) },
        ) { tab ->
            val (data, buttons, bg) = runCatching { getPagedData(tab) }.getOrElse {
                throw it.toAppException(extension)
            }
            data.map { result ->
                val id = extension.id
                val list = result.getOrElse { throw it.toAppException(extension) }
                list.map { it.withExtensionId(id) }
            }.toFeedData(buttons?.withExtensionId(extension), bg)
        }

        private fun Shelf.Category.withExtensionId(extension: Extension<*>): Shelf.Category =
            copy(feed = feed?.injectExtensionId(extension))

        private fun PagedData<Shelf>.injectExtensionId(extension: Extension<*>): PagedData<Shelf> =
            map { result ->
                val id = extension.id
                val client = extension.instance.value
                val list = result.getOrElse { throw it.toAppException(extension) }
                list.map {
                    when (it) {
                        is Shelf.Category -> it.withExtensionId(extension)
                        is Shelf.Item -> it.withExtensionId(id, client)
                        is Shelf.Lists.Categories -> it.copy(
                            list = it.list.map { category -> category.withExtensionId(extension) },
                            more = it.more?.injectExtensionId(extension)
                        )

                        is Shelf.Lists.Items -> it.copy(
                            list = it.list.map { item -> item.withExtensionId(id, client) },
                            more = it.more?.injectExtensionId(extension)
                        )

                        is Shelf.Lists.Tracks -> it.copy(
                            list = it.list.map { track ->
                                track.withExtensionId(id, client)
                            },
                            more = it.more?.injectExtensionId(extension)
                        )
                    }
                }
            }

        fun Tab.injectId(id: String) = copy(extras = extras + mapOf(EXTENSION_ID to id))
    }

    private val context = app.context

    override suspend fun getSettingItems() = listOf(
        SettingSwitch(
            context.getString(R.string.show_tabs),
            "show_tabs",
            context.getString(R.string.show_tab_summary),
            false
        ),
    )

    private lateinit var settings: Settings
    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    private val showTabs get() = settings.getBoolean("show_tabs") ?: false

    override val requiredMusicExtensions = listOf<String>()
    private var extFlow = MutableStateFlow<List<MusicExtension>?>(null)
    suspend fun extensions() = extFlow.first { it != null }!!
    override fun setMusicExtensions(extensions: List<MusicExtension>) {
        extFlow.value = extensions.filter { it.id != UNIFIED_ID && it.metadata.isEnabled }
    }

    private suspend inline fun <reified T> Extension<*>.getFeedData(
        crossinline loadFeed: suspend T.() -> Feed<Shelf>,
    ): Feed.Data<Shelf> {
        val feed = client<T, Feed<Shelf>> { loadFeed() }.injectExtensionId(this)
        val data = feed.run { getPagedData(tabs.firstOrNull()) }
        val otherTabs = feed.tabs.drop(1).map { tab ->
            Shelf.Category(
                tab.id,
                tab.title,
                Feed(listOf()) { feed.run { getPagedData(tab) } }
            )
        }
        return if (feed.tabs.size > 1 && showTabs) {
            Feed.Data(
                PagedData.Concat(
                    PagedData.Single {
                        listOf(
                            Shelf.Lists.Categories(
                                "tabs",
                                context.getString(R.string.tabs),
                                otherTabs
                            )
                        )
                    },
                    data.pagedData
                ),
                data.buttons?.withExtensionId(this),
                data.background
            )
        } else data
    }

    private suspend inline fun <reified T> feed(
        crossinline loadFeed: suspend T.() -> Feed<Shelf>,
    ): Feed<Shelf> {
        val list = extensions()
        return if (list.size == 1) {
            val ext = list.first()
            ext.client<T, Feed<Shelf>> { loadFeed() }.injectExtensionId(ext)
        } else Feed(
            list.map { Tab(it.id, it.name).injectId(it.id) }
        ) { tab ->
            val extensions = extensions()
            // ⚠️ THE ELVIS COVERS `tab == null` ONLY — it is NOT a guard on a missing stamp.
            // Read as one expression it looks like it handles both, so state which case is which:
            //   tab == null            -> `?.` short-circuits, and we fall back to the first enabled
            //                             non-Unified extension. Deliberate: the same terminal tier as
            //                             getCurrentExtension's cold-start safety net.
            //   tab != null, unstamped -> UNREACHABLE BY CONSTRUCTION. Every tab this function emits is
            //                             built `Tab(...).injectId(it.id)`, which writes EXTENSION_ID, and
            //                             FeedData only ever hands back a tab it found in `feed.tabs`. If it
            //                             ever happens, extensionId throws — which is CORRECT here and must
            //                             stay that way: `get(id)` on a WRONG id silently loads another
            //                             extension's feed under the user's selected tab, the cross-extension
            //                             bleed that the AA search cache key (String -> Pair(query, extId))
            //                             was changed to stop. Loud beats guessing.
            // No circularity: extensions() is extFlow, filtered `id != UNIFIED_ID && isEnabled` at
            // setMusicExtensions, so the fallback can never select Unified itself. That filter is on the
            // PRODUCER, which is why it is invisible from here. Producer-side filtering has left a gap
            // once already on this same axis: AndroidAutoCallback.onGetChildren filters Unified out of the
            // ROOT listing but its per-node lookup (`extensions.firstOrNull { it.id == extId }`, extId
            // parsed out of parentId) reads the UNFILTERED extensionList. Confirmed still unfiltered
            // 2026-09-07. When a filter lives at the producer, check every consumer separately.
            val id = tab?.extras?.extensionId ?: extensions.firstOrNull()?.id
            extensions.get(id).getFeedData(loadFeed)
        }
    }

    override suspend fun loadHomeFeed() = feed<HomeFeedClient> { loadHomeFeed() }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return feed<SearchFeedClient> { loadSearchFeed(query) }
    }

    val db = Room.databaseBuilder(
        context, UnifiedDatabase::class.java, "unified-db"
    ).fallbackToDestructiveMigration(true).build()

    private suspend fun getCached() = cache.keys.mapNotNull {
        val key = context.getFromCache<String>(it, "player")
        key?.toKey()?.getOrNull()
    }.reversed().groupBy { it.trackId }.mapNotNull {
        var (id, _, extId) = it.value.first()
        val state = Cached.getMedia<Track>(app, extId, id).getOrNull()
            ?: return@mapNotNull null
        if (extId == UNIFIED_ID) extId = state.item.extras[EXTENSION_ID] ?: return@mapNotNull null
        val client = extensions().getOrNull(extId)?.instance?.value
        state.item.withExtensionId(extId, client, true)
    }

    private var cachedTracks = listOf<Track>()
    private fun cachePlaylist() = if (cachedTracks.isNotEmpty()) Playlist(
        id = "cached",
        title = context.getString(R.string.cached_songs),
        isEditable = false,
        cover = cachedTracks.first().cover,
        description = context.getString(R.string.cache_playlist_warning),
        trackCount = cachedTracks.size.toLong(),
        extras = mapOf(EXTENSION_ID to UNIFIED_ID)
    ) else null

    val downloadFeed = MutableStateFlow(listOf<EchoMediaItem>())
    override suspend fun loadLibraryFeed() = Feed(
        listOf(Tab("Unified", context.getString(R.string.all))) + extensions().map {
            Tab(it.id, it.name)
        }
    ) { tab ->
        val extension = extensions().getOrNull(tab?.id)
        extension?.getFeedData<LibraryFeedClient> { loadLibraryFeed() } ?: run {
            PagedData.Single {
                cachedTracks = getCached()
                listOfNotNull(
                    Shelf.Category(
                        "saved",
                        context.getString(R.string.saved),
                        context.getFeed(db.getSaved())
                    ),
                    Shelf.Category(
                        "downloads",
                        context.getString(R.string.downloads),
                        context.getFeed(downloadFeed.value)
                    ),
                    cachePlaylist()?.toShelf()
                ) + db.getCreatedPlaylists().map { it.toShelf() }
            }.toFeedData()
        }
    }

    override suspend fun loadRadio(radio: Radio): Radio {
        val id = radio.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<RadioClient, Radio> {
            loadRadio(radio).withExtensionId(id, this)
        }
    }

    override suspend fun loadTracks(radio: Radio): Feed<Track> {
        val id = radio.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<RadioClient, Feed<Track>> {
            this.loadTracks(radio).injectExtension(extension)
        }
    }

    override suspend fun radio(item: EchoMediaItem, context: EchoMediaItem?): Radio {
        val id = item.extras.extensionId
        return extensions().get(id).client<RadioClient, Radio> {
            radio(item, context).withExtensionId(id, this)
        }
    }

    /**
     * ⚠️ KNOWN DEFECT, RECORDED 2026-09-07, NOT YET FIXED: `track.extras.extensionId` below THROWS FOR
     * EVERY RESTORED TRACK, and it has been doing so since 2026-07-26.
     *
     * A queue is persisted through ResumptionUtils, which stores `QueueEntry(s.item.toSlim(), s.extensionId)`.
     * History's Track.toSlim() sets `extras = emptyMap()` (HistoryEntity.kt), so the track's own
     * `extension_id` stamp is destroyed on save. The id survives BESIDE the track in the QueueEntry and is
     * restored into MediaState.Unloaded — which is why `mediaItem.extensionId` is correct everywhere — but
     * nothing ever re-stamps the Track itself. Introduced by 92af04f5 (2026-07-26), whose own comment says
     * "toSlim dropping streamables/extras/nested": the loss was stated and not traced. The 2026-08-10 OOM
     * hoist (de6d344b) only re-routed the surviving copy and is innocent.
     *
     * ⚠️ WHY NOBODY NOTICED FOR MONTHS: the throw is CAUGHT AND HIDDEN. Cached.loadMedia wraps this call
     * and its `result.getOrElse { getMedia(...).getOrNull() ?: throw it }` serves the previously-cached
     * MediaState.Loaded instead. So a restored Unified queue plays fine — from cache — and the failure is
     * invisible. It also means restored tracks are never re-resolved, which is faster and works offline,
     * entirely by accident.
     *
     * The same missing stamp surfaces UNCAUGHT on other paths, which is how it was finally found:
     * UnifiedExtension.radio (a non-fatal ExtensionNotFoundException("null") from PlayerRadio.loadPlaylist
     * on a cold start restoring a one-item queue with auto-radio on) and loadFeed(track).
     *
     * ⚠️ FIXING THE STAMP ALONE WOULD REGRESS OFFLINE. Today the throw happens BEFORE any I/O, so the
     * cache fallback is instant. With a correct id this would attempt the sub-extension's network call
     * first and only fall back after its timeouts (Deezer: connect 15s + read 10s), inside the buffering
     * path. The fix therefore needs the cache-first ordering made EXPLICIT at Cached.loadMedia's playback
     * call site, not just the stamp restored.
     */
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val cached = track.extras["cached"]?.toBoolean() ?: false
        if (cached) return track
        val id = track.extras.extensionId
        return extensions().get(id).client<TrackClient, Track> {
            loadTrack(track, isDownload).withExtensionId(id, this)
        }
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, isDownload: Boolean,
    ): Streamable.Media {
        val id = streamable.extras.extensionId
        return extensions().get(id).client<TrackClient, Streamable.Media> {
            loadStreamableMedia(streamable, isDownload)
        }
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        val id = track.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<TrackClient, Feed<Shelf>?> {
            loadFeed(track)?.injectExtensionId(extension)
        }
    }

    override suspend fun loadAlbum(album: Album): Album {
        val id = album.extras.extensionId
        return extensions().get(id).client<AlbumClient, Album> {
            loadAlbum(album).withExtensionId(id, this)
        }
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val id = album.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<AlbumClient, Feed<Track>?> {
            loadTracks(album)?.injectExtension(extension)
        }
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? {
        val id = album.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<AlbumClient, Feed<Shelf>?> {
            loadFeed(album)?.injectExtensionId(extension)
        }
    }

    override suspend fun loadArtist(artist: Artist): Artist {
        val id = artist.extras.extensionId
        return extensions().get(id).client<ArtistClient, Artist> {
            loadArtist(artist).withExtensionId(id, this)
        }
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val id = artist.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<ArtistClient, Feed<Shelf>> {
            loadFeed(artist).injectExtensionId(extension)
        }
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val extId = playlist.extras.extensionId
        return if (extId == UNIFIED_ID) {
            if (playlist.id == "cached") cachePlaylist() ?: playlist else db.loadPlaylist(playlist)
        } else extensions().get(extId).client<PlaylistClient, Playlist> {
            loadPlaylist(playlist).withExtensionId(extId, this)
        }
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val id = playlist.extras.extensionId
        return if (id == UNIFIED_ID) PagedData.Single {
            if (playlist.id == "cached") cachedTracks
            else db.getTracks(playlist)
        }.toFeed()
        else {
            val extension = extensions().get(id)
            extension.client<PlaylistClient, Feed<Track>> {
                loadTracks(playlist).injectExtension(extension)
            }
        }
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? {
        val id = playlist.extras.extensionId
        val extension = if (id != UNIFIED_ID) extensions().get(id) else return null
        return extension.client<PlaylistClient, Feed<Shelf>?> {
            loadFeed(playlist)?.injectExtensionId(extension)
        }
    }

    override suspend fun saveToLibrary(item: EchoMediaItem, shouldSave: Boolean) {
        if (shouldSave) db.save(item) else db.deleteSaved(item)
    }

    override suspend fun isItemSaved(item: EchoMediaItem): Boolean {
        return db.isSaved(item)
    }

    override suspend fun listEditablePlaylists(track: Track?) = db.getCreatedPlaylists().map {
        val has = db.getTracks(it).any { t -> t.id == track?.id }
        it to has
    }

    override suspend fun createPlaylist(title: String, description: String?): Playlist {
        return db.createPlaylist(title, description)
    }

    val coverDir = context.filesDir.resolve("unified-playlist-covers")
    override suspend fun deletePlaylist(playlist: Playlist) {
        db.deletePlaylist(playlist)
        File(coverDir, playlist.id).let {
            if (it.exists()) it.delete()
        }
    }

    override suspend fun editPlaylistMetadata(
        playlist: Playlist, title: String, description: String?
    ) {
        db.editPlaylistMetadata(playlist, title, description)
    }

    override suspend fun editPlaylistCover(
        playlist: Playlist, cover: File?
    ) {
//        coverDir.listFiles {
//            it.nameWithoutExtension == playlist.id
//        }?.firstOrNull()?.delete()
//        val savedFile = cover?.let {
//            val newFile = File(coverDir, playlist.id + "."+ it.extension)
//            it.copyTo(newFile, true)
//        }
        db.editPlaylistCover(playlist, cover)
    }

    override suspend fun addTracksToPlaylist(
        playlist: Playlist, tracks: List<Track>, index: Int, new: List<Track>,
    ) {
        db.addTracksToPlaylist(playlist, index, new)
    }

    override suspend fun removeTracksFromPlaylist(
        playlist: Playlist, tracks: List<Track>, indexes: List<Int>,
    ) {
        db.removeTracksFromPlaylist(playlist, tracks, indexes)
    }

    override suspend fun moveTrackInPlaylist(
        playlist: Playlist, tracks: List<Track>, fromIndex: Int, toIndex: Int,
    ) {
        db.moveTrack(playlist, fromIndex, toIndex)
    }

    override suspend fun searchTrackLyrics(clientId: String, track: Track): Feed<Lyrics> {
        val extId = track.extras.extensionId
        val extension = extensions().get(extId)
        return extension.clientOrNull<LyricsClient, Feed<Lyrics>> {
            searchTrackLyrics(clientId, track).injectLyricsExtId(extension)
        } ?: listOf<Lyrics>().toFeed()
    }

    override suspend fun loadLyrics(lyrics: Lyrics): Lyrics {
        val extId = lyrics.extras.extensionId
        return extensions().get(extId).client<LyricsClient, Lyrics> {
            loadLyrics(lyrics).withExtensionId(extId)
        }
    }

    private var current: Track? = null
    /**
     * The tracker callbacks' extension lookup: skips tracking for an unstamped track, and SAYS SO.
     *
     * ⚠️ SKIPPING, NOT THROWING: tracking is telemetry, and a missing extension_id stamp is a
     * known-and-fixed condition (ResumptionUtils.restamped), so it must not surface to the user
     * mid-playback. Until 2026-09-07 two of the four callbacks had a `?: return` that could never fire —
     * see the pattern note on extensionIdOrNull — and two had no guard at all, so this threw instead.
     *
     * ⚠️ AND NOT SILENTLY, WHICH IS THE POINT. The house rule from the swallowed-exception sweep is that
     * a deliberate best-effort skip carries a rationale at the narrowest scope AND a log line; silence was
     * explicitly rejected as the resolution. The cautionary case is tryWithSuspend returning null
     * "normally" for a cancellation, which destroyed the distinction the caller needed — "cancelled" and
     * "genuinely no image" were both null. The same risk applies here: a silent skip is indistinguishable
     * from an extension that simply has no tracker, and this defect already hid for six weeks precisely
     * because a site LOOKED guarded. A log line keeps it visible without competing for Crashlytics
     * attention, which a known-and-fixed condition does not deserve.
     */
    private fun TrackDetails.trackerExtensionId(callback: String): String? =
        track.extras.extensionIdOrNull ?: run {
            Log.d(
                "UnifiedTracker",
                "skipping $callback: no extension_id stamp on track id=${track.id} \"${track.title}\""
            )
            null
        }

    override suspend fun onTrackChanged(details: TrackDetails?) {
        current = details?.track
        val id = details?.trackerExtensionId("onTrackChanged") ?: return
        val extension = extensions().get(id)
        extension.clientOrNull<TrackerClient, Unit> { onTrackChanged(details) }
    }

    override suspend fun onMarkAsPlayed(details: TrackDetails) {
        val id = details.trackerExtensionId("onMarkAsPlayed") ?: return
        val extension = extensions().get(id)
        extension.clientOrNull<TrackerMarkClient, Unit> { onMarkAsPlayed(details) }
    }

    override suspend fun onPlayingStateChanged(details: TrackDetails?, isPlaying: Boolean) {
        val id = details?.trackerExtensionId("onPlayingStateChanged") ?: return
        val extension = extensions().get(id)
        extension.clientOrNull<TrackerClient, Unit> { onPlayingStateChanged(details, isPlaying) }
    }

    override suspend fun getMarkAsPlayedDuration(details: TrackDetails): Long? {
        val id = details.trackerExtensionId("getMarkAsPlayedDuration") ?: return null
        val extension = extensions().get(id)
        return extension.clientOrNull<TrackerMarkClient, Long?> { getMarkAsPlayedDuration(details) }
    }

    override suspend fun onShare(item: EchoMediaItem): String {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<ShareClient, String> { onShare(item) }
    }

    override suspend fun isFollowing(item: EchoMediaItem): Boolean {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<FollowClient, Boolean> { isFollowing(item) }
    }

    override suspend fun getFollowersCount(item: EchoMediaItem): Long? {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<FollowClient, Long?> { getFollowersCount(item) }
    }

    override suspend fun followItem(item: EchoMediaItem, shouldFollow: Boolean) {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        extension.client<FollowClient, Unit> { followItem(item, shouldFollow) }
    }

    override suspend fun likeItem(item: EchoMediaItem, shouldLike: Boolean) {
        if (item !is Track) throw ClientException.NotSupported("LikeItem only supports Track")
        val likedPlaylist = db.getLikedPlaylist(context)
        val tracks = loadTracks(likedPlaylist).loadAll()
        if (shouldLike) addTracksToPlaylist(likedPlaylist, tracks, 0, listOf(item))
        else removeTracksFromPlaylist(
            likedPlaylist, tracks, listOf(tracks.indexOfFirst { it.id == item.id })
        )
        // A like MUTATES THE LIKED PLAYLIST, so its durable tracks entry is now stale. No app-side call
        // site can do this: only this extension knows which playlist db.getLikedPlaylist resolved to, and
        // the app's like path (PlayerCallback.onSetRating / MediaDetailsViewModel.likeItem) sees only a
        // Track. Without it, liking a song left the Liked playlist missing that song for up to 24h.
        // Reaching into the app cache from an extension is a layering inversion in general; it is
        // acceptable HERE because UnifiedExtension is a built-in, lives in the app module, and already
        // holds `app`. A third-party extension cannot do this and must not need to - every OTHER mutation
        // path is busted app-side (SaveToPlaylistViewModel, EditPlaylistViewModel), which covers all
        // extensions. This site exists only because the target playlist is chosen inside the extension.
        Cached.bustPlaylistTracksCache(app, likedPlaylist.id)
    }

    override suspend fun isItemLiked(item: EchoMediaItem): Boolean {
        if (item !is Track) throw ClientException.NotSupported("IsItemLiked only supports Track")
        return db.isLiked(item)
    }

    override suspend fun hideItem(item: EchoMediaItem, shouldHide: Boolean) {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        extension.client<HideClient, Unit> { hideItem(item, shouldHide) }
    }

    override suspend fun isItemHidden(item: EchoMediaItem): Boolean {
        val id = item.extras.extensionId
        val extension = extensions().get(id)
        return extension.client<HideClient, Boolean> { isItemHidden(item) }
    }
}