package dev.brahmkshatriya.echo.utils

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext

object CoroutineUtils {
    fun setDebug() {
        System.setProperty(
            kotlinx.coroutines.DEBUG_PROPERTY_NAME,
            kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
        )
    }

    fun <T> Flow<T>.throttleLatest(delayMillis: Long): Flow<T> = conflate().transform {
        emit(it)
        delay(delayMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    inline fun <reified T, R> combineTransformLatest(
        vararg flows: Flow<T>,
        noinline transform: suspend FlowCollector<R>.(Array<T>) -> Unit
    ): Flow<R> {
        return combine(*flows) { it }
            .transformLatest(transform)
    }

    fun <T1, T2, R> Flow<T1>.combineTransformLatest(
        flow2: Flow<T2>,
        transform: suspend FlowCollector<R>.(T1, T2) -> Unit
    ): Flow<R> {
        return combineTransformLatest(this, flow2) { args ->
            @Suppress("UNCHECKED_CAST")
            transform(
                args[0] as T1,
                args[1] as T2
            )
        }
    }

    fun <T> CoroutineScope.future(
        context: CoroutineContext = Dispatchers.IO, block: suspend () -> T
    ): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        launch(context) {
            future.set(block())
        }
        return future
    }

    /**
     * ⚠️ OPEN, WITH A KNOWN BLOCKER — READ THIS BEFORE "FIXING" IT. STILL CATCHING AT 2026-09-07.
     *
     * THE DEFECT. runCatching catches Throwable, and CancellationException IS a Throwable, so a cancelled
     * `block()` is reported to the consumer as a FAILURE via setException. Instance 3 of four in this
     * project — the named pattern and the full list live at DeezerParser.toShelfItemsList's `more` lambda;
     * the others are ImageUtils.tryWithSuspend and PlayerBitmapLoader (both fixed).
     *
     * ⚠️ WHY THE OBVIOUS FIX IS WRONG HERE, WHICH IS WHY THIS HAS SAT OPEN. The fix applied at the other
     * three sites — rethrow CancellationException ahead of the generic catch — IS A REGRESSION AT THIS ONE.
     * Rethrowing out of the `launch` block means `future` IS NEVER COMPLETED: SettableFuture has no
     * timeout and no failure of its own, so the ListenableFuture never resolves and MEDIA3'S CALLER WAITS
     * FOREVER. Today's behaviour at least completes the future, wrongly, as a failure; a naive rethrow
     * trades a wrong answer for a HANG, which is strictly worse. This is readable straight off the code
     * above: nothing but `future.set` / `future.setException` ever completes it.
     * ⚠️ THIS ITEM HAS NOW BEEN PICKED UP AND SET DOWN TWICE with only "still catching" recorded, which
     * is exactly enough information to pick it up a third time and either drop it again or fix it naively.
     * The blocker is the point, not the defect.
     *
     * THE CORRECT SHAPE, RECORDED SO THE NEXT READER STARTS FROM THE ANSWER RATHER THAN THE PROBLEM:
     * propagate cancellation through the ListenableFuture CONTRACT, not through the coroutine —
     *     runCatching { future.set(block()) }.getOrElse {
     *         if (it is CancellationException) future.cancel(false) else future.setException(it)
     *     }
     * `cancel(false)` completes the future as CANCELLED, which is the state a ListenableFuture consumer
     * already knows how to read (isCancelled, and CancellationException from get()), so Media3 sees
     * "cancelled" instead of "failed" and nothing hangs. NOT BUILT: this is a shared utility on the
     * playback path and deserves its own pass with a device check, not a drive-by on someone else's fix.
     * Before building it, enumerate the callers — a consumer that treats cancellation and failure alike
     * gains nothing, and one that special-cases isCancelled is the reason to do it.
     */
    fun <T> CoroutineScope.futureCatching(
        context: CoroutineContext = Dispatchers.IO, block: suspend () -> T
    ): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        launch(context) {
            runCatching {
                future.set(block())
            }.getOrElse {
                future.setException(it)
            }
        }
        return future
    }


    suspend fun <T> ListenableFuture<T>.await(context: Context) = suspendCancellableCoroutine {
        it.invokeOnCancellation {
            cancel(true)
        }
        addListener({
            it.resumeWith(runCatching { get()!! })
        }, ContextCompat.getMainExecutor(context))
    }
}