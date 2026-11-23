#!/usr/bin/env python3
"""Check native rich text and table placement without Android or a device."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / "android/app/src/main/java/com/xxxk2020/zssteink"
files = [source / (name + ".java") for name in ("NativeRichText", "NativeTableModel")]
files.append(root / "tools/tests/NativeContentModelTest.java")
java, javac = shutil.which("java"), shutil.which("javac")
if not java or not javac:
    raise SystemExit("JDK 17 is required")
with tempfile.TemporaryDirectory(prefix="zsst-content-") as out:
    subprocess.run([javac, "-encoding", "UTF-8", "--release", "8", "-d", out, *map(str, files)], check=True, timeout=60)
    subprocess.run([java, "-Xmx128m", "-cp", out, "com.xxxk2020.zssteink.NativeContentModelTest"], check=True, timeout=60)
