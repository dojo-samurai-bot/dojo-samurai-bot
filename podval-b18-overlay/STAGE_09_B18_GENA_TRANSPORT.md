# STAGE 09 — B18 Gena transport fix

Base: B17 `0.9.0-helpers-ready-b17`, package `ru.dachafibonacci.podb13`.

## Physical diagnosis before B18

The same Gena API endpoint was verified from the PC:

- `POST http://127.0.0.1:8765/api/chat` -> `{ok:true, reply:"OK"}`
- `POST https://ozzyhere.tail6a3ead.ts.net/api/chat` -> `{ok:true, reply:"OK"}`

On B17, the Android request reached Gena and Gena produced the requested `ГОТОВ` reply, but the client reported `connection closed` / `Software caused connection abort` while reading the result.

## Change

Only the Gena HTTP transport is changed:

- replace `HttpURLConnection` transport with explicit OkHttp 4.12.0;
- force HTTP/1.1 for the Gena endpoint;
- request `Connection: close`;
- enable retry on connection failure;
- keep 180 s read timeout for local-model generation;
- keep the exact `/api/chat` JSON contract (`{message}` -> `{ok, reply, tools}`).

No Room schema, helper routing, helper knowledge, Drive sync, UI, or package ID changes.

## Version

- versionCode: 18
- versionName: `0.9.1-gena-transport-b18`
- build ID: `PODVAL-MYSLEI-GENA-TRANSPORT-B18`

## Physical acceptance test

Install over B17 without clearing app data, keep base URL `https://ozzyhere.tail6a3ead.ts.net`, tap `Проверить`.
Expected: `Гена отвечает`. Then save the URL and verify queued Trainer/Psychologist/Dietitian requests are processed.
