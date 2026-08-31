package dev.brahmkshatriya.echo.utils

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.di.App
import java.util.concurrent.ConcurrentHashMap

class HealthMonitor(private val app: App) {

    enum class Scope { PERSISTENT, MEMORY_ONLY }

    /**
     * Base for every HealthMonitor report.
     *
     * Fields are `val`s, not merely interpolated into [message]: the message is a human-readable
     * rendering, and string-parsing it back was previously the ONLY way to recover this data — which
     * breaks silently the first time somebody reformats a message.
     *
     * [extensionId] is the extension the report is ABOUT. report() writes it straight into the
     * `throwing_extension_id` crash key, so attribution comes from the report itself rather than from
     * whatever the last throwFlow-routed error happened to leave on the Crashlytics singleton. null
     * where a report genuinely has no extension (the restore-time integrity checks).
     */
    sealed class HealthException(message: String, val extensionId: String?) : Exception(message)

    class ExtensionResolutionTimeout(extensionId: String, val durationMs: Long) :
        HealthException("extensionId=$extensionId durationMs=$durationMs", extensionId)

    /**
     * Split into two concrete families ON PURPOSE — do not collapse it back.
     *
     * Crashlytics groups a non-fatal on the exception CLASS plus the top stack frames, and this had
     * exactly one construction site (PlayerEventListener.reportAndResetConsecutiveSkips), so every trip
     * produced an identical class and byte-identical frames. A 403 storm from one extension and a
     * buffering stall from another landed in ONE issue, and there was no way to mute the expected family
     * without also hiding the one being watched for. The differing MESSAGE does not help: it is the issue
     * subtitle, and subtitles are not part of the grouping key. Neither is a custom key — keys are
     * per-event metadata, while mute/close act on the issue.
     *
     * ⚠️ NAMES ARE DELIBERATELY FLAT, NOT NESTED. `report()` derives both the Crashlytics issue identity
     * and the `health_report_type` key from `javaClass.simpleName`, and for a nested class that returns
     * ONLY the inner name — declaring these as `ConsecutiveSkipException.Stall` would title the issue
     * "Stall" with no context. Keep any future family a sibling with a fully-qualifying name.
     *
     * The family comes from PlayerEventListener's `recentSkipRunHadError` flag, NOT from string-matching
     * [lastCauses] for "StuckBuffering" — see the note on this file's [HealthException] about why parsing
     * the message back is the thing these `val`s exist to prevent.
     *
     * The MESSAGE FORMAT IS UNCHANGED, and must stay that way: [lastCauses] carries the probe fields, and
     * `report()`'s dedupe signature is simpleName + message. The two families already produced different
     * messages (a stall run reads `StuckBuffering loaded=… …`, an error run reads `CODE ext:… Class …`),
     * so prefixing a different class name changes the signature STRING without changing the PARTITIONING —
     * the 10-minute cooldown was already per-family and still is. Scope is MEMORY_ONLY, so the only cost of
     * the renamed signatures is that the first trip after an update reports instead of being suppressed,
     * once. (A PERSISTENT report renamed this way would instead strand dead keys in the prefs forever and
     * restart every cooldown — relevant if ResumptionUtils' or StreamableLoader's reports are ever split.)
     *
     * NOT split on extension-attributability. That sounds like the natural axis and is the wrong one: the
     * `ext:` field in a cause comes from an AppException in the chain, and an HTTP 403 on a STREAM URL is a
     * Media3 `InvalidResponseCodeException` from the datasource carrying no AppException — so an
     * extension-attributable split would not separate a 403 storm from a stall at all. If the error family
     * later needs subdividing, do it on HTTP-code presence.
     */
    sealed class ConsecutiveSkipException(
        val skipCount: Int, val lastExtensionId: String, val lastCauses: String
    ) : HealthException(
        "skipCount=$skipCount lastExtensionId=$lastExtensionId lastCauses=$lastCauses", lastExtensionId
    )

    /** Every skip in the run came from the buffering watchdog — `recordSkip(null)`, no error object. */
    class ConsecutiveSkipStallException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    /**
     * At least one skip in the run carried a real throwable. Mixed runs land here deliberately: a genuine
     * error present is the actionable half, and burying it in the mutable stall family is the failure this
     * split exists to prevent.
     */
    class ConsecutiveSkipErrorException(
        skipCount: Int, lastExtensionId: String, lastCauses: String
    ) : ConsecutiveSkipException(skipCount, lastExtensionId, lastCauses)

    class OrphanedSessionException(val savedTrackCount: Int, val firstTrackId: String) :
        HealthException("savedTrackCount=$savedTrackCount firstTrackId=$firstTrackId", null)

    // Tripwire for the resumption fix: on restore, the track at the saved index must be the track
    // that was current at save time. Fires only if a future change re-poisons the persisted index
    // with a non-full-basis value (e.g. a windowed index). Diagnostic only — restore still proceeds.
    class ResumeIndexMismatchException(
        val expectedId: String, val actualId: String, val index: Int, val size: Int
    ) : HealthException(
        "resume index/id mismatch: index=$index size=$size expected=$expectedId actual=$actualId", null
    )

    // Benign teardown race: a media3 datasource raised a bare IllegalStateException from one of its
    // checkState() lifecycle assertions because the player/cache was torn down while a load was still
    // closing. Suppressed at the player layer (PlayerEventListener.onPlayerError) so it never reaches the
    // user; reported here rate-limited so the FREQUENCY is still visible. The original ISE is attached as
    // the cause, so its retraceable close-cascade stack survives.
    // The message is deliberately CONSTANT: report()'s dedupe signature is simpleName + message, so a
    // varying message (track id, extension) would defeat the cooldown and turn this back into spam.
    class DataSourceTeardownRaceException(cause: Throwable) : HealthException(
        "IllegalStateException during media3 datasource close/teardown", null
    ) {
        init { initCause(cause) }
    }

    private val prefs = app.context.getSharedPreferences("gladix_health_monitor", Context.MODE_PRIVATE)
    private val memoryTimestamps = ConcurrentHashMap<String, Long>()

    fun report(exception: HealthException, scope: Scope, cooldownMs: Long) {
        val signature = "${exception.javaClass.simpleName}_${exception.message}"
        val now = System.currentTimeMillis()
        val lastReported = when (scope) {
            Scope.PERSISTENT -> prefs.getLong(signature, 0L)
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] ?: 0L
        }
        if (now - lastReported < cooldownMs) return
        when (scope) {
            Scope.PERSISTENT -> prefs.edit { putLong(signature, now) }
            Scope.MEMORY_ONLY -> memoryTimestamps[signature] = now
        }
        // BuildConfig.HAS_FIREBASE is a compile-time boolean (no Firebase type referenced), so in
        // no-json builds this branch is dead and FirebaseCrashlytics is never loaded.
        //
        // ALWAYS WRITE, NEVER OMIT. Crashlytics keys are sticky on the singleton, so a key this path
        // skips silently keeps whatever the previous report left there — indistinguishable from a value
        // written on purpose. This path deliberately does NOT go through App.throwFlow (that would put a
        // snackbar in front of the user for every breaker trip), so nothing else populates these keys for
        // a HealthMonitor report. `throwing_extension_id` was the worst of them: it named a DIFFERENT
        // exception entirely, and only 1 of the 7 breaker trip sites happened to have a throwFlow emit
        // immediately before it — so it was wrong far more often than right (the 2026-08-19 "deezer"
        // attribution was correct by coincidence, and a triage decision was made on it).
        //
        // extension_id / player_state / is_playing are LIVE @Volatile snapshots maintained by
        // PlayerService, so copying them here is correct, not merely fresher. throwing_extension_id comes
        // from the exception's OWN identity — never a cause-chain walk, which would find nothing: these
        // exceptions carry no cause.
        if (BuildConfig.HAS_FIREBASE) FirebaseCrashlytics.getInstance().apply {
            setCustomKey("health_report_type", exception.javaClass.simpleName)
            setCustomKey("throwing_extension_id", exception.extensionId ?: "none")
            setCustomKey("extension_id", app.crashExtensionId)
            setCustomKey("player_state", app.crashPlayerState)
            setCustomKey("is_playing", app.crashIsPlaying)
            recordException(exception)
        }
    }
}
