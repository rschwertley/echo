package dev.brahmkshatriya.echo.playback

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import dev.brahmkshatriya.echo.MainActivity.Companion.getMainActivity
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.ui.common.ErrorCategory
import dev.brahmkshatriya.echo.ui.common.classify
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.extensionPrefId
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.prefs
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.playback.MediaItemUtils.extensionId
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverPlaylist
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverRepeat
import dev.brahmkshatriya.echo.playback.ResumptionUtils.recoverShuffle
import dev.brahmkshatriya.echo.playback.listener.AudioFocusListener
import dev.brahmkshatriya.echo.playback.listener.EffectsListener
import dev.brahmkshatriya.echo.playback.listener.MediaSessionServiceListener
import dev.brahmkshatriya.echo.playback.listener.PlayerEventListener
import dev.brahmkshatriya.echo.playback.listener.PlayerRadio
import dev.brahmkshatriya.echo.playback.listener.TrackingListener
import dev.brahmkshatriya.echo.playback.renderer.AudioEffectsProcessor
import dev.brahmkshatriya.echo.playback.renderer.PlayerBitmapLoader
import dev.brahmkshatriya.echo.playback.renderer.RenderersFactory
import dev.brahmkshatriya.echo.playback.source.StreamableMediaSource
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel.Companion.KEEP_QUEUE
import kotlinx.coroutines.async
import dev.brahmkshatriya.echo.utils.ContextUtils.listenFuture
import dev.brahmkshatriya.echo.utils.CrashKeys
import dev.brahmkshatriya.echo.utils.HealthMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

@OptIn(UnstableApi::class)
class PlayerService : MediaLibraryService() {

    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private val extensionLoader by inject<ExtensionLoader>()
    private val extensions by lazy { extensionLoader }
    private val exoPlayer by lazy { createExoplayer(this.audioEffectsProcessor) }

    private var mediaSession: MediaLibrarySession? = null
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    // Media3's onStartCommand() always returns START_STICKY, which causes the OS to restart the
    // service after memory pressure kills it — even with no user intent to play. This produces a
    // blank "Gladix" notification on cold restart with no track loaded. START_NOT_STICKY means
    // the service only restarts when something explicitly starts it (ButtonReceiver on BT PLAY,
    // or the app binding via PlayerViewModel). super.onStartCommand() must still be called: it
    // dispatches the media button intent to MediaSessionImpl.handleMediaButtonEvent() via Handler,
    // which is load-bearing for BT AVRCP PLAY triggering onPlaybackResumption().
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private lateinit var audioFocusListener: AudioFocusListener
    private lateinit var carConnection: CarConnection
    private var isAndroidAutoConnected = false
    private val carConnectionObserver = Observer<Int> { connectionType ->
        val isConnected = connectionType == CarConnection.CONNECTION_TYPE_PROJECTION
            || connectionType == CarConnection.CONNECTION_TYPE_NATIVE
        val wasConnected = isAndroidAutoConnected
        isAndroidAutoConnected = isConnected
        CrashKeys.onAndroidAutoState(isConnected)   // per AA route change (not hot)
        // AA is the authoritative connect/disconnect signal for the phantom-PLAY route-state, because AA
        // projection does NOT reliably present as an audio-output device to AudioDeviceCallback — so an
        // AA disconnect may never fire onAudioDevicesRemoved. Recompute on every AA transition (connect
        // clears the flag so a head-unit resume-on-connect passes through; disconnect sets it when no
        // other external route remains). recompute reads isAndroidAutoConnected, updated just above.
        recomputeRouteState()
        if (wasConnected && !isConnected) {
            mediaSession?.player?.let { if (it.playWhenReady) it.pause() }
        }
    }

    // Phantom-PLAY route tracking (see PlayerState.isPostDisconnect). AudioDeviceCallback covers the
    // BT-A2DP / wired / USB-audio disconnect; the CarConnection observer covers AA/Automotive; the
    // onCreate seed covers cold-open after the service was killed during a long disconnect.
    private val audioManager by lazy { getSystemService<AudioManager>()!! }
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = recomputeRouteState()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = recomputeRouteState()
    }

    // True if any current OUTPUT device is not a built-in speaker/earpiece — i.e. a real external audio
    // route (BT/wired/USB/dock/etc.) is present. getDevices(GET_DEVICES_OUTPUTS) is never empty (the
    // built-in speaker is always listed), so we test for a NON-built-in type rather than an empty list.
    private fun hasExternalAudioOutput(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> false
                else -> !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
            }
        }

    // We are "post-disconnect" only when there is neither an external audio route NOR an AA connection.
    // Composing both inputs here (rather than writing the flag separately per signal) prevents the
    // AudioDeviceCallback from falsely flagging post-disconnect while AA is connected but presenting no
    // audio-output device. Runs on the application looper (all three callers do).
    private fun recomputeRouteState() {
        state.isPostDisconnect = !(isAndroidAutoConnected || hasExternalAudioOutput())
    }

    private val app by inject<App>()
    private val healthMonitor by inject<HealthMonitor>()
    private val state by inject<PlayerState>()
    private val fullQueueFlow by inject<MutableStateFlow<List<MediaItem>>>()
    // Safety net for UNCAUGHT exceptions on this scope — the same pattern App.scope and
    // ExtensionLoader.scope already carry, missing here until now. This is the reason an
    // off-application-thread session.player read was FATAL rather than a reported non-fatal: an uncaught
    // throw in a scope.launch child reaches the default uncaught handler and crashes the process, because
    // SupervisorJob only stops siblings being cancelled — it does not absorb the exception.
    // Routes to app.throwFlow so it degrades to the same non-fatal path as every other reported error
    // (printStackTrace + Crashlytics recordException, plus the snackbar collector).
    // CancellationException is never delivered to a CoroutineExceptionHandler (normal cancellation) — the
    // guard is defensive so it can never be reported. runCatching so a failure to record cannot re-crash
    // or loop; SupervisorJob keeps the scope alive after a child fails, so the launch is safe.
    // The `: CoroutineExceptionHandler` annotation is LOAD-BEARING, not style. Without it the property's
    // type must be inferred from the initializer, whose lambda body references `scope`, whose type is
    // inferred from an expression containing this handler — a cycle the compiler reports as "Type checking
    // has run into a recursive problem". Declaring the type lets it resolve `scope` without analysing this
    // body first. App.exceptionHandler and ExtensionLoader.exceptionHandler carry it for the same reason.
    private val exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        runCatching { scope.launch { app.throwFlow.emit(throwable) } }
    }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("PlayerService") + exceptionHandler
    )

    private val audioEffectsProcessor by lazy {
        AudioEffectsProcessor().apply {
            // One-time migration: force normalization off for all existing users.
            // Safe to re-enable by clearing normalization_force_disabled_v1 from prefs.
            if (!app.settings.getBoolean("normalization_force_disabled_v1", false)) {
                app.settings.edit {
                    putBoolean(LOUDNESS_NORMALIZATION, false)
                    putBoolean("normalization_force_disabled_v1", true)
                }
            }
            crossfadeEnabled = app.settings.getBoolean(CROSSFADE_ENABLED, false)
            // Clamp on READ. The sliders only clamp what they DISPLAY — neither persists the
            // corrected value (the programmatic `value =` assignment happens before the change
            // listener is attached), so anyone who stored 6-12 while that was the allowed range
            // still has it, and without this they get a 12-second fade while both UIs show 5.
            crossfadeDurationMs = app.settings.getInt(CROSSFADE_DURATION, 2)
                .coerceIn(CROSSFADE_DURATION_MIN, CROSSFADE_DURATION_MAX) * 1000
            normalizationEnabled = app.settings.getBoolean(LOUDNESS_NORMALIZATION, false)
        }
    }

    @OptIn(UnstableApi::class)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            SKIP_SILENCE -> exoPlayer.skipSilenceEnabled = prefs.getBoolean(key, true)
            LOUDNESS_NORMALIZATION -> {
                audioEffectsProcessor.normalizationEnabled = prefs.getBoolean(key, false)
                effects.updateNormalizationSettings()
            }
            CROSSFADE_ENABLED -> {
                audioEffectsProcessor.crossfadeEnabled = prefs.getBoolean(key, false)
                effects.updateCrossfadeSettings()
            }
            CROSSFADE_DURATION -> {
                audioEffectsProcessor.crossfadeDurationMs = prefs.getInt(key, 2)
                    .coerceIn(CROSSFADE_DURATION_MIN, CROSSFADE_DURATION_MAX) * 1000
                effects.updateCrossfadeSettings()
            }
        }
    }
    private val effects by lazy { EffectsListener(exoPlayer, this, state.session, audioEffectsProcessor, scope) }

    private val historyRepository by inject<HistoryRepository>()
    private val downloader by inject<Downloader>()
    private val downloadFlow by lazy { downloader.flow }

    @Volatile private var foregroundStartSuppressed = false

    // Lever B: turn the raw player error into a friendly, categorized PlaybackException for the media
    // session (Android Auto). Category comes from the shared classify() chain-walk so the AA message
    // and the phone snackbar (ExceptionUtils) never disagree; the message/code are AA-tailored with a
    // "check your phone" recovery cue. Never returns null (the null case is handled upstream in
    // ShufflePlayer.getPlayerError before this is called).
    @OptIn(UnstableApi::class)
    private fun mapAaError(raw: PlaybackException): PlaybackException {
        val (code, message) = when (classify(raw)) {
            ErrorCategory.Network -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to
                getString(R.string.playback_error_no_connection)
            ErrorCategory.LoginOrAuth -> PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED to
                getString(R.string.playback_error_login_required)
            ErrorCategory.Generic -> PlaybackException.ERROR_CODE_UNSPECIFIED to
                getString(R.string.playback_error_generic)
        }
        return PlaybackException(message, raw, code)
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // age_s_svc + service_create_count + heap sample. service_create_count > 1 means this process has
        // destroyed and recreated the service — the kill/rebind loop that makes heap_used_mb_svc a LATE sample.
        CrashKeys.onServiceCreate()
        startForegroundCompat()
        setListener(MediaSessionServiceListener(this, getPendingIntent(this)))

        val player = ShufflePlayer(exoPlayer, ::mapAaError)
        scope.launch(Dispatchers.Main) {
            mediaChangeFlow.collect { (o, n) -> player.onMediaItemChanged(o, n) }
        }

        val callback = PlayerCallback(
            app, scope, app.throwFlow, extensions, state, downloadFlow, historyRepository
        )

        val session = MediaLibrarySession.Builder(this, player, callback)
            .setBitmapLoader(PlayerBitmapLoader(this, scope))
            .setSessionActivity(getPendingIntent(this))
            // Disable Media3's ~1/sec periodic PlaybackState position pushes: recent Android Auto
            // versions reset the browse/queue scroll on every playback-state change, so the periodic
            // updates make the AA queue jump to the top every few seconds during playback (androidx/
            // media #2192, b/400923507). Discrete PlaybackState updates (play/pause/seek/track change)
            // still fire, and all standard controllers (AA, notification, lockscreen) extrapolate
            // position between them, so the seek bar is unaffected. Our own UI reads player position
            // directly on a 500ms ticker (PlayerUiListener), so it's independent of this flag.
            .setPeriodicPositionUpdateEnabled(false)
            // Behaviourally a no-op — NON_FATAL is already Media3's default (MediaLibraryService:465).
            // Stated explicitly because the default is load-bearing and its loss would be SILENT: under
            // FATAL, a library error replicated from ANY onGetChildren node takes the isFatal branch in
            // MediaSessionLegacyStub.createPlaybackStateCompat (:1843-1861) and publishes STATE_ERROR with
            // setActions(0) — over live playback of a DIFFERENT extension, since the platform session is
            // shared. That is the stuck-error-over-playback class we spent Aug 9-11 diagnosing. NON_FATAL
            // instead attaches only setErrorMessage + extras and leaves state/position/actions intact.
            // No compile error and no test would catch a change here, so do not "simplify" this line away.
            .setLibraryErrorReplicationMode(
                MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_NON_FATAL
            )
            .build()

        player.addListener(
            PlayerEventListener(this, scope, session, state.current, extensions, app.throwFlow,
                fullQueueFlow = fullQueueFlow,
                isAndroidAutoConnected = { isAndroidAutoConnected },
                requestAudioFocus = { audioFocusListener.requestFocus() },
                activeLoadCount = { state.activeLoadCount.get() },
                // Clears the resumption marker once the queue lands (timeline non-empty) — the success
                // clear for onPlaybackResumption; on Main, since Player.Listener fires on the app looper.
                onQueueApplied = { state.resumptionApplying = false },
                // Returns-and-clears the cold-start re-seek latch (Main; Player.Listener fires on the app looper).
                consumeRestoreSeek = { state.pendingRestoreSeek.also { state.pendingRestoreSeek = null } },
                // Non-consuming peek at the same latch — gates the saveCurrentPos 0-write without clearing it
                // (Main; Player.Listener fires on the app looper).
                isRestoreSeekArmed = { state.pendingRestoreSeek != null },
                healthMonitor = healthMonitor,
            )
        )
        player.addListener(
            PlayerRadio(
                app, scope, player, app.throwFlow, state.radio, extensions.music, downloadFlow
            )
        )
        player.addListener(
            TrackingListener(player, scope, extensions, state.current, app.throwFlow, historyRepository)
        )
        player.addListener(effects)
        audioFocusListener = AudioFocusListener(this, player)
        carConnection = CarConnection(this)
        carConnection.type.observeForever(carConnectionObserver)
        // Phantom-PLAY route tracking: observe live BT/wired/USB audio-route changes, and SEED the flag
        // now from the current output set. The seed is the cold-open fix — after a long disconnect the
        // service is killed (START_NOT_STICKY) and this in-memory flag resets, so on recreation we must
        // re-derive it: no external output present => start post-disconnect, which is exactly the state
        // after a BT/car/AA disconnect. The CarConnection observer's initial emission recomputes again
        // once AA state is known.
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        recomputeRouteState()
        player.addListener(audioFocusListener)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                app.crashPlayerState = playbackState
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                app.crashIsPlaying = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                app.crashExtensionId = mediaItem?.extensionId ?: "none"
                // Reuse the already-decoded id (no second state round-trip); once per track transition.
                CrashKeys.onPlayingExtension(app.crashExtensionId)
            }
        })
        app.settings.registerOnSharedPreferenceChangeListener(listener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clearQueueReceiver, IntentFilter(ACTION_CLEAR_QUEUE),
                RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(clearQueueReceiver, IntentFilter(ACTION_CLEAR_QUEUE))
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.app_name)
            .build()
        notificationProvider.setSmallIcon(R.drawable.ic_gladix_mono)
        setMediaNotificationProvider(SafeNotificationProvider(notificationProvider))
        // Suppress the notification entirely when the timeline is empty (no track loaded).
        // NEVER mode causes MediaNotificationManager.shouldShowNotification() to return false
        // and call removeNotification() instead of our provider when the player is idle.
        // startForegroundCompat()'s initial placeholder remains visible until the first real
        // track notification replaces it — this is acceptable and avoids the "Loading…" flash.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)

        mediaSession = session
        // Belt-and-suspenders: ensure ExoPlayer's internal AudioFocusManager has
        // handleAudioFocus=false after all Media3 session initialization completes.
        // Session init calls player.setAudioAttributes(DEFAULT, true) through ShufflePlayer
        // (caught by the override), but calling directly on exoPlayer guarantees the
        // AudioFocusManager's internal state is locked off regardless of any timing race.
        exoPlayer.setAudioAttributes(musicAudioAttributes, false)

        // Producer: read the saved queue from disk ONCE into the shared Deferred (see PlayerState). Every
        // consumer awaits THIS — no path runs its own recoverPlaylist, so the cold-start double-restore
        // race is gone at the root. with(this@PlayerService) supplies the Context receiver the recover*
        // extensions need inside the coroutine.
        state.restoreDeferred = scope.async(Dispatchers.IO) {
            with(this@PlayerService) {
                val (items, index, pos) = recoverPlaylist(app, downloadFlow.value, healthMonitor)
                Log.d("GladixPlayback", "restore read: items=${items.size}")
                if (items.isEmpty()) null
                else RestoreData(
                    items, index, pos,
                    recoverShuffle() ?: false,
                    recoverRepeat() ?: Player.REPEAT_MODE_OFF
                )
            }
        }
        // App-open apply: the sole restorer for a controller-driven cold start. Main-atomic, gated on an
        // empty player + the resumption marker; KEEP_QUEUE is honored inside applyRestoreIfCold. It does
        // NOT prepare() — same lazy-STATE_READY reason as before; prepare() waits for play().
        scope.launch { callback.applyRestoreIfCold(player) }
    }

    // Called at the very top of onCreate() to satisfy Android's 5-second startForeground()
    // requirement before any heavyweight initialization (ExoPlayer, MediaLibrarySession, etc.).
    // Uses DefaultMediaNotificationProvider's channel/notification IDs so that Media3's own
    // startForeground() call (which fires once the session has an active player state) cleanly
    // replaces this placeholder notification with real media controls.
    @OptIn(UnstableApi::class)
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService<NotificationManager>()!!
            if (nm.getNotificationChannel(DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(
            this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_gladix_mono)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(getPendingIntent(this))
            .setSilent(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (API 31+): service was started via
            // bindService() from a background caller (e.g. widget MediaController connection)
            // rather than startForegroundService(), so mAllowStartForeground=false and
            // startForeground() is rejected. No 5-second obligation exists for bind-started
            // services; the service runs as bound until startForegroundService() elevates it.
            // foregroundStartSuppressed is set so onUpdateNotification() can retry once Media3
            // posts a real notification and the AA binding chain has had time to allow it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                foregroundStartSuppressed = true
                return
            }
            throw e
        }
    }

    // Media3's MediaNotificationManager.startForeground() (called when album art loads
    // asynchronously and the notification is rebuilt) throws ForegroundServiceStartNotAllowedException
    // on API 31+ when the service was started via bindService() rather than startForegroundService().
    // We can't patch Media3's internal code, but onUpdateNotification() is the last public override
    // point before execution enters MediaNotificationManager, so we catch the exception here.
    //
    // If the initial startForegroundCompat() was suppressed (foregroundStartSuppressed == true),
    // we attempt to promote to foreground here before delegating to Media3, closing the vulnerable
    // window between the AA bind and the first real media notification.
    @OptIn(UnstableApi::class)
    override fun onUpdateNotification(session: MediaSession, startInForeground: Boolean) {
        // Playback resumed — swap back to the media controls notification
        if (foregroundStartSuppressed) {
            try {
                val placeholder = NotificationCompat.Builder(
                    this, DefaultMediaNotificationProvider.DEFAULT_CHANNEL_ID
                )
                    .setSmallIcon(R.drawable.ic_gladix_mono)
                    .setSilent(true)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                        placeholder,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(
                        DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                        placeholder
                    )
                }
                foregroundStartSuppressed = false
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    e.javaClass.name != "android.app.ForegroundServiceStartNotAllowedException"
                ) throw e
                // Still not allowed — remain suppressed, try again on the next update
            }
        }
        try {
            super.onUpdateNotification(session, startInForeground)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) return
            throw e
        }
    }

    // Wraps DefaultMediaNotificationProvider to catch ForegroundServiceStartNotAllowedException
    // thrown from the async bitmap callback (OnBitmapLoadedFutureCallback.onSuccess). That path
    // fires after onUpdateNotification() has already returned, so the catch in onUpdateNotification()
    // cannot intercept it. Wrapping the Provider.Callback here catches it at the last public point
    // before the exception propagates to an uncaught crash.
    @OptIn(UnstableApi::class)
    private class SafeNotificationProvider(
        private val delegate: DefaultMediaNotificationProvider
    ) : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback
        ): MediaNotification {
            val safeCallback = MediaNotification.Provider.Callback { notification ->
                try {
                    callback.onNotificationChanged(notification)
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        e.javaClass.name != "android.app.ForegroundServiceStartNotAllowedException"
                    ) throw e
                    // Service was demoted while album art was loading — safe to swallow
                }
            }
            return delegate.createNotification(session, customLayout, actionFactory, safeCallback)
        }

        override fun handleCustomCommand(
            session: MediaSession, action: String, extras: Bundle
        ): Boolean = delegate.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo() = delegate.notificationChannelInfo
    }

    override fun onDestroy() {
        if (::carConnection.isInitialized) carConnection.type.removeObserver(carConnectionObserver)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterReceiver(clearQueueReceiver)
        mediaSession?.run {
            // Flush the debounced queue save synchronously BEFORE releasing the player and cancelling
            // the scope (which would drop a pending scheduleSaveQueue). Must run before player.release()
            // — a released player reports an empty timeline, which saveQueueBlocking no-ops on.
            ResumptionUtils.saveQueueBlocking(applicationContext, player)
            audioFocusListener.release()
            // Synchronous final effect-session CLOSE. MUST precede scope.cancel() below: the runtime CLOSE
            // (broadcastAudioSessionCloseDeferred) posts to this scope, which cancel() would drop — so the
            // teardown close is done synchronously here, while the session is still live and before the
            // scope dies. effects is always initialized (addListener in onCreate); teardown isn't a
            // contended cold-start, so a synchronous sendBroadcast is fine.
            effects.releaseBlocking()
            release()                  // mediaSession first — Media3 requirement
            player.release()           // player second — main thread, synchronous
            mediaSession = null
        }
        scope.cancel()
        super.onDestroy()
    }

    private val cache by inject<SimpleCache>()

    private val mediaChangeFlow = MutableSharedFlow<Pair<MediaItem, MediaItem>>()

    @OptIn(UnstableApi::class)
    private fun offloadPreferences() =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            // Offload is disabled unconditionally. The Pixel 10 (Tensor G5) gapless-offload
            // HAL drops audio silently across a natural track boundary; forcing ExoPlayer to
            // decode on the CPU gives device-agnostic software gapless.
            .setAudioOffloadMode(AUDIO_OFFLOAD_MODE_DISABLED)
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(true)
            .build()

    @OptIn(UnstableApi::class)
    private fun createExoplayer(
        audioEffectsProcessor: AudioEffectsProcessor,
        handleAudioBecomingNoisy: Boolean = true
    ) = run {
        val audioOffloadPreferences = offloadPreferences()

        val factory = StreamableMediaSource.Factory(
            app, scope, state, extensions, cache, downloadFlow, mediaChangeFlow, healthMonitor
        )

        ExoPlayer.Builder(this, factory)
            .setRenderersFactory(RenderersFactory(this, audioEffectsProcessor))
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(musicAudioAttributes, false)
            // 500 ms = Media3's own default, restored deliberately. The 150 ms here came from 464d4d3f
            // (2026-06-06), the same commit that made onDestroy call mediaSession.release() then
            // player.release() — the latter is SYNCHRONOUS ON THE MAIN THREAD, and the tight budget was
            // an ANR guard. That intent is preserved: 500 ms is still two orders of magnitude below any
            // service-destroy ANR threshold, so the guard costs at most 350 ms more in the worst case.
            // 150 ms was demonstrably too tight — it fired on a Pixel 10 / Android 17 (build 1036),
            // where the budget bounds renderer/codec teardown, not media-source teardown, so it does NOT
            // scale with queue size. Do not re-tighten without a concrete ANR attributable to this line.
            .setReleaseTimeoutMs(500)
            .build()
            .also {
                it.trackSelectionParameters = it.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(audioOffloadPreferences)
                    .build()
                it.skipSilenceEnabled = app.settings.getBoolean(SKIP_SILENCE, true)
            }
    }


    private val clearQueueReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CLEAR_QUEUE) return
            mediaSession?.player?.run {
                clearMediaItems()
                stop()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (app.settings.getBoolean(CLOSE_PLAYER, false)) {
            mediaSession?.player?.run { stop(); clearMediaItems() }
            stopSelf()
        }
    }

    companion object {
        const val CLOSE_PLAYER = "close_player"
        private const val ACTION_CLEAR_QUEUE = "dev.rschwertley.gladix.auto.CLEAR_QUEUE"
        const val SKIP_SILENCE = "skip_silence"
        const val LOUDNESS_NORMALIZATION = "loudness_normalization"
        const val CROSSFADE_ENABLED = "crossfade_enabled"
        const val CROSSFADE_DURATION = "crossfade_duration"

        // Single source of truth for the fade range. It was previously written out separately in the
        // settings slider, the audio-fx sheet's coerceIn, the audio-fx layout XML and the summary
        // string; when the range was retuned from 1-12 to 1-5 (530681cf, 2026-08-02) the string was
        // missed, so the label advertised a range the widgets had stopped allowing. The summary is
        // now formatted from these, so it cannot drift again. The layout XML still hardcodes 1..5 —
        // resource attributes can't reference these, so that one file remains a manual match.
        const val CROSSFADE_DURATION_MIN = 1
        const val CROSSFADE_DURATION_MAX = 5

        const val SKIP_FADE_ON_ALBUMS = "skip_fade_on_albums"

        const val CACHE_SIZE = "cache_size"

        // Function-level suppression: getCache has exactly ONE try/catch (the corrupt-cache wipe+rebuild
        // recovery below), so this scopes to precisely that one finding with no collateral. The swallow is
        // deliberate best-effort recovery and the cause is now logged (Log.w) — rethrowing would change the
        // wipe-and-retry control flow. Not try-scoped only because it's a return-position try.
        @Suppress("SwallowedException")
        @OptIn(UnstableApi::class)
        fun getCache(
            app: Application,
            settings: SharedPreferences,
        ): SimpleCache {
            val cacheDir = File(app.cacheDir, "exo-player")
            val cacheSize = settings.getInt(CACHE_SIZE, 250)
            val evictor = LeastRecentlyUsedCacheEvictor(cacheSize * 1024 * 1024L)
            return try {
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(app))
            } catch (e: Exception) {
                // Corrupt/locked ExoPlayer cache → wipe and rebuild (deliberate recovery). Log the cause so
                // a recurring rebuild (disk full / corruption / lock) isn't invisible — control flow unchanged.
                Log.w("GladixPlayback", "ExoPlayer cache init failed, recreating: ${e.message}")
                cacheDir.deleteRecursively()
                SimpleCache(cacheDir, evictor, StandaloneDatabaseProvider(app))
            }
        }

        const val STREAM_QUALITY = "stream_quality"
        const val UNMETERED_STREAM_QUALITY = "unmetered_stream_quality"
        val streamQualities = arrayOf("highest", "medium", "lowest")

        fun selectServerIndex(
            app: App,
            extensionId: String,
            streamables: List<Streamable>,
            downloaded: List<String>,
        ) = if (downloaded.isNotEmpty()) streamables.size
        else if (streamables.isNotEmpty()) {
            val streamable = streamables.select(app, extensionId) { it.quality }
            streamables.indexOf(streamable)
        } else -1

        private fun <E> List<E>.select(
            app: App,
            settings: SharedPreferences,
            quality: (E) -> Int,
            default: String = streamQualities[1],
        ): E? {
            if (app.isUnmetered) {
                val unmeteredQuality = settings.getString(UNMETERED_STREAM_QUALITY, "off")
                if (unmeteredQuality != "off") return selectQuality(unmeteredQuality, quality)
                if (default == "off") return null  // extension level — stop here
                // app level — fall through to stream_quality
                return selectQuality(settings.getString(STREAM_QUALITY, default), quality)
            }
            return selectQuality(settings.getString(STREAM_QUALITY, default), quality)
        }

        private fun <E> List<E>.selectQuality(final: String?, quality: (E) -> Int): E? {
            return when (final) {
                streamQualities[0] -> maxBy { quality(it) }
                streamQualities[1] -> sortedBy { quality(it) }[size / 2]
                streamQualities[2] -> minBy { quality(it) }
                else -> null
            }
        }


        fun <T> List<T>.select(
            app: App, extensionId: String, quality: (T) -> Int,
        ): T {
            val extSettings =
                extensionPrefId(ExtensionType.MUSIC.name, extensionId).prefs(app.context)
            return select(app, extSettings, quality, "off")
                ?: select(app, app.settings, quality)
                ?: first()
        }

        fun getController(
            context: Application,
            block: (MediaController) -> Unit,
        ): () -> Unit {
            val sessionToken =
                SessionToken(context, ComponentName(context, PlayerService::class.java))
            val playerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            context.listenFuture(playerFuture) { result ->
                val controller = result.getOrElse {
                    return@listenFuture it.printStackTrace()
                }
                block(controller)
            }
            return { MediaController.releaseFuture(playerFuture) }
        }

        fun getPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, context.getMainActivity()).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("fromNotification", true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}