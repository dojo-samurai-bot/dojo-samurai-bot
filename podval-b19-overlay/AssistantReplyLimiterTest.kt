package ru.dachafibonacci.podval.gena

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dachafibonacci.podval.data.AssistantDomain
import ru.dachafibonacci.podval.data.AssistantEventEntity
import ru.dachafibonacci.podval.data.AssistantStatus

class AssistantReplyLimiterTest {
    @Test fun removesThinkAndCapsHugeReply() {
        val event = AssistantEventEntity(
            id="1", sourceEntryId="1", assistant=AssistantDomain.TRAINER,
            userText="Какая следующая тренировка?", status=AssistantStatus.PROCESSING,
            createdAt=1, updatedAt=1, idempotencyKey="chat:trainer:1"
        )
        val result = AssistantReplyLimiter.limit(event, "<think>secret</think>" + "Ответ. ".repeat(500))
        assertFalse(result.contains("secret"))
        assertTrue(result.length <= 1651)
    }
}
