package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.clients.LikeClient
import dev.brahmkshatriya.echo.common.models.Message
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

    private val player get() = session.player

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
            if ((session.player as? ShufflePlayer)?.isRearranging == true) return@launch
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
        if ((session.player as? ShufflePlayer)?.isRearranging != true) {
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

    override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d("GladixPlayback", "onPlaybackStateChanged: state=$playbackState")
        if (playbackState == Player.STATE_BUFFERING) {
            armBufferingWatchdog()
        } else {
            bufferingWatchdog?.cancel()
            bufferingWatchdog = null
        }
        if (playbackState == Player.STATE_READY) {
            resetConsecutiveSkips()
            // A track resolved successfully — the queue is not all-dead (removed-extension tracks never reach
            // READY). Suppresses the removed-extension exhaustion message for any queue that played anything.
            resolvedSinceQueueReplace = true
            // A track resolved, so any prior run of 5xx server errors has ended — re-arm the one-per-run
            // server-error snackbar for the next run.
            serverErrorNotified = false
            retried404MediaId = null
            retriedSocketMediaId = null
            retriedNetworkMediaId = null
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
                    recordSkip(null)
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
        private const val MAX_MSG_LEN = 80
        private const val MAX_CAUSE_LEN = 140
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
    // Moves in lockstep with consecutiveUnavailableSkips: recordSkip() appends as it increments and
    // resetConsecutiveSkips() clears as it zeroes — the two are never mutated apart, so a reported run can
    // never carry a cause from a previous run.
    private val recentSkipCauses = ArrayDeque<String>()

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
    private var retried404MediaId: String? = null
    private var retriedSocketMediaId: String? = null
    private var retriedNetworkMediaId: String? = null

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
    private fun recordSkip(cause: Throwable?, playbackError: PlaybackException? = null) {
        consecutiveUnavailableSkips++
        recentSkipCauses.addLast(safeCause(cause, playbackError))
        if (recentSkipCauses.size > maxConsecutiveUnavailableSkips) recentSkipCauses.removeFirst()
    }

    // The ONLY way to clear the breaker: zeroes the counter and the causes together. Both reset points
    // (STATE_READY and the trip) go through here, so the two fields always reset atomically.
    private fun resetConsecutiveSkips() {
        consecutiveUnavailableSkips = 0
        recentSkipCauses.clear()
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
    private fun safeCause(cause: Throwable?, playbackError: PlaybackException? = null): String {
        val code = playbackError?.errorCodeName
        val ext = playbackError?.appExtensionName()?.let { "ext:$it" }
        val cls = cause?.let { it::class.simpleName ?: "Unknown" } ?: "StuckBuffering"
        val http = (cause as? HttpDataSource.InvalidResponseCodeException)?.let { "HTTP ${it.responseCode}" }
        val msg = cause?.deepestSafeMessage()
        return listOfNotNull(code, ext, cls, http, msg).joinToString(" ").take(MAX_CAUSE_LEN)
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
        healthMonitor?.report(
            HealthMonitor.ConsecutiveSkipException(
                consecutiveUnavailableSkips, extensionId ?: "unknown", recentSkipCauses.joinToString(",")
            ),
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
                recordSkip(rootCause, error)
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
                recordSkip(rootCause, error)
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
        // re-prepares and retries). retriedNetworkMediaId is kept on the hold so a failed resume
        // holds again (one attempt per tap); it resets in the STATE_READY block on recovery and
        // auto-invalidates when the mediaId changes. Scoped to these two exceptions only, so
        // genuinely-unavailable tracks still skip via the branches above/below.
        // isNetworkDown is computed above the socket branch (see there for why the ordering matters).
        if (isNetworkDown) {
            val currentMediaId = mediaItem?.mediaId
            if (retriedNetworkMediaId == null || retriedNetworkMediaId != currentMediaId) {
                retriedNetworkMediaId = currentMediaId
                Log.d("GladixPlayback", "onPlayerError: network down for $currentMediaId, retrying once")
                val savedIndex = player.currentMediaItemIndex
                val savedPosition = player.currentPosition
                player.stop()
                internalSeek { player.seekTo(savedIndex, savedPosition) }
                player.prepare()
                player.play()
                requestAudioFocus()
            } else {
                Log.d("GladixPlayback", "onPlayerError: network down retry failed for $currentMediaId, holding")
                scope.launch { throwableFlow.emit(PlayerException(mediaItem, rootCause)) }
                player.pause()
            }
            return
        }

        if (rootCause is TrackUnavailableException || rootCause.message?.contains("not available", ignoreCase = true) == true) {
            recordSkip(rootCause, error)
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
        val isTimeout = rootCause is TimeoutCancellationException || rootCause is SocketTimeoutException
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
                recordSkip(rootCause, error)
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
            recordSkip(rootCause, error)
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
            recordSkip(rootCause, error)
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
