package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLineMemoryTest {
    private val channel = LiveChannel(
        number = 1,
        groupName = "央视",
        name = "CCTV1",
        lines = (1..53).map { LiveChannelLine(name = "线路$it", url = "http://line/$it") },
    )

    @Test
    fun startsFromRememberedLine() {
        assertEquals(8, preferredLiveLineIndex(channel, "http://line/9"))
    }

    @Test
    fun fallsBackToFirstLineWhenNothingRememberedOrLineRemoved() {
        assertEquals(0, preferredLiveLineIndex(channel, null))
        assertEquals(0, preferredLiveLineIndex(channel, "http://gone"))
    }

    @Test
    fun failureWrapsAroundAndStopsAtStart() {
        assertEquals(9, nextLiveLineAfterFailure(currentIndex = 8, startIndex = 8, lineCount = 53))
        assertEquals(0, nextLiveLineAfterFailure(currentIndex = 52, startIndex = 8, lineCount = 53))
        assertEquals(7, nextLiveLineAfterFailure(currentIndex = 6, startIndex = 8, lineCount = 53))
        assertNull(nextLiveLineAfterFailure(currentIndex = 7, startIndex = 8, lineCount = 53))
    }

    @Test
    fun failureFromFirstLineKeepsOldBehaviour() {
        assertEquals(1, nextLiveLineAfterFailure(currentIndex = 0, startIndex = 0, lineCount = 3))
        assertNull(nextLiveLineAfterFailure(currentIndex = 2, startIndex = 0, lineCount = 3))
        assertNull(nextLiveLineAfterFailure(currentIndex = 0, startIndex = 0, lineCount = 1))
    }

    @Test
    fun keyIgnoresGroupAndCase() {
        assertEquals(channel.lineMemoryKey(), channel.copy(groupName = "其他", name = " cctv1 ").lineMemoryKey())
    }
}
