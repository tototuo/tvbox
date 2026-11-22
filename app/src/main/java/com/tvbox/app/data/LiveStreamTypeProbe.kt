package com.tvbox.app.data

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 有些直播地址不带后缀（如 http://host:1234/608807420），302 跳转后才是 m3u8。
 * 播放器只看地址后缀猜格式，会把它当普通文件读而直接报错；这里先请求一次看实际内容，
 * 是 HLS 就明确告诉播放器。
 */
object LiveStreamTypeProbe {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, String>()
    private const val UNKNOWN = ""

    /** 返回需要显式指定的 MIME（目前只识别 HLS），不需要或判断不了时返回 null。 */
    suspend fun mimeTypeFor(url: String): String? {
        if (!needsProbe(url)) return null
        cache[url]?.let { return it.ifEmpty { null } }
        val mime = withContext(Dispatchers.IO) { runCatching { probe(url) }.getOrNull() }
        cache[url] = mime ?: UNKNOWN
        return mime
    }

    /** 路径最后一段没有扩展名才探测，带 .m3u8/.flv/.ts 等后缀的播放器自己能判断。 */
    internal fun needsProbe(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        if (!path.startsWith("http://") && !path.startsWith("https://")) return false
        val lastSegment = path.substringAfter("://").substringAfter('/', "").substringAfterLast('/')
        // 播放器只看路径后缀，?id=xxx.m3u8 这种写在参数里的不算
        return !lastSegment.contains('.')
    }

    private fun probe(url: String): String? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val finalPath = response.request.url.encodedPath.lowercase()
            val head = response.body?.source()?.let { source ->
                source.request(16)
                source.buffer.snapshot().utf8().trimStart()
            }.orEmpty()
            return if (
                "mpegurl" in contentType || finalPath.endsWith(".m3u8") || head.startsWith("#EXTM3U")
            ) {
                MimeTypes.APPLICATION_M3U8
            } else {
                null
            }
        }
    }
}
