package dev.brahmkshatriya.echo.extensions.cache

import com.mayakapps.kache.FileKache
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.FollowClient
import dev.brahmkshatriya.echo.common.clients.HideClient
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SaveClient
import dev.brahmkshatriya.echo.common.clients.ShareClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.exceptions.MediaUnavailableException
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getAs
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getIf
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.history.db.toSlim
import dev.brahmkshatriya.echo.utils.CacheUtils
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class CachedPlaylistTracks(val savedAtMs: Long, val tracks: List<Track>)

object Cached {
    class NotFound(id: String) : Exception("Cache not found for $id")

    private const val DURABLE_PLAYLIST_FOLDER = "playlist-tracks"
    private const val PLAYLIST_TRACKS_TTL_MS = 24L * 60 * 60 * 1000  // 24h

    // History's toSlim drops ALL extras; a playlist track's NEXT / playlist_id are read by DeezerUtil.log
    // (play-logging context), so preserve exactly those two while dropping streamables + everything heavy.
    private fun Track.toPlaylistSlim(): Track =
        toSlim().copy(extras = extras.filterKeys { it == "NEXT" || it == "playlist_id" })

    // Invalidate the durable playlist-tracks entry so the next loadTracks re-fetches (via the canonical
    // re-resolve path) instead of short-circuiting within TTL. Wired to pull-to-refresh + edit "reload".
    fun bustPlaylistTracksCache(app: App, playlistId: String) {
        runCatching {
            val dir = CacheUtils.cacheDir(app.context, DURABLE_PLAYLIST_FOLDER, durable = true)
            File(dir, "$playlistId-tracks".hashCode().toString()).delete()
        }
    }

    suspend inline fun <reified T> FileKache.getData(id: String) = runCatching {
        val file = get(id) ?: throw NotFound(id)
        File(file).readText().toData<T>().getOrThrow()
    }

    suspend inline fun <reified T> FileKache.putData(id: String, data: T) = runCatching {
        put(id) {
            runCatching {
                File(it).writeText(data.toJson())
            }.isSuccess
        }
    }

    suspend inline fun <reified T : EchoMediaItem> getMedia(
        app: App, extensionId: String, itemId: String,
    ) = runCatching {
        val fileCache = app.fileCache.await()
        val id = "media-$extensionId-$itemId-state"
        fileCache.getData<MediaState.Loaded<T>>(id).getOrThrow()
    }

    // Normalize a media id before the round-trip equality check in loadMedia below. Some extensions return a
    // CANONICALIZED form of the id they were asked for; comparing normalized forms tolerates that without
    // weakening the wrong-item guard — two genuinely different ids still differ after normalizing, so
    // cache-poisoning protection is intact — and it is a NO-OP for ids that round-trip unchanged (e.g.
    // Deezer), so exact-match behaviour is preserved identically. Structured as "normalize then compare": add
    // the next known convention as another transform here, not as another guard.
    // Known conventions:
    //   - YouTube Music wraps a playlist id in a "VL" browse prefix: loadItem("VLPLaJ…") legitimately returns
    //     the same playlist with canonical id "PLaJ…". Strip a leading "VL".
    // @PublishedApi internal so the public inline loadMedia can reference it after inlining.
    @PublishedApi
    internal fun canonicalId(id: String) = id.removePrefix("VL")

    suspend inline fun <reified T : EchoMediaItem> loadMedia(
        app: App, extension: Extension<*>, state: MediaState<T>,
    ) = coroutineScope {
        runCatching {
            val result = runCatching {
                val new = loadItem(extension, state.item).getOrThrow()
                // Deleted/unavailable content: some extensions (e.g. Deezer) return a BLANK item (empty id)
                // instead of raising an error when the content no longer exists — a NORMAL condition, not a
                // bug. Surface a friendly, localized "no longer available" message (the header Error holder
                // renders it via getFinalTitle's `?: throwable.message` fallback) rather than the raw
                // wrong-item ISE below. Checked FIRST: a blank id can never legitimately match a real id, so
                // it would otherwise always fall into the mismatch branch. (Still routes through the cache
                // fallback below, so a previously-cached copy is preferred when one exists.)
                if (new.id.isBlank()) throw MediaUnavailableException(
                    app.context.getString(R.string.x_no_longer_available, state.item.title)
                )
                // Compare CANONICAL ids, not raw strings: an extension may legitimately return a canonicalized
                // form of the requested id (see canonicalId — YTM drops its "VL" playlist prefix), and raw
                // equality would false-positive this guard. A genuinely different item still mismatches. The
                // message keeps the RAW ids so a real mismatch stays diagnosable.
                if (canonicalId(new.id) != canonicalId(state.item.id)) error(
                    "loadItem returned wrong item: expected ${state.item.id}, got ${new.id}"
                )
                val isSaved = async {
                    if (new.isSaveable) extension.getIf<SaveClient, Boolean> {
                        isItemSaved(new)
                    } else null
                }
                val isFollowed = async {
                    if (new.isFollowable) extension.getIf<FollowClient, Boolean> {
                        isFollowing(new)
                    } else null
                }
                val followers = async {
                    if (new.isFollowable) extension.getIf<FollowClient, Long?> {
                        getFollowersCount(new)
                    } else null
                }
                val isLiked = async {
                    if (new.isLikeable) extension.getIf<LikeClient, Boolean> {
                        isItemLiked(new)
                    } else null
                }
                val isHidden = async {
                    if (new.isHideable) extension.getIf<HideClient, Boolean> {
                        isItemHidden(new)
                    } else null
                }
                val newState = MediaState.Loaded(
                    item = new,
                    extensionId = extension.id,
                    isSaved = isSaved.await()?.getOrThrow(),
                    isFollowed = isFollowed.await()?.getOrThrow(),
                    followers = followers.await()?.getOrThrow(),
                    isLiked = isLiked.await()?.getOrThrow(),
                    isHidden = isHidden.await()?.getOrThrow(),
                    showRadio = new.isRadioSupported && extension.isClient<RadioClient>(),
                    showShare = new.isShareable && extension.isClient<ShareClient>(),
                )
                val fileCache = app.fileCache.await()
                val id = "media-${extension.id}-${newState.item.id}-state"
                fileCache.putData(id, newState)
                newState
            }
            result.getOrElse {
                getMedia<T>(app, extension.id, state.item.id).getOrNull()
                    ?: throw it
            }
        }
    }

    suspend inline fun <reified T : EchoMediaItem> loadItem(
        extension: Extension<*>, item: T,
    ) = runCatching {
        when (item) {
            is Artist -> extension.getAs<ArtistClient, Artist> { loadArtist(item) }
            is Album -> extension.getAs<AlbumClient, Album> { loadAlbum(item) }
            is Playlist -> extension.getAs<PlaylistClient, Playlist> { loadPlaylist(item) }
            is Track -> extension.getAs<TrackClient, Track> { loadTrack(item, false) }
            is Radio -> extension.getAs<RadioClient, Radio> { loadRadio(item) }
        }.getOrThrow() as T
    }

    suspend fun loadStreamableMedia(
        app: App, extension: Extension<*>, track: Track, streamable: Streamable,
    ) = extension.getAs<TrackClient, Streamable.Media> {
        loadStreamableMedia(streamable, false)
    }

    suspend fun getTracks(
        app: App, extensionId: String, item: EchoMediaItem,
    ) = runCatching {
        if (item !is EchoMediaItem.Lists) return@runCatching null
        val itemId = item.id
        // Playlist tracks live in an isolated durable store (canonical re-resolve is expensive). Instant
        // cached read, age-agnostic; loadTracks owns the TTL/revalidate decision.
        if (item is Playlist) {
            val cached = app.context.getFromCache<CachedPlaylistTracks>(
                "$itemId-tracks", DURABLE_PLAYLIST_FOLDER, durable = true
            )
            if (cached != null) return@runCatching cached.tracks.toFeed()
        }
        getFeed<Track>(app, extensionId, "$itemId-tracks") { it }
    }

    suspend fun loadTracks(app: App, extension: Extension<*>, item: EchoMediaItem) = runCatching {
        if (item is Playlist) return@runCatching loadPlaylistTracksCached(app, extension, item)
        val feed = when (item) {
            is Album -> extension.getAs<AlbumClient, Feed<Track>?> { loadTracks(item) }
            is Radio -> extension.getAs<RadioClient, Feed<Track>> { loadTracks(item) }
            is Artist -> null
            is Track -> null
            is Playlist -> null // handled above
        }?.getOrThrow() ?: return@runCatching null
        savingFeed(app, extension, "${item.id}-tracks", feed)
    }

    // Durable SWR on top of the canonical re-resolve. Within TTL: pure short-circuit (no fetch, no
    // revalidate). Otherwise fetch via the extension (the song.getListData re-resolve path), materialize
    // (Deezer = PagedData.Single → one page), store slim {now, tracks}, and return the fresh tracks.
    private suspend fun loadPlaylistTracksCached(
        app: App, extension: Extension<*>, item: Playlist,
    ): Feed<Track> {
        val cached = app.context.getFromCache<CachedPlaylistTracks>(
            "${item.id}-tracks", DURABLE_PLAYLIST_FOLDER, durable = true
        )
        if (cached != null && System.currentTimeMillis() - cached.savedAtMs < PLAYLIST_TRACKS_TTL_MS)
            return cached.tracks.toFeed()

        val feed = extension.getAs<PlaylistClient, Feed<Track>> { loadTracks(item) }.getOrThrow()
        val tracks = feed.loadAll()
        app.context.saveToCache(
            "${item.id}-tracks",
            CachedPlaylistTracks(System.currentTimeMillis(), tracks.map { it.toPlaylistSlim() }),
            DURABLE_PLAYLIST_FOLDER, durable = true
        )
        return tracks.toFeed()
    }

    suspend fun getFeed(
        app: App, extensionId: String, item: EchoMediaItem,
    ) = run {
        if (item !is EchoMediaItem.Lists) return@run null
        val itemId = item.id
        getFeedShelf(app, extensionId, itemId)
    }

    suspend fun loadFeed(app: App, extension: Extension<*>, item: EchoMediaItem) = runCatching {
        val feed = when (item) {
            is Artist -> extension.getAs<ArtistClient, Feed<Shelf>> { loadFeed(item) }
            is Album -> extension.getAs<AlbumClient, Feed<Shelf>?> { loadFeed(item) }
            is Playlist -> extension.getAs<PlaylistClient, Feed<Shelf>?> { loadFeed(item) }
            is Track -> extension.getAs<TrackClient, Feed<Shelf>?> { loadFeed(item) }
            is Radio -> null
        }?.getOrThrow() ?: return@runCatching null
        savingFeed(app, extension, item.id, feed)
    }

    suspend fun loadLyrics(app: App, extension: Extension<*>, lyrics: Lyrics) = runCatching {
        val fileCache = app.fileCache.await()
        val id = "lyrics-${extension.id}-${lyrics.id}"
        val loaded = extension.getAs<LyricsClient, Lyrics> {
            loadLyrics(lyrics)
        }.getOrElse { throwable ->
            fileCache.getData<Lyrics>(id).getOrNull() ?: throw throwable
        }
        fileCache.putData(id, loaded)
        loaded
    }

    suspend fun getLyricsFeed(
        app: App, extensionId: String, clientId: String, track: Track, query: String,
    ) = runCatching {
        val id = if (query.isEmpty()) "lyrics-$clientId-${track.id}" else "lyrics-search-$query"
        getFeed<Lyrics>(app, extensionId, id) { it }
    }

    suspend fun loadLyricsFeed(
        app: App, extension: Extension<*>, clientId: String, track: Track, query: String,
    ) = runCatching {
        val feed = if (query.isEmpty()) extension.getAs<LyricsClient, Feed<Lyrics>> {
            searchTrackLyrics(clientId, track)
        }.getOrThrow() else extension.getAs<LyricsSearchClient, Feed<Lyrics>> {
            searchLyrics(query)
        }.getOrThrow()
        val id = if (query.isEmpty()) "lyrics-$clientId-${track.id}" else "lyrics-search-$query"
        savingFeed(app, extension, id, feed)
    }

    suspend fun getFeedShelf(
        app: App, extensionId: String, feedId: String,
    ): Result<Feed<Shelf>> = runCatching {
        getFeed<Shelf>(app, extensionId, feedId) { shelf ->
            when (shelf) {
                is Shelf.Item -> shelf
                is Shelf.Category -> shelf.copy(
                    feed = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Categories -> shelf.copy(
                    list = shelf.list.map {
                        it.copy(feed = getFeedShelf(app, extensionId, it.id).getOrNull())
                    },
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Items -> shelf.copy(
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )

                is Shelf.Lists.Tracks -> shelf.copy(
                    more = getFeedShelf(app, extensionId, shelf.id).getOrNull()
                )
            }
        }
    }


    // FEED STUFF

    suspend inline fun <reified T : Any> getFeed(
        app: App, extensionId: String, feedId: String, crossinline transform: suspend (T) -> T,
    ): Feed<T> {
        val fileCache = app.fileCache.await()
        val tabId = "feed-$extensionId-$feedId"
        val tabs = fileCache.getData<List<Tab>>(tabId).getOrThrow()
        return Feed(tabs) { tab ->
            val id = "$tabId-${tab?.id}"
            val (buttons, bg) = fileCache.getData<Pair<Feed.Buttons?, ImageHolder?>>(id)
                .getOrThrow()
            PagedData.Continuous { token ->
                val id = "$id-$token"
                val page = fileCache.getData<Page<T>>(id).getOrThrow()
                page.copy(page.data.map { transform(it) })
            }.toFeedData(buttons, bg)
        }
    }

    suspend inline fun <reified T : Any> savingFeed(
        app: App, extension: Extension<*>, feedId: String, feed: Feed<T>,
    ): Feed<T> {
        val fileCache = app.fileCache.await()
        val tabId = "feed-${extension.id}-$feedId"
        fileCache.putData(tabId, feed.tabs)
        return Feed(feed.tabs) { tab ->
            val data = runCatching {
                feed.getPagedData(tab)
            }.getOrElse {
                throw it.toAppException(extension)
            }
            val (pagedData, buttons, bg) = data
            val id = "$tabId-${tab?.id}"
            fileCache.putData(id, Pair(buttons, bg))
            PagedData.Continuous { token ->
                val page = runCatching {
                    pagedData.loadPage(token)
                }.getOrElse {
                    throw it.toAppException(extension)
                }
                val id = "$id-$token"
                fileCache.putData(id, page)
                page
            }.toFeedData(buttons, bg)
        }
    }

}