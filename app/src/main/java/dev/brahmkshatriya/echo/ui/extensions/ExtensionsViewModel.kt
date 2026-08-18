package dev.brahmkshatriya.echo.ui.extensions

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImportType
import dev.brahmkshatriya.echo.common.models.Message
import dev.brahmkshatriya.echo.di.App
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.brahmkshatriya.echo.extensions.ExtensionUtils.getOrThrow
import dev.brahmkshatriya.echo.extensions.InstallationUtils.ensureCanInstallPackages
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installApp
import dev.brahmkshatriya.echo.extensions.InstallationUtils.installFile
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallApp
import dev.brahmkshatriya.echo.extensions.InstallationUtils.uninstallFile
import dev.brahmkshatriya.echo.extensions.db.models.ExtensionEntity
import dev.brahmkshatriya.echo.extensions.exceptions.AppException.Companion.toAppException
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInstallerBottomSheet.Companion.createLinksDialog
import dev.brahmkshatriya.echo.ui.extensions.list.ExtensionListViewModel
import dev.brahmkshatriya.echo.utils.AppUpdater
import dev.brahmkshatriya.echo.utils.AppUpdater.downloadUpdate
import dev.brahmkshatriya.echo.utils.AppUpdater.getUpdateFileUrl
import dev.brahmkshatriya.echo.utils.AppUpdater.updateApp
import dev.brahmkshatriya.echo.utils.CacheUtils.getFromCache
import dev.brahmkshatriya.echo.utils.CacheUtils.saveToCache
import dev.brahmkshatriya.echo.utils.ContextUtils.cleanupTempApks
import dev.brahmkshatriya.echo.utils.ContextUtils.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.io.File

class ExtensionsViewModel(
    val extensionLoader: ExtensionLoader,
    val app: App
) : ExtensionListViewModel<MusicExtension>() {
    override val extensionsFlow = extensionLoader.music
    override val currentSelectionFlow = extensionLoader.current
    override fun onExtensionSelected(extension: MusicExtension) {
        extensionLoader.setupMusicExtension(extension, true)
    }

    private val extensionDao = extensionLoader.db.extensionDao()
    fun setExtensionEnabled(extensionType: ExtensionType, id: String, checked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            extensionDao.setExtension(ExtensionEntity(id, extensionType, checked))
        }
    }

    fun changeExtension(id: String) {
        viewModelScope.launch {
            runCatching {
                val ext = extensionLoader.music.getExtensionOrThrow(id)
                extensionLoader.setupMusicExtension(ext, true)
            }.getOrElse {
                if (it is CancellationException) throw it
                app.throwFlow.emit(it)
            }
        }
    }

    val lastSelectedManageExt = MutableStateFlow(0)
    val manageExtListFlow = extensionLoader.all.combine(lastSelectedManageExt) { _, last ->
        extensionLoader.getFlow(ExtensionType.entries[last]).value
    }

    fun moveExtensionItem(toPos: Int, fromPos: Int) {
        val type = ExtensionType.entries[lastSelectedManageExt.value]
        val flow = extensionLoader.priorityMap[type]!!
        val list = extensionLoader.getFlow(type).value.map { it.id }.toMutableList()
        list.add(toPos, list.removeAt(fromPos))
        flow.value = list
    }

    // 2 HOURS is the intended value. a5c0059f (2026-06-12) deliberately set it to 2h; b00018c3
    // (2026-06-14) put it back to 24h inside a commit about cover spacing and dimens — a stray
    // one-line revert, not a decision, and it went unnoticed for two months. Restored 2026-08-17.
    // This constant has been clobbered by an unrelated change once already: if you change it, do it
    // deliberately and record why here.
    private val updateTime = 1000 * 60 * 60 * 2 // Check every 2hrs
    private fun shouldCheckForExtensionUpdates(): Boolean {
        val check = app.settings.getBoolean("check_for_updates", true)
        if (!check) return false
        val lastUpdateCheck = app.context.getFromCache<Long>("last_update_check") ?: 0
        val elapsed = System.currentTimeMillis() - lastUpdateCheck
        return elapsed > updateTime
    }

    private suspend fun message(msg: String) {
        app.messageFlow.emit(Message(msg))
    }

    fun update(activity: FragmentActivity, force: Boolean) = viewModelScope.launch {
        if (!force) extensionLoader.isLoaded.first { it }
        if (!force && !shouldCheckForExtensionUpdates()) return@launch
        app.context.saveToCache("last_update_check", System.currentTimeMillis())
        activity.cleanupTempApks()
        message(app.context.getString(R.string.checking_for_extension_updates))
        // The install-permission prompt is threaded in as a lambda rather than checked here, so it
        // only ever fires once an update actually exists (updateApp calls it after resolving the
        // URL, before downloading). Declining returns null, which falls through to the extension
        // branch below exactly as "no app update" already does.
        val appApk = updateApp(app) { activity.ensureCanInstallPackages() }
        runCatching {
            if (appApk != null) {
                // 0L, not 0: saveToCache picks its folder from T::class.java.simpleName, so an Int
                // literal wrote to the "int" folder while shouldCheckForExtensionUpdates reads
                // getFromCache<Long> out of "long". This reset has therefore never taken effect.
                app.context.saveToCache("last_update_check", 0L)
                awaitInstallation(appApk).getOrThrow()
            } else {
                var anyUpdateFound = false
                var anyFailed = false
                extensionLoader.all.value.forEach {
                    when (updateExt(it)) {
                        ExtUpdate.Updated -> anyUpdateFound = true
                        ExtUpdate.Failed -> anyFailed = true
                        ExtUpdate.UpToDate -> Unit
                    }
                }
                // Only claim "up to date" when we actually found out. A failed check or download
                // used to land here too, so a transient GitHub error reassured the user that
                // everything was current. On failure we stay silent rather than adding a second
                // message — the failure already produced its own snackbar via throwFlow.
                if (anyFailed) app.context.saveToCache("last_update_check", 0L)
                else if (!anyUpdateFound)
                    message(app.context.getString(R.string.all_extensions_up_to_date))
            }
        }.getOrElse { if (it is CancellationException) throw it; app.throwFlow.emit(it) }
    }

    data class PromptResult(
        val file: File,
        val accepted: Boolean,
        val type: ImportType,
        val id: String,
        val supportedLinks: List<String>
    )

    val installPromptFlow = MutableSharedFlow<File>()
    private val promptResultFlow = MutableSharedFlow<PromptResult>()
    val installFileFlow = MutableSharedFlow<File>()
    val installedFlow = MutableSharedFlow<Pair<File, Result<Unit>>>()
    val linksDialogFlow = MutableSharedFlow<Pair<File, List<String>>>()

    private suspend fun install(id: String, type: ImportType, file: File): Result<Unit> {
        return if (type == ImportType.App) awaitInstallation(file)
        else runCatching { installFile(app.context, extensionLoader.fileIgnoreFlow, id, file) }
    }

    private suspend fun awaitInstallation(file: File): Result<Unit> {
        installFileFlow.emit(file)
        return installedFlow.first { it.first == file }.second
    }

    fun promptDismissed(
        file: File, install: Boolean, type: ImportType, id: String, supportedLinks: List<String>
    ) = viewModelScope.launch {
        promptResultFlow.emit(PromptResult(file, install, type, id, supportedLinks))
    }

    // Tri-state. A plain Boolean conflated "no update available" with "we never found out", which
    // is what let update() report "all extensions up to date" straight after a failed check or a
    // failed download. Failed also covers a failed INSTALL, which previously returned `true` — it
    // suppressed the up-to-date message correctly but for the wrong reason, and reported nothing.
    private enum class ExtUpdate { Updated, UpToDate, Failed }

    private suspend fun updateExt(ext: Extension<*>, show: Boolean = false): ExtUpdate {
        val file = getExtensionUpdate(ext, show).getOrElse { return ExtUpdate.Failed }
            ?: return ExtUpdate.UpToDate
        val type = ext.metadata.importType
        if (type == ImportType.File) {
            installPromptFlow.emit(file)
            val result = promptResultFlow.first { it.file == file }
            if (!result.accepted) return ExtUpdate.Updated
        }
        install(ext.id, type, file).onFailure {
            if (it is CancellationException) throw it
            app.throwFlow.emit(it)
            return ExtUpdate.Failed
        }
        message(app.context.getString(R.string.extension_updated_successfully, ext.name))
        return ExtUpdate.Updated
    }

    fun update(extension: Extension<*>) = viewModelScope.launch { updateExt(extension, true) }

    fun installWithPrompt(files: List<File>) = viewModelScope.launch {
        files.forEach { file ->
            installPromptFlow.emit(file)
            val result = promptResultFlow.first { it.file == file }
            if (!result.accepted) return@forEach
            install(result.id, result.type, result.file).onFailure {
                if (it is CancellationException) throw it
                app.throwFlow.emit(it)
                return@forEach
            }
            message(app.context.getString(R.string.extension_installed_successfully))
            if (result.type == ImportType.App)
                linksDialogFlow.emit(file to result.supportedLinks)
        }
    }

    fun uninstall(activity: FragmentActivity, extension: Extension<*>) = viewModelScope.launch {
        val fileResult = runCatching {
            uninstallFile(extensionLoader.fileIgnoreFlow, extension.metadata.path)
        }.exceptionOrNull()
        val appResult = runCatching {
            uninstallApp(activity, extension.metadata.path)
        }.exceptionOrNull()
        val result = if (extension.metadata.importType == ImportType.App) appResult else fileResult
        if (result == null) message(app.context.getString(R.string.extension_uninstalled_successfully))
        else if (result is CancellationException) throw result
        else app.throwFlow.emit(result)
    }

    companion object {
        fun FragmentActivity.configureExtensionsUpdater() {
            val viewModel by viewModel<ExtensionsViewModel>()
            collect(viewModel.installPromptFlow) {
                ExtensionInstallerBottomSheet.newInstance(it).show(supportFragmentManager, null)
            }
            collect(viewModel.linksDialogFlow) {
                createLinksDialog(it.first, it.second)
            }

            viewModel.update(this, false)
            var currentFile: File? = null
            collect(viewModel.installFileFlow) {
                currentFile = it
                viewModel.installedFlow.emit(it to runCatching { installApp(this, it) })
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    val file = currentFile ?: return
                    viewModel.run {
                        viewModelScope.launch {
                            installedFlow.emit(
                                file to Result.failure(CancellationException())
                            )
                        }
                    }
                }
            })
        }
    }

    private val client = OkHttpClient()
    // Result<File?> rather than File?, so the caller can tell the two null cases apart:
    // success(null) = nothing to update, failure = we never found out. Both already emit to
    // throwFlow; only the return value was lossy.
    private suspend fun getExtensionUpdate(
        extension: Extension<*>,
        show: Boolean = false
    ): Result<File?> {
        val currentVersion = extension.version
        val updateUrl = extension.metadata.updateUrl ?: return Result.success(null)
        val url = runCatching {
            getUpdateFileUrl(currentVersion, updateUrl, client).getOrThrow()
        }.getOrElse {
            if (it is CancellationException) throw it
            val e = it.named(extension.name)
            app.throwFlow.emit(e)
            return Result.failure(e)
        }
        if (url == null) {
            if (show) message(
                app.context.getString(R.string.no_update_available_for_x, extension.name)
            )
            return Result.success(null)
        }
        message(app.context.getString(R.string.downloading_update_for_x, extension.name))
        val file = runCatching {
            downloadUpdate(app.context, url, client).getOrThrow()
        }.getOrElse {
            if (it is CancellationException) throw it
            val e = it.named(extension.name)
            app.throwFlow.emit(e)
            return Result.failure(e)
        }
        return Result.success(file)
    }

    // getUpdateFileUrl/downloadUpdate wrap failures as UpdateException, which carries no identity, so
    // an extension update error rendered as a bare "Error while updating" — indistinguishable from an
    // app-update failure in a user report. Re-tag with the extension name. The cause chain is carried
    // over unchanged and anything that isn't an UpdateException passes straight through, so the
    // Result contract and control flow are identical to before.
    private fun Throwable.named(name: String): Throwable =
        (this as? AppUpdater.UpdateException)?.let { AppUpdater.UpdateException(it.cause, name) } ?: this

}