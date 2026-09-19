package ru.dachafibonacci.podval.gena

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import ru.dachafibonacci.podval.data.AssistantDomain

/**
 * Canonical helper knowledge bundled in the APK.
 *
 * B19 keeps the full source assets, but retrieves a compact task-specific view
 * before calling Gena. This reduces local-model prompt processing time without
 * losing the exact trainer plan on the phone.
 */
class HelperKnowledgeStore(private val context: Context) {
    private val cache = ConcurrentHashMap<String, String>()
    private val trainerPlan by lazy { TrainerPlanKnowledge(asset("trainer_session_plan_v1.json")) }

    fun routerProtocol(): String = asset("router_protocol_v1.txt")

    fun promptBlock(assistant: String, eventText: String, createdAt: Long): String = when (assistant) {
        AssistantDomain.PSYCHOLOGIST -> buildString {
            appendLine("=== БАЗА ПСИХОЛОГА · компактный runtime B19 ===")
            appendLine(asset("psychologist_context_v1.txt"))
        }
        AssistantDomain.DIETITIAN -> buildString {
            appendLine("=== БАЗА ДИЕТОЛОГА · компактный runtime B19 ===")
            appendLine(asset("dietitian_context_v1.txt"))
        }
        AssistantDomain.TRAINER -> trainerPromptBlock(eventText, createdAt)
        else -> error("Неизвестный помощник: $assistant")
    }.trim()

    private fun trainerPromptBlock(eventText: String, createdAt: Long): String {
        val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        return buildString {
            appendLine("=== БАЗА ТРЕНЕРА · task-specific runtime B19 ===")
            appendLine(trainerCycleHint(createdAt))
            appendLine()
            appendLine(COMPACT_TRAINER_CORE)
            appendLine()
            appendLine("=== РЕЛЕВАНТНЫЙ СРЕЗ ТОЧНОГО TRAINER_SESSION_PLAN_v1 ===")
            appendLine(trainerPlan.slice(date, eventText))
            if (looksLikeTrainingCompletion(eventText)) {
                appendLine()
                appendLine("=== ПОСЛЕТРЕНИРОВОЧНЫЙ ЧЕК-ИН · нужен только сейчас ===")
                appendLine(asset("trainer_checkin_v1.txt"))
            }
        }.trim()
    }

    private fun asset(name: String): String = cache.getOrPut(name) {
        context.assets.open("assistant_knowledge/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun trainerCycleHint(createdAt: Long): String {
        val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val dayPlan = when (date.dayOfWeek) {
            DayOfWeek.MONDAY -> "скалолазание: техника + сложность"
            DayOfWeek.TUESDAY -> "домашний блок A: мобильность + корпус"
            DayOfWeek.WEDNESDAY -> "скалолазание: техника + выносливость"
            DayOfWeek.THURSDAY -> "домашний блок B: баланс + мобильность"
            DayOfWeek.FRIDAY -> "силовая / ОФП"
            DayOfWeek.SATURDAY -> "фингерборд по детальному плану и только при хорошем восстановлении"
            DayOfWeek.SUNDAY -> "отдых / восстановление"
        }
        return buildString {
            append("КАЛЕНДАРНЫЙ ОРИЕНТИР: ").append(date).append(". ")
            append(TrainerPlanCalendar.phaseHint(date)).append(" План дня: ").append(dayPlan).append(". ")
            append("Фактически выполненные/пропущенные тренировки и ответы пользователя всегда важнее календаря.")
        }
    }

    private fun looksLikeTrainingCompletion(text: String): Boolean {
        val t = text.lowercase()
        val completed = listOf("трениров", "лазал", "скалодром", "пролез", "трасс", "подход", "памп", "сорвал", "срыв")
        val past = listOf("сегодня", "был", "была", "сделал", "закончил", "после", "получилось", "не получилось")
        return completed.any(t::contains) && past.any(t::contains)
    }

    companion object {
        const val KNOWLEDGE_VERSION = "B19-2026-09-19-fast-prompts-v1"

        private val COMPACT_TRAINER_CORE = """
            РОЛЬ И ПРИОРИТЕТЫ:
            - Ты постоянный тренер по скалолазанию, а не генератор новой программы.
            - Источники по приоритету: последнее решение пользователя → фактически выполненные/пропущенные тренировки и чек-ины → точный план ниже → общий контекст.
            - Цель цикла: от рабочего 6B к уверенному 6C; ключевой навык — статика, баланс, ноги, центр тяжести и экономичность. Долгосрочно обсуждался 7B.
            - Существенно менять утверждённый план без подтверждения пользователя нельзя.
            - Календарь не доказывает выполнение: пропущенную тренировку не считать выполненной автоматически.
            - Учитывай сон, восстановление, алкоголь, питание и боль только когда они реально влияют на нагрузку.
            - Безопасность: резкая ѱоль или нарастание боли >3/10 — прекратить/снизить/заменить провоцирующее движение; силовые не до отказа; фингерборд только после разогрева и без боли.
            - На вопрос «что сегодня/что дальше/какая следующая тренировка» дай конкретный следующий блок из точного среза ниже, а не пересказ всей программы.
        """.trimIndent()
    }
}
