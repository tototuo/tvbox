package com.tvbox.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamTypeProbeTest {
    @Test
    fun probesExtensionlessUrls() {
        assertTrue(LiveStreamTypeProbe.needsProbe("http://175.24.134.198:1234/608807420"))
        assertTrue(LiveStreamTypeProbe.needsProbe("https://tv.example.cn/608807420?token=1"))
        // 后缀写在参数里，播放器看不到
        assertTrue(LiveStreamTypeProbe.needsProbe("http://host:20247/migu?id=608807420.m3u8"))
    }

    @Test
    fun skipsUrlsWithKnownExtensionOrM3u8Hint() {
        assertFalse(LiveStreamTypeProbe.needsProbe("http://1.2.3.4/live/cctv1hd.m3u8"))
        assertFalse(LiveStreamTypeProbe.needsProbe("http://host/live/a.flv"))
        assertFalse(LiveStreamTypeProbe.needsProbe("rtmp://host/live/abc"))
    }
}
