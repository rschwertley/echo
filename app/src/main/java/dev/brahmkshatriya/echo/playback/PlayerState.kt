package dev.brahmkshatriya.echo.playback

import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.context
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

data class PlayerState(
    val current: MutableStateFlow<Current?> = MutableStateFlow(null),
    val radio: MutableStateFlow<Radio> = MutableStateFlow(Radio.Empty),
    val session: MutableStateFlow<Int> = MutableStateFlow(0)
) {

    val servers: MutableMap<String, Result<Streamable.Media.Server>> =
        Collections.synchronizedMap(LinkedHashMap())
    val serverChanged = MutableSharedFlow<Unit>(replay = 1)
    val activeLoadCount = AtomicInteger(0)

    // Single cold-start restore: the queue is read from disk ONCE at service creation
    // (PlayerService.onCreate) into this Deferred, and shared by every consumer — so no path runs its own
    // recoverPlaylist and races another. A null payload means the disk was empty.
    //
    // ⚠️ THE CONSUMERS, VERIFIED AT HEAD 2026-09-07 — THERE ARE EXACTLY THREE. Grep `restoreDeferred`
    // before trusting any other list, including this one:
    //   1. PlayerCallback.applyRestoreIfCold   — the app-open apply, at service create.
    //   2. PlayerCallback.onPlaybackResumption — a media button (isForPlayback = true).
    //   3. PlayerViewModel (getController)     — the UI's at-rest seed for `current` and the scrubber.
    // This comment previously read "applyRestoreIfCold, resume(), and onPlaybackResumption": it named a
    // `resume()` consumer that does not exist anywhere at HEAD, and OMITTED consumer 3 — which is the one
    // that turned out to bind the restore-snapshot release gate (see
    // PlayerService.scheduleRestoreSnapshotRelease), because it awaits AFTER the timeline is populated by
    // design. A stale list here is how a "four consumers" count went on being quoted; the count is three
    // and the names are above.
    var restoreDeferred: Deferred<RestoreData?>? = null

    // Cache of the last built restore, keyed on ResumptionUtils.queueGeneration. Survives PlayerService
    // death because this class is a Koin singleton, while the service (and its ExoPlayer) is not.
    //
    // WHY THIS EXISTS — it is NOT "restore once per process". Media3's MediaSessionService.onStartCommand
    // has a stale-start-intent branch ("Terminating service that was started by a stale start intent")
    // that STOPS an instance it has already created, and onCreate has fully run by then. So the service
    // can be created and destroyed in a tight loop, and every creation re-ran recoverPlaylist. On build
    // 1039 that was ~1050 creations in ~60s x 81 items = ~85,000 MediaItem builds, which took the heap
    // from ~20MB to 255MB and OOM'd the process — the loop caused the OOM, not the reverse.
    //
    // The APPLY still happens on every creation: a new service means a new ExoPlayer with an empty
    // timeline, so the queue must be re-applied or playback cannot resume. Only the disk read and the
    // item construction are skipped. That turns the loop from fatal into merely wasteful, which is the
    // failure mode we can observe (service_create_count + age_s_svc_first) instead of dying blind.
    //
    // Accepted staleness: build() bakes in download state, the show-background setting and the
    // quality-derived serverIndex. All three are user actions that cannot meaningfully occur inside a
    // sub-minute service-recreation storm, and any real queue write bumps the generation and evicts this.
    // NOTHING time-sensitive or credentialed is cached — stream URLs are resolved later, at playback.
    @Volatile
    var restoreCache: Pair<Long, RestoreData?>? = null

    // Set true SYNCHRONOUSLY when onPlaybackResumption is invoked (media-button / system resume) so the
    // app-open applyRestoreIfCold defers while the framework is about to apply the same queue — a second
    // setMediaItems tears the timeline down and re-prepares. Plain var, NOT AtomicBoolean: read and
    // written ONLY on the player's application looper — which is Main, because the player is built on Main
    // in PlayerService.onCreate (ExoPlayer.Builder defaults its looper to the current thread). It is set
    // in onPlaybackResumption's synchronous body (Media3 invokes that callback on that looper), read in
    // applyRestoreIfCold's withContext(Main), and cleared on Main by the timeline listener (success) and
    // a withContext(Main) in the non-return paths (failure). If the player is ever built off-Main this
    // invariant breaks and this must become atomic.
    var resumptionApplying = false

    // Cold-start re-seek latch. Media3 loses the restored startPositionMs when prepare() resolves the
    // deferred StreamableMediaSource's placeholder->real timeline to the default position (0) — traced in
    // ExoPlayerImplInternal.resolvePositionForPlaylistChange. Armed at the restore-apply sites
    // (applyRestoreIfCold / onPlaybackResumption) with the restored current item's (mediaId, savedPositionMs),
    // consumed at the FIRST STATE_READY in PlayerEventListener, which re-seeks now that the real timeline
    // exists. mediaId-guarded so a track the user plays during the restore's buffering window can't be seeked
    // to the stale position. Main-only (same application-looper invariant as resumptionApplying above).
    var pendingRestoreSeek: Pair<String, Long>? = null

    // Route-state gate for the BT/car/AA "phantom PLAY" fix. True when there is NO external audio route
    // AND Android Auto is not connected — i.e. we are "post-disconnect". Written by PlayerService from
    // three signals (AudioDeviceCallback add/remove, the CarConnection observer, and an onCreate
    // getDevices probe that seeds it for the cold-open case after the service was killed), and read in
    // PlayerCallback.onMediaButtonEvent to swallow a phantom hardware KEYCODE_MEDIA_PLAY that a head unit
    // emits around disconnect. @Volatile: all reads/writes happen on the application looper today (the
    // AudioDeviceCallback is registered with a Main handler, the observer runs on Main, onMediaButtonEvent
    // is invoked on the app looper), so it is defensive insurance rather than strictly required.
    @Volatile
    var isPostDisconnect: Boolean = false

    data class Current(
        val index: Int,
        val mediaItem: MediaItem,
        val isLoaded: Boolean,
        val isPlaying: Boolean,
        val isPlaceholder: Boolean = false,
    ) {

        val context by lazy { mediaItem.context }
        val track by lazy { mediaItem.track }
        fun isPlaying(id: String?): Boolean {
            val same = mediaItem.mediaId == id
                    || context?.id == id
                    || track.album?.id == id
                    || track.artists.any { it.id == id }
            return isPlaying && same
        }

        companion object {
            fun Current?.isPlaying(id: String?): Boolean = this?.isPlaying(id) ?: false
        }
    }

    sealed class Radio {
        data object Empty : Radio()
        data object Loading : Radio()
        data class Loaded(
            val clientId: String,
            val context: EchoMediaItem,
            val cont: String?,
            val tracks: suspend (String?) -> Page<Track>?
        ) : Radio()
    }
}

// The shared cold-start restore snapshot (see PlayerState.restoreDeferred). One IO read fills it; the
// app-open apply uses items raw, onPlaybackResumption maps them through withUnloaded for the framework.
data class RestoreData(
    val items: List<MediaItem>,
    val index: Int,
    val pos: Long,
    val shuffle: Boolean,
    val repeat: Int,
)
