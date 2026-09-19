#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")

replacements = {
    "versionCode = 17": "versionCode = 18",
    'versionName = "0.9.0-helpers-ready-b17"': 'versionName = "0.9.1-gena-transport-b18"',
    'buildConfigField("String", "BUILD_ID", "\\"PODVAL-MYSLEI-HELPERS-READY-B17\\"")':
        'buildConfigField("String", "BUILD_ID", "\\"PODVAL-MYSLEI-GENA-TRANSPORT-B18\\"")',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit(f"Expected B17 marker not found: {old}")
    text = text.replace(old, new, 1)

work = '    implementation("androidx.work:work-runtime-ktx:2.10.0")\n'
okhttp = '    implementation("com.squareup.okhttp3:okhttp:4.12.0")\n'
if okhttp not in text:
    if work not in text:
        raise SystemExit("WorkManager dependency marker not found")
    text = text.replace(work, work + okhttp, 1)

path.write_text(text, encoding="utf-8")
