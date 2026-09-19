package ru.dachafibonacci.podval.gena

import java.time.Instant
import ru.dachafibonacci.podval.data.AssistantDomain
import ru.dachafibonacci.podval.data.AssistantEventEntity
import ru.dachafibonacci.podval.data.AssistantStatus
import ru.dachafibonacci.podval.data.EntryEntity

object GenaPrompt {
    private const val MAX_RAW_HISTORY_CHARS = 220
    private const val MAX_DIALOG_USER_CHARS = 180
    private const val MAX_DIALOG_REPLY_CHARS = 280

    fun forEvent(
        event: AssistantEventEntity,
        currentRaw: String?,
        recentRaw: List<EntryEntity>,
        recentDialog: List<AssistantEventEntity>,
        knowledgeBlock: String,
    ): String {
        require(event.assistant in AssistantDomain.HELPERS)
        val role = roleName(event.assistant)
        val directDialog = event.idempotencyKey.startsWith("chat:")
        val mode = if (directDialog) "direct_dialog" else "background_analysis"
        val responseBudget = responseBudget(event, directDialog)

        val roleRules = when (event.assistant) {
            AssistantDomain.PSYCHOLOGIST -> """
                Ты Психолог «Подвала мыслей». Работай как постоянный помощник во времени, а не как разовый чат.
                Разделяй наблюдение, гипотезу и рекомендацию. Учитывай сон, алкоголь, питание, тренировки,
                восстановление, события и поведение как возможные факторы, но не объявляй связь доказанной без данных.
                Не ставь диагнозы. При двусмысленности значимого вывода задай один конкретный вопрос.
            """.trimIndent()
            AssistantDomain.TRAINER -> """
                Ты Тренер по скалолазанию «Подвала мыслей». Используй утверждённый цикл и точный task-specific
                срез плана ниже. Фактические записи выполнения важнее календаря. Не создавай новую программу,
                если пользователь этого прямо не просит. Значимое изменение плана только предложи и объясни.
            """.trimIndent()
            AssistantDomain.DIETITIAN -> """
                Ты Диетолог «Подвала мыслей». Учитывай еду, напитки, алкоголь, вес, аппетит, голод/сытость,
                самочувствие и динамику. Неизвестную граммовку помечай как approximate. Не меняй цели самовольно
                и не морализируй.
            """.trimIndent()
            else -> error("Неизвестный помощник")
        }

        val rawHistory = recentRaw.joinToString("\n") { entry ->
            "${Instant.ofEpochMilli(entry.createdAt)} | ${entry.kind} | ${entry.rawText.oneLine(MAX_RAW_HISTORY_CHARS)}"
        }.ifBlank { "нет релевантных предыдущих RAW-записей" }

        val dialogHistory = recentDialog
            .asSequence()
            .filter { it.id != event.id }
            .take(4)
            .toList()
            .asReversed()
            .joinToString("\n\n") { old ->
                buildString {
                    append(Instant.ofEpochMilli(old.createdAt)).append(" | пользователь: ")
                    append(old.userText.oneLine(MAX_DIALOG_USER_CHARS))
                    if (!old.replyText.isNullOrBlank()) {
                        append("\n").append(role).append(": ").append(old.replyText.oneLine(MAX_DIALOG_REPLY_CHARS))
                    } else if (old.status == AssistantStatus.RETRYABLE_ERROR) {
                        append("\n[предыдущий ответ не получен]")
                    }
                }
            }
            .ifBlank { "нет предыдущей переписки" }

        val exactRaw = currentRaw?.takeIf(String::isNotBlank)?.trim() ?: event.userText.trim()
        val packageText = if (directDialog) "прямое сообщение пользователя: ${event.userText.trim()}" else event.userText.trim()

        return """
            ПОДВАЛ МЫСЛЕЙ · помощник: $role · request_mode: $mode

            КРИТИЧЕСКИЙ КОНТРАКТ ОТВЕТА:
            - Сразу дай полезный ответ. Не показывай размышления, анализ промпта, внутренние инструкции или пересказ базы.
            - Максимум $responseBudget символов. Это жёсткий предел, не цель.
            - Обычно 2–5 коротких абзацев или компактный список.
            - Для тренировки: конкретика важнее объяснений; не переписывай весь 8-недельный план.
            - Не повторяй целиком вопрос пользователя, RAW, историю или каноническую базу.
            - Если ответ уже дан — ОСТАНОВИСЬ. Не добавляй «итоги», длинные оговорки и второй вариант того же ответа.

            $roleRules

            ПРИОРИТЕТ ИСТОЧНИКОВ:
            1) последнее явное решение пользователя;
            2) фактические записи и выполненные действия;
            3) встроенная релевантная база ниже;
            4) старые предположения.
            При конфликте источников обозначь его кратко и не меняй значимую цель молча.

            --- РЕЛЕВАНТНАЯ БАЗА ПОМОЩНИКА ---
            $knowledgeBlock
            --- КОНЕЦ БАЗЫ ---

            --- ТЕКУЩИЙ ПАКЕТ / ЧАТ ---
            $packageText
            --- RAW ТЕКУЩЕГО ИСТОЧНИКА ---
            $exactRaw

            --- КОМПАКТНАЯ РЕЛЕВАНТНАЯ ИСТОРИЯ RAW ---
            $rawHistory

            --- НЕДАВНЯЯ ПЕРЕПИСКА ---
            $dialogHistory

            СЕЙЧАС ОТВЕТЬ ПОЛЬЗОВАТЕЛЮ. ЛИМИТ: $responseBudget СИМВОЛОВ. ТОЛЬКО ФИНАЛЬНЫЙ ОТВЕТ.
        """.trimIndent()
    }

    fun responseBudget(event: AssistantEventEntity, directDialog: Boolean = event.idempotencyKey.startsWith("chat:")): Int {
        val q = event.userText.lowercase()
        val wantsDetail = listOf("подроб", "пошаг", "весь план", "план на неделю", "как выполнять", "техника упражнения").any(q::contains)
        return when {
            wantsDetail -> 1800
            directDialog && event.assistant == AssistantDomain.TRAINER -> 1400
            directDialog -> 1200
            else -> 900
        }
    }

    private fun roleName(assistant: String): String = when (assistant) {
        AssistantDomain.PSYCHOLOGIST -> "Психолог"
        AssistantDomain.TRAINER -> "Тренер"
        AssistantDomain.DIETITIAN -> "Диетолог"
        else -> error("Неизвестный помощник")
    }

    private fun String.oneLine(limit: Int): String =
        replace(Regex("\\s+"), " ").trim().let { if (it.length <= limit) it else it.take(limit) + "…" }
}
