package ru.dachafibonacci.podval.gena

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainerPlanKnowledgeTest {
    @Test fun nextSessionSliceIsSmallAndExact() {
        val source = TEST_PLAN
        val slice = TrainerPlanKnowledge(source).slice(LocalDate.of(2026, 9, 19), "Какая у меня следующая тренировка?")
        assertTrue(slice.contains("2026-09-21"))
        assertTrue(slice.contains("Проектный блок 6B"))
        assertTrue(slice.length < 6000)
    }

    @Test fun explicitWeekReturnsCompactIndexRatherThanWholeVerbosePlan() {
        val slice = TrainerPlanKnowledge(TEST_PLAN).slice(LocalDate.of(2026, 9, 22), "Покажи неделю 1")
        assertTrue(slice.contains("ТОЧНЫЙ ПЛАН НЕДЕЛИ 1"))
        assertTrue(slice.contains("2026-09-21"))
        assertFalse(slice.contains("После каждой попытки определить"))
    }

    companion object {
        private const val TEST_PLAN = """{
          "weeks":[{"week":1,"sessions":[
            {"date":"2026-09-21","day":"Понедельник","code":"W1_CLIMB_LIMIT","title":"Скалолазание: техника + сложность","duration_min":115,
             "blocks":[{"exercise":"Проектный блок 6B","dose":"4 качественные попытки","rest":"4–6 мин","instruction":"После каждой попытки определить 1 причину срыва.","substitute":null,"stop_condition":null}],
             "checkin":"чек-ин"},
            {"date":"2026-09-22","day":"Вторник","code":"W1_MOBILITY_A","title":"Домашний блок A","duration_min":20,
             "blocks":[{"exercise":"Dead bug","dose":"2×8","rest":"30 с","instruction":"Медленно.","substitute":null,"stop_condition":null}],
             "checkin":"короткий чек-ин"}
          ]}]}
        """
    }
}
