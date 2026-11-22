package com.tvbox.app.domain

val DEFAULT_AI_QUICK_PROMPTS = listOf(
    "悬疑电视剧",
    "最近高分电影",
    "动画电影",
    "韩国犯罪剧",
    "搞笑综艺",
    "适合小孩看",
)

const val MAX_AI_QUICK_PROMPTS = 12
const val MAX_AI_QUICK_PROMPT_LENGTH = 20

/** 手机提交的文本：一行一个，去空行、去重、截断；全空时恢复默认。 */
fun parseAiQuickPrompts(text: String): List<String> =
    normalizeAiQuickPrompts(text.lines())

fun normalizeAiQuickPrompts(prompts: List<String>): List<String> =
    prompts.asSequence()
        .map { it.trim().take(MAX_AI_QUICK_PROMPT_LENGTH) }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_AI_QUICK_PROMPTS)
        .toList()
        .ifEmpty { DEFAULT_AI_QUICK_PROMPTS }
