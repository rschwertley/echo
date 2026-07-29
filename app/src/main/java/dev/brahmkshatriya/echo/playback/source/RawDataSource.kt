package dev.brahmkshatriya.echo.playback.source

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import dev.brahmkshatriya.echo.common.models.Streamable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

@OptIn(UnstableApi::class)
class RawDataSource : BaseDataSource(true) {

    class Factory : DataSource.Factory {
        override fun createDataSource() = RawDataSource()
    }

    private var stream: InputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val streamable = dataSpec.customData as Streamable.Source.Raw
        Log.d("GladixPlayback", "RawDataSource.open: invoking InputProvider pos=${dataSpec.position} uri=${dataSpec.uri}")
        val (source, total) = try {
            runBlocking {
                streamable.streamProvider!!.provide(dataSpec.position, dataSpec.length)
            }
        } catch (e: IOException) {
            throw e                     // already the recoverable type — pass through unchanged
        } catch (e: CancellationException) {
            throw e                     // loader cancellation must propagate untouched (not an error)
        } catch (e: InterruptedException) {
            throw e                     // loader-thread interrupt must propagate untouched
        } catch (e: Exception) {
            // A failed extension stream-resolve throws AppException (wrapping the real UnknownHostException),
            // a NON-IOException. DataSource.open() is contracted to throw IOException, and Media3's Loader only
            // treats an IOException as a RECOVERABLE load error (→ onLoadError → onPlayerError → the existing
            // retry/error-skip). A non-IOException escapes uncaught and crashes (build-1013 fatal DNS crash).
            // Rewrap as IOException, preserving the real cause, so a Raw-stream network/DNS failure degrades
            // exactly like the HTTP DataSource path instead of crashing. (Error, e.g. OOM, still propagates.)
            throw IOException(e)
        }
        Log.d("GladixPlayback", "RawDataSource.open: stream ready total=$total pos=${dataSpec.position}")
        uri = dataSpec.uri
        stream = source
        return total
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return stream!!.read(buffer, offset, length)
    }

    override fun getUri() = uri

    override fun close() {
        stream?.close()
        stream = null
        uri = null
    }
}