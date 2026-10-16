// Sends http(s) reads through the stream cache and everything else (content://, file://)
// straight to the device, so local files are never copied into the cache.
package dev.yuwixx.resonance.data.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@OptIn(UnstableApi::class)
class StreamCachingDataSource private constructor(
    private val cached: DataSource,
    private val direct: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val source = if (scheme == "http" || scheme == "https") cached else direct
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try { active?.close() } finally { active = null }
    }

    class Factory(
        private val cached: DataSource.Factory,
        private val direct: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            StreamCachingDataSource(cached.createDataSource(), direct.createDataSource())
    }
}
