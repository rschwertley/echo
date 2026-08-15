package dev.brahmkshatriya.echo.extensions

import android.app.Activity
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import dev.brahmkshatriya.echo.extensions.repo.ExtensionParser.Companion.PACKAGE_FLAGS
import dev.brahmkshatriya.echo.extensions.repo.FileRepository.Companion.getExtensionsFileDir
import dev.brahmkshatriya.echo.utils.ContextUtils.getTempFile
import dev.brahmkshatriya.echo.utils.PermsUtils.registerActivityResultLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object InstallationUtils {

    suspend fun installApp(activity: FragmentActivity, file: File) {
        val contentUri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.provider", file
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            data = contentUri
        }
        val result = activity.waitForResult(installIntent)
        if (result.resultCode == Activity.RESULT_OK) return

        // resultCode / EXTRA_RETURN_RESULT are only contractual for the deprecated
        // ACTION_INSTALL_PACKAGE. On ACTION_VIEW many installers return RESULT_CANCELED even after a
        // SUCCESSFUL install, so a non-OK code must not be treated as failure. Ask the PackageManager
        // what is actually installed instead of branching on a value we know we cannot trust.
        // (On an app SELF-update we never reach this line at all — installing over ourselves kills
        // the process — so everything below is effectively the extension path.)
        val apk = activity.packageManager.getPackageArchiveInfo(file.path, 0)
        val pkg = apk?.packageName
        val installed = pkg?.let {
            runCatching { activity.packageManager.getPackageInfo(it, 0) }.getOrNull()
        }
        if (apk != null && installed != null &&
            PackageInfoCompat.getLongVersionCode(installed) >=
            PackageInfoCompat.getLongVersionCode(apk)
        ) return

        // Backing out of the system dialog is not an error. Signal it exactly as uninstallApp below
        // already does, so a decline stops producing a snackbar and a Crashlytics non-fatal.
        if (result.resultCode == Activity.RESULT_CANCELED)
            throw CancellationException("Install cancelled by user")

        val status = result.data?.extras
            ?.getInt("android.intent.extra.INSTALL_RESULT", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        // The old text ("Please uninstall the existing extension first") was a decent hint for the
        // common signature-mismatch case but wrong for every other one, and wrong for the app. Key
        // it on the CONDITION rather than on the caller: the hint only applies when a copy is
        // already installed and did not advance, which is caller-agnostic and needs no flag.
        throw Exception(
            if (installed != null)
                "Install failed — you may need to uninstall the existing version first " +
                    "(resultCode=${result.resultCode}" + (status?.let { ", status=$it" } ?: "") + ")"
            else "Install failed (resultCode=${result.resultCode}" +
                (status?.let { ", status=$it" } ?: "") + ")"
        )
    }

    // True when this app is allowed to install APKs. Since API 26 the grant is per-source, and
    // nothing here requested it — so the user met the system's cold "not allowed to install unknown
    // apps" dialog with no explanation from us. Ask first instead. Below API 26 the setting is
    // global and there is nothing to request.
    // Used for the APP update only; extension installs keep their existing behaviour.
    suspend fun FragmentActivity.ensureCanInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()
        )
        // Not every device ships a handler for this screen; a missing one must degrade to "no app
        // update", never crash the update pass.
        runCatching { waitForResult(intent) }.getOrElse { return false }
        // The settings screen returns RESULT_CANCELED whether or not the toggle was flipped, so
        // re-query rather than reading its resultCode.
        return packageManager.canRequestPackageInstalls()
    }

    suspend fun installFile(
        context: Context, fileIgnoreFlow: MutableSharedFlow<File?>, id: String, tempFile: File
    ) {
        val dir = context.getExtensionsFileDir()
        val newFile = File(dir, "$id.apk")
        dir.setWritable(true)
        newFile.setWritable(true)
        if (newFile.exists() && !newFile.delete())
            Log.d("InstallUtils", "Failed to delete existing file: $newFile")
        tempFile.renameTo(newFile)
        newFile.setWritable(false)
        dir.setReadOnly()
        fileIgnoreFlow.emit(null)
    }

    suspend fun FragmentActivity.openFileSelector(
        fileType: String = "application/octet-stream"
    ): File {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = fileType
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val result = waitForResult(intent)
        val uri = result.data?.data ?: throw IllegalStateException("No file selected")
        return getTempFile(uri)
    }

    fun Context.getTempFile(uri: Uri): File {
        val stream = contentResolver.openInputStream(uri)!!
        val tempFile = getTempFile("dat")
        tempFile.outputStream().use { outputStream ->
            stream.copyTo(outputStream)
        }
        return tempFile
    }

    suspend fun uninstallApp(activity: FragmentActivity, path: String) {
        val packageName =
            activity.packageManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)?.packageName
                ?: throw IllegalStateException("Invalid APK path or package name not found")
        activity.packageManager.getPackageInfo(packageName, 0)
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:$packageName".toUri()
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        val result = activity.waitForResult(intent)
        when (result.resultCode) {
            Activity.RESULT_OK -> {} // uninstalled
            Activity.RESULT_CANCELED ->
                // User backed out of the system uninstall dialog (or it aborted with no detail) — NOT a
                // failure. Signal it the way the caller (ExtensionsViewModel) already handles cancellation:
                // a CancellationException is rethrown there without a "uninstalled" message and without
                // emitting to the error flow. That's what stops the "Failed to uninstall extension: null"
                // snackbar and the crash-reporter non-fatal on a plain cancel.
                throw CancellationException("Uninstall cancelled by user")

            else -> {
                // Genuine failure (e.g. RESULT_FIRST_USER). The legacy ACTION_DELETE result carries an int
                // status in EXTRA_INSTALL_RESULT (there is no PackageInstaller EXTRA_STATUS_MESSAGE on this
                // path); surface the resultCode and that status so a real failure is diagnosable instead of
                // the old bare "null".
                val status = result.data?.extras
                    ?.getInt("android.intent.extra.INSTALL_RESULT", Int.MIN_VALUE)
                    ?.takeIf { it != Int.MIN_VALUE }
                throw Exception(
                    "Failed to uninstall extension (resultCode=${result.resultCode}" +
                        (status?.let { ", status=$it" } ?: "") + ")"
                )
            }
        }
    }

    suspend fun uninstallFile(
        fileIgnoreFlow: MutableSharedFlow<File?>, path: String
    ) = withContext(Dispatchers.IO) {
        val file = File(path)
        fileIgnoreFlow.emit(file)
        file.parentFile!!.setWritable(true)
        file.setWritable(true)
        if (file.exists() && !file.delete())
            Log.d("InstallUtils", "Failed to delete file: $file")
        fileIgnoreFlow.emit(null)
    }

    private suspend fun FragmentActivity.waitForResult(
        intent: Intent
    ) = suspendCancellableCoroutine { cont ->
        val contract = ActivityResultContracts.StartActivityForResult()
        val launcher = registerActivityResultLauncher(contract) { cont.resume(it) }
        cont.invokeOnCancellation { launcher.unregister() }
        launcher.launch(intent)
    }
}