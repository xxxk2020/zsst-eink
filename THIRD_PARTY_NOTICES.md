# Third-party notices

The original application code, documentation, geometric icon and demonstration
questions are provided under the root MIT license. The following files retain
their own licenses and are not relicensed under MIT.

## Android Open Source Project — Apache License 2.0

`tools/tests/android-json/` contains test-only Android 5.1.1_r9 implementations
of `android.util` JSON streaming classes, `libcore.internal.StringPool` and
`org.json`. Their original copyright headers, complete Apache-2.0 license,
provenance README and SHA256SUMS are preserved in that directory. They are
compiled only for host regression tests and are not packaged into the APK.

Upstream sources:

- https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r9/core/java/android/util/
- https://android.googlesource.com/platform/libcore/+/android-5.1.1_r9/json/src/main/java/org/json/
- https://android.googlesource.com/platform/libcore/+/android-5.1.1_r9/luni/src/main/java/libcore/internal/StringPool.java

## Gradle build infrastructure — Apache License 2.0

When using the Gradle Wrapper included with this project, the wrapper scripts
and JAR are provided by the Gradle project under Apache-2.0. Their headers and
the accompanying license are retained. The wrapper downloads the pinned
Gradle distribution from `services.gradle.org` and checks its published SHA-256.

The Android Gradle plugin and Android SDK are external build tools, obtained
from their official repositories under their respective licenses. They are
not application runtime libraries or bundled question content.
