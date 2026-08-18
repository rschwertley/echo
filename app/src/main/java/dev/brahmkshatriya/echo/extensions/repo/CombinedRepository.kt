package dev.brahmkshatriya.echo.extensions.repo

import android.content.Context
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.models.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import java.io.File

class CombinedRepository(
    scope: CoroutineScope,
    context: Context,
    fileIgnoreFlow: Flow<File?>,
    extensionParser: ExtensionParser,
    vararg builtIns: Pair<Metadata, Lazy<ExtensionClient>>
) : ExtensionRepository {

    private val list = builtIns.map { Result.success(it) }
    private val appRepository = AppRepository(scope, context, extensionParser)
    private val fileRepository = FileRepository(context, extensionParser, fileIgnoreFlow)

    // The stateIn initial value MUST be null, not `list`. `null` is this flow's "not loaded yet"
    // sentinel — the combine below emits it deliberately while appRepository is still scanning
    // installed packages — and seeding the StateFlow with the non-null built-ins defeated it: every
    // consumer saw a non-null, NON-EMPTY list before the ImportType.App scan finished. That silently
    // broke two things for over a year:
    //  - ExtensionLoader.isLoaded (`flow.map { it != null }`) reported true immediately, so the
    //    cold-start update-check race fix built on it (73023a6f, 2026-06-12) never actually waited.
    //    It was born broken: this line already predated it (d7184731, 2025-06-28).
    //  - ExtensionUtils.getExtension's `first { it.isNotEmpty() }` latched onto the built-ins-only
    //    list, so resolving a third-party extension id during cold start (e.g. an `echo://music/...`
    //    deep link) threw a spurious ExtensionNotFoundException for an extension that WAS installed.
    // Do NOT "optimise" this back to `list`.
    override val flow = fileRepository.flow.combine(appRepository.flow) { file, app ->
        if (app == null) return@combine null
        list + file + app
    }.stateIn(scope, SharingStarted.Lazily, null)

    override suspend fun loadExtensions() = flow.first { it != null } ?: list
}