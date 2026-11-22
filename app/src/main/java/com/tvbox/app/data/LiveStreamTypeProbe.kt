package com.tvbox.app.data

import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 实际交给播放器的地址和格式；mimeType 为 null 时让播放器自己按后缀判断。 */
data class ResolvedLiveStream(val url: String, val mimeType: String?)

/**
 * 有些直播地址不带后缀（如 http://host:1234/608807420），302 跳转后才是 m3u8。
 * 播放器只看地址后缀猜格式，会把它当普通文件读而直接报错；这里先请求一次看实际内容，
 * 是 HLS 就把跳转后的最终地址和格式一起交给播放器（少走一次跳转）。
 * 咪咕这类源偶尔返回 602 / 空内容，所以失败会重试几次，且结果不缓存。
 */
object LiveStreamTypeProbe {
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 400L

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolve(url: String): ResolvedLiveStream {
        if (!needsProbe(url)) return ResolvedLiveStream(url, null)
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val result = withContext(Dispatchers.IO) { runCatching { probe(url) }.getOrNull() }
            if (result != null) return result
        }
        return ResolvedLiveStream(url, null)
    }

    /** 路径最后一段没有扩展名才探测，带 .m3u8/.flv/.ts 等后缀的播放器自己能判断。 */
    internal fun needsProbe(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        if (!path.startsWith("http://") && !path.startsWith("https://")) return false
        val lastSegment = path.substringAfter("://").substringAfter('/', "").substringAfterLast('/')
        // 播放器只看路径后缀，?id=xxx.m3u8 这种写在参数里的不算
        return !lastSegment.contains('.')
    }

    /** 拿到有效内容返回结果；HTTP 失败或空内容返回 null（调用方重试）。 */
    private fun probe(url: String): ResolvedLiveStream? {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(16)
            val head = source.buffer.snapshot().utf8().trimStart()
            if (head.isEmpty()) return null
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val isHls = head.startsWith("#EXTM3U") ||
                "mpegurl" in contentType ||
                response.request.url.encodedPath.lowercase().endsWith(".m3u8")
            return if (isHls) {
                ResolvedLiveStream(response.request.url.toString(), MimeTypes.APPLICATION_M3U8)
            } else {
                ResolvedLiveStream(url, null)
            }
        }
    }
}
