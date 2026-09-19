#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# Version only. Keep B18 OkHttp transport and its timeout values unchanged.
p = ROOT / 'app/build.gradle.kts'
s = p.read_text(encoding='utf-8')
for old, new in [
    ('versionCode = 18', 'versionCode = 19'),
    ('versionName = "0.9.1-gena-transport-b18"', 'versionName = "0.9.2-fast-prompts-b19"'),
    ('PODVAL-MYSLEI-GENA-TRANSPORT-B18', 'PODVAL-MYSLEI-FAST-PROMPTS-B19'),
]:
    if old not in s:
        raise SystemExit(f'missing build marker: {old}')
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Smaller semantic history window: keeps recency + older relevance but avoids huge prompts.
p = ROOT / 'app/src/main/java/ru/dachafibonacci/podval/gena/ContextSelector.kt'
s = p.read_text(encoding='utf-8')
for old, new in [('MAX_SELECTED = 20', 'MAX_SELECTED = 10'), ('ALWAYS_RECENT = 8', 'ALWAYS_RECENT = 4')]:
    if old not in s: raise SystemExit(f'missing ContextSelector marker: {old}')
    s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')

# Direct helper chat must not sit behind old background Director jobs.
p = ROOT / 'app/src/main/java/ru/dachafibonacci/podval/data/AssistantEventDao.kt'
s = p.read_text(encoding='utf-8')
old = '''        """SELECT * FROM assistant_events
           WHERE status IN ('pending_analysis','retryable_error')
           ORDER BY created_at ASC, id ASC LIMIT :limit"""'''
new = '''        """SELECT * FROM assistant_events
           WHERE status IN ('pending_analysis','retryable_error')
           ORDER BY CASE
               WHEN idempotency_key LIKE 'chat:%' THEN 0
               WHEN assistant != 'director' THEN 1
               ELSE 2
           END, created_at ASC, id ASC LIMIT :limit"""'''
if old not in s: raise SystemExit('missing AssistantEventDao nextPending marker')
p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Do not cancel an active Gena request merely because another event was enqueued.
p = ROOT / 'app/src/main/java/ru/dachafibonacci/podval/gena/GenaScheduler.kt'
s = p.read_text(encoding='utf-8')
old = 'enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)'
new = 'enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)'
if old not in s: raise SystemExit('missing GenaScheduler REPLACE marker')
p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Persist only bounded final assistant text. Gena itself is also instructed to stop early.
p = ROOT / 'app/src/main/java/ru/dachafibonacci/podval/gena/GenaAnalysisWorker.kt'
s = p.read_text(encoding='utf-8')
old = '''                        val reply = gateway.chat(endpoint, prompt).text
                        dao.markProcessed(event.id, reply, System.currentTimeMillis())'''
new = '''                        val rawReply = gateway.chat(endpoint, prompt).text
                        val reply = AssistantReplyLimiter.limit(event, rawReply)
                        dao.markProcessed(event.id, reply, System.currentTimeMillis())'''
if old not in s: raise SystemExit('missing worker helper reply marker')
p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Bound Director output both in the prompt and after parsing so it cannot create huge helper packets.
p = ROOT / 'app/src/main/java/ru/dachafibonacci/podval/gena/Director.kt'
s = p.read_text(encoding='utf-8')
old = '''        Верни ТОЛЬКО валидный JSON без markdown:
        {"packages":[
          {"target_helper":"PSYCHOLOG|TRAINER|DIETOLOG",
           "summary":"краткая релевантная выжимка",
           "relevant_fragments":["точная фраза при необходимости"],
           "topics":["тема"],
           "relevance":"почему это важно этому помощнику"}
        ]}
        Если запись никому не нужна: {"packages":[]}.'''
new = '''        Верни ТОЛЬКО валидный JSON без markdown и без пояснений до/после JSON.
        ЖЁСТКИЙ БЮДЖЕТ: весь ответ максимум 1400 символов. Каждый пакет должен быть компактным:
        summary <= 240 символов; relevance <= 140; topics максимум 4; relevant_fragments максимум 2.
        Не пересказывай RAW целиком и не дублируй одну мысль разными словами.
        {"packages":[
          {"target_helper":"PSYCHOLOG|TRAINER|DIETOLOG",
           "summary":"краткая релевантная выжимка",
           "relevant_fragments":["точная фраза при необходимости"],
           "topics":["тема"],
           "relevance":"почему это важно этому помощнику"}
        ]}
        Если запись никому не нужна: {"packages":[]}.'''
if old not in s: raise SystemExit('missing Director output contract marker')
s = s.replace(old, new, 1)
old = '''            val summary = item.optString("summary").trim()
            if (summary.isBlank()) continue
            val relevance = item.optString("relevance").trim()
            val topics = item.optJSONArray("topics").strings()
            val fragments = item.optJSONArray("relevant_fragments").strings()'''
new = '''            val summary = item.optString("summary").trim().take(280)
            if (summary.isBlank()) continue
            val relevance = item.optString("relevance").trim().take(160)
            val topics = item.optJSONArray("topics").strings().take(4).map { it.take(80) }
            val fragments = item.optJSONArray("relevant_fragments").strings().take(2).map { it.take(180) }'''
if old not in s: raise SystemExit('missing Director parse marker')
p.write_text(s.replace(old, new, 1), encoding='utf-8')

# Existing test should assert the new smaller cap.
p = ROOT / 'app/src/test/java/ru/dachafibonacci/podval/gena/ContextSelectorTest.kt'
s = p.read_text(encoding='utf-8')
s = s.replace('neverReturnsMoreThanThirtyRows', 'neverReturnsMoreThanTenRows')
s = s.replace('selected.size <= 30', 'selected.size <= 10')
p.write_text(s, encoding='utf-8')
