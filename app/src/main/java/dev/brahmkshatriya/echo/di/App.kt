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

data class App(
    val context: Application,
    val settings: SharedPreferences,
) {
    val throwFlow = MutableSharedFlow<Throwable>()
    val messageFlow = MutableSharedFlow<Message>()

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

    val fileCache = scope.async(Dispatchers.IO, CoroutineStart.LAZY) {
        runCatching { getCache() }.getOrElse {
            context.cacheDir.resolve("kache").deleteRecursively()
            getCache()
        }
    }

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
                @Suppress("KotlinConstantConditions", "SimplifyBooleanWithConstants")
                if (BuildConfig.HAS_FIREBASE && !it.isLoginRequired()) FirebaseCrashlytics.getInstance().apply {
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
                    setCustomKey("player_state", crashPlayerState)
                    setCustomKey("is_playing", crashIsPlaying)
                    recordException(it)
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
