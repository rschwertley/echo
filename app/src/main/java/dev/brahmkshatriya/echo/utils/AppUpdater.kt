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
        val messageFlow = app.messageFlow
        val githubRepo = app.context.getString(R.string.app_github_repo)
        val appType = BuildConfig.BUILD_TYPE
        val version = appVersion()

        val url = runCatching {
            when (appType) {
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

        // Ask for the install permission HERE — after we know an update exists, before we pull the
        // APK. Gating at the call site instead would prompt every user every 24h even when nothing
        // is available; gating after the download would spend ~40MB on a user who then declines.
        // The default no-op keeps this a pure addition for any caller that doesn't pass one.
        if (!ensureInstallPermission()) return null

        messageFlow.emit(
            Message(
                app.context.run {
                    getString(R.string.downloading_update_for_x, getString(R.string.app_name))
                }
            )
        )
        return runCatching {
            val download = downloadUpdate(app.context, url, client).getOrThrow()
            if (appType == "stable") download else unzipApk(download)
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
        val (user, repo) = githubRegex.find(updateUrl)?.destructured
            ?: throw Exception("Invalid Github URL")
        val url = "https://api.github.com/repos/$user/$repo/releases/latest"
        val request = Request.Builder().url(url).build()
        val res = runCatching {
            client.newCall(request).await().use {
                it.body.string().toData<GithubReleaseResponse>()
            }.getOrThrow()
        }.getOrElse {
            throw Exception("Failed to fetch latest release", it)
        }
        if (res.tagName != currentVersion) {
            res.assets.sortedByDescending {
                it.name.contains(Build.SUPPORTED_ABIS.first())
            }.firstOrNull {
                it.name.endsWith("apk")
            }?.browserDownloadUrl ?: throw Exception("No EApk assets found")
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

    class UpdateException(override val cause: Throwable) : Exception(cause) {
        override val message: String
            get() = "Update failed: ${cause.message}"
    }
}