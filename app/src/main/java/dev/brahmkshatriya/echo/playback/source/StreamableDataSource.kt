package dev.brahmkshatriya.echo.playback.source

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.playback.source.StreamableResolver.Companion.copy
import java.util.concurrent.atomic.AtomicInteger

@UnstableApi
class StreamableDataSource(
    private val defaultDataSourceFactory: Lazy<DefaultDataSource.Factory>,
    private val defaultHttpDataSourceFactory: Lazy<DefaultHttpDataSource.Factory>,
    private val rawDataSourceFactory: Lazy<RawDataSource.Factory>,
) : BaseDataSource(true) {

    class Factory(
        context: Context,
    ) : DataSource.Factory {
        private val defaultDataSourceFactory = lazy {
            DefaultDataSource.Factory(context, defaultHttpDataSourceFactory.value)
        }
        private val defaultHttpDataSourceFactory = lazy {
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        }
        private val rawDataSourceFactory = lazy { RawDataSource.Factory() }
        override fun createDataSource() = StreamableDataSource(
            defaultDataSourceFactory, defaultHttpDataSourceFactory, rawDataSourceFactory
        )
    }

    // PROBE (2026-08-29). Counts every entry into open() below, process wide.
    // WHY A COUNTER AND NOT A LOG: ProgressiveMediaPeriod.prepare() reaches startLoading() with no
    // LoadControl, allocator or state gate in between (ProgressiveMediaPeriod:286-304 -> :1079-1102), so a
    // load starting is a faithful proxy for "prepare() was invoked on the period". Nine periods were created
    // and never opened in the 1059 captures; logcat showed it and Crashlytics could not, because the main
    // buffer rolls in minutes. PlayerEventListener diffs this against a per-item snapshot and puts the delta
    // in the consecutive-skip report, so the answer survives the buffer.
    // Process wide and static ON PURPOSE: only the deltas are ever read, there is exactly one player process,
    // and a static needs no constructor change on the Factory or on StreamableMediaSource.Factory. It
    // survives service recreation, which is correct - the ExoPlayer instance does too.
    // REMOVE WITH THE PROBE. This is diagnostic scaffolding, not a feature.
    companion object {
        val openCount = AtomicInteger(0)
    }

    private var source: DataSource? = null

    override fun getUri() = source?.uri

    override fun read(buffer: ByteArray, offset: Int, length: Int) =
        source?.read(buffer, offset, length) ?: throw Exception("Source not opened")

    override fun close() {
        source?.close()
        source = null
    }

    override fun open(dataSpec: DataSpec): Long {
        openCount.incrementAndGet()
        Log.d("GladixPlayback", "StreamableDataSource.open: ${dataSpec.uri}")
        val result = dataSpec.customData as? Result<*>
        val (factory, spec) = when (result) {
            null -> defaultDataSourceFactory to dataSpec
            else -> when (val streamable = result.getOrThrow() as Streamable.Source) {
                is Streamable.Source.Raw -> rawDataSourceFactory to
                        dataSpec.copy(uri = streamable.uri, customData = streamable)

                is Streamable.Source.Http -> {
                    val spec = streamable.request.run {
                        defaultHttpDataSourceFactory.value.setDefaultRequestProperties(headers)
                        dataSpec.copy(uri = url.toUri(), httpRequestHeaders = headers)
                    }
                    defaultDataSourceFactory to spec
                }
            }
        }
        val source = factory.value.createDataSource()
        this.source = source
        return source.open(spec)
    }

    companion object {
        val Streamable.Source.uri
            get() = when (this) {
                is Streamable.Source.Http -> request.url.toUri()
                is Streamable.Source.Raw -> "raw://${id.hashCode()}".toUri()
            }
    }
}
