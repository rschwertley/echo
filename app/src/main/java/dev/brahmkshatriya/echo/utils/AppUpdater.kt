// ⚠️ READ BEFORE REMOVING THE SUPPRESSION BELOW (or the @Suppress("KotlinConstantConditions") on
// updateApp). BuildConfig.BUILD_TYPE is a compile-time constant, so in any single variant every arm of
// updateApp's `when (appType)` except that variant's own is provably dead, and every `appType == "..."`
// test is provably true or false. These two suppressions silence exactly the warnings that fact would
// raise — and that is the mechanism by which three separate defects lived in this file undetected until
// 2026-09-05, all of them in code no variant had ever executed:
//   1. NO "release" ARM AT ALL, so this fork's only distributed variant fell to `else -> return null` and
//      was never told about any update — silently, forever.
//   2. The download branch read `if (appType == "stable")`, a test that is CONSTANT FALSE in every
//      variant that can reach it except stable, which this fork never builds.
//   3. (2) then routed the new release arm into unzipApk, failing every update with "No APK file found
//      in the zip".
// Kept rather than removed because they predate all of the above and their original reason is not
// recorded anywhere I could find — an unexplained suppression is a reason for more caution, not less.
// But whoever removes them should expect real findings, not noise: the warnings they hide are about
// branches that cannot run, which is precisely the defect class this file keeps producing.
@file:Suppress("UNREACHABLE_CODE")

package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.os.Build
import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.utils.ContextUtils.appVersion
import dev.brahmkshatriya.echo.utils.ContextUtils.getTempFile
import dev.brahmkshatriya.echo.utils.Serializer.toData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object AppUpdater {

    private val client = OkHttpClient()

    // Package names of stores that own updates for anything they installed. If we were installed by
    // one of these, self-update MUST NOT run: the user updates through that store, and handing them
    // a sideloaded APK over a store install is wrong for them and, for Play, a policy problem.
    private val STORE_INSTALLERS = setOf(
        "com.android.vending",              // Google Play
        "com.google.android.feedback",      // legacy Play installer id, still present on old installs
        "com.amazon.venezia",               // Amazon Appstore
        "com.sec.android.app.samsungapps",  // Samsung Galaxy Store
        "com.huawei.appmarket",             // Huawei AppGallery
        "org.fdroid.fdroid",                // F-Droid
    )

    // True when this copy was installed by a store that manages its own updates.
    //
    // FAILS CLOSED: any exception returns true (= skip the self-update). "Never offer a store user
    // an APK" is a hard requirement, and the asymmetry is stark — a false skip costs a sideloader
    // one missed update, a false allow puts a sideloaded APK over a Play install.
    //
    // A NULL installer is the one case treated as "not a store", deliberately: it is the normal
    // answer for `adb install` and for manual installs where no installing package was recorded,
    // and it is also what remains if the installing package was itself later uninstalled. Both of
    // those are sideloads. (A device whose Play Store has been removed would self-update — correct:
    // there is no longer a store to update through.)
    //
    // API 30+ exposes both names. initiatingPackageName is verified by the system, while
    // installingPackageName can be reassigned by the installer via setInstallerPackageName, so a
    // match on EITHER counts. Below 30 only the legacy single value exists.
    private fun App.isStoreInstall(): Boolean = runCatching {
        val pm = context.packageManager
        val names = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val info = pm.getInstallSourceInfo(context.packageName)
            listOfNotNull(info.initiatingPackageName, info.installingPackageName)
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(pm.getInstallerPackageName(context.packageName))
        }
        // NO recorded installer at all -> UNKNOWN, and unknown resolves to "skip", same fail-closed
        // reasoning as the catch below. This previously returned false (allow), which silently turned
        // "the platform told us nothing" into "not a store" — the wrong default for an absolute
        // requirement. Known cost, accepted: `adb install` records no installer, so developer builds
        // no longer self-update. Browser and file-manager sideloads DO record one (the initiating app),
        // so GitHub distribution is unaffected.
        if (names.isEmpty()) return@runCatching true
        names.any { it in STORE_INSTALLERS }
    }.getOrDefault(true)

    @Suppress("KotlinConstantConditions")
    suspend fun updateApp(
        app: App,
        ensureInstallPermission: suspend () -> Boolean = { true }
    ): File? {
        // Install-source gate. This is the ONLY thing standing between a Play user and a sideloaded
        // APK, and it must run before any network work. It sits ALONGSIDE the build-type check
        // below, it does not replace it: they answer different questions. Build type answers "is
        // this a channel that publishes releases at all" (debug/release have no tag scheme or
        // assets); install source answers "did this copy come from a store". A stable build can be
        // distributed both ways, which is exactly why the build type cannot decide this.
        // Scoped to the APP update only — updateApp has no extension callers, so extension updates
        // (including for Play users, which is intended) are untouched.
        if (app.isStoreInstall()) return null
        // Gate PASSED — record it. Placed here, after the isStoreInstall() return and before the
        // build-type branch below, so the key means "the gate let us proceed" rather than "we checked".
        // That is the reading that makes the requirement verifiable: install_source = com.android.vending
        // together with app_update_attempted = true is a violation, and nothing else can set this key
        // (updateApp has no extension callers). It can be true while no network call follows — a debug
        // build returns null at the `else` branch — which is correct for this question. (Before the
        // "release" arm was added on 2026-09-05 this sentence also said "or plain release build"; release
        // now has its own arm and does reach the network.)
        CrashKeys.onAppUpdateGatePassed()
        val messageFlow = app.messageFlow
        // (!) LOCALE-RESOLVED, AND THAT IS A HAZARD, NOT A DETAIL. This getString decides WHICH REPO the
        // app fetches its own updates from, and values-bn/strings.xml carries its own copy of
        // app_github_repo. The two are identical today (rschwertley/gladix, checked 2026-09-05), so
        // nothing is wrong right now — but a device in that locale reads the override, not values/, and a
        // divergence would silently point self-update at a different repo for those users only.
        // `translatable="false"` does NOT prevent this: it is a tooling hint that keeps a string out of
        // translation exports, and has no effect on resource resolution once an override exists in the
        // tree. Same shape as the self-update string finding. If this ever needs to be guaranteed
        // locale-invariant, it belongs in BuildConfig, not in resources.
        val githubRepo = app.context.getString(R.string.app_github_repo)
        val appType = BuildConfig.BUILD_TYPE
        val version = appVersion()

        val url = runCatching {
            when (appType) {
                // THIS FORK'S SIDELOAD CHANNEL. `release` is the variant actually distributed outside
                // Play here (signed with the debug key so it upgrades in place over the historical debug
                // APKs — see app/build.gradle.kts). Before 2026-09-05 this `when` had no "release" arm, so
                // every sideloaded user fell to `else -> return null` and was NEVER told about a new
                // version: no update, no error, no snackbar, indistinguishable from "up to date".
                //
                // Same body as "stable" deliberately — both are "check this repo's GitHub releases" — but
                // kept as a separate arm rather than merged, because "stable" is UPSTREAM's channel and is
                // never built here (see the buildTypes note). Merging them would re-imply that stable is
                // one of ours.
                //
                // ⚠️ WHAT THE GITHUB RELEASE MUST LOOK LIKE, since getGithubUpdateUrl matches on it:
                //   TAG   must equal versionName.substringBefore('_'), i.e. "v3.1.NNNNN" zero-padded to 5
                //         digits (build.gradle.kts: version = "3.1." + gitCount.padStart(5,'0'),
                //         versionName = "v${'$'}version_${'$'}gitHash(${'$'}gitCount)"). A tag that differs in ANY
                //         way — no "v", unpadded count, a suffix — compares unequal and offers an update
                //         forever; a tag equal to the running version correctly offers nothing.
                //   ASSET must be a file ending ".apk". Assets are sorted so a name CONTAINING the device's
                //         Build.SUPPORTED_ABIS.first() (e.g. "arm64-v8a") wins; with a single universal
                //         APK the sort is a no-op and it is picked anyway.
                //   It reads /releases/LATEST, so the release must be published and not a draft or
                //         pre-release.
                "release" -> {
                    val currentVersion = version.substringBefore('_')
                    val updateUrl = "https://api.github.com/repos/$githubRepo/releases"
                    getGithubUpdateUrl(currentVersion, updateUrl, client) ?: return null
                }

                // UPSTREAM'S CHANNEL, NOT BUILT HERE — kept so a stable build would still work if one were
                // ever produced. See the buildTypes note in app/build.gradle.kts.
                "stable" -> {
                    val currentVersion = version.substringBefore('_')
                    val updateUrl = "https://api.github.com/repos/$githubRepo/releases"
                    getGithubUpdateUrl(currentVersion, updateUrl, client) ?: return null
                }

                "nightly" -> {
                    val hash = version.substringBefore("(").substringAfter('_')
                    val id = getGithubWorkflowId(hash, githubRepo, client) ?: return null
                    "https://nightly.link/$githubRepo/actions/runs/$id/artifact.zip"
                }

                // debug (local dev) and anything unrecognised. NOT "no channel publishes for you" — see
                // the "release" arm above; this is now genuinely only local builds.
                else -> return null
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            // REPORT, then keep the existing "return null" contract for the caller. Until this line
            // the app-update CHECK was the only network path in the app that could fail with no
            // signal anywhere: no snackbar, no Crashlytics non-fatal, and the caller silently falls
            // through to the extension branch. A user could sit on a months-stale build indefinitely
            // and it was invisible to them AND to us — so an absence of app-update failures in
            // Crashlytics was never evidence they weren't happening; this path structurally could
            // not report one. Emitting to throwFlow also yields a snackbar, which matches what the
            // extension path already does on the same failure (ExtensionsViewModel.getExtensionUpdate),
            // and SnackBarHandler collapses the duplicate when both fail for one reason (e.g. offline).
            app.throwFlow.emit(it)
            return null
        }
        // An update EXISTS and its asset url resolved. Everything above this line is "we looked";
        // everything below is "we are updating". See CrashKeys.onAppUpdateStage for how to read the
        // furthest-stage-reached semantics.
        CrashKeys.onAppUpdateStage("offered")

        // Ask for the install permission HERE — after we know an update exists, before we pull the
        // APK. Gating at the call site instead would prompt every user every 24h even when nothing
        // is available; gating after the download would spend ~40MB on a user who then declines.
        // The default no-op keeps this a pure addition for any caller that doesn't pass one.
        //
        // A DECLINE MUST NOT BE SILENT, and this is the only `return null` on the path that the user can
        // do something about. Everything else that returns null here is either correct (no update, a store
        // install) or already reported via throwFlow. This one meant: an update EXISTS, we resolved its
        // url, and we then stopped — with no message, no error, and no way to tell it apart from "you are
        // up to date". The next check is 24h away (ExtensionsViewModel saves last_update_check BEFORE
        // calling us), so a single mis-tap postponed the update for a day and said nothing.
        //
        // A MESSAGE, NOT A LOG LINE: "allow installing unknown apps" is a setting the user owns and can
        // fix in about ten seconds, which makes it worth their attention; a log line would only help us,
        // and we are not the ones who can act. It is deliberately NOT gated on the caller's `force` flag,
        // matching the "downloading update for X" emit below — the no-announcement rule covers checks that
        // found nothing, not work that actually started and then stopped.
        //
        // PLAIN Message, NO Action button, for two reasons: Message.Action's handler is a plain
        // `() -> Unit`, so it cannot call this suspend helper again to re-open the settings screen, and it
        // has no Activity to launch one from — updateApp holds `app` and a lambda, not a host. The text
        // therefore has to be self-contained about what to do.
        //
        // ⚠️ THIS MESSAGE MUST BE ABLE TO REPEAT — the user has to leave, grant the permission, come back
        // and check again, so one showing is not enough. It does repeat, and the reason is worth stating
        // because it is not obvious: emitting to app.messageFlow DIRECTLY bypasses SnackBarHandler.create,
        // which is the only thing that touches the deduped, activity-recreation-surviving `messages` queue.
        // MainActivity observes app.messageFlow itself, so a direct emit is shown and forgotten. Every
        // other update-path message works this way too (ExtensionsViewModel.message, "Downloading update
        // for X"), which is why those repeat on every check.
        //
        // ⚠️ SO DO NOT "TIDY" THIS INTO createSnack/SnackBarHandler.create. That would enrol it in the
        // dedupe (by text + action name) and it would then be suppressed for as long as an identical entry
        // sits in the queue — exactly wrong for a message whose whole purpose is to reappear on the next
        // check. Queue entries are dropped on dismissal, so the suppression is not permanent, but the
        // semantics are still wrong for this one.
        //
        // RESIDUAL RISK, ACCEPTED AND NOT FIXED HERE: app.messageFlow's only subscriber is a
        // lifecycle-gated observe() (ContextUtils.observe -> flowWithLifecycle, STARTED). Its 64-slot
        // DROP_OLDEST buffer stops an emit from suspending, but a buffer does not retain values for a
        // subscriber that is not there yet, so a message emitted while MainActivity is STOPPED is still
        // lost. That is a live concern for this particular message, because it is emitted immediately after
        // returning from the system settings screen, i.e. right at the moment our Activity is coming back
        // to STARTED. Making it survive that window needs a held-state mechanism (something the Activity
        // drains on start), not a bigger buffer and not a different emit shape.
        //
        // In the normal case the user has ALREADY seen the system "install unknown apps" screen by now —
        // ensureCanInstallPackages launches it and re-queries afterwards — so this reads as the follow-up
        // to something they just did rather than an unprompted demand. The exception is a device with no
        // handler for ACTION_MANAGE_UNKNOWN_APP_SOURCES: there the helper catches the failure and returns
        // false having shown NOTHING, and this message is the only thing the user ever sees. That case is
        // an argument for the message, not against it.
        if (!ensureInstallPermission()) {
            CrashKeys.onAppUpdateStage("permission_denied")
            messageFlow.emit(
                Message(
                    app.context.run {
                        getString(
                            R.string.install_permission_needed_for_update, getString(R.string.app_name)
                        )
                    }
                )
            )
            return null
        }

        messageFlow.emit(
            Message(
                app.context.run {
                    getString(R.string.downloading_update_for_x, getString(R.string.app_name))
                }
            )
        )
        return runCatching {
            val download = downloadUpdate(app.context, url, client).getOrThrow()
            // Transfer complete and length-checked. If this is the LAST stage a report shows, the failure
            // is between here and the installer — which for builds 1072-1078 was the unzip branch below
            // running on a plain release APK.
            CrashKeys.onAppUpdateStage("downloaded")
            // WHY THE UNZIP BRANCH EXISTS AT ALL: only "nightly" downloads a zip. Its url is
            // nightly.link/<repo>/actions/runs/<id>/artifact.zip, and nightly.link serves GitHub ACTIONS
            // ARTIFACTS, which the Actions API only ever exposes as a zip - the APK is an entry inside it.
            // Every other channel points at a GitHub RELEASE ASSET, which is the plain .apk that was
            // uploaded. So the zip is a property of one channel's artifact hosting, not of the app.
            //
            // (!) THE OLD FORM WAS `if (appType == "stable") download else unzipApk(download)` - AN
            // EXCLUSION LIST OF ONE. It asked "is this not stable" when the real question is "is this
            // nightly", so adding the "release" arm above on 2026-09-05 silently put this fork's ONLY
            // distributed variant on the unzip side. The symptom is not a crash and does not name the
            // cause: an APK is itself a zip, so ZipFile() opens it happily and unzipApk then fails looking
            // for a nested ".apk" entry, giving the user "No APK file found in the zip" on every single
            // update attempt, forever.
            //
            // In this form a future channel that serves a plain APK needs NO CHANGE HERE - it falls to
            // `download` by default, which is the safe side. Only a channel that genuinely ships a zip
            // has to be named.
            val apk = if (appType == "nightly") unzipApk(download) else download
            CrashKeys.onAppUpdateStage("ready")
            apk
        }.getOrElse {
            if (it is CancellationException) throw it
            // Same reasoning as the check above, and this is the likelier half to fail: the APK
            // transfer is a long-lived stream, and a reset during BODY transfer happens after
            // newCall().await() has returned, so OkHttp's retryOnConnectionFailure cannot recover
            // it. Note the user has already been told "downloading update" by the emit above, so
            // staying silent here left them watching a download that never resolved.
            app.throwFlow.emit(it)
            return null
        }
    }

    private val githubRegex = Regex("https://api\\.github\\.com/repos/([^/]*)/([^/]*)/")
    // Matches a github.com BROWSER url and captures user/repo from the first two path segments:
    // https://github.com/<user>/<repo> and any suffix (/, /releases, /releases/latest, /releases/tag/…),
    // with optional http/www. `[^/]+` stops at the next slash so trailing paths/slashes are ignored.
    private val githubBrowserRegex = Regex("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)")
    suspend fun getGithubUpdateUrl(
        currentVersion: String,
        updateUrl: String,
        client: OkHttpClient
    ) = run {
        // Every message below names the repo. This function has TWO callers — updateApp (the APP
        // update, repo = app_github_repo) and getUpdateFileUrl (the EXTENSION update, repo = that
        // extension's) — and their failures were previously worded identically, so a user-facing
        // report could not be attributed to either path. The repo is a plain string, so it survives
        // R8 obfuscation and shows up in the in-app trace view where class names do not.
        // Diagnostics only: no control flow or exception type changes.
        val (user, repo) = githubRegex.find(updateUrl)?.destructured
            ?: throw Exception("Invalid Github URL: $updateUrl")
        val url = "https://api.github.com/repos/$user/$repo/releases/latest"
        val request = Request.Builder().url(url).build()
        val res = runCatching {
            client.newCall(request).await().use {
                it.body.string().toData<GithubReleaseResponse>()
            }.getOrThrow()
        }.getOrElse {
            throw Exception("Failed to fetch latest release for $user/$repo", it)
        }
        // ⚠️ STRING INEQUALITY, AND IT MUST STAY THAT WAY — THIS FUNCTION IS SHARED BY THREE CALLERS WITH
        // THREE DIFFERENT NOTIONS OF "version". Verified 2026-09-05:
        //   updateApp (APP update)            -> currentVersion = versionName.substringBefore('_'),
        //                                        i.e. "v3.1.NNNNN", our 5-digit zero-padded gitCount.
        //   ExtensionsViewModel:282 (EXT)     -> the installed extension's own version string.
        //   AddViewModel:116 (ADD EXTENSION)  -> "" — a SENTINEL meaning "no current version, take
        //                                        whatever is latest". With "", any non-empty tag compares
        //                                        unequal, which is exactly the intent.
        //
        // ⚠️ THE TRAP: a "parse the number and require strictly greater" rule looks like an obvious
        // improvement here and CANNOT go in this function. Extension tags are authored by third parties in
        // arbitrary shapes ("1.2.3", "v0.9-beta", a date) — none of them parse as our scheme, so a numeric
        // rule would refuse every extension update. And it would turn AddViewModel's "" sentinel from
        // "take latest" into "never offer anything", breaking the add-extension flow outright. If a numeric
        // constraint is ever wanted for the APP path, it belongs behind an OPT-IN parameter defaulting to
        // "no constraint", never as a change to this comparison.
        //
        // Considered and deliberately NOT done (2026-09-05): `!=` also offers a LOWER tag, i.e. a
        // downgrade. Left alone because the OS refuses a lower versionCode at install time, so the live
        // cost is one spurious prompt and a failed install — the same cost as the mis-tag case below, and
        // not worth constraining a shared function for.
        //
        // NOTE this compares the TAG against the RUNNING BUILD, never against the asset. A release whose
        // tag disagrees with its own APK's versionName is offered, installs as a permitted same-code
        // reinstall, and is then offered again on every check — a loop that no client-side rule here can
        // detect, ending only when a later release whose tag matches its binary supersedes it.
        // ⚠️ NOTHING CHECKS THAT THEY AGREE — not here, not at build time, not at upload. The only thing
        // working against it is that the APK filename now carries its own build number and variant
        // (`base { archivesName }` in app/build.gradle.kts), so tagging a release is COPYING a number off
        // the filename rather than recalling it. Whether the tag and the filename actually match is still
        // an unchecked manual step.
        if (res.tagName != currentVersion) {
            res.assets.sortedByDescending {
                it.name.contains(Build.SUPPORTED_ABIS.first())
            }.firstOrNull {
                it.name.endsWith("apk")
            }?.browserDownloadUrl ?: throw Exception("No EApk assets found for $user/$repo")
        } else {
            null
        }
    }

    private suspend fun getGithubWorkflowId(
        hash: String,
        githubRepo: String,
        client: OkHttpClient
    ) = runCatching {
        val url =
            "https://api.github.com/repos/$githubRepo/actions/workflows/nightly.yml/runs?per_page=1&conclusion=success"
        val request = Request.Builder().url(url).build()
        client.newCall(request).await().body.string().toData<GithubRunsResponse>().getOrThrow()
            .workflowRuns.firstOrNull { it.sha.take(7) != hash }?.id
    }.getOrElse {
        throw Exception("Failed to fetch workflow ID", it)
    }

    @Serializable
    data class GithubReleaseResponse(
        @SerialName("tag_name")
        val tagName: String,
        @SerialName("created_at")
        val createdAt: String,
        val assets: List<Asset>
    ) {
        @Serializable
        data class Asset(
            val name: String,
            @SerialName("browser_download_url")
            val browserDownloadUrl: String
        )
    }

    @Serializable
    data class GithubRunsResponse(
        @SerialName("workflow_runs")
        val workflowRuns: List<Run>
    ) {
        @Serializable
        data class Run(
            val id: Long,
            @SerialName("head_sha")
            val sha: String,
        )
    }

    suspend fun downloadUpdate(
        context: Context,
        url: String,
        client: OkHttpClient
    ) = runIOCatching {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).await()
        val expected = response.body.contentLength()
        val file = context.getTempFile()
        response.body.byteStream()
            .use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        // Length check. Note OkHttp already throws on a short FIXED-LENGTH body, so this is not the
        // primary guard and will rarely fire — its value is that when it does, the failure is a
        // readable "incomplete download" instead of an opaque installer parse error, and the
        // truncated file is removed rather than left for the next cleanupTempApks pass. -1 means a
        // chunked/compressed response with no declared length: nothing to compare, accept as before.
        val actual = file.length()
        if (expected >= 0 && actual != expected) {
            file.delete()
            throw IOException("Incomplete download: got $actual of $expected bytes")
        }
        file
    }

    private fun unzipApk(file: File): File {
        val zipFile = ZipFile(file)
        val apkFile = File.createTempFile("temp", ".apk", file.parentFile!!)
        zipFile.use { zip ->
            val apkEntry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith(".apk")
            } ?: throw Exception("No APK file found in the zip")
            zip.getInputStream(apkEntry).use { input ->
                apkFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return apkFile
    }

    suspend fun getUpdateFileUrl(
        currentVersion: String,
        updateUrl: String,
        client: OkHttpClient
    ) = runIOCatching {
        if (updateUrl.isEmpty()) return@runIOCatching null
        // Accept the api.github.com/repos/ form directly; normalize a github.com BROWSER url
        // (github.com/<user>/<repo>[/releases…]) to the api form getGithubUpdateUrl expects.
        val apiUrl = when {
            updateUrl.startsWith("https://api.github.com/repos/") -> updateUrl
            else -> githubBrowserRegex.find(updateUrl)?.destructured?.let { (user, repo) ->
                "https://api.github.com/repos/$user/${repo.removeSuffix(".git")}/releases"
            }
        }
        // Non-empty but not a recognizable GitHub url (GitLab, self-hosted, direct-APK host, …):
        // return null quietly instead of throwing. getExtensionUpdate/AddViewModel treat null as
        // "nothing to download" (silent on auto-checks; a benign "no update available" only when
        // user-triggered), so an unsupported-host extension no longer emits a recurring "error
        // updating extension" via throwFlow.emit on every silent auto-check.
        apiUrl?.let { getGithubUpdateUrl(currentVersion, it, client) }
    }

    private suspend fun <T> runIOCatching(
        block: suspend () -> T
    ) = withContext(Dispatchers.IO) {
        runCatching { runCatching { block() }.getOrElse { throw UpdateException(it) } }
    }

    class UpdateException(
        override val cause: Throwable,
        // WHAT was being updated: an extension name, or null for the app itself. runIOCatching has no
        // identity to supply, so it leaves this null and the caller re-tags (see
        // ExtensionsViewModel.named). Without it every update failure rendered as a bare
        // "Error while updating", which made an extension failure indistinguishable from an app one
        // in a user report — the ambiguity that cost a full round of investigation on 2026-08-17.
        val name: String? = null
    ) : Exception(cause) {
        override val message: String
            get() = "Update failed${name?.let { " for $it" } ?: ""}: ${cause.message}"
    }
}