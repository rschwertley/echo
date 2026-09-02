package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.StatsDataSource
import androidx.media3.datasource.TeeDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import dev.brahmkshatriya.echo.extensions.exceptions.ExtensionNotFoundException
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtension
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.isClient
import dev.brahmkshatriya.echo.playback.MediaItemUtils
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.MediaItemUtils.isLoaded
import dev.brahmkshatriya.echo.playback.MediaItemUtils.retries
import dev.brahmkshatriya.echo.playback.PlayerCommands.getLikeButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getRepeatButton
import dev.brahmkshatriya.echo.playback.PlayerCommands.getShuffleButton
import dev.brahmkshatriya.echo.playback.PlayerState
import dev.brahmkshatriya.echo.playback.ResumptionUtils
import dev.brahmkshatriya.echo.playback.ShufflePlayer
import dev.brahmkshatriya.echo.playback.exceptions.PlayerException
import dev.brahmkshatriya.echo.playback.source.StreamableDataSource
import dev.brahmkshatriya.echo.playback.exceptions.TrackUnavailableException
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import dev.brahmkshatriya.echo.utils.HealthMonitor
import dev.brahmkshatriya.echo.utils.Serializer.rootCause
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.reflect.KClass

@OptIn(UnstableApi::class)
class PlayerEventListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaLibrarySession,
    private val currentFlow: MutableStateFlow<PlayerState.Current?>,
    private val extensions: ExtensionLoader,
    private val throwableFlow: MutableSharedFlow<Throwable>,
    private val fullQueueFlow: MutableStateFlow<List<MediaItem>>,
    private val isAndroidAutoConnected: () -> Boolean = { false },
    private val requestAudioFocus: () -> Unit = {},
    // Live PlayerState.activeLoadCount (>0 ⇒ a stream resolution is in flight). Wired from
    // PlayerService where PlayerState is in scope; this listener is not given PlayerState directly.
    private val activeLoadCount: () -> Int = { 0 },
    // Invoked when the timeline becomes non-empty (a queue was applied, from any source) — the success
    // clear for PlayerState.resumptionApplying. Fires on the app looper (Main), preserving that invariant.
    private val onQueueApplied: () -> Unit = {},
    // Returns-and-clears PlayerState.pendingRestoreSeek (the cold-start re-seek latch). Wired from
    // PlayerService like onQueueApplied; this listener is not given PlayerState directly. Returns null once
    // consumed, so it fires at most once per cold restore.
    private val consumeRestoreSeek: () -> Pair<String, Long>? = { null },
    // Non-consuming PEEK at PlayerState.pendingRestoreSeek — true iff the cold-start re-seek latch is armed.
    // Never clears it (unlike consumeRestoreSeek), so it can gate the saveCurrentPos 0-write below WITHOUT
    // stealing the latch the STATE_READY re-seek depends on. Wired from PlayerService like the others; fires
    // on the app looper (Main). A latch is armed only when the restored position was > 0, so "armed" means
    // "we restored to a known non-zero position that the placeholder timeline hasn't resolved yet" — exactly
    // the window in which a currentPosition of 0 is spurious and must never overwrite the good saved value.
    private val isRestoreSeekArmed: () -> Boolean = { false },
    private val healthMonitor: HealthMonitor? = null,
) : Player.Listener {

    // CACHED at construction, deliberately not `get() = session.player`.
    // MediaSession.getPlayer() -> MediaSessionImpl.getPlayerWrapper() calls verifyApplicationThread()
    // and throws IllegalStateException off the application looper (Media3 1.11.0; 1.10.1 had NO check,
    // so off-main reads silently returned torn state — that is what the AA metadata desync fixed in
    // 2ee949c6 looked like). Several uses below run inside scope.launch on Dispatchers.IO, so a
    // per-use accessor was already wrong today and would become fatal on 1.11.0.
    // This listener is constructed in PlayerService.onCreate on the MAIN thread, which is the player's
    // application looper, so the single read happens on the app thread and every use is then a plain
    // field access with no dispatch cost.
    // SAFE because MediaSession.setPlayer() is never called anywhere in the app (verified by grep) —
    // the reference cannot go stale. If that ever changes, this must become a withContext(Main) read.
    private val player = session.player

    // True only while an INTERNAL seek is in flight — a buffering-watchdog re-prepare OR an onPlayerError
    // retry (both stop→seek→prepare the current track). onPositionDiscontinuity's
    // latch-disarm reads it to tell such a seek — which Media3 delivers as
    // DISCONTINUITY_REASON_SEEK, indistinguishable by reason from a user seek (ExoPlayerImpl.seekTo sets
    // it unconditionally) — from a real user seek, so the watchdog does NOT steal the cold-start re-seek
    // latch. Plain var (not Atomic): every touch is on the app looper (Main). The watchdog body runs in
    // withContext(Dispatchers.Main), and the seek's discontinuity is delivered SYNCHRONOUSLY on that same
    // thread inside player.seekTo (updatePlaybackInfo → ListenerSet.flushEvents runs events inline), so the
    // set/clear reliably brackets the callback — the same single-thread invariant as resumptionApplying.
    private var internalSeekInFlight = false

    // Brackets an internal seek (buffering-watchdog re-prepare or onPlayerError retry) with
    // internalSeekInFlight so the SEEK discontinuity it triggers is not mistaken for a user seek. try/finally
    // so an unexpected throw can never strand the flag set.
    private inline fun internalSeek(block: () -> Unit) {
        internalSeekInFlight = true
        try { block() } finally { internalSeekInFlight = false }
    }

    // Durable-position ticker (the SAVE-side half of the mid-song-reboot fix). POSITION is otherwise written
    // only on discrete events (onPositionDiscontinuity / onIsPlayingChanged), so a song played straight
    // through never updates it after the AUTO_TRANSITION into it wrote 0 at song start — a reboot / OS memory-
    // kill / crash (none of which fire an event or reach onDestroy's flush) then resumes at 0. This snapshots
    // the live position periodically so the on-disk value stays fresh. Runs on the app looper (Main) so its
    // write SERIALIZES with the event saves — both go through the synchronous, atomic saveCurrentPosGated →
    // saveToQueue (tmp+rename), so there is never a concurrent POSITION-file write (do NOT move the write to
    // IO — that would break the serialization). Gated to actual playback via player.isPlaying (skips
    // buffering / paused / idle / released), and routed through saveCurrentPosGated so the isRestoreSeekArmed
    // gate still suppresses the placeholder 0 during the restore window. A child of `scope`, so it is
    // cancelled with the service in onDestroy (scope.cancel), and cannot fire between player.release() and
    // that cancel because both run synchronously on Main.
    // Launched from init{} (fire-and-forget, no stored Job handle): teardown is via scope.cancel() in
    // onDestroy, as the comment above describes. Same construction-phase launch and dependencies as the
    // former property-initializer form — only scope (a constructor param) is needed at launch-call time;
    // the body runs async (posted to Main + 5s delay) so player/saveCurrentPosGated are fully ready.
    init {
        scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                if (player.isPlaying) saveCurrentPosGated()
            }
        }
    }

    // True only while an INVOLUNTARY auto-skip's seekToNextMediaItem is in flight. onMediaItemTransition
    // reads it to SKIP persisting the resume pointer (saveIndex) for this advance, so CURRENT_ID stays on
    // the failed/stuck track: cold start then retries that track (recovering a transient token/network
    // failure) instead of over-advancing past it (the "resume one track ahead" bug). Same single-thread
    // bracket invariant as internalSeekInFlight — seekToNextMediaItem delivers onMediaItemTransition
    // SYNCHRONOUSLY on this (Main) thread inline (ListenerSet.flushEvents inside seekTo), so the flag is
    // reliably set when the callback observes it. Dedicated flag (NOT internalSeekInFlight, which gates the
    // restore-seek latch) and MARKER-gated, not reason-gated: an involuntary skip and a user's manual Next
    // both surface as reason=SEEK, so only this marker distinguishes them. finally-reset so it can't stick.
    private var involuntarySkipInFlight = false

    // Every skip in this listener is an INVOLUNTARY auto-skip (a failed/stuck current track). Route
    // them through here so ShufflePlayer removes the departing track WITHOUT pushing it to the play-
    // history back-stack (Seam 3) — Previous must never land back on a dead track that would re-fail.
    private fun skipInvoluntarily() {
        (player as? ShufflePlayer)?.suppressPushOnNextAdvance = true
        involuntarySkipInFlight = true
        try {
            player.seekToNextMediaItem()
        } finally {
            involuntarySkipInFlight = false
        }
    }

    private var pendingFullQueueUpdate: Job? = null
    private fun emitFullQueue() {
        pendingFullQueueUpdate?.cancel()
        pendingFullQueueUpdate = scope.launch(Dispatchers.Main) {
            delay(50)
            fullQueueFlow.value = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        }
    }

    // remove-on-advance fires onTimelineChanged (→ this listener) on EVERY advance, so an un-debounced
    // saveQueue would launch a fresh IO coroutine per track change — under rapid Next-mashing that's an
    // IO storm plus a read-snapshot-then-write race that can persist a stale index/queue (the cold-
    // start-wrong-track class). Debounce so a burst of advances coalesces into one save after it
    // settles. saveIndex (fired synchronously on each transition) keeps the index fresh meanwhile, and
    // recoverPlaylist's index coerce bounds any crash-in-window gap. isRearranging re-checked at fire.
    private var pendingSaveQueue: Job? = null
    private fun scheduleSaveQueue() {
        pendingSaveQueue?.cancel()
        pendingSaveQueue = scope.launch {
            delay(300)
            if ((player as? ShufflePlayer)?.isRearranging == true) return@launch
            ResumptionUtils.saveQueue(context, player)
        }
    }

    private fun updateCustomLayout() = scope.launch(Dispatchers.Main) {
        val item = player.currentMediaItem ?: return@launch
        val supportsLike = withContext(Dispatchers.IO) {
            extensions.music.getExtension(item.extensionId)?.isClient<LikeClient>() ?: false
        }
        val commandButtons = listOfNotNull(
            getShuffleButton(context, player.shuffleModeEnabled),
            getRepeatButton(context, player.repeatMode),
            getLikeButton(context, item).takeIf { supportsLike }
        )
        session.setCustomLayout(commandButtons)
    }

    private fun updateCurrentFlow() {
        val item = player.currentMediaItem
        if (item != null) {
            val isPlaying = player.isPlaying && player.playbackState == Player.STATE_READY
            currentFlow.value = PlayerState.Current(
                player.currentMediaItemIndex, item, item.isLoaded, isPlaying, false
            )
        } else if (player.mediaItemCount == 0 && currentFlow.value?.isPlaceholder == true) {
            // Keep the placeholder until we have real items or decide to clear
        } else {
            currentFlow.value = null
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem == null) return  // fired on player.release() with index=0; don't overwrite saved position
        updateCustomLayout()
        // Persist the current index so cold-start restore seeks to the correct track. mediaItem is the
        // new current item.
        val fullIndex = player.currentMediaItemIndex
        // Skip persisting the resume pointer for an INVOLUNTARY auto-skip (see involuntarySkipInFlight):
        // keep CURRENT_ID/INDEX on the failed track so cold start retries it instead of resuming one ahead.
        // Genuine advances and the user's manual Next (marker not set) persist as before; the debounced
        // saveQueue is untouched and still reconciles the persisted queue to the live post-skip state.
        if (!involuntarySkipInFlight)
            ResumptionUtils.saveIndex(context, fullIndex, mediaItem.mediaId)
        session.notifyChildrenChanged("recent", 1, null)
        retriedMediaId = null
        retriedWatchdogCount = 0
        // A fresh queue (replace / cold-restore) moves the current item with this reason; queue EDITS that
        // leave the current item in place (radio top-up append, etc.) and our own skips (SEEK) do not. So
        // this marks "a new queue that hasn't played anything yet", arming the removed-extension exhaustion
        // message for the next all-dead run.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            resolvedSinceQueueReplace = false
            // Re-arm the once-per-episode 5xx snackbar too: a new queue is a fresh context, so if the user
            // swaps queues mid-CDN-outage the new queue's server errors should notify again. (serverErrorNotified
            // otherwise only re-arms on a successful STATE_READY, i.e. the CDN recovering.)
            serverErrorNotified = false
        }
    }

    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        updateCustomLayout()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        emitFullQueue()
        if (timeline.windowCount > 0) onQueueApplied()
        // Post-resumption custom-layout push (display-only). A restored/fresh queue applies via
        // PLAYLIST_CHANGED here — AFTER onConnect (connect → resume → queue applied), so AA is stably
        // connected. Re-push so the full layout (incl. the like button the synchronous onConnect seed
        // couldn't include) reaches the connected controller instead of being lost in the connect race.
        // updateCustomLayout no-ops when currentMediaItem is null, so an empty timeline here is safe.
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) updateCustomLayout()
        if ((player as? ShufflePlayer)?.isRearranging != true) {
            scheduleSaveQueue()
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                bufferingWatchdog?.cancel()
                bufferingWatchdog = null
                if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                    armBufferingWatchdog()
                }
            }
        }
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            retriedMediaId = null
            retriedWatchdogCount = 0
        }
        if (!timeline.isEmpty() && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
            && player.playlistMetadata.title.isNullOrEmpty()
        ) {
            player.setPlaylistMetadata(
                MediaMetadata.Builder().setTitle(context.getString(R.string.queue)).build()
            )
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        updateCustomLayout()
        ResumptionUtils.saveRepeat(context, repeatMode)
        emitFullQueue()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateCustomLayout()
        ResumptionUtils.saveShuffle(context, shuffleModeEnabled)
        scope.launch { ResumptionUtils.saveQueue(context, player) }
        emitFullQueue()
    }

    // THE BREAKER LEAVES THE PLAYER SOMEWHERE NOTHING CAN RE-ARM FROM. reportAndResetConsecutiveSkips
    // ends in player.pause(), which flips playWhenReady ONLY - playbackState stays STATE_BUFFERING, because
    // the load never completed. Every arm site needs an event that then cannot happen:
    //   onPlaybackStateChanged fires on a TRANSITION, and BUFFERING -> BUFFERING is not one;
    //   onTimelineChanged needs TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
    //   the cold-grace self re-arm lives inside a job that has already completed.
    // So a play press after a trip changed playWhenReady and nothing else, and the player span 12+ minutes
    // with no watchdog behind it. The field evidence for that is exact: the 10:22:09 play produced a SINGLE
    // audio-focus request, where a real state transition produces two (onPlayWhenReadyChanged plus
    // AudioFocusListener's STATE_BUFFERING branch). One request means no transition happened.
    //
    // Safe to fire before a breaker trip: arming during ordinary buffering is exactly the intended
    // behaviour, and armBufferingWatchdog cancels any existing job first, so it cannot double-arm.
    // This is a MITIGATION, not the fix - it bounds the hang, it does not stop periods failing to prepare.
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady && player.playbackState == Player.STATE_BUFFERING) armBufferingWatchdog()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d("GladixPlayback", "onPlaybackStateChanged: state=$playbackState")
        if (playbackState == Player.STATE_BUFFERING) {
            armBufferingWatchdog()
        } else {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
        }
        if (playbackState == Player.STATE_READY) {
            // PROBE (2026-08-29) - see the field declarations. This is the only moment the current item's
            // timeline is final and the shouldLoadNextMediaPeriod gate is being evaluated against it, which
            // is why a LAST-READY value is the right subject for `dur`: shouldLoadNextMediaPeriod gates
            // loading the NEXT period against the CURRENTLY PLAYING one, so the track that reached READY is
            // exactly the period whose duration the gate reads. Main thread here, so session.player is safe
            // to touch under 1.11's app-thread enforcement. player.duration is on the Player interface, so
            // it reads correctly through the ShufflePlayer wrapper - unlike the `mime` field that used to
            // sit here, which did not (see below).
            //
            // ⚠️ DO NOT REINSTATE A `mime` FIELD AS `(player as? ExoPlayer)?.audioFormat` - IT IS DEAD.
            // `player` is `session.player`, and PlayerService builds the session with
            // ShufflePlayer(exoPlayer), which is a ForwardingPlayer - NOT an ExoPlayer. The safe cast
            // therefore always yields null, so the probe reported mime=none in every report it ever
            // produced, on every path, and read as a finding about the stalled track when it was a constant.
            // Removed 2026-09-01. A working version would need the wrapper unwrapped AND a per-item capture
            // point - audioFormat is only populated once decoding begins, so at a stall it can only ever
            // describe the PREVIOUS track. That is new instrumentation, not a repair.
            lastReadyDurationKnown = player.duration != C.TIME_UNSET
            resetConsecutiveSkips()
            // A track resolved successfully — the queue is not all-dead (removed-extension tracks never reach
            // READY). Suppresses the removed-extension exhaustion message for any queue that played anything.
            resolvedSinceQueueReplace = true
            // A track resolved, so any prior run of 5xx server errors has ended — re-arm the one-per-run
            // server-error snackbar for the next run.
            serverErrorNotified = false
            retried404MediaId = null
            retriedSocketMediaId = null
            networkRetryCount = 0
            // Cold-start re-seek: the saved position was lost when prepare() resolved the deferred source's
            // placeholder->real timeline to the default (0). The real timeline now exists (STATE_READY), so a
            // seek sticks. Position-only on the current window (no index form — sidesteps ShufflePlayer's
            // windowed-index seeks). Guarded so it fires exactly once and loses to a user action: mediaId must
            // still be the restored track (not one the user tapped mid-buffer), and currentPosition must still
            // be at the start (a user seek before this READY moves it past the belt and we leave it alone).
            consumeRestoreSeek()?.let { (id, pos) ->
                if (player.currentMediaItem?.mediaId == id
                    && player.currentPosition < RESTORE_SEEK_BELT_MS
                ) player.seekTo(pos)
            }
        }
    }

    private fun armBufferingWatchdog() {
        Log.d("GladixPlayback", "STATE_BUFFERING: ${player.currentMediaItem?.mediaId} \"${player.currentMediaItem?.mediaMetadata?.title}\"")
        // Start (or keep) the cold-resolution grace timer for the current item.
        val coldMediaId = player.currentMediaItem?.mediaId
        if (coldMediaId != coldBufferingMediaId) {
            coldBufferingMediaId = coldMediaId
            coldBufferingStart = System.currentTimeMillis()
            // PROBE (2026-08-29) - baseline for the `opens` delta, taken per ITEM rather than per arm so a
            // watchdog retry on the same track does not reset it. Anything the retries open still counts.
            // This guard is `coldMediaId != coldBufferingMediaId`, i.e. it fires when the current mediaId
            // CHANGES - it is not inside the STATE_READY branch above, so the baseline does not depend on a
            // track ever having reached READY.
            openCountAtItemStart = StreamableDataSource.openCount.get()
            // PROBE (2026-09-01) - same baseline, same reasoning, for the `bytes` delta.
            bytesReadAtItemStart = StreamableDataSource.bytesRead.get()
        }
        bufferingWatchdog?.cancel()
        bufferingWatchdog = scope.launch {
            delay(BUFFERING_WATCHDOG_MS)
            withContext(Dispatchers.Main) {
                if (player.playbackState != Player.STATE_BUFFERING) return@withContext
                // COLD-START SUPPRESSION: a first-time stream resolution is actively in flight
                // (current item not loaded AND a load running) and we're still inside the grace
                // window → the buffering is expected, not stuck. Re-arm and wait WITHOUT touching the
                // player: stop()+re-prepare() would cancel the running loadJob and restart the
                // resolution clock, skipping valid-but-slow cold tracks (the AA cold-connect bug).
                if (player.currentMediaItem?.isLoaded == false
                    && activeLoadCount() > 0
                    && System.currentTimeMillis() - coldBufferingStart < COLD_GRACE_MS
                ) {
                    Log.d("GladixPlayback", "Buffering watchdog: cold resolution in flight, re-arming")
                    armBufferingWatchdog()
                    return@withContext
                }
                // Preserve the pre-retry intent: a paused, still-loading restore (playWhenReady=
                // false) must re-prepare WITHOUT resuming, else the watchdog converts a paused
                // cold-start restore into active playback. Captured before stop()/pause() below.
                val wasPlaying = player.playWhenReady
                val currentMediaId = player.currentMediaItem?.mediaId
                if (retriedMediaId != currentMediaId) {
                    retriedMediaId = currentMediaId
                    retriedWatchdogCount = 1
                    Log.d("GladixPlayback", "Buffering watchdog: retrying $currentMediaId (attempt 1/$maxWatchdogRetries)")
                    // Position-only seek: stop() keeps the current item, so re-selecting it by index
                    // isn't needed.
                    val savedPosition = player.currentPosition
                    player.stop()
                    internalSeek { player.seekTo(savedPosition) }
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                        requestAudioFocus()
                    }
                } else if (retriedWatchdogCount < maxWatchdogRetries) {
                    retriedWatchdogCount++
                    Log.d("GladixPlayback", "Buffering watchdog: retrying $currentMediaId (attempt $retriedWatchdogCount/$maxWatchdogRetries)")
                    val savedPosition = player.currentPosition
                    player.stop()
                    internalSeek { player.seekTo(savedPosition) }
                    player.prepare()
                    if (wasPlaying) {
                        player.play()
                        requestAudioFocus()
                    }
                } else {
                    retriedMediaId = null
                    retriedWatchdogCount = 0
                    Log.d("GladixPlayback", "Buffering watchdog fired: skipping ${player.currentMediaItem?.mediaId}")
                    // Stall-mode discriminator for the otherwise bare "StuckBuffering" cause. This path
                    // passes recordSkip(null) -- there is no exception to describe -- so before this the
                    // report said only "it stalled", which is exactly what the class name already said.
                    // Build-1055/1056 produced four reports reading
                    // lastCauses=StuckBuffering,StuckBuffering,StuckBuffering and they were unactionable.
                    // The four modes these three fields separate:
                    //   loaded=false loads=1+ -> extension resolve still in flight, never returned a stream
                    //   loaded=false loads=0  -> resolve ended without producing a source
                    //   loaded=true  buf=0    -> source opened, zero bytes arrived (CDN / network stall)
                    //   loaded=true  buf=some -> bytes arriving, just too slowly for BUFFERING_WATCHDOG_MS
                    // and three context fields that say WHICH failure this is a case of:
                    //   item= 1st / same / next -- three trips on three tracks is a queue- or source-wide
                    //         fault; three on ONE track is ours, and reachable: skipInvoluntarily() is
                    //         seekToNextMediaItem(), which under REPEAT_MODE_ONE resolves to the SAME
                    //         index, so the breaker can trip without the queue ever advancing.
                    //   net=  up / down / ? -- separates "this device lost the network" from "the source
                    //         is unreachable while we are online", which no other field here can. Read
                    //         from ConnectivityManager rather than App.networkFlow because this listener
                    //         is not given App; runCatching because a diagnostic must never throw.
                    //   play= yes / no -- playWhenReady at the trip. The watchdog arms on ANY
                    //         STATE_BUFFERING, so a PAUSED restore that stalls can trip the breaker with
                    //         the user never having pressed play. Not inferable from the crash keys:
                    //         is_playing is false throughout STATE_BUFFERING regardless of intent.
                    //
                    // Deliberately NOT added, because they carry no information here:
                    //   time-in-buffering -- continuous (see the cardinality note below), and the grace
                    //     expiry it would show is already implied: reaching this branch with loaded=false
                    //     loads=1+ can ONLY happen after COLD_GRACE_MS elapsed, or the cold branch above
                    //     would have re-armed instead.
                    //   retriedWatchdogCount -- always maxWatchdogRetries here; a constant.
                    //   mediaId / track title -- extension-authored, would need scrubbing, and item=
                    //     answers the only question they were wanted for. extensionId already attributes.
                    //
                    // (!) EVERY FIELD MUST STAY LOW-CARDINALITY. HealthMonitor.report() dedupes on
                    // simpleName + message, and lastCauses is PART of that message, so any continuous value
                    // here -- elapsed ms, a buffered-ms count, a track id -- gives every trip a unique
                    // signature, defeats the 10-minute cooldown and turns this back into spam. That is the
                    // same trap the deliberately-constant message on DataSourceTeardownRaceException exists
                    // to avoid. Hence the booleans, the "2+" clamp and the bucketed net/item: the ceiling
                    // is 2 x 3 x 2 x 3 x 3 x 2 = 216 signatures, but that is a CEILING and not a rate --
                    // the fields are strongly correlated (loaded=true forces loads=0; net=down forces
                    // buf=0) and every one of them is stable for the duration of an episode, so a
                    // repeating condition still collapses to one report per cooldown while a CHANGE of
                    // condition reports immediately. What would actually break the cooldown is a field
                    // that MOVES for uninteresting reasons; that is the test to apply to anything you add
                    // here, not the size of the product. Bucket anything new; never interpolate a raw
                    // number. Every field is ours - a player/system read, never an extension-authored
                    // string - so none of them need scrubbing, and the mediaId stays out for exactly that
                    // reason (extensionId already attributes, and item= answers what it was wanted for).
                    val loads = activeLoadCount()
                    val bufferedAhead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    // currentMediaId, not a fresh read: it is captured at the top of this withContext and
                    // nothing has touched the player on this branch yet, so the two are identical here.
                    val item = when {
                        lastWatchdogSkipMediaId == null -> "1st"
                        lastWatchdogSkipMediaId == currentMediaId -> "same"
                        else -> "next"
                    }
                    lastWatchdogSkipMediaId = currentMediaId
                    val net = runCatching {
                        val cm = context.getSystemService(ConnectivityManager::class.java)
                        if (cm?.activeNetwork == null) "down" else "up"
                    }.getOrDefault("?")
                    // PROBE (2026-08-29) - probeDetail() carries dur/opens/mime; the fields before it are
                    // watchdog-local (item, play) or cheap player reads, so they stay here.
                    recordSkip(
                        null,
                        detail = "loaded=${player.currentMediaItem?.isLoaded == true} " +
                            "loads=${if (loads > 2) "2+" else "$loads"} " +
                            "buf=${if (bufferedAhead > 0L) "some" else "0"} " +
                            "item=$item net=$net play=${if (wasPlaying) "yes" else "no"} " +
                            probeDetail()
                    )
                    if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                        reportAndResetConsecutiveSkips(player.currentMediaItem?.extensionId, "pause")
                        player.pause()
                        return@withContext
                    }
                    // hasNextMediaItem() compares the inner full index against the full count — a
                    // correct end-of-queue guard.
                    if (!player.hasNextMediaItem()) {
                        player.pause()
                        return@withContext
                    }
                    if (isAndroidAutoConnected()) {
                        player.pause()
                        delay(50)
                    }
                    // Cross-cancel the error-driven skip so the two skip triggers can't both advance this
                    // one stuck track (this watchdog IS the bufferingWatchdog job; launchInvoluntarySkip
                    // cancels it in the reverse direction).
                    involuntarySkipJob?.cancel()
                    internalSeek { player.seekTo(0) }
                    skipInvoluntarily()
                    player.prepare()
                    if (wasPlaying) player.play()
                }
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (player.mediaItemCount == 0) return  // fired during/after player.release(); position is 0
        saveCurrentPosGated()
    }

    // The saveCurrentPos gate (fix 1). NEVER persist a 0 over the good saved position while the cold-start
    // re-seek latch is armed: a currentPosition of 0 during that window is the unresolved placeholder
    // timeline, never a real user position (the latch is armed only when the restored position was > 0).
    // Trigger-independent — it drops BOTH the watchdog's seek-to-0 write and any timeline-resolution
    // 0-discontinuity, because both land in the same pre-first-STATE_READY window. Legit saves are untouched:
    // a real pause carries its real P (> 0, ungated); a genuinely-at-0 queue never armed the latch, so its 0
    // persists normally. Peeks the latch NON-destructively (isRestoreSeekArmed) — reading it via
    // consumeRestoreSeek would clear it and re-open the very latch theft fix 2 closes.
    private fun saveCurrentPosGated() {
        val position = player.currentPosition
        if (position == 0L && isRestoreSeekArmed()) return
        ResumptionUtils.saveCurrentPos(context, position)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_MEDIA_METADATA_CHANGED,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED
            )
        ) {
            updateCurrentFlow()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
    ) {
        if (player.mediaItemCount == 0) return  // fired during player.release(); position is 0
        // A user seek before the cold-start re-seek fires must win — disarm the latch. Our own re-seek also
        // lands here, but it consumed the latch first, so this is a no-op for it. EXCLUDE internal
        // (buffering-watchdog) seeks: Media3 delivers them as DISCONTINUITY_REASON_SEEK too (indistinguishable
        // by reason), so without the flag the watchdog would steal the latch and the corrective re-seek would
        // never fire (fix 2).
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !internalSeekInFlight) consumeRestoreSeek()
        saveCurrentPosGated()
    }

    companion object {
        // RUNTIME class names of the media3 datasource close() cascade, resolved from the classes
        // themselves so each string carries whatever R8 renamed (or merged) it to in this build. Compared
        // against StackTraceElement.className, which is also a runtime name — see the long note at the
        // isDataSourceTeardownRace check for why literal source strings cannot work here.
        // The set spans the whole cascade because inlining moves the throwing frame: the 1052 report had
        // SimpleCache.commitFile inlined into CacheDataSink, so a SimpleCache-only match found nothing.
        // DataSourceUtil is deliberately EXCLUDED: R8 merged it into a class that also hosts Ac4Util,
        // HctSolver and SntpClient, so matching its runtime name could swallow unrelated ISEs.
        private val dataSourceRuntimeClassNames: Set<String> = setOf(
            CacheDataSource::class.java.name,
            CacheDataSink::class.java.name,
            TeeDataSource::class.java.name,
            ResolvingDataSource::class.java.name,
            StatsDataSource::class.java.name,
            SimpleCache::class.java.name,
        )

        private const val BUFFERING_WATCHDOG_MS = 5_000L
        // Cold-start re-seek belt: only re-apply the saved position if the current position is still at the
        // start. A user seek before the first STATE_READY moves it past this, and we leave their choice.
        private const val RESTORE_SEEK_BELT_MS = 1_000L
        // Durable-position snapshot interval. POSITION is otherwise written only on discrete events
        // (seek/pause/transition), so a straight-through song never refreshes it after the transition wrote 0
        // at song start — a reboot/OS-kill/crash then resumes at 0. 5s loses at most ~5s of position and
        // writes a tiny POSITION-only file at most 12×/min while playing (negligible).
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        // ≥ Deezer stream-resolution ceiling: DeezerApi clientNP connect 15s + read 10s;
        // getContentLength 10s. If clientNP ever gains a callTimeout, anchor to that instead.
        private const val COLD_GRACE_MS = 25_000L
        // Bounds for enriched skip-cause reporting (safeCause) — keep the Crashlytics non-fatal small and
        // spiral-proof: per-cause detail is capped, and only maxConsecutiveUnavailableSkips (3) causes are
        // ever joined, so lastCauses stays ~a few hundred chars regardless of how nested a message is.
        // 140 -> 200 (2026-08-29). safeCause now also carries probeDetail() on the ERROR path, where it
        // competes with code/ext/cls/http/msg for the budget; .take() truncates from the END, so at 140 a
        // long message lost its tail — and the message is what identifies the fault.
        //
        // 80/200 -> 256/400 (2026-09-01). ⚠️ THE TWO MUST BE RAISED TOGETHER. The message is nested INSIDE
        // the cause: safeCause builds `listOfNotNull(code, ext, cls, detail, http, msg).joinToString(" ")`
        // and then `.take(MAX_CAUSE_LEN)`, so raising MAX_MSG_LEN alone changes nothing — MAX_CAUSE_LEN
        // clips the joined string regardless. That is what made Cached's wrong-item guard unreadable: it
        // reports BOTH ids ("expected X, got Y"), but the reports arrived cut at 80 chars, mid-percent-
        // escape, before ", got" was ever reached — so it read as though only the expected id was logged.
        // Sizing: the guard now elides each id to 95 chars (Cached.idForMessage), giving a message of
        // ~240; MAX_MSG_LEN 256 clears that, and MAX_CAUSE_LEN 400 leaves ~140 for code/ext/cls/detail/
        // http alongside it on the ERROR path. 3 x 400 = 1200 chars for lastCauses.
        // THIS IS A SELF-IMPOSED BUDGET, NOT AN EXTERNAL LIMIT. Verified 2026-08-29: nothing parses these
        // strings, and lastCauses reaches Crashlytics only inside the HealthException MESSAGE — no custom
        // key carries it (the keys are health_report_type / throwing_extension_id / extension_id /
        // player_state / is_playing, all short), so Crashlytics'''s 1024-char CUSTOM KEY limit is not the
        // binding constraint here. The one place the message is reused is report()'''s dedupe signature
        // (simpleName + message); for ConsecutiveSkipException that is Scope.MEMORY_ONLY, i.e. a HashMap
        // key with no length limit, and a longer string does not change the signature CARDINALITY because
        // identical content still produces an identical signature. Safe to move again if needed.
        private const val MAX_MSG_LEN = 256
        private const val MAX_CAUSE_LEN = 400
        // Extension display names are author-declared and unbounded; cap independently so a long one can't
        // eat the MAX_CAUSE_LEN budget the errorCodeName/type/message actually need.
        private const val MAX_EXT_NAME_LEN = 24
    }

    private val maxRetries = 3
    private val maxSingleItemRetries = 1
    private var currentRetries = 0
    private var last: KClass<*>? = null

    private val maxConsecutiveUnavailableSkips = 3
    private var consecutiveUnavailableSkips = 0
    // The safeCause() of each skip in the current run, oldest→newest, bounded to maxConsecutiveUnavailableSkips.
    // Moves in lockstep with consecutiveUnavailableSkips AND recentSkipRunHadError below: recordSkip()
    // advances all three, resetConsecutiveSkips() clears all three — they are never mutated apart, so a
    // reported run can never carry a cause, a count or a family from a previous run.
    private val recentSkipCauses = ArrayDeque<String>()

    // Picks the HealthException FAMILY for this run: false -> ConsecutiveSkipStallException (every skip was
    // the buffering watchdog's recordSkip(null)), true -> ConsecutiveSkipErrorException. Crashlytics groups
    // on the class, so this is what lets a stall storm be muted without hiding a real error — see the long
    // note on HealthMonitor.ConsecutiveSkipException.
    //
    // A FLAG, NOT A SCAN OF recentSkipCauses. Deriving it by looking for "StuckBuffering" in the joined
    // string would reintroduce exactly the message-parsing fragility that HealthException's `val` fields
    // exist to remove: the first reformat of safeCause() would silently misfile every report.
    //
    // Sticky over the run, which is exact rather than approximate here: every recordSkip() call site is
    // immediately followed by the `>= maxConsecutiveUnavailableSkips` trip check, so the counter never
    // reaches 4 and the deque's removeFirst() never actually evicts. The flag therefore always describes
    // the same set of skips that recentSkipCauses reports. If a future skip site ever omits that trip
    // check, an early error could scroll out of the causes while this stayed true — reinstate the check
    // rather than weakening this.
    //
    // Third member of the reset trio. consecutiveUnavailableSkips, recentSkipCauses and this MUST be
    // mutated together (recordSkip advances all three, resetConsecutiveSkips clears all three) or a report
    // can carry one run's count with another run's family.
    private var recentSkipRunHadError = false

    // True once a track has resolved to STATE_READY since the queue was last set fresh (reset below on a
    // PLAYLIST_CHANGED media-item transition). Removed-extension tracks fail during resolution and never
    // reach READY, so this stays false only when the WHOLE queue was unplayable — the sole case where the
    // removed-extension exhaustion message should fire (so a normal session that merely ends on a couple of
    // removed tracks stays silent).
    private var resolvedSinceQueueReplace = false

    // Gates the 5xx "server error" snackbar to once per run of server errors — set on the first skip caused
    // by a 5xx, reset on the next STATE_READY (a track resolved, so the run ended). Keeps a burst of CDN 5xx
    // to a single message instead of one per skipped track.
    private var serverErrorNotified = false

    private var bufferingWatchdog: Job? = null
    // Serializes the involuntary auto-skip coroutine (error-driven skip-to-next). A 403 cascade fires an
    // auto-skip per failed track; without this guard those pause->delay->skip->prepare->play coroutines
    // could stack and over-skip. launchInvoluntarySkip() cancels any prior in-flight skip AND the
    // buffering watchdog (the other skip trigger) so at most one involuntary skip is pending. Cancel only
    // lands at the delay(50) suspension — everything after it is synchronous on Main — so a cancelled
    // coroutine never advanced, hence no over-skip and no dropped skip (the latest trigger always skips).
    private var involuntarySkipJob: Job? = null
    private fun launchInvoluntarySkip(body: suspend CoroutineScope.() -> Unit) {
        bufferingWatchdog?.cancel(); bufferingWatchdog = null
        involuntarySkipJob?.cancel()
        involuntarySkipJob = scope.launch(Dispatchers.Main, block = body)
    }
    // Cold-resolution grace timer, keyed to the current item: restarts when the current mediaId
    // changes (a new buffering episode) and persists across watchdog re-arms of the same item.
    // Keyed by mediaId rather than reset via player callbacks, so it survives the
    // onMediaItemTransition / PLAYLIST_CHANGED events that fire as part of the cold-restore setMediaItems.
    private var coldBufferingStart = 0L
    private var coldBufferingMediaId: String? = null
    private var retriedMediaId: String? = null
    private var retriedWatchdogCount = 0
    private val maxWatchdogRetries = 1
    // Item of the PREVIOUS watchdog skip in the current breaker run - the only thing that can tell three
    // trips on three tracks from three trips on ONE. Cleared in resetConsecutiveSkips() so it moves in
    // lockstep with recentSkipCauses and never compares across runs. NOT foldable into retriedMediaId:
    // that one is cleared at the top of this same branch (it scopes the per-track RETRY, not the run).
    private var lastWatchdogSkipMediaId: String? = null

    // PROBE (2026-08-29) - the two faults the 1059 captures separated, neither of which the existing
    // detail fields can see. REMOVE WITH THE PROBE.
    //
    // FAULT 1, the advance that was never queued. MediaPeriodQueue.shouldLoadNextMediaPeriod():209-215 is
    //   loading == null || (!loading.info.isFinal && loading.isFullyBuffered()
    //                       && loading.info.durationUs != C.TIME_UNSET && length < MAX_BUFFER_AHEAD)
    // There is NO LoadControl term and NO allocator term in it - which is what refuted the allocator
    // hypothesis before it cost a build. A track played for 131 seconds and emitted no prepareSourceInternal
    // for the next item at all, so that gate was shut for the whole track. Of its two candidate terms,
    // durationUs == C.TIME_UNSET is the one a stream property could explain. `dur` reads it.
    //
    // FAULT 2, the forced retry that never started. queue.clear() nulls `loading`, so after player.stop()
    // the gate reopens via the first branch and a holder IS enqueued and prepared - yet nine createPeriod
    // calls produced zero opens. `opens` is the observable that separates this from fault 1.
    //
    // `mime` tests whether fault 1 is per-stream: FLAC carries total samples in STREAMINFO so duration is
    // always known, while MP3 without a Xing/VBRI header needs a content length to divide. If failures are
    // MP3 and successes are FLAC, the duration hypothesis holds; if both containers appear on both sides it
    // is refuted, and `opens` still answers fault 2. media3-authored, never extension text - no scrubbing.
    //
    // Sampled at STATE_READY, NOT at the watchdog tick: by the tick the player has been stopped and
    // re-prepared repeatedly and the reading describes the wreckage, not the state that shut the gate.
    private var lastReadyDurationKnown: Boolean? = null
    private var bytesReadAtItemStart = 0L
    private var openCountAtItemStart = 0

    // The three probe fields rendered for a detail string. ONE helper, called from EVERY recordSkip site,
    // because the fields were originally built inline in the buffering watchdog and therefore reported on
    // the watchdog/breaker path ONLY. The error path has six recordSkip sites of its own (404, socket,
    // network, the missing-file/401/malformed/timeout family, maxRetries, per-item retries) and every one
    // of them passed detail = null, so a stall that surfaced through onPlayerError carried no probe data at
    // all - the instrument was blind on exactly the path a StuckPlayerDetector report takes.
    // Cheap and side-effect free: three field reads and an AtomicInteger get. REMOVE WITH THE PROBE.
    private fun probeDetail(): String {
        val opens = (StreamableDataSource.openCount.get() - openCountAtItemStart).coerceAtLeast(0)
        val dur = when (lastReadyDurationKnown) {
            true -> "set"
            false -> "unset"
            null -> "?"
        }
        // Bucketed, not absolute: the question is "did bytes arrive", not how many, and a raw count would
        // be a continuous value in a string that feeds report()'s dedupe signature - the trap the whole
        // field set is built to avoid. Thresholds chosen against what the fault actually needs: an audio
        // extractor declares its tracks from a few KB of header, so 64k already exceeds anything
        // preparation could be waiting on, and 1M+ with nothing prepared is unambiguous.
        //   0     -> the source opened and delivered nothing: the connection is the fault
        //   <64k  -> trickling: too little to prepare, consistent with a dying connection
        //   <1M   -> substantial delivery; not a connection problem
        //   1M+   -> plenty arrived and the player still never prepared: the fault is downstream of
        //            delivery, in the extractor/prepare path (see StreamableDataSource.bytesRead)
        // Deliberately NOT expressed as a fraction of the source length: that would need the length
        // threaded out of open(), and the discrimination being asked for does not need it - a megabyte
        // with nothing prepared already settles it.
        val delta = (StreamableDataSource.bytesRead.get() - bytesReadAtItemStart).coerceAtLeast(0L)
        val bytes = when {
            delta == 0L -> "0"
            delta < 64 * 1024 -> "<64k"
            delta < 1024 * 1024 -> "<1M"
            else -> "1M+"
        }
        return "dur=$dur opens=${if (opens > 1) "2+" else "$opens"} bytes=$bytes"
    }
    private var retried404MediaId: String? = null
    private var retriedSocketMediaId: String? = null
    // Retry BUDGET for the network-down branch, not a per-track latch. It was a single mediaId compared
    // only against the current one, which cannot bound retries ACROSS tracks: alternating A -> B -> A the
    // id never matches the previous, so every track retried every time it came round and the hold was
    // never reached. STATE_READY is the only reset (see the block that clears retried404/retriedSocket),
    // and while the network is down STATE_READY never fires - so the old form was an unbounded retry
    // storm for exactly as long as the outage lasted. A counter bounds the outage, not the track.
    private var networkRetryCount = 0
    private val maxNetworkRetries = 2

    // Chain-walk, NOT rootCause. `rootCause` (Serializer.kt:35) is the DEEPEST node, and Android's real
    // network chains bottom out in PLATFORM types: UnknownHostException -> android.system.GaiException,
    // and ConnectException -> android.system.ErrnoException. So `rootCause is UnknownHostException` and
    // `rootCause is SocketException` were BOTH always false, and the DNS-hold and socket-retry branches
    // in onPlayerError have never fired since they were written. Build 1037 proved it: the recorded skip
    // causes read "GaiException android_getaddrinfo failed: EAI_NODATA" and "ErrnoException isConnected
    // failed: ECONNREFUSED" — exactly the nodes rootCause resolves to.
    // ⚠️ PATTERN: never type-check a wrapped exception against a non-chain-walking accessor.
    private fun Throwable.anyCause(predicate: (Throwable) -> Boolean): Boolean =
        generateSequence(this) { it.cause }.any(predicate)

    // The ONLY way to advance the breaker: increments the counter and records this skip's cause together,
    // so they cannot drift. Called at every skip site; never at the exempt (5xx / removed-extension) sites,
    // which do not skip. cause == null for the buffering watchdog (a stuck resolve, no error object).
    private fun recordSkip(
        cause: Throwable?, playbackError: PlaybackException? = null, detail: String? = null
    ) {
        consecutiveUnavailableSkips++
        recentSkipCauses.addLast(safeCause(cause, playbackError, detail))
        // Mirrors safeCause's own test: `cause == null` is what renders as "StuckBuffering", so the two can
        // never disagree about which family a skip belongs to. playbackError is included because a skip
        // carrying only a PlaybackException is still a real error, not a stall.
        if (cause != null || playbackError != null) recentSkipRunHadError = true
        if (recentSkipCauses.size > maxConsecutiveUnavailableSkips) recentSkipCauses.removeFirst()
    }

    // The ONLY way to clear the breaker: zeroes the counter and the causes together. Both reset points
    // (STATE_READY and the trip) go through here, so the two fields always reset atomically.
    private fun resetConsecutiveSkips() {
        consecutiveUnavailableSkips = 0
        recentSkipCauses.clear()
        recentSkipRunHadError = false
        lastWatchdogSkipMediaId = null
    }

    // Enriched skip-cause detail (build-985 "lastCauses=Exception,Exception,Exception" was uselessly bare —
    // it stored only the class simpleName, hiding the real reason). Now carries, most-diagnostic first:
    //   • the Media3 PlaybackException errorCodeName (ERROR_CODE_IO_BAD_HTTP_STATUS / _PARSING_ / etc.) —
    //     the true IO-vs-parse-vs-source discriminator, previously not captured at all;
    //   • the OWNING extension's name (ext:<name>), walked off the AppException in the chain;
    //   • the exception type + HTTP responseCode (401/403/404 = token/auth vs missing);
    //   • the DEEPEST cause's message, URL-STRIPPED and length-capped.
    // Message handling is the security-sensitive part: Media3 embeds the signed CDN URL (token/hmac) in the
    // raw message, so any URL is replaced with <url> and the whole thing is hard-capped — never emitted raw.
    // Bounded by construction (MAX_CAUSE_LEN per cause × 3 causes) so a nested message can't spiral.
    //
    // ext:<name> closes the Unified attribution blind spot. ConsecutiveSkipException's extensionId comes from
    // the MediaItem, which for a Unified-browsed track is "unified" — while the actual failure came from a
    // SUB-extension. UnifiedExtension.client wraps with the sub-extension's Metadata and toAppException
    // returns an existing AppException as-is, so the sub-extension's identity IS in the chain; it was simply
    // never read. deepestSafeMessage() can't recover it either: it walks to the DEEPEST message (the raw
    // "Error 403"), skipping AppException.Other's "Error 403 error in Spotify". Placed early so it survives
    // the MAX_CAUSE_LEN truncation — attribution is the part that was missing entirely.
    // Same scrub/cap treatment as the message: the name is an author-declared third-party string, so it is
    // never emitted raw, and its own cap keeps it from eating the budget the real diagnosis needs.
    // `detail` is caller-supplied context for a skip that has NO exception to describe -- today only the
    // buffering watchdog, whose recordSkip(null) otherwise renders as the bare word "StuckBuffering". It is
    // placed immediately after the class name because it QUALIFIES it; the http/msg fields are always null
    // on that path, so nothing is displaced. Callers must keep it low-cardinality -- see the note at the
    // watchdog's call site for why (report()'s dedupe signature includes this string).
    private fun safeCause(
        cause: Throwable?, playbackError: PlaybackException? = null, detail: String? = null
    ): String {
        val code = playbackError?.errorCodeName
        val ext = playbackError?.appExtensionName()?.let { "ext:$it" }
        val cls = cause?.let { it::class.simpleName ?: "Unknown" } ?: "StuckBuffering"
        val http = (cause as? HttpDataSource.InvalidResponseCodeException)?.let { "HTTP ${it.responseCode}" }
        val msg = cause?.deepestSafeMessage()
        return listOfNotNull(code, ext, cls, detail, http, msg).joinToString(" ").take(MAX_CAUSE_LEN)
    }

    // Name of the extension that OWNS this failure: the first AppException in the chain (ExtensionUtils.get
    // wraps every extension call, so one is present for any extension-sourced error). Walked from the full
    // PlaybackException, not from rootCause — rootCause is the DEEPEST node and the AppException sits above
    // it. Null when no extension was involved (pure Media3 data-source failure, or the buffering watchdog's
    // recordSkip(null)), in which case the emitted string is byte-identical to before this field existed.
    private fun Throwable.appExtensionName(): String? {
        var t: Throwable? = this
        while (t != null) {
            (t as? AppException)?.let { return it.extension.name.scrubbed(MAX_EXT_NAME_LEN) }
            t = t.cause
        }
        return null
    }

    // Deepest non-null message in the cause chain, URL-stripped (signed-CDN-token guard) and capped. Returns
    // null if every message is null (the type alone then carries the cause).
    private fun Throwable.deepestSafeMessage(): String? {
        var t: Throwable? = this
        var last: String? = null
        while (t != null) {
            t.message?.let { last = it }
            t = t.cause
        }
        return last?.scrubbed(MAX_MSG_LEN)
    }

    // The shared guard for any third-party string that reaches Crashlytics: strip URLs (signed CDN tokens
    // live in them) and hard-cap. Extracted so the extension name gets exactly the same treatment as the
    // message rather than a second, drifting copy of the rule.
    private fun String.scrubbed(max: Int) =
        replace(Regex("https?://\\S+"), "<url>").trim().take(max)

    // Single convergence point for EVERY breaker trip, so one log line here covers all call sites. `outcome`
    // is the caller's intent ("stop" for the error paths, "pause" for the buffering watchdog) — the two end
    // in different player states and therefore different session/notification behaviour, and the log is the
    // only way to tell them apart after the fact. It cannot be derived here: player.playbackState at this
    // moment is the PRE-action state (already IDLE from the error), not the resulting one.
    // Logged BEFORE resetConsecutiveSkips(), which zeroes both the count and the causes.
    private fun reportAndResetConsecutiveSkips(extensionId: String?, outcome: String) {
        Log.d(
            "GladixPlayback",
            "Consecutive-skip breaker TRIPPED after $consecutiveUnavailableSkips skips " +
                "(ext=${extensionId ?: "unknown"}, outcome=$outcome): " +
                recentSkipCauses.joinToString(" | ")
        )
        // Family picks the CLASS, which is what Crashlytics groups on — a stall storm and a real error no
        // longer share an issue. Both carry the identical message, so lastCauses and its probe fields are
        // unchanged and the dedupe partitioning is the same as before the split.
        val causes = recentSkipCauses.joinToString(",")
        val skips = consecutiveUnavailableSkips
        val ext = extensionId ?: "unknown"
        healthMonitor?.report(
            if (recentSkipRunHadError) HealthMonitor.ConsecutiveSkipErrorException(skips, ext, causes)
            else HealthMonitor.ConsecutiveSkipStallException(skips, ext, causes),
            HealthMonitor.Scope.MEMORY_ONLY, 10 * 60 * 1000L
        )
        resetConsecutiveSkips()
    }

    override fun onPlayerError(error: PlaybackException) {
        val cause = error.cause ?: error
        val rootCause = cause.rootCause
        val mediaItem = player.currentMediaItem

        if (rootCause is CancellationException && rootCause !is TimeoutCancellationException) {
            Log.d("GladixPlayback", "onPlayerError: ignoring CancellationException for ${mediaItem?.mediaId}")
            return
        }

        // Login-required is non-transient: every queued track fails identically, so letting it fall
        // through to the generic tail cascades retries/skips across the whole queue. classify() chain-
        // walks the wrapped form the extension actually produces (ExoPlaybackException -> IOException ->
        // AppException.LoginRequired, which has no cause so a rootCause type-check misses it). Emit once
        // and stop cleanly on the failing track: stop() preserves getPlayerError() -> the phone "Login"
        // snackbar (getMessage.rootCause) and the Lever B "Sign in" AA tile both show once, and the queue
        // is kept so play() after logging in re-resolves this same track.
        if (classify(error) == ErrorCategory.LoginOrAuth) {
            scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
            player.stop()
            return
        }

        if (rootCause is HttpDataSource.InvalidResponseCodeException && rootCause.responseCode == 404) {
            val currentMediaId = mediaItem?.mediaId
            if (retried404MediaId != currentMediaId) {
                retried404MediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: 404 for $currentMediaId, retrying with stop/prepare")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                retried404MediaId = null
                Log.d("GladixPlayback", "onPlayerError: 404 retry failed for $currentMediaId, skipping")
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
                    player.stop()
                    return
                }
                val hasMore = player.hasNextMediaItem()
                if (!hasMore) {
                    player.stop()
                    return
                }
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        // HTTP 5xx (500/502/503/504) = a transient REMOTE server/CDN error — not our bug. Moved off the
        // generic tail: report to messageFlow (user snackbar, NO Crashlytics non-fatal, same category as the
        // removed-extension fix) and EXEMPT from consecutiveUnavailableSkips so a CDN wobble can't trip the
        // circuit breaker and halt an otherwise-good queue. Otherwise this is the generic tail's per-item path
        // unchanged: ONE immediate retry (replaceMediaItem/withRetry — no backoff; backoff-retry is parked as
        // its own task), then skip. Bounded by end-of-queue (hasNextMediaItem): a fully-500ing CDN skips
        // monotonically to the end and stops — no loop, no spin.
        if (rootCause is HttpDataSource.InvalidResponseCodeException
            && rootCause.responseCode in 500..599
        ) {
            if (mediaItem == null) return
            val index = player.currentMediaItemIndex
            if (mediaItem.retries >= maxSingleItemRetries) {
                // Retry exhausted for this track — skip. Report ONCE per run of server errors (serverErrorNotified,
                // reset on the next STATE_READY) so a burst of 5xx shows a single snackbar, not one per track.
                if (!serverErrorNotified) {
                    serverErrorNotified = true
                    scope.launch {
                        extensions.app.messageFlow.emit(
                            Message(context.getString(R.string.server_error_skipping))
                        )
                    }
                }
                if (!player.hasNextMediaItem()) {
                    player.stop()
                    return
                }
                skipInvoluntarily()
            } else {
                player.replaceMediaItem(index, MediaItemUtils.withRetry(mediaItem))
            }
            player.prepare()
            player.play()
            return
        }

        // Computed HERE, above the socket branch, because ConnectException IS a SocketException: without
        // this precedence ECONNREFUSED would take the retry-then-SKIP path below and advance the breaker,
        // which is exactly the build-1037 outcome we are removing. A network-level failure is never a
        // per-track fault, so it must reach the hold branch instead. Consumed again at the hold branch.
        val isNetworkDown = error.anyCause {
            it is UnknownHostException || it is UnresolvedAddressException ||
                it is ConnectException || it is NoRouteToHostException
        }

        // A mid-stream SocketException (connection reset) stays here deliberately: it IS a per-track
        // transient, so retry-once-then-skip remains right. Only the connection-level subtypes above
        // are diverted.
        val isTransientServerError = !isNetworkDown && error.anyCause { it is SocketException }
        if (isTransientServerError) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedSocketMediaId == null || retriedSocketMediaId != currentMediaId) {
                retriedSocketMediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: SocketException for $currentMediaId, retrying")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                retriedSocketMediaId = null
                Log.d("GladixPlayback", "onPlayerError: SocketException retry failed for $currentMediaId, skipping")
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
                    player.stop()
                    return
                }
                val hasMore = player.hasNextMediaItem()
                if (!hasMore) {
                    player.stop()
                    return
                }
                if (isAndroidAutoConnected()) {
                    launchInvoluntarySkip {
                        player.pause()
                        delay(50)
                        skipInvoluntarily()
                        player.prepare()
                        player.play()
                    }
                } else {
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            }
            return
        }

        // Network-resolution failure (DNS down / host unresolved) is whole-connection, NOT a
        // per-track problem — so hold position, never skip. Mirrors the SocketException branch
        // (retry the SAME track once) but ends in pause() instead of seekToNextMediaItem(). First
        // occurrence: silent re-prepare (clears the player error, so a transient blip recovers to
        // clean playback with no message). Second occurrence: the retry also failed → pause and
        // hold, surface the no_internet message, and let the user / AA-BT resume via play (which
        // re-prepares and retries). The budget is kept on the hold so a failed resume holds again;
        // it resets in the STATE_READY block on recovery, i.e. only a track that actually PLAYS
        // refills it. Scoped to these two exceptions only, so
        // genuinely-unavailable tracks still skip via the branches above/below.
        // Cache timeout is a WHOLE-APP condition, like a network outage - so it holds, exactly as the
        // network-down branch below does, and for the same reason: skipping cannot help. app.fileCache is a
        // single lazily-started Deferred shared process-wide, so the NEXT track awaits the identical object
        // and will time out identically. Skipping would walk the whole queue silently at 60s a track.
        // Placed ABOVE the generic tail on purpose: with no explicit branch this fell through to it and got
        // the retry-then-skip bookkeeping, i.e. a slow cache produced tracks that quietly skipped - the
        // symptom family this codebase has repeatedly had to chase. No recordSkip, so the consecutive-skip
        // breaker is untouched; a cache stall must not consume the budget that exists for dead tracks.
        // See App.FileCacheTimeoutException for why it carries no cause (attaching one would route it into
        // the silent-skip family via rootCause).
        if (rootCause is App.FileCacheTimeoutException) {
            Log.d("GladixPlayback", "onPlayerError: file cache timed out, holding")
            scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
            player.pause()
            return
        }

        // isNetworkDown is computed above the socket branch (see there for why the ordering matters).
        if (isNetworkDown) {
            val currentMediaId = mediaItem?.mediaId
            if (networkRetryCount < maxNetworkRetries) {
                networkRetryCount++
                Log.d(
                    "GladixPlayback",
                    "onPlayerError: network down for $currentMediaId, " +
                        "retry $networkRetryCount/$maxNetworkRetries"
                )
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                Log.d(
                    "GladixPlayback",
                    "onPlayerError: network down, retry budget spent for $currentMediaId, holding"
                )
                scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                player.pause()
            }
            return
        }

        if (rootCause is TrackUnavailableException || rootCause.message?.contains("not available", ignoreCase = true) == true) {
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
                player.stop()
                val isRetryExhausted = rootCause.message?.contains("not available after retries", ignoreCase = true) == true
                if (!isRetryExhausted) scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            if (isAndroidAutoConnected()) {
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            } else {
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        val isMissingFile = rootCause is FileDataSource.FileDataSourceException
                || rootCause is FileNotFoundException
                || rootCause.message?.contains("ENOENT", ignoreCase = true) == true
        val is401 = (rootCause is HttpDataSource.InvalidResponseCodeException
                && rootCause.responseCode in listOf(401, 403))
                || (rootCause is IllegalStateException
                && (rootCause.message?.contains("HTTP 401") == true
                    || rootCause.message?.contains("HTTP 403") == true))
        val isMalformedContent = rootCause is ParserException && rootCause.contentIsMalformed
        // ⚠️ TYPE-BASED, AND THAT IS WHY StuckPlayerException MISSES IT. media3's StuckPlayerDetector
        // surfaces through ExoPlayerImpl.onStuckPlayerDetected (:3652) as
        //   stopInternal(ExoPlaybackException.createForUnexpected(exception, ERROR_CODE_TIMEOUT))
        // so it CARRIES ERROR_CODE_TIMEOUT - but that is a PlaybackException CODE, not a Kotlin type, and
        // this check tests the rootCause's type. StuckPlayerException is neither of the two below, so it
        // falls past this branch, past the ExoTimeoutException release check, and into the generic tail.
        // Do not "fix" that by widening this line: matching a code here would pull genuine socket/parse
        // timeouts and the stuck detector into one bucket with one recovery, and they need different ones.
        //
        // WHAT THE GENERIC TAIL THEN DOES, measured on 1059 and contradicting the earlier scoping of a
        // StuckPlayerDetector threshold change: it does NOT consume the retry/skip budget on a first
        // occurrence. The tail emits the non-fatal, does last/currentRetries bookkeeping (currentRetries
        // resets to 0 because the root-cause class changed), falls past BOTH recordSkip gates, and takes
        // the else branch - replaceMediaItem(withRetry) then prepare() + play(). The budget is touched only
        // once the same class repeats to maxRetries, or the item's own retries reach maxSingleItemRetries.
        // It also DOES arm the watchdog: stopInternal drives STATE_IDLE (which cancels the watchdog and
        // abandons focus), and the tail's prepare() then drives STATE_BUFFERING - a real transition. The
        // 1059 timeline confirms it end to end: detector at 10:32:09, breaker trip at 10:32:39, exactly
        // 3 x (5s retry + 5s skip) later.
        // The one caveat that DOES survive: at a lowered threshold it can fire on a genuinely slow cold
        // resolve, so it still needs reconciling with COLD_GRACE_MS = 25_000 before the threshold moves.
        val isTimeout = rootCause is TimeoutCancellationException || rootCause is SocketTimeoutException

        // Benign media3 datasource teardown race — suppressed, but counted. The player/cache is torn down
        // while a load is still closing (the stop()+prepare() churn from the watchdog and skip paths) and
        // one of media3's checkState() lifecycle assertions fires. The throwing frame MOVES around the
        // close() cascade — SimpleCache.commitFile inlined into CacheDataSink one report, TeeDataSource
        // .close the next — which is why this matches a family rather than a single class.
        //
        // ⚠️ MATCH ON RUNTIME NAMES, NEVER ON LITERAL SOURCE NAMES. This guard has been written three
        // times and twice shipped broken:
        //   3c438db6 (Jun 17)  className == SimpleCache::class.java.name   — correct but too narrow:
        //                      R8 INLINES SimpleCache.commitFile into CacheDataSink, so no SimpleCache
        //                      frame exists in the trace at all.
        //   f6464c00 (Jun 23)  className.contains("SimpleCache")           — BROKEN in release.
        //   3707e4c9 (Jul 1)   className.startsWith("androidx.media3.datasource.") — BROKEN in release,
        //                      then deleted as dead code by fe71e813 (Jul 3).
        // proguard-rules.pro has no media3 keep rules, so these classes ARE obfuscated: the frames read
        // xw / zs4 / zw / m04 / gm4 in a release build and no literal string can ever match them.
        // Comparing StackTraceElement.className against Class.getName() compares two RUNTIME names, so it
        // survives both renaming and horizontal merging (a merged class reports its host's name on both
        // sides). Only inlining defeats it, which is why the set spans the whole cascade.
        //
        // message == null is load-bearing twice over: media3's bare checkState(boolean) throws a
        // message-less ISE, and it keeps this disjoint from the is401 branch above, which matches an
        // IllegalStateException carrying "HTTP 401"/"HTTP 403" and must keep its retry.
        val isDataSourceTeardownRace = rootCause is IllegalStateException
            && rootCause.message == null
            && rootCause.stackTrace.any { it.className in dataSourceRuntimeClassNames }
        if (isDataSourceTeardownRace) {
            healthMonitor?.report(
                HealthMonitor.DataSourceTeardownRaceException(rootCause),
                HealthMonitor.Scope.MEMORY_ONLY, 10 * 60 * 1000L
            )
            return
        }

        if (is401) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedMediaId != currentMediaId) {
                retriedMediaId = currentMediaId
                retriedWatchdogCount = 1
                Log.d("GladixPlayback", "onPlayerError: 401 for $currentMediaId, retrying with stop/prepare (fresh TRACK_TOKEN)")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
                return
            }
            retriedMediaId = null
            retriedWatchdogCount = 0
            Log.d("GladixPlayback", "onPlayerError: 401 retry exhausted for $currentMediaId, skipping")
            // fall through to silent skip below
        }

        // ExtensionNotFoundException = the track's extension was UNINSTALLED (removed) while queued. Disabled
        // extensions stay in the music flow and getExtensionOrThrow returns them (they don't throw this), so
        // this is removed-only. It's a synchronous list.find miss — instant, no network, and no retry can
        // ever succeed — so it joins the silent-skip family but is EXEMPT from the consecutiveUnavailableSkips
        // circuit breaker: that cap throttles retry-loop storms (CDN/token), whereas skipping a dead-extension
        // track is free. Only the end-of-queue bound below applies, so we skip past e.g. 30 removed-Spotify
        // tracks straight to the live-extension track at 31.
        val isExtensionRemoved = rootCause is ExtensionNotFoundException
        if (isMissingFile || is401 || isMalformedContent || isTimeout || isExtensionRemoved) {
            if (!isExtensionRemoved) {
                recordSkip(rootCause, error, probeDetail())
                if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                    reportAndResetConsecutiveSkips(mediaItem?.extensionId, "stop")
                    player.stop()
                    return
                }
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                // Queue exhausted. For a removed-extension run, surface ONE message iff NOTHING resolved to
                // READY since the queue was last set (resolvedSinceQueueReplace) — i.e. the whole queue was
                // unplayable — so a normal session that merely ends on a couple of removed tracks stays
                // silent. messageFlow = user snackbar, no Crashlytics (expected input, not a bug).
                if (isExtensionRemoved && !resolvedSinceQueueReplace) {
                    scope.launch {
                        extensions.app.messageFlow.emit(
                            Message(context.getString(R.string.removed_extension_playback_stopped))
                        )
                    }
                }
                player.stop()
                return
            }
            if (isAndroidAutoConnected()) {
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            } else {
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }

        // Media3's own RELEASE diagnostic, not a playback failure. ExoPlayerImpl.release() emits this
        // through EVENT_PLAYER_ERROR when a renderer misses the releaseTimeoutMs budget, then finishes
        // teardown unconditionally and sets playerReleased = true — so the player IS released, there is
        // no playback left to recover, and the report names nothing we can act on. It reached the
        // generic tail below and was recorded as a PlayerException (build 1036, Pixel 10).
        // Scoped to TIMEOUT_OPERATION_RELEASE ONLY: Media3 raises two other operations
        // (SET_FOREGROUND_MODE, DETACH_SURFACE) on a LIVE player, and those are genuine faults that
        // must keep reporting. Returning here also skips the retry bookkeeping below, which is correct
        // — nothing should be retried on a player that is being torn down.
        val releaseTimeout = generateSequence(cause) { it.cause }
            .filterIsInstance<ExoTimeoutException>()
            .firstOrNull()
            ?.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_RELEASE
        if (releaseTimeout) return

        scope.launch { throwableFlow.emit(PlayerException(mediaItem, cause)) }

        val old = last
        last = rootCause::class
        if (old != null && old == last) currentRetries++
        else currentRetries = 0

        if (mediaItem == null) return
        // Current index: replaceMediaItem below applies to the full timeline, so this must be the
        // current track's real index or the retry would swap the wrong track.
        val index = player.currentMediaItemIndex
        val retries = mediaItem.retries

        if (currentRetries >= maxRetries) {
            currentRetries = 0
            last = null
            // Split from the old single "…, skipping" line: that fired BEFORE the breaker check below, so it
            // claimed a skip on the very run that stopped instead — which is why a trip was invisible in a
            // logcat capture. This line states only the fact (retries are done); the outcome is logged by
            // whichever branch actually runs (the breaker's own line inside reportAndResetConsecutiveSkips,
            // or the "skipping" line below).
            Log.d("GladixPlayback", "onPlayerError: maxRetries exhausted for ${mediaItem.mediaId}")
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem.extensionId, "stop")
                player.stop()
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            Log.d("GladixPlayback", "onPlayerError: skipping ${mediaItem.mediaId}")
            if (isAndroidAutoConnected()) {
                launchInvoluntarySkip {
                    player.pause()
                    delay(50)
                    player.seekTo(player.currentMediaItemIndex, 0)
                    skipInvoluntarily()
                    player.prepare()
                    player.play()
                }
            } else {
                player.seekTo(player.currentMediaItemIndex, 0)
                skipInvoluntarily()
                player.prepare()
                player.play()
            }
            return
        }
        if (retries >= maxSingleItemRetries) {
            // Per-item retries are exhausted, so this IS a skip and must advance the breaker like every other
            // skip site — see recordSkip's contract above ("called at every skip site"), which this violated.
            // The omission is why a 3-strike breaker needed SIX tracks to trip: only the currentRetries >=
            // maxRetries path counted, and it zeroes currentRetries each time it fires, so the alternating
            // per-item skips were invisible to the breaker. Now 3 tracks, matching the documented intent.
            // Ordering mirrors every other counted site exactly (recordSkip -> breaker -> end-of-queue ->
            // skip). The breaker check belongs HERE rather than being left to the next site that happens to
            // check: incrementing without deciding is precisely the count/decision drift recordSkip guards
            // against, and it would defer the trip by an unbounded number of tracks.
            Log.d("GladixPlayback", "onPlayerError: item retries exhausted for ${mediaItem.mediaId}")
            recordSkip(rootCause, error, probeDetail())
            if (consecutiveUnavailableSkips >= maxConsecutiveUnavailableSkips) {
                reportAndResetConsecutiveSkips(mediaItem.extensionId, "stop")
                player.stop()
                return
            }
            val hasMore = player.hasNextMediaItem()
            if (!hasMore) {
                player.stop()
                return
            }
            skipInvoluntarily()
        } else {
            val newItem = MediaItemUtils.withRetry(mediaItem)
            player.replaceMediaItem(index, newItem)
        }
        player.prepare()
        player.play()
    }
}
