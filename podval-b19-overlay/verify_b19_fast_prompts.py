#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def read(rel): return (ROOT/rel).read_text(encoding='utf-8')
def req(v,msg):
    if not v: raise AssertionError(msg)

build=read('app/build.gradle.kts')
req('versionCode = 19' in build, 'B19 versionCode')
req('versionName = "0.9.2-fast-prompts-b19"' in build, 'B19 versionName')
req('applicationId = "ru.dachafibonacci.podb13"' in build, 'package changed')
req('okhttp:4.12.0' in build, 'B18 transport lost')

gateway=read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaGateway.kt')
req('.readTimeout(180, TimeUnit.SECONDS)' in gateway, 'read timeout changed')
req('.callTimeout(195, TimeUnit.SECONDS)' in gateway, 'call timeout changed')
req('Protocol.HTTP_1_1' in gateway and 'Connection", "close' in gateway, 'B18 transport invariants lost')

prompt=read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaPrompt.kt')
req('КРИТИЧЕСКИЙ КОНТРАКТ ОТВЕТА' in prompt, 'response contract missing')
req('ТОЛЬКО ФИНАЛЬНЫЙ ОТВЕТ' in prompt, 'final-only instruction missing')
req('take(4)' in prompt, 'dialog history not bounded')

selector=read('app/src/main/java/ru/dachafibonacci/podval/gena/ContextSelector.kt')
req('MAX_SELECTED = 10' in selector and 'ALWAYS_RECENT = 4' in selector, 'history budget not reduced')

store=read('app/src/main/java/ru/dachafibonacci/podval/gena/HelperKnowledgeStore.kt')
req('TrainerPlanKnowledge' in store, 'task-specific trainer retrieval missing')
req('trainer_session_plan_v1.json' in store, 'exact canonical trainer JSON no longer used')
req('trainer_checkin_v1.txt' in store and 'looksLikeTrainingCompletion' in store, 'conditional checkin missing')
req('trainer_sessions_week_' not in store, 'whole week text assets still injected at runtime')

plan=read('app/src/main/java/ru/dachafibonacci/podval/gena/TrainerPlanKnowledge.kt')
req('nearestSessions(date, 2)' in plan, 'next-session compact window missing')
req('ТОЧНЫЙ ПЛАН НЕДЕЛИ' in plan, 'week compact index missing')

worker=read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaAnalysisWorker.kt')
req('AssistantReplyLimiter.limit(event, rawReply)' in worker, 'reply limiter not wired')

scheduler=read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaScheduler.kt')
req('ExistingWorkPolicy.KEEP' in scheduler, 'active work can still be replaced')

dao=read('app/src/main/java/ru/dachafibonacci/podval/data/AssistantEventDao.kt')
req("idempotency_key LIKE 'chat:%' THEN 0" in dao, 'direct chat priority missing')
req("assistant != 'director' THEN 1" in dao, 'helper priority missing')

director=read('app/src/main/java/ru/dachafibonacci/podval/gena/Director.kt')
req('весь ответ максимум 1400 символов' in director, 'Director response budget missing')
req('.take(280)' in director and '.take(2)' in director, 'Director parsed fields not bounded')

room=read('app/src/main/java/ru/dachafibonacci/podval/data/PodvalDatabase.kt')
req('version = 2' in room, 'Room schema unexpectedly changed')
req('fallbackToDestructiveMigration' not in room, 'destructive migration forbidden')

print('PASS B19 fast-prompt invariants: compact retrieval + bounded replies + direct-chat priority; B18 timeout unchanged')
