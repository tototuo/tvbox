package com.tvbox.app.data

import android.content.Context

/** 每个直播频道上次播放成功的线路地址。读写都很轻，直接走 SharedPreferences。 */
interface LiveLineMemoryRepository {
    fun rememberedLineUrl(channelKey: String): String?
    fun rememberLineUrl(channelKey: String, url: String)
}

class SharedLiveLineMemoryRepository(context: Context) : LiveLineMemoryRepository {
    private val prefs = context.applicationContext.getSharedPreferences("live_line_memory", Context.MODE_PRIVATE)

    override fun rememberedLineUrl(channelKey: String): String? = prefs.getString(channelKey, null)

    override fun rememberLineUrl(channelKey: String, url: String) {
        if (prefs.getString(channelKey, null) == url) return
        prefs.edit().putString(channelKey, url).apply()
    }
}

class InMemoryLiveLineMemoryRepository : LiveLineMemoryRepository {
    private val urls = mutableMapOf<String, String>()

    override fun rememberedLineUrl(channelKey: String): String? = urls[channelKey]

    override fun rememberLineUrl(channelKey: String, url: String) {
        urls[channelKey] = url
    }
}
