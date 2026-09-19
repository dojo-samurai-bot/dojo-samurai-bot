# STAGE 10 — B19 fast prompts / bounded answers

Base: B18 `0.9.1-gena-transport-b18`, package `ru.dachafibonacci.podb13`.

## Physical finding from B18

Transport is fixed: `Гена отвечает` passes over Tailscale HTTPS. A real Trainer request also reaches Gena and Gena eventually produces a response, but the response was very large and the helper request took several minutes. The problem is therefore prompt/queue/output efficiency, not connectivity.

## B19 strategy

B19 intentionally does **not increase the B18 network timeouts** (`readTimeout=180s`, `callTimeout=195s`). Instead it reduces unnecessary work:

- direct helper chat is prioritized ahead of old background Director jobs;
- active Gena work uses `ExistingWorkPolicy.KEEP`, so a new enqueue does not cancel an in-flight model request;
- semantic RAW history budget is reduced from 20 rows (8 always recent) to 10 rows (4 always recent + older relevant matches);
- helper dialog history in the prompt is reduced to 4 compact turns;
- every helper gets a strict output character budget and an explicit instruction to return only the final answer;
- an app-side reply limiter prevents giant responses from being stored/displayed even if the model ignores the prompt budget;
- Director JSON is capped and parsed fields are bounded before they become helper input;
- Trainer no longer receives whole 7-day detailed week text plus the full check-in on every question;
- the full canonical `trainer_session_plan_v1.json` remains bundled, but the phone deterministically retrieves only the relevant exact sessions/exercise blocks;
- for “какая следующая тренировка?” only the nearest two exact sessions are supplied, allowing actual RAW/check-in history to decide which is next;
- the large post-workout check-in is injected only when the current text actually looks like a completed training report.

No Room schema, package ID, existing data, Drive sync format, or B18 OkHttp transport change.

## Version

- versionCode: 19
- versionName: `0.9.2-fast-prompts-b19`
- build ID: `PODVAL-MYSLEI-FAST-PROMPTS-B19`

## First physical acceptance test

Install over B18 without clearing data. Keep Gena URL unchanged. Open Trainer and retry `Какая у меня следующая тренировка?` if needed. Expected: direct Trainer chat is serviced before background Director backlog and returns a compact answer rather than a multi-thousand-character wall of text.
