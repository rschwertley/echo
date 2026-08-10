package dev.brahmkshatriya.echo.utils

import android.os.SystemClock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Crashlytics custom keys set at natural checkpoints during NORMAL operation (never in a crash handler), so an
 * UNCAUGHT fatal — e.g. OutOfMemoryError, which never reaches the throwFlow recorder in App — still carries
 * them on the Crashlytics singleton when the default uncaught handler reports it.
 *
 * Constraints honored:
 * - primitives only (Int/String/Boolean), no allocation beyond the key value itself;
 * - every write is HAS_FIREBASE-guarded (compile-time const → the branch is dead-stripped and
 *   FirebaseCrashlytics is never referenced in the no-Firebase/F-Droid variant) and runCatching-wrapped
 *   (a not-yet-initialized Crashlytics can never throw);
 * - counts are monotonic AtomicInts, not flags — keys are last-write-wins, so a flag would lose the history
 *   a counter keeps;
 * - every caller is a per-event checkpoint (service create, restore build, debounced save, track transition,
 *   extension switch, feed load, controller connect/disconnect, AA route change) — none on a tight loop.
 */
object CrashKeys {

    @Volatile private var processStartElapsedMs = 0L
    private val extensionSwitches = AtomicInteger(0)
    private val feedLoads = AtomicInteger(0)
    private val remoteControllers = AtomicInteger(0)

    private fun set(key: String, value: Int) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    private fun set(key: String, value: String) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    private fun set(key: String, value: Boolean) {
        if (BuildConfig.HAS_FIREBASE) runCatching { FirebaseCrashlytics.getInstance().setCustomKey(key, value) }
    }

    /** Recorded once at process birth (MainApplication.onCreate). elapsedRealtime is monotonic + alloc-free. */
    fun markProcessStart() {
        processStartElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun stampAge() {
        val start = processStartElapsedMs
        set("process_age_s", if (start == 0L) -1 else ((SystemClock.elapsedRealtime() - start) / 1000L).toInt())
    }

    // used = total - free; headroom = growth-limit (maxMemory) - used. Runtime is a singleton; counters only.
    private fun sampleHeap(usedKey: String, headroomKey: String) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        set(usedKey, (used / (1024 * 1024)).toInt())
        set(headroomKey, ((rt.maxMemory() - used) / (1024 * 1024)).toInt())
    }

    fun onServiceCreate() {
        stampAge()
        sampleHeap("heap_used_mb_svc", "heap_headroom_mb_svc")
    }

    fun onQueueBuild(itemCount: Int) {
        stampAge()
        set("restore_build_count", itemCount)
        sampleHeap("heap_used_mb_build", "heap_headroom_mb_build")
    }

    fun onQueueSize(count: Int) {
        stampAge()
        set("player_media_item_count", count)
    }

    fun onExtensionSwitch(extensionId: String) {
        stampAge()
        set("extension_switch_count", extensionSwitches.incrementAndGet())
        set("current_extension_id", extensionId)
    }

    fun onFeedLoad() {
        stampAge()
        set("feed_load_count", feedLoads.incrementAndGet())
        // The only heap sample tied to the aggregate-working-set hypothesis (feed loads accumulate
        // covers/shelves that the svc-create and queue-build samples both miss, being earlier). Caller is
        // debounced 100ms + collectLatest (~once per settled switch/refresh); 3 Runtime reads + 3 key writes,
        // no allocation — not hot, no every-Nth gating needed.
        sampleHeap("heap_used_mb_feed", "heap_headroom_mb_feed")
    }

    fun onPlayingExtension(extensionId: String) {
        stampAge()
        set("playing_extension_id", extensionId)
    }

    fun onControllerConnected() {
        stampAge()
        set("remote_controller_count", remoteControllers.incrementAndGet())
        // All three known crashes fired at MediaController connect, a few hundred ms after onCreate — so the
        // svc-create sample can already be stale. The svc→conn heap delta shows whether startup is climbing
        // fast or the heap was already high on arrival.
        sampleHeap("heap_used_mb_conn", "heap_headroom_mb_conn")
    }

    fun onControllerDisconnected() {
        set("remote_controller_count", remoteControllers.updateAndGet { (it - 1).coerceAtLeast(0) })
    }

    fun onAndroidAutoState(connected: Boolean) {
        set("aa_connected", connected)
    }
}
