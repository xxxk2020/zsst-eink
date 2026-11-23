package com.xxxk2020.zssteink;

import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-document, two-generation durable backups. No Android API above 21 is needed. */
final class StudyProgressStore {
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final Map<String, java.lang.ref.WeakReference<StudyProgressStore>> SHARED = new LinkedHashMap<>();
    static synchronized StudyProgressStore shared(File directory) {
        String path;
        try { path = directory.getCanonicalPath(); } catch (IOException unavailable) { path = directory.getAbsolutePath(); }
        java.lang.ref.WeakReference<StudyProgressStore> reference = SHARED.get(path);
        StudyProgressStore result = reference == null ? null : reference.get();
        if (result == null) { result = new StudyProgressStore(directory); SHARED.put(path, new java.lang.ref.WeakReference<>(result)); }
        return result;
    }
    private final File directory;
    private final Map<String, Record> cache = new LinkedHashMap<String, Record>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Record> e) { return size() > 64; }
    };
    private static final class Record {
        final long generation; final JSONObject values;
        Record(long generation, JSONObject values) { this.generation = generation; this.values = values; }
    }
    StudyProgressStore(File directory) { this.directory = directory; }

    static String group(String key) throws IOException {
        if (key == null || key.length() > 300) throw new IOException("进度键无效");
        if ("zskg_stars".equals(key)) return "stars";
        for (String prefix : new String[]{"zskg_ans_", "zskg_pos_"}) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                for (String mode : new String[]{"_drill", "_practice", "_read"})
                    if (rest.endsWith(mode) && rest.length() > mode.length()) return "doc:" + rest.substring(0, rest.length() - mode.length());
            }
        }
        for (String prefix : new String[]{"zskg_submit_", "zskg_result_", "zskg_done_"})
            if (key.startsWith(prefix) && key.length() > prefix.length()) return "doc:" + key.substring(prefix.length());
        throw new IOException("不支持的进度键");
    }
    synchronized JSONObject snapshot(String key) throws Exception {
        return new JSONObject(load(group(key)).values.toString());
    }
    synchronized void apply(String raw) throws Exception {
        if (raw == null || raw.length() > LIMIT) throw new IOException("进度数据过大");
        JSONObject changes = new JSONObject(raw); String group = null;
        for (Iterator<String> keys = changes.keys(); keys.hasNext();) {
            String key = keys.next(), current = group(key);
            if (group == null) group = current;
            if (!group.equals(current)) throw new IOException("一次只能保存同一份资料的进度");
            Object value = changes.get(key);
            if (value != JSONObject.NULL && !(value instanceof String)) throw new IOException("进度值无效");
        }
        if (group == null) return;
        Record previous = load(group); JSONObject values = new JSONObject(previous.values.toString());
        for (Iterator<String> keys = changes.keys(); keys.hasNext();) {
            String key = keys.next();if (changes.isNull(key)) values.remove(key);else values.put(key, changes.getString(key));
        }
        String payload = values.toString(); long next = previous.generation + 1;
        byte[] data = new JSONObject().put("version", 1).put("group", group).put("generation", next)
                .put("payload", payload).put("sha256", digest(payload)).toString().getBytes("UTF-8");
        if (data.length > LIMIT) throw new IOException("单份资料进度过大");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建进度备份目录");
        File target = slot(group, (int)(next % 2)), temporary = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(data); out.getFD().sync(); }
            // The other generation remains intact if replacement is interrupted.
            if (target.exists() && !target.delete()) throw new IOException("无法替换进度备份");
            if (!temporary.renameTo(target)) throw new IOException("无法保存进度备份");
            cache.put(group, new Record(next, values));
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    private Record load(String group) throws Exception {
        Record saved = cache.get(group);if (saved != null) return saved;
        Record best = null; boolean exists = false;
        for (int i = 0; i < 2; i++) {
            File file = slot(group, i);exists |= file.exists();
            if (!file.isFile() || file.length() > LIMIT) continue;
            try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];int n;
                while ((n = in.read(buffer)) != -1) { if (out.size() + n > LIMIT) throw new IOException();out.write(buffer, 0, n); }
                JSONObject envelope = new JSONObject(new String(out.toByteArray(), "UTF-8"));String payload = envelope.getString("payload");
                long generation = envelope.getLong("generation");
                if (envelope.getInt("version") != 1 || !group.equals(envelope.getString("group")) || generation <= 0 ||
                        !digest(payload).equals(envelope.getString("sha256"))) continue;
                JSONObject values = new JSONObject(payload);
                for (Iterator<String> keys = values.keys(); keys.hasNext();) { String key = keys.next();if (!group.equals(group(key)) || !(values.get(key) instanceof String)) throw new IOException(); }
                if (best == null || generation > best.generation) best = new Record(generation, values);
            } catch (Exception invalid) { /* Try the other intact generation. */ }
        }
        if (best == null && exists) throw new IOException("进度备份损坏，原文件已保留");
        if (best == null) best = new Record(0, new JSONObject());cache.put(group, best);return best;
    }
    private File slot(String group, int slot) throws Exception { return new File(directory, digest(group) + "." + slot + ".json"); }
    private static String digest(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255));return out.toString();
    }
}

