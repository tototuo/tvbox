package com.tvbox.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AiQuickPromptsTest {
    @Test
    fun parsesOnePromptPerLineAndDropsBlanksAndDuplicates() {
        assertEquals(
            listOf("科幻电影", "国产悬疑剧"),
            parseAiQuickPrompts("  科幻电影 \r\n\n国产悬疑剧\n科幻电影\n   "),
        )
    }

    @Test
    fun emptyInputRestoresDefaults() {
        assertEquals(DEFAULT_AI_QUICK_PROMPTS, parseAiQuickPrompts(" \n \n"))
    }

    @Test
    fun limitsCountAndLength() {
        val prompts = parseAiQuickPrompts((1..20).joinToString("\n") { "推荐词$it" + "很长".repeat(20) })
        assertEquals(MAX_AI_QUICK_PROMPTS, prompts.size)
        assertEquals(MAX_AI_QUICK_PROMPT_LENGTH, prompts.first().length)
    }
}
