package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 *   (a not-yet-initialized Crashlytics can never throw).
 *
 * ── Key semantics, learned the hard way on the build-1033 OOM ────────────────────────────────────────────
 * Crashlytics keys are LAST-WRITE-WINS. Three corrections came out of that report, plus one from the
 * 2026-08-20 HealthMonitor attribution bug:
 *
 * 1. COUNTS ARE MONOTONIC, GAUGES ARE NOT. The old `remote_controller_count` incremented on connect and
 *    DECREMENTED on disconnect, i.e. it was a gauge, not the monotonic counter this doc used to claim. That
 *    made it unreadable: Media3 calls onConnect unconditionally from
 *    MediaSessionServiceLegacyStub.onGetRoot (no existing-controller check) while ConnectedControllersManager
 *    .addController de-dupes on RemoteUserInfo and never removes the earlier record — so a client that
 *    reconnects in a loop produces N increments and ZERO decrements. The gauge drifts upward by construction
 *    and cannot be read as "how many are connected". Connects and disconnects are now separate monotonic
 *    counters, so churn and residency are distinguishable.
 *
 * 2. ONE SHARED AGE KEY CANNOT ATTRIBUTE. `process_age_s` used to be stamped by every checkpoint, so with
 *    ~1120 controller connects its final value was the age at the last CONNECT — not, as it read, the age at
 *    service create. Every checkpoint now stamps its OWN age key. `process_age_s` is kept, but is explicitly
 *    "age at the most recent checkpoint of any kind" and must not be read as belonging to any one of them.
 *
 * 3. LAST-WRITE HEAP SAMPLES CANNOT SHOW A TRAJECTORY. Every heap key read 255/0, which is near-tautological
 *    once the heap is full and events keep firing — it could not distinguish "born high" from "climbed to
 *    full and stayed". A first-ever sample and a running max are now recorded alongside, giving three points
 *    (first → peak → last) instead of one.
 *
 * 4. WHETHER OMITTING A WRITE IS SAFE DEPENDS ON THE KEY'S KIND. Because keys are last-write-wins, skipping a
 *    write leaves the previous value in place. That is CORRECT for a monotonic ACCUMULATOR (heap_peak_mb is
 *    only written when the peak advances — not writing means "no new peak", and the retained value is still
 *    true). It is a LIE for a SNAPSHOT (extension_id, player_state, throwing_extension_id describe one report;
 *    an unwritten one silently describes a different, earlier one — this is exactly how HealthMonitor reports
 *    carried another exception's attribution until 2026-08-20). Test before adding a key: if this write is
 *    skipped, is the value left behind still true? Accumulator -> yes, omit freely. Snapshot -> no, ALWAYS
 *    write, with an explicit "none" where there is nothing to say.
 *
 * 5. LAST-WRITE CANNOT DISTINGUISH ONE LATE EVENT FROM A STORM. Every checkpoint key is last-write, so a
 *    checkpoint that fires 1000 times reports occurrence 1000 and nothing else. Build 1039 (2026-08-23)
 *    had service_create_count ~1050 in ~60s, and age_s_svc / age_s_conn / age_s_build / process_age_s all
 *    read equal to the process age — not because they fired together, but because each fired LAST at the
 *    moment of death. Every heap sample was likewise a dying-end reading. Each checkpoint now also writes
 *    a CAS-guarded <key>_first (and <heapKey>_first), so first + existing count + last reads as a rate.
 *    READ _first WHEN ATTRIBUTING; read the bare key only as "state at the end".
 *
 * Note on hotness: onControllerConnected is NOT rate-limited and, under the connect storm this instrumentation
 * exists to diagnose, can fire several times a second. Its writes are a handful of map puts with no allocation
 * beyond the values, so this is acceptable — but do not add anything expensive to that path.
 *
 * Heap note: "used" is totalMemory - freeMemory and is sampled WITHOUT forcing a GC, so it includes garbage
 * not yet collected and can overstate live data under a high allocation rate. "headroom" is maxMemory - used,
 * i.e. room to the growth limit — NOT Runtime.freeMemory (free-within-committed, which is misleading near OOM).
 */
object CrashKeys {

    @Volatile private var processStartElapsedMs = 0L
    private val extensionSwitches = AtomicInteger(0)
    private val feedLoads = AtomicInteger(0)
    // Split from the old remote_controller_count gauge (see doc note 1). Both monotonic: their DIFFERENCE is
    // the old gauge, their RATIO is the churn signal the gauge hid.
    private val controllerConnects = AtomicInteger(0)
    private val controllerDisconnects = AtomicInteger(0)
    // Monotonic. >1 means PlayerService was destroyed and recreated within one process — the kill/rebind loop
    // that would explain a late, already-full heap_used_mb_svc sample.
    private val serviceCreates = AtomicInteger(0)

    private val heapFirstRecorded = AtomicBoolean(false)
    private val heapPeakMb = AtomicInteger(0)

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

    /**
     * Install source, recorded ONCE at process birth. It cannot change during a process, so this is
     * set-and-forget rather than a per-report computation.
     *
     * Records PACKAGE NAMES, not a boolean: "com.android.vending" vs a browser package vs "none" is what
     * makes a report triageable, where true/false could not tell a Play install from a sideload.
     *
     * BOTH names are recorded because AppUpdater.isStoreInstall() matches on EITHER, so storing one would
     * leave the gate's own input half-visible. initiatingPackageName is system-verified and is the primary
     * triage value; installingPackageName is reassignable by the installer via setInstallerPackageName, but
     * is the only value available below API 30.
     *
     * Cost: one PackageManager binder call, same class as the getPackageInfo calls made elsewhere. Runs on
     * the main thread at onCreate so it is attached to crashes from startup onward; doing it off-thread
     * would race the very reports it exists to label.
     *
     * Direct Boot caveat: on a pre-unlock spawn Crashlytics may not be ready, in which case the guarded
     * writes are swallowed and the key is simply absent for that (rare) process.
     */
    fun recordInstallSource(context: Context) {
        runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(context.packageName)
                set("install_source", info.initiatingPackageName ?: "none")
                set("install_source_installer", info.installingPackageName ?: "none")
            } else {
                @Suppress("DEPRECATION")
                val installer = pm.getInstallerPackageName(context.packageName)
                set("install_source", "api<30")
                set("install_source_installer", installer ?: "none")
            }
        }
    }

    private fun ageS(): Int {
        val start = processStartElapsedMs
        return if (start == 0L) -1 else ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
    }

    // Keys whose FIRST value has already been written. A checkpoint that fires N times leaves its
    // last-write key showing occurrence N; the _first companion pins occurrence 1, and the existing
    // counters give N — so first + count + last is readable as a RATE. Without the first-write, a
    // storm is indistinguishable from a single late event (see doc note 5).
    private val firstStamped = ConcurrentHashMap.newKeySet<String>()
    private val processAgeStamped = AtomicBoolean(false)

    // Writes THREE things per checkpoint:
    //  • <key>        — last write, name unchanged so existing Crashlytics issues stay comparable;
    //  • <key>_first  — CAS-guarded first occurrence, the one that attributes;
    //  • process_age_s — ONCE, at the first checkpoint of any kind.
    // process_age_s used to be re-stamped by every checkpoint, so under a storm it reported the age at
    // the LAST one — i.e. the moment of death — while reading like "how old was the process". That
    // silently misled the build-1039 OOM triage. It is now first-write and means what it says.
    private fun stampAge(checkpointKey: String) {
        val age = ageS()
        set(checkpointKey, age)
        if (firstStamped.add(checkpointKey)) set("${checkpointKey}_first", age)
        if (processAgeStamped.compareAndSet(false, true)) set("process_age_s", age)
    }

    private fun sampleHeap(usedKey: String, headroomKey: String) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val usedMb = (used / (1024 * 1024)).toInt()
        val headroomMb = ((rt.maxMemory() - used) / (1024 * 1024)).toInt()
        set(usedKey, usedMb)
        set(headroomKey, headroomMb)
        // Per-checkpoint FIRST sample, same reasoning as stampAge: a repeatedly-fired checkpoint's
        // last-write heap value is the dying-end reading, not the reading at the event you are
        // attributing to. heap_used_mb_conn on the build-1036 AA report was the LAST connect of the
        // session, not the first — which is exactly the misreading these companions prevent.
        if (firstStamped.add(usedKey)) {
            set("${usedKey}_first", usedMb)
            set("${headroomKey}_first", headroomMb)
        }
        // First-ever sample: pins the STARTING point of the trajectory, which no last-write key can. CAS so
        // the first sampler wins even if two checkpoints race.
        if (heapFirstRecorded.compareAndSet(false, true)) {
            set("heap_first_mb", usedMb)
            set("heap_first_at_age_s", ageS())
        }
        // Running max: written only when it actually advances, so a heap that plateaus stops writing. With
        // first + peak + last, "born high" (first ≈ peak ≈ last) is distinguishable from "climbed" (first low,
        // peak late), and heap_peak_at_age_s dates the climb.
        val previousPeak = heapPeakMb.getAndUpdate { if (usedMb > it) usedMb else it }
        if (usedMb > previousPeak) {
            set("heap_peak_mb", usedMb)
            set("heap_peak_at_age_s", ageS())
        }
    }

    fun onServiceCreate() {
        stampAge("age_s_svc")
        set("service_create_count", serviceCreates.incrementAndGet())
        sampleHeap("heap_used_mb_svc", "heap_headroom_mb_svc")
    }

    // Sampled immediately AFTER AndroidAutoCallback.clearCaches(). heap_used_mb_conn is taken in
    // onConnect, which Media3 invokes BEFORE onGetLibraryRoot — so conn measures the retained graph the
    // last session left, and this measures what survives the clear. The difference is the browse caches.
    fun onAutoCachesCleared() {
        stampAge("age_s_auto_clear")
        sampleHeap("heap_used_mb_auto_clear", "heap_headroom_mb_auto_clear")
    }

    /**
     * The app-update install-source gate LET US PROCEED. Deliberately means exactly that — not "we
     * checked", and not "we made a network call" — so it is set immediately after
     * AppUpdater.isStoreInstall() returns false, before the build-type branch.
     *
     * This is the verification half of an absolute requirement: Play users must never be offered an
     * app update. Enforcement alone was only half-observable — a FAILED app update reported itself
     * (and, since the repo now appears in getGithubUpdateUrl's messages, was attributable to the app
     * path), but a SUCCESSFUL bypass produced nothing at all. With this key, any report carrying
     * install_source = com.android.vending AND app_update_attempted = true is a visible violation.
     *
     * Sticky for the process by design: once the gate passes it rides on every later report.
     */
    fun onAppUpdateGatePassed() {
        stampAge("age_s_app_update_gate")
        set("app_update_attempted", true)
    }

    fun onQueueBuild(itemCount: Int) {
        stampAge("age_s_build")
        set("restore_build_count", itemCount)
        sampleHeap("heap_used_mb_build", "heap_headroom_mb_build")
    }

    fun onQueueSize(count: Int) {
        stampAge("age_s_queue")
        set("player_media_item_count", count)
    }

    fun onExtensionSwitch(extensionId: String) {
        stampAge("age_s_switch")
        set("extension_switch_count", extensionSwitches.incrementAndGet())
        set("current_extension_id", extensionId)
    }

    fun onFeedLoad() {
        stampAge("age_s_feed")
        set("feed_load_count", feedLoads.incrementAndGet())
        // The only heap sample tied to the aggregate-working-set hypothesis (feed loads accumulate
        // covers/shelves that the svc-create and queue-build samples both miss, being earlier). Caller is
        // debounced 100ms + collectLatest (~once per settled switch/refresh); 3 Runtime reads + 3 key writes,
        // no allocation — not hot, no every-Nth gating needed.
        sampleHeap("heap_used_mb_feed", "heap_headroom_mb_feed")
    }

    fun onPlayingExtension(extensionId: String) {
        stampAge("age_s_playing")
        set("playing_extension_id", extensionId)
    }

    // packageName names the storm source directly — gearhead's browser, system UI, Bluetooth, our own UI or
    // the widget — which no count can. It is the one field that turns "something reconnected 1120 times" into
    // an actionable lead.
    fun onControllerConnected(packageName: String) {
        stampAge("age_s_conn")
        set("controller_connect_count", controllerConnects.incrementAndGet())
        set("last_controller_pkg", packageName)
        // All three known crashes fired at MediaController connect, a few hundred ms after onCreate — so the
        // svc-create sample can already be stale. The svc→conn heap delta shows whether startup is climbing
        // fast or the heap was already high on arrival.
        sampleHeap("heap_used_mb_conn", "heap_headroom_mb_conn")
    }

    fun onControllerDisconnected(packageName: String) {
        stampAge("age_s_disc")
        set("controller_disconnect_count", controllerDisconnects.incrementAndGet())
        set("last_disconnected_pkg", packageName)
    }

    fun onAndroidAutoState(connected: Boolean) {
        set("aa_connected", connected)
    }
}
