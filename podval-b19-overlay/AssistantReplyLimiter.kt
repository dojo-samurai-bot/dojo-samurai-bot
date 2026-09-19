package ru.dachafibonacci.podval.gena

import ru.dachafibonacci.podval.data.AssistantEventEntity

/** Final UI safety net: generation is instructed to be short, and oversized replies
 * are bounded before they are persisted into the helper chat. */
object AssistantReplyLimiter {
    fun limit(event: AssistantEventEntity, raw: String): String {
        val clean = raw
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .trim()
        val max = GenaPrompt.responseBudget(event) + 250
        if (clean.length <= max) return clean

        val prefix = clean.take(max)
        val cutAt = maxOf(
            prefix.lastIndexOf("\n\n"),
            prefix.lastIndexOf(". "),
            prefix.lastIndexOf("! "),
            prefix.lastIndexOf("? "),
        ).takeIf { it >= max / 2 } ?: max
        return prefix.take(cutAt).trimEnd() + "…"
    }
}
