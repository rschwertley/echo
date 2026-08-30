package dev.brahmkshatriya.echo.di

import android.app.Application
import android.content.Context.CONNECTIVITY_SERVICE
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mayakapps.kache.FileKache
import com.mayakapps.kache.KacheStrategy
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.common.models.NetworkConnection
import dev.brahmkshatriya.echo.extensions.exceptions.AppException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class App(
    val context: Application,
    val settings: SharedPreferences,
) {
    // ⚠️ THE BUFFER IS LOAD-BEARING. With the default zero buffer these are SUSPEND-on-emit flows, so
    // `emit` blocks until EVERY current subscriber has accepted the value — which makes error REPORTING
    // able to stall the code that is reporting. That is not theoretical: ExtensionUtils.getOrThrow awaits
    // `throwableFlow.emit(it)` inline in the caller's coroutine before returning null, so any failing
    // extension call on the browse/settings/feed paths waits on these subscribers. And the throwFlow
    // collector below has NO suspension point (printStackTrace + six setCustomKey + recordException are
    // all blocking), so collectLatest cannot cancel it — a stuck Crashlytics call wedges the collector and
    // every emitter behind it, with no way out short of a process restart.
    // DROP_OLDEST makes emit non-suspending unconditionally. 64 slots means normal operation loses
    // nothing; only a pathological burst drops, and dropping an error REPORT is strictly better than
    // hanging the caller that produced it. Nothing depends on the back-pressure: both subscribers are
    // fire-and-forget (Crashlytics record, snackbar) and getOrThrow discards the result either way.
    // Do not remove the buffer to "not lose reports" — losing a report is the trade being made.
    val throwFlow = MutableSharedFlow<Throwable>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Same shape, same fix. messageFlow is worse than throwFlow in one respect: its only subscriber is
    // SnackBarHandler's lifecycle-gated observe(), with no ungated sibling, so a message emitted while the
    // Activity is stopped was already lost with no record anywhere.
    val messageFlow = MutableSharedFlow<Message>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Safety net for UNCAUGHT exceptions in background coroutines launched on `scope` — e.g. an extension's
    // background token refresh throwing a raw IllegalStateException. Without a handler these hit the default
    // uncaught handler and CRASH the app; here they route to throwFlow and degrade to the exact same non-fatal
    // path as every other extension error (the throwFlow collector below does printStackTrace + Crashlytics
    // recordException with isLoginRequired() suppression; the ExceptionUtils collector shows the snackbar).
    // Notes: CoroutineExceptionHandler is never invoked for CancellationException (normal cancellation) — the
    // guard is defensive so it can never be reported. We bridge the non-suspend handler to the suspending emit via
    // scope.launch (safe: SupervisorJob keeps `scope` alive after a child fails), wrapped in runCatching so a
    // failure to record can never re-crash or loop. We only emit — the existing collectors do the handling.
    // The `: CoroutineExceptionHandler` annotation is LOAD-BEARING, not style. Without it the property's
    // type must be inferred from the initializer, whose lambda body references `scope`, whose type is
    // inferred from an expression containing this handler — a cycle the compiler reports as "Type checking
    // has run into a recursive problem", pointing at the SCOPE line rather than the annotation that was
    // removed. All four handlers (App, ExtensionLoader, PlayerService, Downloader) carry this note.
    private val exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) return@CoroutineExceptionHandler
        runCatching { scope.launch { throwFlow.emit(throwable) } }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Updated by PlayerService whenever player state changes; read at crash-record time.
    @Volatile var crashExtensionId: String = "none"
    @Volatile var crashPlayerState: Int = 1  // Player.STATE_IDLE
    @Volatile var crashIsPlaying: Boolean = false

    private suspend fun getCache() = FileKache(
        context.cacheDir.resolve("kache").toString(),
        50 * 1024 * 1024
    ) {
        strategy = KacheStrategy.LRU
    }

    // CancellationException MUST propagate: runCatching caught it, so a CANCELLED first attempt fell into
    // the recovery path and ran deleteRecursively() on a cache another coroutine may still be using, then
    // rebuilt over it. That is the wipe-underneath-a-running-instance shape already documented at
    // PlayerService.getCache, and cancellation is not a corrupt cache - it is this coroutine being told to
    // stop. Only a real failure should trigger the wipe.
    private val fileCache = scope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        try {
            getCache()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Corrupt/locked kache -> wipe and rebuild. Deliberate best-effort recovery; if the rebuild
            // also fails the exception completes this Deferred and every awaiter sees it, which is the
            // correct outcome (a failure they can report, not a hang).
            context.cacheDir.resolve("kache").deleteRecursively()
            getCache()
        }
    }

    // 60s, and deliberately generous. The job of this bound is to convert INFINITY into something finite,
    // not to enforce responsiveness - so it should sit far above any plausible normal duration, because a
    // value tuned too tight converts a slow-but-working cache into a failed load, on a path with known I/O
    // and OOM history. It is nearly free: the Deferred is LAZY and single-instance, so construction happens
    // ONCE PER PROCESS and every await after the first returns immediately on a completed Deferred. The
    // bound can therefore only bite on the first track load of a session.
    // NOT measured. Nobody has established what FileKache construction normally costs; if that number is
    // ever wanted it needs a deliberate timing pass, not a guess dressed up as one.
    private val fileCacheTimeoutMs = 60_000L

    // ⚠️ NO CAUSE, DELIBERATELY. Do not "improve" this by attaching the timeout as a cause.
    // PlayerEventListener classifies errors off `rootCause`, the DEEPEST node in the chain, and its
    // silent-skip family matches `rootCause is TimeoutCancellationException`. Attaching a
    // TimeoutCancellationException here would make every cache timeout resolve to it, and a slow cache
    // would silently skip every track in the queue - the same shape as the May 2026 bug where each track
    // skipped until one had a fresh token. Causeless, this resolves to itself and reaches the explicit
    // branch in onPlayerError that pauses and surfaces the message instead.
    class FileCacheTimeoutException(ms: Long) :
        Exception("Timed out waiting for the file cache after ${ms}ms")

    // The raw Deferred is PRIVATE so this is the only way in - the rule is structural, not advisory. That Deferred is a single
    // lazily-started instance shared process-wide and sits on the critical path of every track load
    // (Cached.loadMedia -> StreamableLoader.loadTrack -> StreamableMediaSource), so an unbounded await
    // there means one stalled cache init hangs ALL playback, silently, until the process is killed.
    // Bounding it does NOT poison the Deferred: withTimeoutOrNull cancels the AWAITING coroutine, not the
    // async, which keeps running on `scope` independently. So a transient stall self-heals - the next
    // caller's await returns immediately once it completes - while a permanent one degrades to a bounded
    // failure per load instead of a permanent hang. Throwing rather than returning null keeps every call
    // site unchanged: they already run inside runCatching, so this surfaces as an ordinary failed load.
    suspend fun awaitFileCache(): FileKache =
        withTimeoutOrNull(fileCacheTimeoutMs) { fileCache.await() } ?: throw FileCacheTimeoutException(fileCacheTimeoutMs)

    private val _networkFlow = MutableStateFlow(NetworkConnection.NotConnected)
    val networkFlow = _networkFlow.asStateFlow()
    val isUnmetered get() = networkFlow.value == NetworkConnection.Unmetered

    init {
        scope.launch {
            throwFlow.collectLatest {
                it.printStackTrace()
                // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced),
                // so in no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
                // LoginRequired is an EXPECTED "user not signed in" signal (any extension can raise it), not a
                // fault — so skip Crashlytics for it. This ONLY suppresses recordException: the throwFlow emission
                // is untouched, so the "Sign in" snackbar (the separate setupExceptionHandler collector), the feed
                // login shelf, and the player's LoginOrAuth stop() all still fire. isLoginRequired walks the cause
                // chain to catch BOTH forms — the player path's PlayerException→AppException.LoginRequired AND the
                // AA getList path's RAW ClientException.LoginRequired (which classify() would miss).
                // HAS_FIREBASE is a REAL build toggle (true only when google-services.json is present — false
                // in the no-Firebase / F-Droid variant, where FirebaseCrashlytics isn't on the classpath).
                // Lint sees only THIS build's baked-true value ("condition always true" / "can be simplified"),
                // but the guard MUST stay or the no-Firebase build won't compile. Do NOT simplify. Suppression
                // Two distinct inspections fire here: KotlinConstantConditions ("condition always true", the
                // ID already IDE-generated for the analogous constant-BuildConfig check in AppUpdater) and
                // SimplifyBooleanWithConstants ("boolean expression can be simplified"). The latter's ID is
                // the shortName derived from SimplifyBooleanWithConstantsInspection (no explicit shortName in
                // the Kotlin plugin's registration → class name minus "Inspection"), confirmed against the
                // plugin jar — not guessed.
                // ⚠️ THIS BLOCK MUST STAY runCatching-WRAPPED. Recorded as a KNOWN GAP on
                // 2026-08-20 and CLOSED in build 1057 (f2b661b0); the note is kept rather than deleted
                // because the failure it prevents is completely invisible and the wrap looks removable.
                // FirebaseCrashlytics.getInstance() throws when the default FirebaseApp is absent, and
                // getInstance() itself NPEs if the component is missing - most likely AT STARTUP, before
                // Firebase has initialised, which is exactly when early errors are emitted. This block has
                // no suspension point, so collectLatest cannot cancel it: unwrapped, a throw propagates out
                // of collectLatest, kills this collector for the life of the process, and silences every
                // later non-fatal AND every snackbar with no error anywhere.
                // Before 1057 it was worse: emit then suspended FOREVER on the then-0-buffer
                // MutableSharedFlow, so one early failure took the whole process's reporting down
                // permanently. That is a plausible reason some classes of startup error (a DNS failure on
                // an extension call, say) were under-reported on builds before 1057.
                // Swallowing is correct here: failing to RECORD an error must never escalate into losing
                // all subsequent ones. Pairs with the DROP_OLDEST buffer above - that stops a stuck
                // collector blocking emitters, this stops a throwing one disappearing entirely.
                @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants", "SwallowedException")
                if (BuildConfig.HAS_FIREBASE && !it.isLoginRequired()) runCatching {
                    FirebaseCrashlytics.getInstance().apply {
                        // extension_id is the PLAYING extension (crashExtensionId, written at
                        // PlayerService:322 on media-item transition) — NOT the thrower. Most browse/feed
                        // errors come from a non-playing extension, so this systematically mis-attributes
                        // them. Kept unchanged for historical comparability with existing issues, and
                        // duplicated by playing_extension_id. Read throwing_extension_id for attribution.
                        setCustomKey("extension_id", crashExtensionId)
                        // The extension that actually threw, walked off the AppException in the chain
                        // (ExtensionUtils.get:30 wraps every extension call, so one is present for any
                        // error routed through that helper). ALWAYS written — Crashlytics keys are sticky
                        // on the singleton, so omitting it would leave the previous report's value
                        // attached to a host-side error. "none" now means a genuine HOST-side failure —
                        // the two AndroidAutoCallback sites that bypass ExtensionUtils.get (performSearch
                        // and getList) wrap explicitly, so the AA browse/search path attributes correctly.
                        // AndroidAutoCallback:294 stays deliberately unwrapped: Job.join() does not rethrow
                        // the joined job's failure, so the only throwable reaching it is from Media3's
                        // notifySearchResultChanged — host-side, and "none" is the right answer there.
                        setCustomKey("throwing_extension_id", it.throwingExtensionId() ?: "none")
                        // "none" = this report came through throwFlow, NOT HealthMonitor. Written for the
                        // same always-write reason as the keys around it: HealthMonitor.report sets
                        // health_report_type to its exception type, and without this line that value would
                        // stick on the singleton and mislabel the NEXT throwFlow report as a health report.
                        setCustomKey("health_report_type", "none")
                        setCustomKey("player_state", crashPlayerState)
                        setCustomKey("is_playing", crashIsPlaying)
                        recordException(it)
                    }
                }
            }
        }
        // Network-state monitoring is best-effort. Some OEM/framework builds reject the ConnectivityManager
        // binder call from the system server (e.g. OnePlus 7 / GM1913 / Android 11 threw SecurityException/
        // RemoteException "Package android does not belong to <uid>"). Because this runs in the App singleton's
        // CONSTRUCTOR, that used to cascade through Koin (App -> ExtensionLoader) and crash app launch entirely.
        // Guard it so a flaky framework call degrades to "no live network updates" instead of failing to start.
        try {
            val connectivityManager =
                context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val isMetered = connectivityManager.isActiveNetworkMetered
                    _networkFlow.value = if (isMetered) NetworkConnection.Metered
                    else NetworkConnection.Unmetered
                }

                override fun onLost(network: Network) {
                    _networkFlow.value = NetworkConnection.NotConnected
                }
            }
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            _networkFlow.value = when {
                connectivityManager.activeNetwork == null -> NetworkConnection.NotConnected
                connectivityManager.isActiveNetworkMetered -> NetworkConnection.Metered
                else -> NetworkConnection.Unmetered
            }
        } catch (e: Exception) {
            // Degrade gracefully rather than crash construction: assume an online-but-metered connection so
            // extensions still treat the device as connected (NotConnected would make them behave as offline)
            // and playback stays on the conservative metered-quality path. No live updates on this device.
            e.printStackTrace()
            _networkFlow.value = NetworkConnection.Metered
            scope.launch { throwFlow.emit(e) }  // record non-fatally (same Crashlytics path as everywhere else)
        }
    }

    // True if this throwable (or anything in its cause chain) is a login-required signal. Matches BOTH
    // ClientException.LoginRequired (raw form the AA getList path emits) and AppException.LoginRequired (the
    // wrapped form the player/getOrThrow paths emit) — Unauthorized is a subclass of each, so it's covered.
    // Id of the extension that threw: the first AppException in the cause chain. Same walk shape as
    // isLoginRequired below. Uses Metadata.id (not .name) so values line up with extension_id /
    // playing_extension_id and stay filterable. First-found is correct even through Unified —
    // toAppException returns an existing AppException as-is (AppException.kt:60), so the chain holds
    // exactly one, carrying the SUB-extension's metadata rather than "unified".
    private fun Throwable.throwingExtensionId(): String? {
        var t: Throwable? = this
        while (t != null) {
            (t as? AppException)?.let { return it.extension.id }
            t = t.cause
        }
        return null
    }

    private fun Throwable.isLoginRequired(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t is ClientException.LoginRequired || t is AppException.LoginRequired) return true
            t = t.cause
        }
        return false
    }
}
