package dev.brahmkshatriya.echo.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build.SUPPORTED_ABIS
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutionException

object ContextUtils {
    fun appVersion() = BuildConfig.VERSION_NAME + " " + BuildConfig.BUILD_TYPE
    fun getArch(): String {
        SUPPORTED_ABIS.firstOrNull()?.let { return it }
        return System.getProperty("os.arch")
            ?: System.getProperty("os.product.cpu.abi")
            ?: "Unknown"
    }

    fun Context.copyToClipboard(label: String?, string: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, string))
            return
        } catch (e: RuntimeException) {
            // OEM/OS clipboard restriction — an INTERACT_ACROSS_USERS check misfiring (seen on some Moto,
            // MIUI, and Android 14-15). We can't hold that permission and can't fix the OS; a clipboard
            // failure must never crash. Record it, then fall through to tell the user.
            Log.w("ContextUtils", "copyToClipboard: setPrimaryClip failed", e)
        }
        // Independent, best-effort guard: surface the failure. Its own try so a Toast failure can neither
        // crash nor hide the copy failure already logged above.
        try {
            Toast.makeText(this, R.string.couldnt_copy_to_clipboard, Toast.LENGTH_SHORT).show()
        } catch (_: RuntimeException) {
            // best effort — nothing more we can do
        }
    }

    fun <T> LifecycleOwner.observe(flow: Flow<T>, block: suspend (T) -> Unit) =
        lifecycleScope.launch {
            flow.flowWithLifecycle(lifecycle).collectLatest(block)
        }

    fun <T> LifecycleOwner.collect(flow: Flow<T>, block: suspend (T) -> Unit) =
        lifecycleScope.launch {
            flow.collect {
                runCatching { block(it) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }

    /**
     * A CANCELLED future is not an error, and does not reach [block] at all.
     *
     * `future.get()` on a cancelled future throws CancellationException, which runCatching turns into a
     * failed Result — and every caller treats a failed Result as something to report. That is how normal
     * teardown was arriving as a non-fatal: PlayerViewModel.onCleared calls controllerFutureRelease(),
     * which is MediaController.releaseFuture(playerFuture), which CANCELS a still-pending connection. On a
     * slow device the ViewModel can clear about a second into process life, before the controller has
     * finished connecting, so the app reported its own teardown as a crash (seen on a Redmi Go, Android 10,
     * player_state=1). ControllerHelper holds the same release lambda for the widget.
     *
     * Returning without calling [block] is safe for all three call sites, checked one by one:
     *  - PlayerService.getController — [block] only ever runs on success; the sole other branch is the
     *    error report we are removing. There is no "cancelled" consumer.
     *  - PlayerViewModel.likeCurrent — discards the result apart from reporting failures.
     *  - PlayerViewModel.likeById — this one DOES pass a resultCode on to a caller
     *    (MediaMoreBottomSheet.likeFromSheet, which routes RESULT_ERROR_BAD_VALUE to the extension-only
     *    path and otherwise refreshes). Not calling it is still correct: a cancelled setRating means the
     *    command was abandoned with the session, so the like did not happen and there is nothing to
     *    reconcile. The sheet's like button is rebuilt from state.isLiked via itemResultFlow — it is not
     *    gated on the callback, has no loading or disabled state waiting on it, and so cannot be stranded.
     *
     * Checked with isCancelled rather than by catching CancellationException: for a ListenableFuture the
     * two are equivalent (get() throws it only when the future itself was cancelled), and the explicit
     * query cannot be confused with a CancellationException surfacing from inside the computation. The
     * future is terminal by the time a listener runs, so the flag cannot flip under us.
     *
     * NOT a coroutine cancellation — this is a plain listener callback, so there is no structured
     * concurrency to propagate to and nothing to rethrow. (Different situation from CoroutineUtils'
     * future handling, where a naive rethrow would hang Media3.)
     *
     * ⚠️ CANCELLED IS NOT THE SAME AS A DEAD SERVICE, and this guard does NOT re-blind the
     * service-restart-loop diagnostic that 172f1edb added here (the loop that was invisible until it
     * OOM'd, build 1039). Verified in the media3-session 1.11.0 sources, not assumed:
     *  - `MediaController.release()` ends with `if (connectionNotified) … else
     *    connectionCallback.onRejected()`, and `MediaControllerHolder.maybeSetException()` turns that into
     *    `setException(SecurityException("Session rejected the connection request."))`.
     *  - `onServiceDisconnected` and `onBindingDied` both route to `getInstance()::release`.
     * So a service dying or crashing mid-connect FAILS the future — it does not cancel it, and it is still
     * reported. The only way to reach a cancelled future is `MediaController.releaseFuture`, whose first
     * act is `controllerFuture.cancel(false)`; in this app that is called only from our own teardown
     * (PlayerViewModel.onCleared, ControllerHelper). Cancelled == we tore it down. Failed == it broke.
     *
     * Genuine failures are still reported, now UNWRAPPED. `get()` wraps everything in ExecutionException,
     * whose stack is generated at the `get()` call site — identical for every failure through this helper —
     * so Crashlytics grouped unrelated faults together under one ExecutionException issue. Unwrapping
     * SPLITS that by real cause; it cannot merge anything that is currently distinct. Expect existing
     * mutes/closes on the old ExecutionException issue to stop applying and pre-existing faults to surface
     * as new issues. `isLoginRequired()` and `throwingExtensionId()` both walk the cause chain, so
     * LoginRequired suppression and extension attribution are unaffected.
     */
    fun <T> Context.listenFuture(future: ListenableFuture<T>, block: (Result<T>) -> Unit) {
        future.addListener({
            if (future.isCancelled) return@addListener
            val result = try {
                Result.success(future.get())
            } catch (e: ExecutionException) {
                Result.failure(e.cause ?: e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
            block(result)
        }, ContextCompat.getMainExecutor(this))
    }

    fun <T> LifecycleOwner.emit(flow: MutableSharedFlow<T>, value: T) {
        lifecycleScope.launch {
            flow.emit(value)
        }
    }

    const val SETTINGS_NAME = "settings"
    fun Context.getSettings() = getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)!!

    private fun Context.getTempDir() = cacheDir.resolve("apks").apply { mkdirs() }
    fun Context.getTempFile(ext: String = "apk"): File =
        File.createTempFile("temp", ".$ext", getTempDir())

    fun Context.cleanupTempApks() {
        getTempDir().deleteRecursively()
    }
}