package dev.brahmkshatriya.echo.playback

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.CallSuper
import androidx.annotation.OptIn
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isReplayableContext
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.extensions.MediaState
import dev.brahmkshatriya.echo.extensions.builtin.offline.OfflineExtension
import dev.brahmkshatriya.echo.extensions.builtin.unified.UnifiedExtension
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.CoroutineUtils.await
import dev.brahmkshatriya.echo.utils.CoroutineUtils.future
import dev.brahmkshatriya.echo.utils.CoroutineUtils.futureCatching
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverCurrentId
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverIndex
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverTracks
import dev.brahmkshatriya.echo.playback.ResumptionUtils.resolveCurrentIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import androidx.appcompat.content.res.AppCompatResources
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// ── Android Auto self-describing browse id (P4) ──────────────────────────────────────────────────
// A browsed track's play id round-trips through Android Auto as its mediaId. Legacy form is
// "auto/<trackId>" (extension + context live ONLY in the durable "auto/" file cache). The new form
// embeds the extension id + optional context (type/id/title) as base64url(JSON) after "auto/", so a
// cache miss can still re-resolve the item instead of silently dropping it. parseAutoId reads both.
@Serializable
private data class AutoId(
    val t: String,          // track id — also the durable "auto/" cache key
    val e: String? = null,  // extension id
    val ct: String? = null, // context type: album | playlist | radio | artist
    val ci: String? = null, // context id
    val cn: String? = null, // context title (labels the header without a re-fetch)
    val cst: String? = null, // album subtype (Album.Type name) — album-only; preserves Show/Book so a
                             // type-aware extension's loadAlbum branches correctly on a thin cache-miss item.
                             // New optional field; ignoreUnknownKeys makes it safe for old builds to drop.
)

@OptIn(ExperimentalEncodingApi::class)
private fun encodeAutoId(trackId: String, extId: String, con: EchoMediaItem?): String {
    val payload = AutoId(
        t = trackId, e = extId, ct = con?.autoContextType(), ci = con?.id, cn = con?.title,
        cst = (con as? Album)?.type?.name,
    )
    return "auto/" + Base64.UrlSafe.encode(payload.toJson().encodeToByteArray())
}

@OptIn(ExperimentalEncodingApi::class)
private fun parseAutoId(mediaId: String): AutoId? {
    if (!mediaId.startsWith("auto/")) return null
    val payload = mediaId.substringAfter("auto/")
    // New form: base64url(JSON). Legacy "auto/<trackId>": the payload IS the raw track id.
    return runCatching {
        Base64.UrlSafe.decode(payload).decodeToString().toData<AutoId>().getOrThrow()
    }.getOrNull() ?: AutoId(t = payload)
}

private fun EchoMediaItem.autoContextType(): String? = when (this) {
    is Album -> "album"
    is Playlist -> "playlist"
    is Radio -> "radio"
    is Artist -> "artist"
    else -> null // Track / others: no re-fetchable collection context
}

// Thin context rebuilt from a mediaId on a cache miss — enough for listTracks/loadAlbum to re-fetch
// fresh (with valid tokens), so #4 recovery matches the History fresh-resolve path.
private fun AutoId.toThinContext(): EchoMediaItem? {
    val id = ci ?: return null
    val title = cn.orEmpty()
    return when (ct) {
        // Carry the album subtype (Show/Book/…) back so a type-aware extension's loadAlbum branches
        // correctly — otherwise a podcast show is handed back as a plain album and re-fetched via the
        // album endpoint. Absent name (old-format id) or a future/unknown Type -> null, same as before.
        "album" -> Album(
            id = id, title = title,
            type = cst?.let { runCatching { Album.Type.valueOf(it) }.getOrNull() }
        )
        "playlist" -> Playlist(id = id, title = title, isEditable = false)
        "radio" -> Radio(id = id, title = title)
        "artist" -> Artist(id = id, name = title)
        else -> null
    }
}

// Thin track rebuilt from the mediaId + the metadata Android Auto round-trips, so a cache-miss item is
// KEPT in the queue (never silently dropped). It loads on play where loadTrack works by id, and error-
// skips visibly where the original token is required (e.g. Deezer) instead of vanishing.
private fun MediaItem.toThinTrack(id: String): Track {
    val md = mediaMetadata
    return Track(
        id = id,
        title = md.title?.toString() ?: id,
        artists = md.artist?.toString()?.let { listOf(Artist(id = "", name = it)) } ?: listOf(),
    )
}

@UnstableApi
abstract class AndroidAutoCallback(
    open val app: App,
    open val scope: CoroutineScope,
    open val extensionList: StateFlow<List<MusicExtension>>,
    open val downloadFlow: StateFlow<List<Downloader.Info>>
) : MediaLibrarySession.Callback {

    val context get() = app.context

    open val throwableFlow: MutableSharedFlow<Throwable>? get() = null
    open val historyRepository: HistoryRepository? = null

    internal val userQueueSet = AtomicBoolean(false)
    @Volatile private var lastSearchQuery = ""
    @Volatile protected var lastBrowsedExtId: String? = null
    private val searchResults = boundedMap<Pair<String, String>, List<MediaItem>>()
    private val searchJobs = boundedMap<String, Job>()
    private val searchMutex = Mutex()
    // ⚠️ THIS JOB'S LIFETIME IS THE WHOLE OF THE REMAINING PROTECTION AGAINST THE JUNE IPC LOOP.
    // START HERE if a notification loop ever reappears, rather than re-deriving it.
    //
    // The June loop ("extensionWatcherJob calling notifyChildrenChanged() to a System UI phantom binding
    // creates an unthrottled IPC loop") had TWO causes and got TWO fixes: this job is cancelled in
    // onDisconnected, AND isPlaybackOngoing() was rekeyed off bare playWhenReady — the latter mattered
    // because it made the 10-minute idle timeout a no-op, so the SERVICE NEVER WENT AWAY and the loop had
    // somewhere to live.
    // THE SECOND FIX IS GONE. The isPlaybackOngoing() override was removed when it became final in 1.10.1,
    // justified by onTaskRemoved() "already correctly handling equivalent behaviour: when CLOSE_PLAYER=false
    // the if block is skipped entirely, service stays alive when paused with a populated queue". That is
    // SERVICE LIFETIME ON TASK REMOVAL. It is a different property from idle-timeout-driven service teardown
    // and has nothing to do with phantom binding, so it does NOT carry the persistence half forward. Stated
    // as fact, not suspicion: the persistence half of the June protection no longer exists.
    //
    // WHAT ACTUALLY BOUNDS IT TODAY — watcher lifetime alone, in two places, both COUNTING watchers rather
    // than rate-limiting notifies:
    //   • `extensionWatcherJob?.cancel()` at the top of onGetLibraryRoot — a re-root REPLACES the watcher
    //     instead of accumulating one per root. This is the one that closes the feedback edge.
    //   • the cancel in onDisconnected — stops a watcher outliving its browser and firing into a dead binding.
    // The feedback edge is real and visible in the DHU capture of 2026-09-07: onGetLibraryRoot at 11:39:35.150
    // produced a notify at 11:39:35.151. One millisecond — that is the StateFlow replaying its current value
    // into the fresh collect. So EVERY RE-ROOT EMITS A NOTIFY IMMEDIATELY, and if a notify can induce a
    // re-bind and re-root through a phantom binding, the cycle closes.
    // With the two cancels the AMPLIFICATION FACTOR IS 1: each re-root yields one notify, never N. The
    // dangerous version was N surviving watchers multiplied by every emission. Bounded, NOT throttled — the
    // notify rate equals the extensionList emission rate, which is user-paced (install / enable / disable).
    private var extensionWatcherJob: Job? = null
    private var pendingSearchJob: Job? = null

    override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        userQueueSet.set(false)
        lastBrowsedExtId = null
        pendingSearchJob?.cancel()
        // Load-bearing, not tidy-up: half of the June IPC-loop protection. See the note on the field — the
        // other half (isPlaybackOngoing keeping the service alive past the idle timeout) is GONE, so watcher
        // lifetime is now the entire bound. Note this fires per RENEGOTIATION, not per connection.
        extensionWatcherJob?.cancel()
        super.onDisconnected(session, controller)
    }

    @CallSuper
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        if (params?.isRecent == true) return scope.futureCatching {
            // recoverTracks() decodes the (unbounded) saved queue. onGetLibraryRoot runs on the app looper, so
            // decode OFF it — a large queue was a synchronous ANR on connect. onGetLibraryRoot already returns
            // a ListenableFuture; AA awaits the resolved root exactly as before, only the decode location moved.
            val tracks = context.recoverTracks()
            val rawIndex = context.recoverIndex() ?: 0
            // Same repair as recoverPlaylist so the AA resume tile shows the true current, not a skewed one.
            val index = resolveCurrentIndex(tracks ?: emptyList(), rawIndex, context.recoverCurrentId()) {
                it.first.item.id
            }
            val track = (tracks?.getOrNull(index) ?: tracks?.firstOrNull())?.first?.item
            LibraryResult.ofItem(
                if (track != null)
                    browsableItem(
                        "recent",
                        track.title,
                        track.subtitleWithE,
                        browsable = true,
                        artWorkUri = track.cover?.toUri(context)
                    )
                else
                    browsableItem("recent", "", browsable = false),
                null
            )
        }
        if (browser.packageName != "com.google.android.projection.gearhead")
            return Futures.immediateFuture(
                LibraryResult.ofItem(browsableItem(ROOT, "", browsable = false), null)
            )
        Log.d("GladixAuto", "onGetLibraryRoot: extensionList.value.size=${extensionList.value.size}, clearing caches, starting watcher")
        // Load-bearing: this replace-don't-accumulate cancel is what keeps the June loop's amplification
        // factor at 1, because the collect below replays the current StateFlow value immediately (measured:
        // root 11:39:35.150 -> notify 11:39:35.151). See the note on extensionWatcherJob.
        extensionWatcherJob?.cancel()
        extensionWatcherJob = scope.launch {
            cacheMutex.withLock {
                clearCaches()
                searchResults.clear()
                // Pairs with heap_used_mb_conn, which is sampled in PlayerCallback.onConnect — i.e.
                // BEFORE this clear, and before onGetLibraryRoot even runs. Without a post-clear sample
                // every conn number in the OOM investigation measures the graph the PREVIOUS session
                // left behind, not what the connect allocates. conn → root drop implicates these caches;
                // no drop moves suspicion to root-build allocation (which force-instantiates every
                // enabled extension via isClient<…>). One sampleHeap, same style as the six existing.
                CrashKeys.onAutoCachesCleared()
            }
            extensionList.collect { extensions ->
                Log.d("GladixAuto", "extensionWatcher: collected ${extensions.size} extensions: ${extensions.map { it.id }}")
                if (extensions.isNotEmpty()) {
                    // ⚠️ DIAGNOSTIC + CANDIDATE FIX, 2026-09-07. DHU capture showed NO onGetChildren(ROOT)
                    // following either of two mid-session notifies (toggle off 11:40:27, toggle on 11:40:34),
                    // 50s after connect, while the connect-time control DID log a ROOT query — and that
                    // control preceded nothing: the notify at :35.151 came a millisecond AFTER the root at
                    // :35.150 and a second BEFORE the query at :36.193, so it was Gearhead's own initial
                    // browse, not a response. So the ROOT notify has delivered nothing, possibly since it was
                    // written.
                    //
                    // TWO CANDIDATE CAUSES, AND THE SILENCE ALONE CANNOT SEPARATE THEM:
                    //   (a) nothing is subscribed to ROOT at all;
                    //   (b) the wrong ControllerInfo was targeted — the SAME defect "recent" already had and
                    //       was fixed for, by switching to the broadcast overload. This call was left in the
                    //       targeted 4-arg form; the "recent" call below is already the 3-arg broadcast.
                    // (b) is live because subscriptions are keyed by ControllerCb, not by package or by
                    // ControllerInfo.equals: MediaLibrarySessionImpl.onSubscribeOnHandler stores
                    // `checkNotNull(browser.getControllerCb())` and isSubscribed does containsEntry on that
                    // map (media3-session 1.11.0, MediaLibrarySessionImpl.java:224-227 and :258-260), while
                    // the dispatch returns silently at :302 when it misses. The browser that ISSUES
                    // onGetLibraryRoot and the one that SUBSCRIBES to ROOT are free to be different objects.
                    //
                    // getSubscribedControllers reads parentIdToSubscribedControllers directly
                    // (MediaLibraryService.java:828), so this settles it by READING the subscription rather
                    // than inferring it from silence. PREDICTIONS, WRITTEN BEFORE THE RUN:
                    //   subscribers=0                      -> cause (a). Broadcast cannot help either; it
                    //     applies the same isSubscribed gate per controller. The ROOT-refresh companion
                    //     scoped at the per-node dispatch is then fiction and must be deleted.
                    //   subscribers>0, includesRootBrowser=false -> cause (b), the "recent" mistake exactly.
                    //     The broadcast swap below IS the fix and a ROOT query should follow each notify.
                    //   subscribers>0, includesRootBrowser=true  -> neither. Subscription and target were
                    //     both right, so the silence has a third cause and needs its own investigation.
                    // Also watch pkgs: if a System-UI-redirected controller appears among ROOT subscribers,
                    // the broadcast swap has opened a dispatch that the targeted form did not — see below.
                    val rootSubs = session.getSubscribedControllers(ROOT)
                    Log.d(
                        "GladixAuto",
                        "extensionWatcher: ROOT subscribers=${rootSubs.size} " +
                            "pkgs=${rootSubs.map { it.packageName }} " +
                            "includesRootBrowser=${rootSubs.any { it == browser }}"
                    )
                    // SWAPPED TARGETED -> BROADCAST as the candidate fix for (b). Revert is one argument.
                    // This opens NO NEW PATH in practice: the "recent" call on the next line is already the
                    // broadcast overload and runs on this same emission, so the loop over
                    // getConnectedControllers and the media-notification -> System UI redirect inside
                    // notifyChildrenChangedOnHandler (MediaLibrarySessionImpl.java:293-298) are exercised on
                    // every emission TODAY. The one residual difference is that the redirect only becomes an
                    // actual IPC when the target is subscribed to THAT parentId, and these two calls carry
                    // different parentIds — which is precisely what the log above measures for ROOT.
                    Log.d("GladixAuto", "extensionWatcher: calling notifyChildrenChanged ROOT count=${extensions.size} (broadcast)")
                    session.notifyChildrenChanged(ROOT, extensions.size, null)
                    session.notifyChildrenChanged("recent", 1, null)
                }
            }
        }
        return Futures.immediateFuture(
            LibraryResult.ofItem(
                browsableItem(ROOT, "", browsable = false),
                null
            )
        )
    }

    @OptIn(UnstableApi::class)
    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> = Futures.immediateFuture(LibraryResult.ofVoid()).also {
        val extras = params?.extras
        val effectiveQuery = listOfNotNull(
            extras?.getString(MediaStore.EXTRA_MEDIA_TITLE),
            extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST),
            extras?.getString(MediaStore.EXTRA_MEDIA_ALBUM),
            query.takeIf { it.isNotEmpty() }
        ).firstOrNull() ?: query
        Log.d("GladixAuto", "onSearch: rawQuery='$query' effectiveQuery='$effectiveQuery'")
        lastSearchQuery = effectiveQuery
        val extId = getCurrentExtension()?.id ?: ""
        val cacheKey = query to extId
        val cached = searchResults[cacheKey]
        if (cached != null) {
            session.notifySearchResultChanged(browser, query, cached.size, params)
        }
        val existing = searchJobs[query]
        if (existing != null && existing.isActive) {
            Log.d("GladixAuto", "onSearch: joining existing in-flight search for query='$query'")
            scope.launch {
                runCatching {
                    existing.join()
                    val tracks = searchResults[cacheKey]
                    if (tracks != null) {
                        Log.d("GladixAuto", "onSearch: notifySearchResultChanged (joined) query='$query' count=${tracks.size}")
                        session.notifySearchResultChanged(browser, query, tracks.size, params)
                    }
                }.onFailure {
                    if (it is CancellationException) throw it
                    throwableFlow?.emit(it)
                    it.printStackTrace()
                }
            }
            return@also
        }
        pendingSearchJob?.cancel()
        pendingSearchJob = scope.launch {
            delay(300)
            searchJobs[query] = coroutineContext[Job]!!
            runCatching { performSearch(effectiveQuery) }
                .onSuccess { tracks ->
                    searchResults[cacheKey] = tracks
                    searchJobs.remove(query)
                    Log.d("GladixAuto", "onSearch: notifySearchResultChanged query='$query' count=${tracks.size}")
                    session.notifySearchResultChanged(browser, query, tracks.size, params)
                }
                .onFailure {
                    searchJobs.remove(query)
                    if (it is CancellationException) throw it
                    throwableFlow?.emit(it)
                    it.printStackTrace()
                }
        }
    }

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.futureCatching {
        val extensions = if (parentId == ROOT) {
            // Raised from 10s, and no longer a hard cliff. Until the CombinedRepository sentinel fix
            // this predicate was satisfied within milliseconds by the built-ins, so the timeout never
            // realistically fired; it now genuinely waits for the ImportType.App package scan.
            // NOTE: degrading to a "built-ins only" list is NOT available here — during the scan
            // `music` is empty (ExtensionLoader.injected maps the null sentinel to emptyList), and the
            // built-ins live privately inside CombinedRepository rather than being published
            // separately. So the only safe degradations are to wait longer and to re-read the value
            // once on expiry (which catches the narrow race where it lands as the timeout fires).
            // The scan is one-time, bounded and off the main thread; the headroom exists for budget
            // eMMC hardware. Expiring still yields the explicit "timed out" tile rather than an empty
            // root, because an empty root reads to the user as "this app has nothing in it".
            withTimeoutOrNull(30_000L) { extensionList.first { it.isNotEmpty() } }
                ?: extensionList.value.takeIf { it.isNotEmpty() }
                ?: return@futureCatching LibraryResult.ofError(
                    SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_timed_out))
                )
        } else extensionList.value
        if (parentId == ROOT) {
            val enabled = extensions.filter { it.isEnabled && it.id != UnifiedExtension.UNIFIED_ID }
            Log.d("GladixAuto", "onGetChildren ROOT: extensionList.first size=${extensions.size} enabled=${enabled.size}, ids=${enabled.map { it.id }}")
            return@futureCatching LibraryResult.ofItemList(
                enabled.map { it.toMediaItem(context) },
                null
            )
        }
        if (parentId == "recent") {
            // Read from live player state first to avoid the race between saveQueue() (async
            // in onTimelineChanged) and notifyChildrenChanged("recent") (sync in
            // onMediaItemTransition) — recoverTracks() may still hold the previous extension's
            // queue when AA calls onGetChildren("recent") after a cross-extension switch.
            val liveItem = withContext(Dispatchers.Main) { session.player.currentMediaItem }
            val liveTrack = runCatching { liveItem?.track }.getOrNull()
            val liveExtId = runCatching { liveItem?.extensionId }.getOrNull()
            if (liveTrack != null && liveExtId != null) {
                return@futureCatching LibraryResult.ofItemList(
                    ImmutableList.of(liveTrack.toItem(context, liveExtId)),
                    null
                )
            }
            val tracks = context.recoverTracks()
            val rawIndex = context.recoverIndex() ?: 0
            val index = resolveCurrentIndex(tracks ?: emptyList(), rawIndex, context.recoverCurrentId()) {
                it.first.item.id
            }
            val (state, _) = tracks?.getOrNull(index) ?: tracks?.firstOrNull()
                ?: return@futureCatching LibraryResult.ofItemList(ImmutableList.of(), null)
            return@futureCatching LibraryResult.ofItemList(
                ImmutableList.of(state.item.toItem(context, state.extensionId)),
                null
            )
        }
        // ⚠️ KNOWN GAP, RECORDED 2026-09-07, DELIBERATELY NOT FIXED HERE. This per-node dispatch
        // resolves extId against the RAW list: `extensions` is extensionList.value verbatim (the else-branch
        // at the top of onGetChildren), while the ROOT branch just above filters
        // `isEnabled && id != UNIFIED_ID`. This branch filters NEITHER. So a parentId of "<root>/unified/..."
        // browses Unified inside Android Auto, and "<root>/<disabled-ext>/..." browses an extension the user
        // has turned off. The next line compounds it: lastBrowsedExtId is written from this same unfiltered
        // parse — see the gap note on getCurrentExtension below and its twin in PlayerCallback.
        //
        // LINE DRIFT, FOR ANYONE MATCHING THIS AGAINST THE OLDER RECORD: the earlier session that found this
        // cited AndroidAutoCallback:371/:375/:377. Same three statements, moved; they are :392/:393/:394 at
        // HEAD 2026-09-07 and they live in AndroidAutoCallback.onGetChildren. Navigate by the symbol.
        //
        // ⚠️ REACHABLE ON A CURRENT BUILD, WITH NO VERSION HISTORY INVOLVED. A head unit caches the
        // browse tree it was served; the user then disables that extension. The cached node is still on
        // screen and still routes here. Worth stating explicitly, because the ORIGINAL INCOMPLETE FIX (May:
        // the guard that filtered the root listing and getCurrentExtension) was reasoned about purely in
        // terms of OLD builds' stale trees, which made the residue look like it would age out. It does not
        // age out. Disable-while-cached regenerates it on demand, on the newest build there is.
        //   Read with the ROOT watcher above, which is the partial mitigation and NOT a fix:
        //   onGetLibraryRoot collects extensionList and fires notifyChildrenChanged(browser, ROOT, …), so a
        //   disable that re-emits the flow WHILE A SESSION IS CONNECTED does invalidate the ROOT tile.
        //   It invalidates ONLY ROOT. Nothing invalidates "<root>/<extId>/…", so a user already inside that
        //   subtree, or a head unit restoring the deep node it was last on, still routes straight here.
        //   INFERENCE, not read from source: that Gearhead retains deeper nodes across a ROOT invalidation,
        //   and that toggling enablement re-emits `music` at all. Neither was verified; the gap does not
        //   depend on either, since the deep-node path is uncovered whichever way both resolve.
        //
        // ⚠️ WHY THIS KEEPS RECURRING — THE FREQUENCY-DROP TRAP. THIS IS THE THIRD APPEARANCE OF
        // THE SHAPE. The May guard cut the crash rate far enough that Crashlytics AUTO-RESOLVED the issue,
        // the auto-resolution was read as a completed fix, and this dispatch was never touched. Both gaps
        // recorded now are QUIETER STILL, because neither one crashes at all: this line serves a perfectly
        // working browse of the wrong extension, and getCurrentExtension tier 1 serves a perfectly working
        // search against a disabled one. THERE IS NOTHING FOR CRASHLYTICS TO COUNT. Absence of reports is
        // therefore not evidence of absence here, and a falling graph must never be read as a fix landing.
        //
        // ⚠️ AND WHY A PRODUCER-SIDE FILTER NEEDS EVERY CONSUMER CHECKED. Both patterns are live
        // in this codebase and only one is safe by construction:
        //   SAFE     — UnifiedExtension.extensions() is filtered ONCE at its producer (setMusicExtensions,
        //              `id != UNIFIED_ID && metadata.isEnabled`). All ~25 `extensions().get(id)` consumers
        //              inherit that and CANNOT opt out; a new consumer is safe without knowing the rule.
        //   NOT SAFE — extensionList.value is the unfiltered flow, filtered at SOME call sites (the ROOT
        //              branch, aaEligible) and not at others (here). Every new consumer is a fresh chance to
        //              forget, and forgetting is silent.
        // A filter applied at a call site is a convention, not a guarantee. Enumerate the consumers.
        //
        // Fix scoped but not taken: one predicate here changes what a head unit sees for a node it is still
        // displaying, so it belongs in its own pass alongside the tier-1 change. UNSHIPPED for want of a way
        // to watch a head unit do it — the scoping below is the record of that pass, not a description of
        // code that exists.
        //
        // ── THE COMPANION, AND WHY IT IS NOT JUST "ADD THE PREDICATE" ──
        // Rejecting here returns auto_error_loading for a tile the head unit is STILL DISPLAYING, and the
        // tile does not go away: it came from a cached parent that nothing invalidates, so it fails again on
        // every tap, forever. Today the tap WORKS on a disabled extension; after the predicate it visibly
        // fails and stays failing, which for the disable-while-cached case is arguably worse than the bug.
        // The companion is therefore a ROOT refresh on the rejection, so the failure is self-clearing.
        // Falling back to the ROOT LISTING instead was considered and rejected: returning ROOT children
        // under a "<root>/<extId>/…" parentId leaves the breadcrumb naming the dead extension while the list
        // shows every other one, and the back stack then holds a node whose contents contradict its title.
        //
        // ⚠️ GATE EVERYTHING BELOW ON ONE CHEAP CHECK FIRST — THE EXISTING ROOT NOTIFY MAY BE A NO-OP.
        // From MediaLibrarySessionImpl.notifyChildrenChangedOnHandler (media3-session 1.11.0,
        // MediaLibrarySessionImpl.java:301): the dispatch ends in `if (!isSubscribed(callback, parentId))
        // return;`. A targeted notify to a controller NOT SUBSCRIBED to that parentId is a SILENT NO-OP —
        // no error, no log, nothing. That is exactly the "recent" defect one node over, now read in source
        // rather than inferred. So if Gearhead never subscribes to ROOT, the extensionWatcher notify in
        // onGetLibraryRoot has done nothing since it was written, the ROOT-invalidation mitigation described
        // above DOES NOT EXIST, and this whole companion is moot.
        //   NO NEW LOGGING IS NEEDED to settle it — both sides already log:
        //     "extensionWatcher: calling notifyChildrenChanged ROOT count=…"  (onGetLibraryRoot's watcher)
        //     "onGetChildren ROOT: extensionList.first size=…"                (the ROOT branch above)
        //   Procedure: connect AA, then TOGGLE AN EXTENSION IN THE APP WHILE STILL CONNECTED (a LATER
        //   emission — the first one fires adjacent to the initial ROOT query and cannot discriminate).
        //   PREDICTIONS, STATED BEFORE THE RUN so the log cannot come out "unclear":
        //     a "onGetChildren ROOT" line within ~1s of the notify -> Gearhead IS subscribed, the notify
        //       lands, the mitigation is real, and the companion below is worth building.
        //     NO ROOT line, only the notify                        -> isSubscribed is false, the :256 notify
        //       has always been a no-op, and BOTH the mitigation in the reachability note above and this
        //       companion are fiction. Delete them and reason about the gap as permanently uncleared.
        //
        // If it lands, the companion is THREE parts, not one:
        // (a) THE OVERLOAD MUST BE THE TARGETED ONE, AND THE "recent" PRECEDENT POINTS THE WRONG WAY HERE.
        //     Both overloads funnel into the same per-controller path, so the broadcast form is NOT "spray at
        //     everyone" — it loops getConnectedControllers() and each delivery still passes the isSubscribed
        //     gate (MediaLibrarySessionImpl.java:271-281). But that loop includes the media notification
        //     controller, and notifyChildrenChangedOnHandler REDIRECTS that one to getSystemUiControllerInfo()
        //     (MediaLibrarySessionImpl.java:293-298). So BROADCAST IS THE OVERLOAD THAT CAN REACH THE SYSTEM
        //     UI PHANTOM BINDING; a targeted notify to the Gearhead browser cannot. The "recent" fix switched
        //     TO broadcast; do not carry that lesson here, it inverts.
        // (b) TARGET THE ROOT-SUBSCRIBED BROWSER, NOT THE ONE THAT ISSUED THE FAILING REQUEST. Whether the
        //     browser arriving at onGetChildren is the same ControllerInfo that subscribed to ROOT is
        //     unanswered — and does not need answering: capture the browser from onGetLibraryRoot (today it
        //     is only closure-captured by extensionWatcherJob) and target that, so isSubscribed passes by
        //     construction and the System UI redirect is never entered.
        //     A SINGLE FIELD HAS THE lastBrowsedExtId PROBLEM — Gearhead, System UI and Assistant can all
        //     connect, and one field is last-writer-wins. If it held System UI's browser when a Gearhead
        //     rejection fired, the notify would go to System UI: Gearhead's stale tile would NOT refresh (the
        //     entire point lost) AND the phantom binding would be poked (the thing targeting was chosen to
        //     avoid). Worst of both, silently.
        //     A MAP KEYED BY ControllerInfo IS OVERKILL; an EQUALITY GUARD is the answer. onGetLibraryRoot
        //     already returns early for any browser whose packageName is not gearhead BEFORE starting the
        //     watcher, and extensionWatcherJob?.cancel() means one watcher lives at a time — the file already
        //     assumes a single Gearhead browsing session. So: set the field beside `extensionWatcherJob = …`
        //     (same lifetime), and at the rejection notify ONLY when `browser == rootBrowser`. That turns the
        //     race from a mis-target into a no-op.
        //     TYING IT TO THE WATCHER IS THE ESTABLISHED SHAPE HERE, NOT AN ARBITRARY CHOICE: the phantom-
        //     binding IPC loop this file already suffered was closed by CANCELLING extensionWatcherJob in
        //     onDisconnected — i.e. bounded by LIFECYCLE rather than by throttling. A rootBrowser whose
        //     lifetime is the watcher's inherits that same bound for free; a field with an independent
        //     lifetime would not.
        //     ControllerInfo.equals (MediaSession.java, media3-session 1.11.0) compares controllerCb by
        //     identity when either side is non-null, falling back to remoteUserInfo only when both are null —
        //     so `==` here is a genuine SAME-CONNECTION test, not a same-package one. That is what is wanted.
        // (c) A ONE-SHOT FLAG, BECAUSE notifyChildrenChanged HAS ALREADY CAUSED AN UNTHROTTLED IPC LOOP IN
        //     THIS FILE. The cycle cannot self-drive through ROOT (the ROOT branch returns long before this
        //     dispatch), so it needs the client to re-request the DEEP node on its own — which the record
        //     says AA does TIGHTLY, having been observed answering a notification immediately, fast enough to
        //     beat an async queue write mid-flight. rejection -> notify -> deep re-query -> rejection is then
        //     unbounded with no user input. A one-shot ceiling is one extra notify per connection whatever
        //     the client does.
        //     Prefer it over a debounce or a per-extId set because IT COSTS NO COVERAGE: the ROOT refresh is
        //     GLOBAL — one invalidation drops every stale tile at once — so a second notify for a different
        //     extId conveys nothing the first did not. A per-extId set is unbounded in extIds and buys zero
        //     extra effect; a debounce leaves the loop running at the debounce rate forever.
        //     It matters more than it looks: the extensionWatcher above ALREADY fires a notify on every
        //     extensionList emission with no throttle, so this would be a SECOND untracked trigger on a
        //     mechanism that has misbehaved once.
        //     ⚠️ RESET IT IN onDisconnected, NOT IN onGetLibraryRoot. onGetLibraryRoot IS NOT THE
        //     CONNECTION BOUNDARY: its clearCaches() block runs only from there, and those caches survive AA
        //     disconnect and persist while the app idles — deliberately, they are warm-start caches.
        //     onDisconnected already clears exactly this class of per-session state (userQueueSet,
        //     lastBrowsedExtId); the flag belongs beside them.
        //     ⚠️ BUT onDisconnected IS NOT THE CONNECTION BOUNDARY EITHER, SO SAY "PER RENEGOTIATION".
        //     It fires on every AA SESSION RENEGOTIATION, which is not the user leaving the car — established
        //     when an immediate player.pause() here was found pausing playback mid-drive, removed from
        //     playback control on the finding that no production app uses onDisconnected that way, and
        //     replaced by CarConnection as the real disconnect detector. Renegotiations can be frequent
        //     mid-drive. So the ceiling is ONE EXTRA NOTIFY PER RENEGOTIATION, NOT PER CONNECTION — looser
        //     than it first reads, and it must not be written down as the tighter bound.
        //     ⚠️ AND THE REAL BOUND IS THE USER'S TAP, NOT THE FLAG. Every notify requires a deliberate
        //     tap on a stale tile, so nothing self-sustains no matter how often the flag resets. What the
        //     flag buys is narrower than "one per session": IT STOPS A SINGLE TAP FROM PRODUCING REPEATED
        //     NOTIFIES if the client re-requests the deep node on invalidation. That is the whole of its job.
        //     Do not reason as though the flag is what makes the loop finite.
        //     ⚠️ AND THE WRITE MUST STAY OUTSIDE cacheMutex. That mutex is taken by every browse node and
        //     by clearCaches(), held across a 15s withTimeoutOrNull with unbounded waiters, and a third-party
        //     extension doing non-cancellable blocking I/O wedges it — a reset placed inside that block would
        //     fail to run in precisely the degraded state where it is needed. Both writes satisfy this as
        //     scoped: onDisconnected does not take the mutex, and the rejection below returns BEFORE the
        //     `cacheMutex.withLock` that begins after the extension resolves (verified at HEAD 2026-09-07).
        //     Resetting per-controller does open a small hole — a System UI disconnect clears the flag and
        //     permits one more Gearhead notify — but that is bounded by disconnect events, which are
        //     user/system-paced, not loop-paced. Acceptable; the ceiling that matters is that no single
        //     tap-and-requery cycle can sustain itself.
        val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
        // The isNotEmpty() condition is DELIBERATE, NOT INCIDENTAL: lastBrowsedExtId updates only on DEEPER
        // paths ("<root>/<extId>/home") and deliberately IGNORES tab-level clicks ("<root>/<extId>"). Reason
        // on record: the tab-level calls arrive as a BURST OF ALL-EXTENSION PREFETCHES, and letting them
        // write would corrupt the value to whichever prefetch landed last. Simplifying this to an
        // unconditional assignment reintroduces that corruption, and it would do so silently — the symptom
        // is a voice search running against an extension the user never browsed into.
        if (parentId.substringAfter("$ROOT/$extId").isNotEmpty()) lastBrowsedExtId = extId
        val extension = extensions.firstOrNull { it.id == extId }
            ?: return@futureCatching LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_error_loading))
            )
        val type = parentId.substringAfter("$extId/").substringBefore("/")
        cacheMutex.withLock { withTimeoutOrNull(15_000L) { when (type) {
            ALBUM -> extension.getList<AlbumClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$ALBUM/").substringBefore("/")
                // Soft-fail the node (empty) on a cache miss (eviction) or a wrong-typed item mis-filed under
                // this mediaId, rather than NPE/CCE the browse future. Deliberately NOT a thin re-fetch: that
                // would route a mismatched id back into loadAlbum — the mistyped-item bug this guards against.
                val unloaded = itemMap[id] as? Album ?: return@getList emptyList()
                val tracks = getTracks(context, id, extId, page) {
                    val album = loadAlbum(unloaded)
                    album to loadTracks(album)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            PLAYLIST -> extension.getList<PlaylistClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$PLAYLIST/").substringBefore("/")
                val unloaded = itemMap[id] as? Playlist ?: return@getList emptyList()
                val tracks = getTracks(context, id, extId, page) {
                    val playlist = loadPlaylist(unloaded)
                    playlist to loadTracks(playlist)
                }
                if (page == 0) listOf(shuffleItem(id, extId, context)) + tracks else tracks
            }

            RADIO -> extension.getList<RadioClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$RADIO/").substringBefore("/")
                val radio = itemMap[id] as? Radio ?: return@getList emptyList()
                getTracks(context, id, extId, page) {
                    radio to loadTracks(radio)
                }
            }

            USER -> extension.getList<ArtistClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$USER/").substringBefore("/")
                val unloaded = itemMap[id] as? Artist ?: return@getList emptyList()
                val artist = loadArtist(unloaded)
                loadFeed(artist).toMediaItems(id, context, extId, page)
            }

            LIST -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$LIST/").substringBefore("/")
                getListsItems(context, id, extId)
            }

            SHELF -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$SHELF/").substringBefore("/")
                getShelfItems(context, id, extId, page)
            }

            HOME -> extension.getFeed<HomeFeedClient>(
                context, parentId, page, throwableFlow
            ) { loadHomeFeed() }

            LIBRARY -> extension.getFeed<LibraryFeedClient>(
                context, parentId, page, throwableFlow
            ) { loadLibraryFeed() }

            FEED -> extension.getList<ExtensionClient>(context, throwableFlow) {
                val id = parentId.substringAfter("$ROOT/$extId/$FEED/")
                val feed = feedMap[id] ?: return@getList emptyList()
                feed.toMediaItems(id, context, extId, page)
            }

            SEARCH -> {
                val query = parentId.substringAfter("$ROOT/$extId/$SEARCH/", "")
                extension.getFeed<SearchFeedClient>(
                    context, parentId, page, throwableFlow
                ) { loadSearchFeed(query) }
            }

            PLAYLISTS -> extension.getList<LibraryFeedClient>(context, throwableFlow) {
                val libFeed = loadLibraryFeed()
                val playlistTab = libFeed.notSortTabs.firstOrNull {
                    it.id.contains("playlist", ignoreCase = true) ||
                    it.title.contains("playlist", ignoreCase = true)
                } ?: libFeed.notSortTabs.firstOrNull()
                Feed(listOf()) { libFeed.getPagedData(playlistTab) }
                    .toMediaItems(parentId, context, extId, page)
            }

            HISTORY -> {
                val items = historyRepository?.getByExtension(extId) ?: emptyList()
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(items.mapNotNull { entity ->
                        val con = if (entity.context is Radio) null else entity.context
                        entity.track?.toItem(context, extId, con)
                    }),
                    null
                )
            }

            else -> LibraryResult.ofItemList(
                listOfNotNull(
                    if (extension.isClient<HomeFeedClient>())
                        browsableItem("$ROOT/$extId/$HOME", "${extension.name} • ${context.getString(R.string.home)}")
                    else null,
                    if (extension.isClient<SearchFeedClient>())
                        browsableItem("$ROOT/$extId/$SEARCH", "${extension.name} • ${context.getString(R.string.aa_browse)}")
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$LIBRARY", "${extension.name} • ${context.getString(R.string.library)}")
                    else null,
                    if (extension.isClient<LibraryFeedClient>())
                        browsableItem("$ROOT/$extId/$PLAYLISTS", "${extension.name} • ${context.getString(R.string.playlists)}")
                    else null,
                    if (historyRepository != null)
                        browsableItem("$ROOT/$extId/$HISTORY", "${extension.name} • ${context.getString(R.string.history)}")
                    else null,
                ),
                null
            )
        } } ?: LibraryResult.ofError(
            SessionError(SessionError.ERROR_IO, context.getString(R.string.auto_timed_out))
        ) }
    }

    @OptIn(UnstableApi::class)
    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return super.onGetItem(session, browser, mediaId)
    }

    // Load the given context's tracks FRESH (via the extension) and return the current+upcoming media
    // items — the tapped track (fresh, live token) first, then the tracks after it. Overridden by
    // PlayerCallback (which owns the track-loading); base default is empty. Used to play History taps
    // and browse-cache-miss taps WITHOUT replaying a stored track's stale resolution state.
    protected open suspend fun freshContextUpcoming(
        extId: String, context: EchoMediaItem, tappedTrackId: String
    ): List<MediaItem> = emptyList()

    @OptIn(UnstableApi::class)
    @CallSuper
    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val extId = getCurrentExtension()?.id ?: ""
        val cacheKey = query to extId
        Log.d("GladixAuto", "onGetSearchResult: query='$query' page=$page pageSize=$pageSize lastSearchQuery='$lastSearchQuery' cachedResults=${searchResults[cacheKey]?.size ?: "none"}")
        return scope.future {
            val effectiveQuery = lastSearchQuery.ifEmpty { query }
            val allTracks = searchResults[cacheKey]
                ?: run { runCatching { searchJobs[query]?.join() }.getOrNull(); searchResults[cacheKey] }
                ?: searchMutex.withLock {
                    searchResults[cacheKey] ?: performSearch(effectiveQuery).also {
                        searchResults[cacheKey] = it
                    }
                }
            val from = page * pageSize
            Log.d("GladixAuto", "onGetSearchResult: returning ${allTracks.drop(from).take(pageSize).size} items (total=${allTracks.size}) for query='$query'")
            LibraryResult.ofItemList(allTracks.drop(from).take(pageSize), null)
        }
    }

    // TIER 1 WAS UNDER-FILTERED — FIXED 2026-09-07. It applied only `it != UNIFIED_ID` to the STRING, so a
    // DISABLED extension reached through a cached browse node became the voice-search target and STAYED it
    // (lastBrowsedExtId persists). Unified was caught; disabled was not. This base body was weaker still:
    // aaEligible itself omitted isEnabled, so NO tier checked it. Both are closed — aaEligible now carries
    // isEnabled and tier 1 runs the resolved extension through it, matching the live override
    // (PlayerCallback.getCurrentExtension, the only subclass). This body is the declared default and must
    // not drift from it again.
    // The id tier 1 reads is still written by AndroidAutoCallback.onGetChildren from an UNFILTERED parse of
    // parentId — THAT GAP IS STILL OPEN, see the note there. This fix closes the consumer, not the source.
    //
    // ⚠️ THE FIX DID NOT MAKE THIS FIELD TRUSTWORTHY — DO NOT READ IT AS MAKING getCurrentExtension
    // CORRECT. aaEligible closed the DISABLED/UNIFIED axis. TWO OTHER AXES SURVIVE IT, and they are
    // DIFFERENT PROBLEMS — keep them apart, because a fix for one does nothing for the other:
    //   (i)  RACINESS — "WHICH CONCURRENT WRITE WON". lastBrowsedExtId is @Volatile and shared across ALL
    //        in-flight browse futures, so under concurrent onGetChildren calls it is simply whichever future
    //        wrote last: which extension wins is NONDETERMINISTIC, disabled or not. An earlier session went
    //        looking for a per-request discriminator at this boundary and checked parentId,
    //        browser.packageName, params, page/pageSize, call ordering and time-since-connect; its conclusion
    //        was that NO RELIABLE DISCRIMINATOR EXISTS at the onGetChildren boundary. Needs a different
    //        mechanism, not a stronger predicate.
    //   (ii) STALENESS — "THE VALUE WAS NEVER UPDATED AT ALL". lastBrowsedExtId is written ONLY on an AA
    //        browse-into, and NEVER on a phone-side extension switch. So if the user changes extension on the
    //        phone while AA is connected, this field still names the one they last browsed in the car — a
    //        STALE-BUT-ENABLED id, which sails through aaEligible untouched. Not a race: nothing competed,
    //        the write simply never happened.
    //
    // Neither state crashes: the search runs and returns real results, just from the wrong extension. See
    // the frequency-drop trap recorded at onGetChildren's per-node dispatch — there is nothing to count.
    protected open fun getCurrentExtension(): MusicExtension? {
        val aaEligible = { ext: MusicExtension ->
            ext.isEnabled && ext.id != UnifiedExtension.UNIFIED_ID
        }
        return lastBrowsedExtId
            ?.let { id -> extensionList.value.firstOrNull { it.id == id } }
            ?.takeIf(aaEligible)
            ?: extensionList.value.firstOrNull(aaEligible)
    }

    private suspend fun performSearch(query: String): List<MediaItem> {
        val ext = getCurrentExtension()
        if (ext == null) {
            Log.d("GladixAuto", "performSearch: no extension available for query='$query'")
            return emptyList()
        }
        Log.d("GladixAuto", "performSearch: query='$query' ext=${ext.id}")
        return runCatching {
            withTimeout(10_000) {
                val client = ext.instance.value().getOrNull() as? SearchFeedClient
                    ?: run {
                        Log.d("GladixAuto", "performSearch: ${ext.id} has no SearchFeedClient")
                        return@withTimeout emptyList<MediaItem>()
                    }
                Log.d("GladixAuto", "performSearch: calling loadSearchFeed query='$query' ext=${ext.id}")
                val feed = client.loadSearchFeed(query)
                val tab = feed.notSortTabs.firstOrNull { it.id.equals("TRACK", ignoreCase = true) }
                val pagedData = feed.getPagedData(tab).pagedData
                val (shelves, _) = pagedData.loadPage(null)
                val tracks = shelves.toTracks()
                Log.d("GladixAuto", "performSearch: ${tracks.size} results for query='$query' ext=${ext.id}")
                tracks.take(25).map { track ->
                    val item = track.toItem(context, ext.id)
                    val artist = item.mediaMetadata.artist
                    item.buildUpon().setMediaMetadata(
                        item.mediaMetadata.buildUpon()
                            .setArtist(if (artist.isNullOrEmpty()) ext.name else "$artist • ${ext.name}")
                            .build()
                    ).build()
                }
            }
        }.getOrElse {
            when (it) {
                is TimeoutCancellationException -> {
                    Log.d("GladixAuto", "performSearch: timeout for query='$query' ext=${ext.id}")
                    emptyList()
                }
                is CancellationException -> throw it
                else -> {
                    // Wrap for ATTRIBUTION only: this path calls loadSearchFeed directly (:555-564) rather
                    // than through ExtensionUtils.get, so without this the throwable reaches App.throwFlow
                    // unwrapped and throwing_extension_id records "none" for every AA search failure.
                    // Emitting the wrapped form does not change what this function returns, nor the AA tile
                    // text. Safe re CancellationException: toAppException rethrows it (AppException.kt:65),
                    // but both cancellation cases are already handled above.
                    throwableFlow?.emit(it.toAppException(ext))
                    Log.d("GladixAuto", "performSearch: error for query='$query' ext=${ext.id}: ${it::class.simpleName}: ${it.message}")
                    emptyList()
                }
            }
        }
    }

    @CallSuper
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ) = scope.future {
        userQueueSet.set(true)
        val shuffleItem = mediaItems.singleOrNull()
            ?.takeIf { it.mediaId.startsWith("$SHUFFLE_PREFIX/") }
        if (shuffleItem != null) {
            val rest = shuffleItem.mediaId.substringAfter("$SHUFFLE_PREFIX/")
            val extId = rest.substringBefore("/")
            val id = rest.substringAfter("/")
            val (item, tracks) = tracksMap[id]
                ?: return@future super.onSetMediaItems(
                    mediaSession, controller, mutableListOf(), 0, startPositionMs
                ).await(context)
            // Build the UNSHUFFLED album order, then shuffle the LIST. Record the unshuffled order so the
            // ensuing setMediaItems keeps it as `original` and flips the shuffle flag ON (parity with the phone
            // Shuffle button: a later toggle-OFF restores album order). The flag flip is changeQueue-free — the
            // queue is applied already shuffled, so nothing re-shuffles.
            val unshuffled = tracks.loadAll().map {
                MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
            }
            val shuffled = unshuffled.shuffled()
            // Player access MUST be on the application/main thread — this future runs on a background
            // dispatcher (see CoroutineUtils.future default). notifyShuffleTileOriginal only sets a field,
            // but it's consumed on the main thread when Media3 applies the returned queue, so hop to Main
            // for ordering/visibility (and parity with the syncShuffleFlag fix below).
            if (unshuffled.isNotEmpty()) withContext(Dispatchers.Main) {
                (mediaSession.player as? ShufflePlayer)?.notifyShuffleTileOriginal(unshuffled)
            }
            return@future super.onSetMediaItems(
                mediaSession, controller, shuffled.toMutableList(), 0, startPositionMs
            ).await(context)
        }

        // Single-track tap from a playlist/album: expand to full queue at correct position
        if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith("auto/")) {
            // In-order tap (all branches below build current+upcoming): sync the shuffle flag/icon OFF WITHOUT
            // changeQueue. Set here (not after the framework applies the returned queue) because the ensuing
            // setMediaItems doesn't touch the flag, so this survives — and `original` becomes the in-order queue.
            // Main-thread hop: this future runs on a background dispatcher, but syncShuffleFlag mutates the
            // ExoPlayer (setShuffleModeEnabled), which asserts main-thread access — the build-999 "wrong
            // thread" crash. Only the player mutation moves to Main; resolution/build below stay on IO.
            withContext(Dispatchers.Main) {
                (mediaSession.player as? ShufflePlayer)?.syncShuffleFlag(false)
            }
            val auto = parseAutoId(mediaItems[0].mediaId)
            val cached = auto?.let {
                context.getFromCache<Triple<Track, String, EchoMediaItem?>>(it.t, "auto", durable = true)
            }
            if (cached != null) {
                val (track, extId, con) = cached
                if (con.isReplayableContext()) {
                    val tracksEntry = tracksMap.values.find { (item, _) ->
                        item is EchoMediaItem.Lists && item.id == con.id
                    }
                    if (tracksEntry != null) {
                        val (item, pagedData) = tracksEntry
                        val allTracks = pagedData.loadAll()
                        // A miss here means the context no longer contains the tapped track — it was
                        // removed from the playlist since it was played. Starting at the top is the
                        // defensible fallback for that, but it must not be SILENT: an identical silent
                        // fallback (this one and freshContextUpcoming's `?: 0`) hid a radio-routing bug for
                        // two months by making the wrong track look like a deliberate default. Radio can no
                        // longer reach here (isReplayableContext above), so a miss now genuinely means
                        // "removed since".
                        val rawIndex = allTracks.indexOfFirst { it.id == track.id }
                        if (rawIndex < 0) Log.w(
                            "GladixContext",
                            "tapped track not in freshly loaded context: ctx=${con.id} — starting at 0"
                        )
                        val tappedIndex = rawIndex.coerceAtLeast(0)
                        // P2 — current+upcoming: drop the tracks before the tapped one so it lands at
                        // index 0 (matches phone playItem + freshContextUpcoming) — no stranded-above,
                        // zero persisted index.
                        val upcomingItems = allTracks.subList(tappedIndex, allTracks.size).map {
                            MediaItemUtils.build(app, downloadFlow.value, MediaState.Unloaded(extId, it), item)
                        }
                        return@future super.onSetMediaItems(
                            mediaSession, controller, upcomingItems.toMutableList(), 0, startPositionMs
                        ).await(context)
                    } else {
                        // Context not in the browse cache — a HISTORY tap (its context was never browsed)
                        // or an evicted album. Load it FRESH and play current+upcoming from the tapped
                        // track, so the cached STALE track is never replayed. Fixes the History-tap skip;
                        // empty result (context load failed) falls through to the stored-track path below.
                        val upcoming = freshContextUpcoming(extId, con, track.id)
                        if (upcoming.isNotEmpty()) {
                            return@future super.onSetMediaItems(
                                mediaSession, controller, upcoming.toMutableList(), 0, startPositionMs
                            ).await(context)
                        }
                    }
                }
            } else if (auto?.e != null) {
                // #4 — durable cache missed, but the mediaId is self-describing: rebuild a thin context
                // and re-resolve FRESH (loadAlbum/listTracks → valid tokens), exactly like a History tap.
                // Full current+upcoming recovery, extension-agnostic. If the context can't be rebuilt or
                // the load returns nothing, fall through to the generic branch's thin-keep (#3).
                val thinContext = auto.toThinContext()
                if (thinContext != null) {
                    val upcoming = freshContextUpcoming(auto.e, thinContext, auto.t)
                    if (upcoming.isNotEmpty()) {
                        return@future super.onSetMediaItems(
                            mediaSession, controller, upcoming.toMutableList(), 0, startPositionMs
                        ).await(context)
                    }
                }
            }
        }

        // Generic rebuild. On a durable-cache HIT, rebuild from the full cached Triple (best: real
        // streamables/tokens). On a MISS, #3 keeps the item as a thin track (never silently drop) when
        // the mediaId carries an extId; only a legacy "auto/<id>" with no recoverable extId is dropped,
        // and #2 messages the user rather than silently shrinking the queue.
        var dropped = 0
        val new = mediaItems.mapNotNull {
            if (it.mediaId.startsWith("auto/")) {
                val auto = parseAutoId(it.mediaId)
                if (auto == null) { dropped++; return@mapNotNull null }
                val cached =
                    context.getFromCache<Triple<Track, String, EchoMediaItem?>>(auto.t, "auto", durable = true)
                when {
                    cached != null -> {
                        val (track, extId, con) = cached
                        // No context (bare-track / Radio-History seed): stamp a display-only "<track>
                        // Radio" label. Marked LABEL_ONLY_RADIO, so PlayerRadio still generates off a null
                        // context — radio generation is unchanged, this is only the label.
                        val seedContext = con ?: MediaItemUtils.trackRadioPlaceholder(track)
                        MediaItemUtils.build(
                            app, downloadFlow.value, MediaState.Unloaded(extId, track), seedContext
                        )
                    }

                    auto.e != null -> {
                        // #3 thin-keep: never silently drop. Rebuild a display track from the mediaId +
                        // the metadata AA round-trips; it loads on play where loadTrack works by id and
                        // error-skips visibly (e.g. Deezer, token-less) instead of vanishing.
                        val thinTrack = it.toThinTrack(auto.t)
                        val seedContext =
                            auto.toThinContext() ?: MediaItemUtils.trackRadioPlaceholder(thinTrack)
                        MediaItemUtils.build(
                            app, downloadFlow.value, MediaState.Unloaded(auto.e, thinTrack), seedContext
                        )
                    }

                    else -> {
                        // Legacy "auto/<id>" with no recoverable extId — genuinely unresolvable.
                        dropped++
                        null
                    }
                }
            } else it
        }
        if (dropped > 0) scope.launch {
            app.messageFlow.emit(Message(context.getString(R.string.some_tracks_couldnt_be_restored)))
        }
        // Enforce super's invariant: the base onAddMediaItems throws UnsupportedOperationException on any
        // item with localConfiguration == null (Media3 1.10.1). The auto/ items above are built with a URI
        // (localConfiguration set) and non-auto items that already carry a URI pass through; only genuinely
        // unresolvable ones — an AA voice "play X" query or a bare mediaId with no URI — are filtered out.
        // Silent by design (a phone snackbar wouldn't show in the car). An empty result hits super's safe
        // empty path (same as the shuffle branch above), clearing the queue rather than re-prepping a fine
        // track. Resolving the query to real results (+ a spoken "couldn't find that" on none) is a separate
        // feature, not this crash guard.
        val playable = new.filter { it.localConfiguration != null }
        // Dropping items can leave startIndex past the end → IllegalSeekPositionException when Media3 applies
        // it. Clamp to the filtered list (0 when empty).
        val safeIndex = startIndex.coerceIn(0, (playable.size - 1).coerceAtLeast(0))
        val future = super.onSetMediaItems(
            mediaSession, controller, playable.toMutableList(), safeIndex, startPositionMs
        )
        future.await(context)
    }

    companion object {
        private const val ROOT = "root"
        private const val LIBRARY = "library"
        private const val PLAYLISTS = "playlists"
        private const val HOME = "home"
        private const val SEARCH = "search"
        private const val FEED = "feed"
        private const val SHELF = "shelf"
        private const val LIST = "list"

        private const val USER = "user"
        private const val ALBUM = "album"
        private const val PLAYLIST = "playlist"
        private const val RADIO = "radio"
        private const val HISTORY = "history"

        private const val SHUFFLE_PREFIX = "auto-shuffle"

        private const val MAX_MAP_SIZE = 500

        private fun <K, V> boundedMap(): MutableMap<K, V> =
            Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > MAX_MAP_SIZE
            })

        private val cacheMutex = Mutex()

        private fun clearCaches() {
            itemMap.clear()
            listsMap.clear()
            shelvesMap.clear()
            feedMap.clear()
            tracksMap.clear()
            continuations.clear()
            extensionIconCache.clear()
        }

        private fun shuffleItem(id: String, extId: String, context: Context) = MediaItem.Builder()
            .setMediaId("$SHUFFLE_PREFIX/$extId/$id")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setTitle(context.getString(R.string.shuffle))
                    .setArtworkUri(context.resources.getUri(R.drawable.ic_shuffle))
                    .build()
            ).build()

        private fun Resources.getUri(int: Int): Uri {
            val scheme = ContentResolver.SCHEME_ANDROID_RESOURCE
            val pkg = getResourcePackageName(int)
            val type = getResourceTypeName(int)
            val name = getResourceEntryName(int)
            val uri = "$scheme://$pkg/$type/$name"
            return uri.toUri()
        }

        private fun ImageHolder.toUri(context: Context) = when (this) {
            is ImageHolder.ResourceUriImageHolder -> uri.toUri()
            is ImageHolder.NetworkRequestImageHolder -> request.url.toUri()
            is ImageHolder.ResourceIdImageHolder -> context.resources.getUri(resId)
            is ImageHolder.HexColorImageHolder -> "".toUri()
        }

        private suspend fun ImageHolder.loadBitmapBytes(context: Context, maxPx: Int): ByteArray? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val src: Bitmap = when (this@loadBitmapBytes) {
                        is ImageHolder.ResourceIdImageHolder ->
                            BitmapFactory.decodeResource(context.resources, resId)
                        is ImageHolder.NetworkRequestImageHolder ->
                            (java.net.URL(request.url).openConnection() as java.net.HttpURLConnection)
                                .apply { connectTimeout = 3000; readTimeout = 3000 }
                                .inputStream.use { BitmapFactory.decodeStream(it) }
                        is ImageHolder.ResourceUriImageHolder ->
                            context.contentResolver.openInputStream(uri.toUri())
                                ?.use { BitmapFactory.decodeStream(it) }
                        is ImageHolder.HexColorImageHolder -> null
                    } ?: return@runCatching null
                    val scale = minOf(1f, maxPx.toFloat() / maxOf(src.width, src.height))
                    val scaled = if (scale < 1f) {
                        src.scale(
                            (src.width * scale).toInt().coerceAtLeast(1),
                            (src.height * scale).toInt().coerceAtLeast(1)
                        ).also { src.recycle() }
                    } else src
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                        scaled.recycle()
                        out.toByteArray()
                    }
                }.getOrNull()
            }

        private suspend fun getTabIconBytes(context: Context, resId: Int): ByteArray? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val size = 96
                    val padding = 8
                    val bitmap = createBitmap(size, size)
                    val canvas = Canvas(bitmap)
                    val drawable = AppCompatResources.getDrawable(context, resId) ?: return@runCatching null
                    drawable.setBounds(padding, padding, size - padding, size - padding)
                    drawable.draw(canvas)
                    ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        bitmap.recycle()
                        out.toByteArray()
                    }
                }.getOrNull()
            }

        private fun browsableItem(
            id: String,
            title: String,
            subtitle: String? = null,
            browsable: Boolean = true,
            artWorkUri: Uri? = null,
            artworkData: ByteArray? = null,
            type: Int = MediaMetadata.MEDIA_TYPE_MIXED
        ) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(false)
                    .setIsBrowsable(browsable)
                    .setMediaType(type)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .apply {
                        if (artworkData != null)
                            setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        else
                            setArtworkUri(artWorkUri)
                    }
                    .build()
            )
            .build()

        private fun Track.toItem(
            context: Context, extensionId: String, con: EchoMediaItem? = null
        ): MediaItem {
            // #1 durable = true → filesDir, so an OS cacheDir wipe can't evict it. #3/#4 encodeAutoId →
            // self-describing mediaId carrying extId + context for cache-miss re-resolution.
            context.saveToCache(id, Triple(this, extensionId, con), "auto", durable = true)
            return MediaItem.Builder()
                .setMediaId(encodeAutoId(id, extensionId, con))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setTitle(title)
                        .setArtist(subtitleWithE)
                        .setAlbumTitle(album?.title)
                        .setArtworkUri(cover?.toUri(context))
                        .build()
                ).build()
        }

        private suspend fun Extension<*>.toMediaItem(context: Context): MediaItem {
            val success = instance.value().isSuccess
            val artworkData = if (extensionIconCache.containsKey(id)) {
                extensionIconCache[id]
            } else {
                val localResId = extensionIconResId[id]
                val bytes = if (localResId != null)
                    getTabIconBytes(context, localResId)
                else
                    metadata.icon?.loadBitmapBytes(context, 96)
                bytes.also { extensionIconCache[id] = it }
            }
            return browsableItem(
                "$ROOT/$id", name, context.getString(R.string.extension),
                success, artworkData = artworkData
            )
        }

        @OptIn(UnstableApi::class)
        val notSupported =
            LibraryResult.ofError<ImmutableList<MediaItem>>(SessionError.ERROR_NOT_SUPPORTED)

        @OptIn(UnstableApi::class)

        suspend inline fun <reified C> Extension<*>.getList(
            context: Context,
            throwableFlow: MutableSharedFlow<Throwable>? = null,
            block: C.() -> List<MediaItem>
        ): LibraryResult<ImmutableList<MediaItem>> = runCatching {
            val client = instance.value().getOrThrow() as? C ?: return@runCatching notSupported
            LibraryResult.ofItemList(
                client.block(),
                MediaLibraryService.LibraryParams.Builder()
                    .setOffline(client is OfflineExtension)
                    .build()
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            // Wrap for ATTRIBUTION only: client.block() (:952) is a direct extension call, not routed
            // through ExtensionUtils.get, so unwrapped throwables make throwing_extension_id read "none"
            // for the whole AA browse path. Deliberately wraps ONLY the emit — the SessionError below
            // still reads the RAW `it.message`, so the AA tile text is byte-identical to before (the
            // wrapped form would differ for NotSupported/Other; that's a separate cosmetic call, not
            // this change). Safe re CancellationException: rethrown on the line above, before
            // toAppException's own rethrow (AppException.kt:65) could fire.
            throwableFlow?.emit(it.toAppException(this))
            it.printStackTrace()
            LibraryResult.ofError(
                SessionError(SessionError.ERROR_IO, it.message ?: context.getString(R.string.auto_error_loading))
            )
        }


        private val extensionIconResId = mapOf(
            "deezer" to R.drawable.ic_aa_deezer,
            "spotify" to R.drawable.ic_aa_spotify,
            "Youtube_music" to R.drawable.ic_aa_youtube_music,
            "GoogleDrive_extension" to R.drawable.ic_aa_google_drive,
            "jellyfin" to R.drawable.ic_aa_jellyfin,
            "saavn_music" to R.drawable.ic_aa_saavn,
            "Groove_music" to R.drawable.ic_aa_groove
        )
        private val extensionIconCache = boundedMap<String, ByteArray?>()
        private val itemMap = boundedMap<String, EchoMediaItem>()
        private fun EchoMediaItem.toMediaItem(
            context: Context, extId: String
        ): MediaItem = when (this) {
            is Track -> toItem(context, extId)
            else -> {
                val id = hashCode().toString()
                itemMap[id] = this
                val (page, type) = when (this) {
                    is Artist -> USER to MediaMetadata.MEDIA_TYPE_MIXED
                    is Radio -> RADIO to MediaMetadata.MEDIA_TYPE_MIXED
                    is Album -> ALBUM to MediaMetadata.MEDIA_TYPE_ALBUM
                    is Playlist -> PLAYLIST to MediaMetadata.MEDIA_TYPE_PLAYLIST
                }
                browsableItem(
                    "$ROOT/$extId/$page/$id",
                    title,
                    subtitleWithE,
                    true,
                    cover?.toUri(context),
                    type = type
                )
            }
        }

        private val listsMap = boundedMap<String, Shelf.Lists<*>>()
        private fun getListsItems(
            context: Context, id: String, extId: String
        ) = run {
            val shelf = listsMap[id] ?: return@run emptyList()
            when (shelf) {
                is Shelf.Lists.Categories -> shelf.list.map { it.toMediaItem(context, extId) }
                is Shelf.Lists.Items -> shelf.list.map { it.toMediaItem(context, extId) }
                is Shelf.Lists.Tracks -> shelf.list.map { it.toItem(context, extId) }
            } + listOfNotNull(
                shelf.more?.let { more ->
                    val moreId = shelf.id
                    feedMap[moreId] = more
                    browsableItem(
                        "$ROOT/$extId/$FEED/$moreId",
                        context.getString(R.string.more)
                    )
                }
            )
        }

        private fun Shelf.toMediaItem(
            context: Context, extId: String
        ): MediaItem = when (this) {
            is Shelf.Category -> {
                val items = feed
                if (items != null) feedMap[id] = items
                browsableItem("$ROOT/$extId/$FEED/$id", title, subtitle, items != null)
            }

            is Shelf.Item -> media.toMediaItem(context, extId)
            is Shelf.Lists<*> -> {
                val id = "${id.hashCode()}"
                listsMap[id] = this
                browsableItem("$ROOT/$extId/$LIST/$id", title, subtitle)
            }
        }


        // THIS PROBABLY BREAKS GOING BACK TBH, NEED TO TEST
        private val shelvesMap = boundedMap<String, PagedData<Shelf>>()
        private val continuations = boundedMap<Pair<String, Int>, String?>()
        private suspend fun getShelfItems(
            context: Context, id: String, extId: String, page: Int
        ): List<MediaItem> {
            val shelf = shelvesMap[id] ?: return emptyList()
            val (list, next) = shelf.loadPage(continuations[id to page])
            continuations[id to page + 1] = next
            return listOfNotNull(
                *list.map { it.toMediaItem(context, extId) }.toTypedArray()
            )
        }

        private val feedMap = boundedMap<String, Feed<Shelf>>()
        private suspend fun Feed<Shelf>.toMediaItems(
            id: String, context: Context, extId: String, page: Int
        ): List<MediaItem> {
            val feedKey = id.hashCode().toString()
            if (notSortTabs.isNotEmpty()) {
                return notSortTabs.map { tab ->
                    val shelfKey = "${feedKey}_${tab.id.hashCode()}"
                    shelvesMap[shelfKey] = PagedData.Suspend { getPagedData(tab).pagedData }
                    browsableItem("$ROOT/$extId/$SHELF/$shelfKey", tab.title)
                }
            }
            if (shelvesMap[feedKey] == null) {
                shelvesMap[feedKey] = getPagedData(null).pagedData
            }
            return getShelfItems(context, feedKey, extId, page)
        }

        private suspend inline fun <reified T> Extension<*>.getFeed(
            context: Context,
            parentId: String,
            pageNumber: Int,
            throwableFlow: MutableSharedFlow<Throwable>? = null,
            getFeed: T.() -> Feed<Shelf>
        ): LibraryResult<ImmutableList<MediaItem>> = getList<T>(context, throwableFlow) {
            val extId = parentId.substringAfter("$ROOT/").substringBefore("/")
            getFeed().toMediaItems(parentId, context, extId, pageNumber)
        }

        private val tracksMap = boundedMap<String, Pair<EchoMediaItem, PagedData<Track>>>()
        private suspend fun getTracks(
            context: Context,
            id: String,
            extId: String,
            page: Int,
            getTracks: suspend () -> Pair<EchoMediaItem, Feed<Track>?>
        ): List<MediaItem> {
            val (item, tracks) = tracksMap[id] ?: run {
                val newPair = getTracks().run {
                    first to (second?.run { getPagedData(tabs.firstOrNull()) }?.pagedData
                        ?: PagedData.empty())
                }
                tracksMap[id] = newPair
                newPair
            }
            val (list, next) = tracks.loadPage(continuations[id to page])
            continuations[id to page + 1] = next
            return list.take(150).map { it.toItem(context, extId, item) }
        }

        private suspend fun List<Shelf>.toTracks(): List<Track> = flatMap { shelf ->
            when (shelf) {
                is Shelf.Item -> listOfNotNull(shelf.media as? Track)
                is Shelf.Lists.Tracks -> shelf.list
                is Shelf.Lists.Items -> shelf.list.filterIsInstance<Track>()
                is Shelf.Category -> shelf.feed?.let { feed ->
                    val pagedData = feed.getPagedData(feed.notSortTabs.firstOrNull()).pagedData
                    val (innerShelves, _) = pagedData.loadPage(null)
                    innerShelves.flatMap { inner ->
                        when (inner) {
                            is Shelf.Item -> listOfNotNull(inner.media as? Track)
                            is Shelf.Lists.Tracks -> inner.list
                            is Shelf.Lists.Items -> inner.list.filterIsInstance<Track>()
                            else -> emptyList()
                        }
                    }
                } ?: emptyList()
                else -> emptyList()
            }
        }
    }

}