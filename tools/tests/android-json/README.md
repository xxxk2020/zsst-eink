# Android 5.1 JSON implementation for host tests

These Apache-2.0 sources are test-only and remain unmodified. All retain their
upstream copyright and license headers; see LICENSE. They are compiled from
source against Java 8 APIs, rather than running Android framework bytecode
(which can call Android-only overloads of java.lang.System.arraycopy).

Upstream version: **android-5.1.1_r9** (API 22).

- `android/util/{JsonReader,JsonToken,JsonScope,MalformedJsonException}.java`:
  https://android.googlesource.com/platform/frameworks/base/+/android-5.1.1_r9/core/java/android/util/
- `libcore/internal/StringPool.java`:
  https://android.googlesource.com/platform/libcore/+/android-5.1.1_r9/luni/src/main/java/libcore/internal/StringPool.java
- `org/json/{JSON,JSONArray,JSONException,JSONObject,JSONStringer,JSONTokener}.java`:
  https://android.googlesource.com/platform/libcore/+/android-5.1.1_r9/json/src/main/java/org/json/

The framework reader and StringPool were extracted from Maven Central's
`org.robolectric:android-all:5.1.1_r9-robolectric-1:sources` archive (SHA-256
`c92d60bbe342efc248a4635881d54ecd772587b50a883b752e8cead82e3aed6a`).
The org.json sources came directly from the libcore URLs above. SHA256SUMS
records each vendored Java file so future dependency updates are explicit.

No external dependency, Android SDK, network access, or Robolectric runner is
needed at test time. These files and the small host doubles under `../host`
are outside `android/app` and are never packaged into the application.
