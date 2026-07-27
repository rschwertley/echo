package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.util.Log
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import java.io.File

object CacheUtils {

    // durable = true stores under filesDir instead of cacheDir, so Android's storage-pressure cache
    // wipe can't evict the entries. Used only for the Android Auto "auto/" Triple cache, whose loss
    // silently drops queue items; all other caches stay in cacheDir (disposable). The 50 MB LRU cap is
    // enforced identically in either location on every save — which matters more for filesDir, since the
    // OS won't reclaim it for us.
    fun cacheDir(context: Context, folderName: String, durable: Boolean = false) =
        File(if (durable) context.filesDir else context.cacheDir, "context/$folderName")
            .apply { mkdirs() }

    const val CACHE_FOLDER_SIZE = 50 * 1024 * 1024 //50MB

    inline fun <reified T> Context.saveToCache(
        id: String, data: T?, folderName: String = T::class.java.simpleName, durable: Boolean = false
    ) = runCatching {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName, durable)
        val file = File(cacheDir, fileName)

        var size = cacheDir.walk().sumOf { it.length().toInt() }
        while (size > CACHE_FOLDER_SIZE) {
            val files = cacheDir.listFiles()
            files?.sortBy { it.lastModified() }
            files?.firstOrNull()?.delete()
            size = cacheDir.walk().sumOf { it.length().toInt() }
        }
        file.writeText(data.toJson())
    }.onFailure {
        // Write failures (disk full, IO error) were silently swallowed — every caller discards the Result.
        // Surface folder + key (never the contents) so a lost save is visible in logcat.
        Log.e("CacheUtils", "saveToCache failed: folder=$folderName key=$id", it)
    }

    inline fun <reified T> Context.getFromCache(
        id: String, folderName: String = T::class.java.simpleName, durable: Boolean = false,
        maxBytes: Long = Long.MAX_VALUE,
    ): T? {
        val fileName = id.hashCode().toString()
        val cacheDir = cacheDir(this, folderName, durable)
        val file = File(cacheDir, fileName)
        if (!file.exists()) return null
        // Size-gate BEFORE readText, parity with ResumptionUtils.getFromQueue: an oversized file — the
        // legacy cacheDir queue read backs the same fat-Track class as the build-1008 OOM — is skipped
        // UNREAD instead of pulled into memory. NOT deleted, matching this util's no-delete policy (a valid
        // file can fail a transient decode; the ancient cacheDir queue is a vanishing population, so a cheap
        // re-stat is fine). Default Long.MAX_VALUE = no gate, so every existing caller is byte-identical.
        if (file.length() > maxBytes) return null
        return runCatching {
            file.readText().toData<T>().getOrThrow()
        }.getOrElse {
            // Present but unreadable → SURFACE it, don't swallow (a swallowed decode failure is how the
            // queue bug hid for five fixes). NOT deleted: "corrupt" can be a transient software decode
            // failure (R8 / polymorphic drift) on a VALID file, and getFromCache backs the legacy queue
            // read (ResumptionUtils "queue") — deleting would turn that into permanent queue loss. Log
            // folder + key only, never the contents.
            Log.e("CacheUtils", "getFromCache unreadable: folder=$folderName key=$id", it)
            null
        }
    }
}
