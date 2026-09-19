from pathlib import Path

root = Path(__file__).resolve().parents[1]
gateway = (root / "app/src/main/java/ru/dachafibonacci/podval/gena/GenaGateway.kt").read_text(encoding="utf-8")
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")

checks = {
    "B18 versionCode": "versionCode = 18" in gradle,
    "B18 versionName": 'versionName = "0.9.1-gena-transport-b18"' in gradle,
    "B18 build id": "PODVAL-MYSLEI-GENA-TRANSPORT-B18" in gradle,
    "OkHttp dependency": 'implementation("com.squareup.okhttp3:okhttp:4.12.0")' in gradle,
    "No HttpURLConnection implementation": "java.net.HttpURLConnection" not in gateway and "as HttpURLConnection" not in gateway,
    "Explicit OkHttp client": "OkHttpClient.Builder()" in gateway,
    "HTTP/1.1 pinned": "Protocol.HTTP_1_1" in gateway,
    "Connection close": '.header("Connection", "close")' in gateway,
    "Retry on connection failure": ".retryOnConnectionFailure(true)" in gateway,
    "API contract unchanged": 'JSONObject().put("message", message).toString()' in gateway,
    "Reply contract unchanged": 'data.optString("reply")' in gateway,
}

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL"), name)
if failed:
    raise SystemExit("B18 transport verification failed: " + ", ".join(failed))
print("PASS B18 transport invariants")
