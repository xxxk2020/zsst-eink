#!/usr/bin/env python3
"""Offline boundary and known-sensitive-pattern checks, without printing matched values."""
from pathlib import Path
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
production = root / "android/app/src/main"
failures = []
def fail(path, reason, line=None):
    failures.append(f"{path.relative_to(root).as_posix()}" + (f":{line}" if line else "") + f": {reason}")

manifest_path = production / "AndroidManifest.xml"
manifest = ET.parse(manifest_path).getroot()
if any(node.tag.startswith("uses-permission") for node in manifest):
    fail(manifest_path, "offline app must not request permissions")
if any(manifest.iter("service")) or any(manifest.iter("provider")) or any(manifest.iter("receiver")):
    fail(manifest_path, "unexpected background or exported component")

boundary = re.compile(r"\b(?:WebView|HttpURLConnection|URLConnection|OkHttpClient|ServerSocket|Socket|CookieManager)\b|import\s+(?:java\.net|javax\.net|android\.webkit)\b", re.I)
java_files = sorted((production / "java").rglob("*.java"))
if not java_files:
    fail(production, "application source missing")
for path in java_files:
    text = path.read_text(encoding="utf-8")
    for line, content in enumerate(text.splitlines(), 1):
        if boundary.search(content) and not content.lstrip().startswith(("//", "/**", "*")):
            fail(path, "network or embedded-browser dependency", line)
    if "package com.xxxk2020.zssteink;" not in text:
        fail(path, "unexpected application namespace")

build = root / "android/app/build.gradle"
if re.search(r"^\s*(implementation|api|compileOnly|runtimeOnly)\s", build.read_text(encoding="utf-8"), re.M):
    fail(build, "unexpected runtime dependency: audit its license and offline boundary")

skip_dirs = {".git", ".gradle", "build", "__pycache__", ".idea"}
sensitive = re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{35,}|AKIA[0-9A-Z]{16})\b|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\b(?:Cookie|Authorization)\s*:\s*\S|\b192\.168\.\d{1,3}\.\d{1,3}\b|[A-Za-z]:[/\\]Users[/\\]", re.I)
private_suffixes = {".apk", ".aab", ".jks", ".keystore", ".pem", ".p12", ".pfx"}
allowed_binary = {"android/gradle/wrapper/gradle-wrapper.jar", "examples/source/data/papers/images/diagram.png"}
count = 0
for path in sorted(root.rglob("*")):
    relative = path.relative_to(root)
    if any(part in skip_dirs for part in relative.parts) or path.name == "local.properties" or not path.is_file():
        continue
    count += 1
    if path.suffix.lower() in private_suffixes or path.name == ".env" or path.name.startswith(".env."):
        fail(path, "private or generated artifact must not be published")
    if relative.as_posix() in allowed_binary:
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        fail(path, "unreviewed binary content")
        continue
    for line, content in enumerate(text.splitlines(), 1):
        if sensitive.search(content):
            fail(path, "possible credential, personal path or private network address", line)

vendor = root / "tools/tests/android-json"
for row in (vendor / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
    expected, relative = row.split(None, 1)
    path = vendor / relative.strip()
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        fail(path, "vendored AOSP source checksum changed")
wrapper = root / "android/gradle/wrapper/gradle-wrapper.jar"
if hashlib.sha256(wrapper.read_bytes()).hexdigest() != "498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17":
    fail(wrapper, "pinned Gradle wrapper checksum changed")
for path in (root / "LICENSE", root / "THIRD_PARTY_NOTICES.md", vendor / "LICENSE", root / "android/gradle/wrapper/GRADLE-LICENSE.txt"):
    if not path.is_file() or path.stat().st_size < 500:
        fail(path, "required license notice missing")

if failures:
    print("FAIL: public source audit")
    print("\n".join(failures))
    sys.exit(1)
print(f"PASS: {count} source files; {len(java_files)} native classes; no requested permissions, network client or known sensitive patterns; vendored checksums and licenses intact")
