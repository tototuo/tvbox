package com.tvbox.app.domain

/** 记忆线路用的频道标识：只按频道名，避免同一频道出现在多个分组时各记各的。 */
fun LiveChannel.lineMemoryKey(): String = name.trim().lowercase()

/** 上次播放成功的线路地址在当前线路列表里的位置；找不到（源更新后被删了）就从第一条开始。 */
fun preferredLiveLineIndex(channel: LiveChannel, rememberedUrl: String?): Int {
    if (rememberedUrl.isNullOrBlank()) return 0
    return channel.lines.indexOfFirst { it.url == rememberedUrl }.coerceAtLeast(0)
}

/**
 * 线路失败后的下一条：从起始线路往后找，到末尾后绕回开头，
 * 回到起始线路说明整轮都试过了，返回 null 不再自动切换。
 */
fun nextLiveLineAfterFailure(currentIndex: Int, startIndex: Int, lineCount: Int): Int? {
    if (lineCount <= 1) return null
    val next = (currentIndex + 1) % lineCount
    return if (next == startIndex.coerceIn(0, lineCount - 1)) null else next
}
