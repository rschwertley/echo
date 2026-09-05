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
import kotlinx.coroutines.flow.onSubscription
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

    // 24 HOURS IS INTENTIONAL — it matches Google Play's own daily auto-update check cadence.
    // b00018c3 (2026-06-14) setting this back to 24h was a DELIBERATE decision, not a stray revert.
    // ⚠️ The 2026-06-13 session note describing 24h as "unintentional scope creep" is WRONG. Do not
    // "restore" 2h on the strength of that note. If this value ever changes, it should be for a
    // reason recorded right here.
    private val updateTime = 1000 * 60 * 60 * 24 // Check every 24hrs
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
        // Automatic (force = false) is an UNPROMPTED background check and must stay silent unless
        // something actually happens — Play doesn't announce that it looked, and F-Droid removed even
        // its index-progress notification. `force` is the correct discriminator: it is false only at
        // configureExtensionsUpdater's startup call, and true at all three user-initiated entry
        // points (ManageExtensionsFragment, SettingsBottomSheet, SettingsOtherFragment). Progress
        // messages further down ("downloading update for X") are deliberately NOT gated — those fire
        // only when work is genuinely under way, which is worth telling the user about either way.
        if (force) message(app.context.getString(R.string.checking_for_extension_updates))
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
                // Only claim "up to date" when we actually found out AND the user asked. Two
                // separate gates:
                //  - anyFailed: a failed check or download used to land here too, so a transient
                //    GitHub error reassured the user that everything was current. On failure we stay
                //    silent rather than adding a second message — the failure already produced its
                //    own snackbar via throwFlow.
                //  - force: an unprompted background check that finds nothing says NOTHING. A manual
                //    check still confirms the result, because the user asked and deserves an answer.
                if (anyFailed) app.context.saveToCache("last_update_check", 0L)
                else if (!anyUpdateFound && force)
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

    // SUBSCRIBE-THEN-EMIT, VIA onSubscription. NOT COSMETIC, AND DO NOT REORDER BACK.
    //
    // This used to be `installFileFlow.emit(file)` followed by `installedFlow.first { ... }` — emit first,
    // subscribe second. Both flows are replay-0 MutableSharedFlows, and a replay-0 emission with no
    // subscriber is DISCARDED, not deferred. So any path where the collector finishes installing before
    // this coroutine gets as far as subscribing loses the result permanently, and `first { }` then waits
    // forever: no message, no failure, and the caller has already written last_update_check, so nothing
    // retries for 24h. A silent strand, on the app-update path that has never once executed.
    //
    // The window is normally closed by luck rather than by design: configureExtensionsUpdater's collector
    // calls installApp, which suspends almost immediately at waitForResult (launching the installer), and
    // that suspension hands the main thread back so this coroutine can subscribe. The luck runs out when
    // installApp fails WITHOUT ever suspending — FileProvider.getUriForFile throws IllegalArgumentException
    // synchronously for a path the provider does not cover — because runCatching then emits the failure
    // with no suspension in between.
    //
    // onSubscription runs its block AFTER this collector is registered and BEFORE any value is collected,
    // which is the exact guarantee needed: the install cannot start until someone is listening for how it
    // ends. Same replay-0 mechanism as the Aug/Sep queueFlow defect, opposite direction — that one lost an
    // emission because the SUBSCRIBER was gone, this one because the subscriber had not arrived yet.
    //
    // (!) THIS DOES NOT COVER the other half: if the ACTIVITY is destroyed (a config change) while an
    // install is in flight, installFileFlow's emit reaches no collector at all and is dropped the same way.
    // That one is deliberately left open — buffering does not fix it (a buffer holds values for existing
    // slow subscribers; it does not retain them for a future one), and replay does, but replay changes what
    // a LATE subscriber sees at subscribe time, which is what caused the cold-start hang. See the report.
    private suspend fun awaitInstallation(file: File): Result<Unit> {
        return installedFlow
            .onSubscription { installFileFlow.emit(file) }
            .first { it.first == file }.second
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