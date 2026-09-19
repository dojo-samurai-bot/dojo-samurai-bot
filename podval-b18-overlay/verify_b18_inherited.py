#!/usr/bin/env python3
from pathlib import Path
import hashlib, json

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/assistant_knowledge'

def read(rel): return (ROOT / rel).read_text(encoding='utf-8')
def req(cond, msg):
    if not cond: raise AssertionError(msg)

build = read('app/build.gradle.kts')
req('versionCode = 18' in build, 'B18 versionCode missing')
req('versionName = "0.9.1-gena-transport-b18"' in build, 'B18 versionName missing')
req('applicationId = "ru.dachafibonacci.podb13"' in build, 'package changed')

required = {
    'psychologist_context_v1.txt': ('КПТ', 'длинной истор'),
    'dietitian_context_v1.txt': ('approximate', '2000'),
    'trainer_context_v1.txt': ('КАК ОПРЕДЕЛЯТЬ «СЛЕДУЮЩУЮ ТРЕНИРОВКУ»', '6C'),
    'trainer_runtime_core_v1.txt': ('КАК ОПРЕДЕЛЯТЬ СЛЕДУЮЩУЮ ТРЕНИРОВКУ', 'детальный TRAINER_SESSION_PLAN_v1'),
    'trainer_plan_8w_v1.txt': ('НЕДЕЛЯ 8', 'ПОНЕДЕЛЬНИК'),
    'trainer_checkin_v1.txt': ('10', 'плеч'),
    'router_protocol_v1.txt': ('source_entry_id', 'target_helper'),
}
for name, tokens in required.items():
    path = ASSETS / name
    req(path.exists(), f'missing knowledge asset {name}')
    text = path.read_text('utf-8')
    req(len(text) > 1000, f'knowledge asset too small {name}')
    for token in tokens: req(token.lower() in text.lower(), f'{name}: missing {token}')

raw_plan = ASSETS / 'trainer_session_plan_v1.json'
req(raw_plan.exists(), 'exact trainer session plan json missing')
plan = json.loads(raw_plan.read_text('utf-8'))
req(plan.get('start_date') == '2026-09-21' and plan.get('end_date') == '2026-11-15', 'trainer session plan dates changed')
req(len(plan.get('weeks', [])) == 8, 'trainer session plan must contain eight weeks')
for week in range(1, 9):
    name = f'trainer_sessions_week_{week}_v1.txt'
    path = ASSETS / name
    req(path.exists(), f'missing detailed trainer week asset {name}')
    text = path.read_text('utf-8')
    req(f'НЕДЕЛЯ {week}' in text and 'Дозировка:' in text and 'Как выполнять:' in text, f'incomplete detailed trainer week {week}')

manifest = json.loads((ASSETS/'manifest_v1.json').read_text('utf-8'))
req(manifest.get('build') == 'B17', 'knowledge manifest build mismatch')
for name, meta in manifest['files'].items():
    data = (ASSETS/name).read_bytes()
    req(hashlib.sha256(data).hexdigest() == meta['sha256'], f'hash mismatch {name}')

worker = read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaAnalysisWorker.kt')
req('HelperKnowledgeStore(app)' in worker, 'knowledge store not wired')
req('ContextSelector.select(' in worker, 'local history retrieval not wired')
req('recentRaw(220)' in worker, 'long history candidate window missing')
req('knowledge.promptBlock' in worker, 'helper knowledge not inserted into prompt')
req('knowledge.routerProtocol()' in worker, 'canonical router protocol not wired')

prompt = read('app/src/main/java/ru/dachafibonacci/podval/gena/GenaPrompt.kt')
req('КАНОНИЧЕСКАЯ БАЗА ПОМОЩНИКА' in prompt, 'knowledge prompt block missing')
req('ПРИОРИТЕТ ИСТОЧНИКОВ' in prompt, 'source priority missing')
req('не создавай новую программу с нуля' in prompt, 'trainer continuity rule missing')

store = read('app/src/main/java/ru/dachafibonacci/podval/gena/HelperKnowledgeStore.kt')
runtime_required = {
    'psychologist_context_v1.txt',
    'dietitian_context_v1.txt',
    'trainer_runtime_core_v1.txt',
    'trainer_checkin_v1.txt',
}
for name in runtime_required:
    req(name in store, f'{name} not referenced at runtime')
req('trainer_context_v1.txt' in manifest['files'], 'full trainer context must remain bundled')
req('trainer_plan_8w_v1.txt' in manifest['files'], 'full trainer plan must remain bundled')
req('router_protocol_v1.txt' in worker or 'knowledge.routerProtocol()' in worker, 'router protocol not wired')
req('Фактически выполненные/пропущенные тренировки'.lower() in store.lower(), 'calendar override rule missing')
req('trainerDetailedSessionWindow' in store, 'detailed trainer session window not wired')
req('trainer_runtime_core_v1.txt' in store, 'budgeted trainer runtime core not wired')
req('trainer_sessions_week_${week}_v1.txt' in store, 'detailed trainer week assets not selected at runtime')
calendar = read('app/src/main/java/ru/dachafibonacci/podval/gena/TrainerPlanCalendar.kt')
req('contextWeeks' in calendar and 'weekOn' in calendar, 'trainer plan calendar selector missing')

selector = read('app/src/main/java/ru/dachafibonacci/podval/gena/ContextSelector.kt')
req('MAX_SELECTED = 20' in selector and 'ALWAYS_RECENT = 8' in selector, 'context budget missing')

ui = read('app/src/main/java/ru/dachafibonacci/podval/ui/Stage05Visuals.kt')
assistant_tail = ui[ui.index('internal fun AssistantPanel('):]
req('ChatComposer(' in assistant_tail and 'AssistantConversation(' in assistant_tail, 'helper chat missing')
req('VoiceOrb(' not in assistant_tail and 'MicrophoneGlyph(' not in assistant_tail, 'helper microphone restored')
req('база готова · подключить Гену' in assistant_tail, 'helper readiness status missing')
req('VoiceOrb(MainTab.HOME' in ui, 'main microphone missing')

all_main = '\n'.join(p.read_text('utf-8', errors='ignore') for p in (ROOT/'app/src/main/java').rglob('*.kt'))
req('192.168.0.120' not in all_main, 'stale direct LM Studio address remains')
req('LmStudioChatViewModel' not in all_main, 'obsolete direct LM Studio client remains')

app = read('app/src/main/java/ru/dachafibonacci/podval/PodvalApplication.kt')
req('GenaScheduler' not in app, 'Gena work started from Application.onCreate')

db = read('app/src/main/java/ru/dachafibonacci/podval/data/PodvalDatabase.kt')
req('version = 2' in db, 'unexpected DB schema bump')
req('fallbackToDestructiveMigration' not in db, 'destructive migration forbidden')

print('PASS B18 inherited helper invariants: B17 helper architecture preserved')
