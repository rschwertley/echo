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

    class ConsecutiveSkipException(
        val skipCount: Int, val lastExtensionId: String, val lastCauses: String
    ) : HealthException(
        "skipCount=$skipCount lastExtensionId=$lastExtensionId lastCauses=$lastCauses", lastExtensionId
    )

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
