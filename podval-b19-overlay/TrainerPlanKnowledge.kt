package ru.dachafibonacci.podval.gena

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, deterministic retrieval layer over the exact canonical trainer plan.
 *
 * The full 8-week JSON stays bundled in the APK, but B19 no longer sends whole
 * week files to the local model for every question. The phone selects only the
 * exact session(s) or exercise blocks that are relevant to the current request.
 */
class TrainerPlanKnowledge(rawJson: String) {
    private data class Block(
        val exercise: String,
        val dose: String,
        val rest: String,
        val instruction: String,
        val substitute: String?,
        val stopCondition: String?,
    )

    private data class Session(
        val week: Int,
        val date: LocalDate,
        val day: String,
        val code: String,
        val title: String,
        val durationMin: Int,
        val blocks: List<Block>,
        val checkin: String,
    )

    private val sessions: List<Session> = parse(rawJson)

    fun slice(date: LocalDate, query: String): String {
        val normalized = query.lowercase()
        val explicitWeek = TrainerPlanCalendar.requestedWeek(query)
        if (explicitWeek != null) {
            val weekSessions = sessions.filter { it.week == explicitWeek }
            return buildString {
                appendLine("ТОЧНЫЙ ПЛАН НЕДЕЛИ $explicitWeek · компактный индекс")
                weekSessions.forEach { appendLine(it.summaryLine()) }
                append("Если пользователь просит детали конкретной сессии, отвечай только по соответствующей строке и известному контексту; не пересказывай всю неделю без запроса.")
            }.trim()
        }

        val matchingBlocks = findMatchingBlocks(normalized)
        if (matchingBlocks.isNotEmpty() && !looksLikeNextSession(normalized)) {
            return buildString {
                appendLine("ТОЧНЫЕ ФРАГМЕНТЫ ПЛАНА ПО ЗАПРОСУ")
                matchingBlocks.take(3).forEach { (session, block) ->
                    appendLine("${session.date} · ${session.title}")
                    appendLine(block.render())
                }
            }.trim()
        }

        if (looksLikeWeekOverview(normalized)) {
            val week = TrainerPlanCalendar.weekOn(date)
                ?: if (date.isBefore(TrainerPlanCalendar.start)) 1 else 8
            return buildString {
                appendLine("БЛИЖАЙШАЯ НЕДЕЛЯ ПЛАНА · $week")
                sessions.filter { it.week == week }.forEach { appendLine(it.summaryLine()) }
            }.trim()
        }

        // For "what is next / today / tomorrow" we deliberately send only the
        // nearest two exact sessions. The helper can use real RAW/check-in history
        // to decide whether the first one was actually completed or skipped.
        val candidates = nearestSessions(date, 2)
        return buildString {
            appendLine("БЛИЖАЙШИЕ ТОЧНЫЕ СЕССИИ ИЗ УТВЕРЖДЁННОГО ПЛАНА")
            candidates.forEachIndexed { index, session ->
                if (index > 0) appendLine()
                appendLine(session.renderFull())
            }
            append("Фактическая история выполнения имеет приоритет: если первая сессия уже выполнена/пропущена по RAW или чек-ину, выбери следующую корректно и не выдумывай факт выполнения.")
        }.trim()
    }

    private fun nearestSessions(date: LocalDate, count: Int): List<Session> {
        val afterOrOn = sessions.filter { !it.date.isBefore(date) }
        if (afterOrOn.isNotEmpty()) return afterOrOn.take(count)
        return sessions.takeLast(count)
    }

    private fun findMatchingBlocks(query: String): List<Pair<Session, Block>> {
        val tokens = TOKEN.findAll(query)
            .map { it.value.lowercase() }
            .filter { it.length >= 4 && it !in STOP_WORDS }
            .take(12)
            .toSet()
        if (tokens.isEmpty()) return emptyList()

        return sessions.flatMap { session -> session.blocks.map { session to it } }
            .map { pair ->
                val hay = (pair.first.title + " " + pair.second.exercise + " " + pair.second.instruction).lowercase()
                pair to tokens.count(hay::contains)
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun looksLikeNextSession(q: String): Boolean = listOf(
        "следующ", "что дальше", "какая тренировка", "что сегодня", "тренировка сегодня",
        "что завтра", "тренировка завтра", "что делать сегодня", "что делать завтра",
    ).any(q::contains)

    private fun looksLikeWeekOverview(q: String): Boolean = listOf(
        "план на неделю", "план недели", "неделя целиком", "вся неделя", "расписание недели",
    ).any(q::contains)

    private fun Session.summaryLine(): String =
        "$date · $day · $title · ${durationMin} мин · блоки: ${blocks.joinToString(", ") { it.exercise }}"

    private fun Session.renderFull(): String = buildString {
        appendLine("$date · $day · $title · ${durationMin} мин · $code")
        blocks.forEachIndexed { index, block ->
            append(index + 1).append(". ").appendLine(block.exercise)
            append("   Дозировка: ").appendLine(block.dose)
            if (block.rest.isNotBlank() && block.rest != "—") append("   Отдых: ").appendLine(block.rest)
            append("   Как: ").appendLine(block.instruction)
            block.substitute?.takeIf(String::isNotBlank)?.let { append("   Замена: ").appendLine(it) }
            block.stopCondition?.takeIf(String::isNotBlank)?.let { append("   Стоп: ").appendLine(it) }
        }
        append("Чек-ин: ").append(checkin)
    }.trim()

    private fun Block.render(): String = buildString {
        appendLine("Упражнение: $exercise")
        appendLine("Дозировка: $dose")
        if (rest.isNotBlank() && rest != "—") appendLine("Отдых: $rest")
        appendLine("Как выполнять: $instruction")
        substitute?.takeIf(String::isNotBlank)?.let { appendLine("Замена: $it") }
        stopCondition?.takeIf(String::isNotBlank)?.let { appendLine("Стоп: $it") }
    }.trim()

    private fun parse(raw: String): List<Session> {
        val root = JSONObject(raw)
        val result = mutableListOf<Session>()
        val weeks = root.getJSONArray("weeks")
        for (wi in 0 until weeks.length()) {
            val week = weeks.getJSONObject(wi)
            val weekNo = week.getInt("week")
            val items = week.getJSONArray("sessions")
            for (si in 0 until items.length()) {
                val item = items.getJSONObject(si)
                result += Session(
                    week = weekNo,
                    date = LocalDate.parse(item.getString("date")),
                    day = item.optString("day"),
                    code = item.optString("code"),
                    title = item.optString("title"),
                    durationMin = item.optInt("duration_min"),
                    blocks = item.getJSONArray("blocks").toBlocks(),
                    checkin = item.optString("checkin"),
                )
            }
        }
        return result.sortedBy { it.date }
    }

    private fun JSONArray.toBlocks(): List<Block> = (0 until length()).map { index ->
        val item = getJSONObject(index)
        Block(
            exercise = item.optString("exercise"),
            dose = item.optString("dose"),
            rest = item.optString("rest"),
            instruction = item.optString("instruction"),
            substitute = item.optNullable("substitute"),
            stopCondition = item.optNullable("stop_condition"),
        )
    }

    private fun JSONObject.optNullable(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private companion object {
        val TOKEN = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf(
            "какая", "какой", "когда", "сегодня", "завтра", "тренировка", "тренировке",
            "следующая", "следующей", "покажи", "расскажи", "делать", "будет", "нужно",
        )
    }
}
