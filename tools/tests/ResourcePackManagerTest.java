package com.xxxk2020.zssteink;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exercises the production manager with real ZIPs, Android 5.1 JSON and disk I/O. */
public final class ResourcePackManagerTest {
    private static int checks;
    private interface Task { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        testUpdates();
        testFailures(false);
        testFailures(true);
        testConflicts();
        testStartup();
        testCorruptCatalogs();
        testValidation();
        testDiagnostics();
        testBinaryImages();
        if(args.length>0) testImageCourse(new File(args[0]));
        System.out.println("PASS: " + checks + " manager checks (full/incremental update, second-copy " +
                "and catalog failure, logical conflicts, startup GC, corrupt catalogs, diagnostics)");
    }

    private static void testBinaryImages() throws Exception {
        try(Fixture f=new Fixture()) {
            File zip=new File(f.base,"images.zip");
            JSONArray items=new JSONArray(), records=new JSONArray();
            Map<String,byte[]> payloads=new LinkedHashMap<String,byte[]>();
            for(String id:Arrays.asList("one","two")) {
                String path="data/courses/"+id+".json";
                JSONObject document=doc(id,"course","image").put("figs",new JSONObject().put("f","images/shared.jpg"));
                payloads.put(path,document.toString().getBytes(StandardCharsets.UTF_8));
                items.put(new JSONObject().put("id",id).put("kind","course").put("file",path));
            }
            payloads.put("data/courses/images/shared.jpg",new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                for(Map.Entry<String,byte[]> entry:payloads.entrySet()) {
                    StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(entry.getValue()))hash.append(String.format(java.util.Locale.US,"%02x",b&255));
                    records.put(new JSONObject().put("path",entry.getKey()).put("size",entry.getValue().length).put("sha256",hash.toString()));
                    out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();
                }
                JSONObject manifest=new JSONObject().put("format",ResourcePackManager.FORMAT).put("version",1).put("packId","images").put("title","images").put("items",items).put("files",records);
                out.putNextEntry(new ZipEntry("resource-pack.json"));out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
            }
            ResourcePackManager.importPack(f,Uri.fromFile(zip));
            File image=new File(f.data(f.catalog().get("one").getString("file")).getParentFile(),"images/shared.jpg");
            check(image.isFile(),"binary image is installed beside JSON");
            ResourcePackManager.removeResourcePackItem(f,"one");ResourcePackManager.prepareCatalog(f);
            check(image.isFile(),"shared image remains for second lesson");
            ResourcePackManager.removeResourcePackItem(f,"two");ResourcePackManager.prepareCatalog(f);
            check(!image.exists(),"image removed after all lessons are deleted");
        }
        try(Fixture f=new Fixture()) {
            JSONObject missing=doc("missing","course","image").put("figs",new JSONObject().put("f","images/missing.jpg"));
            expectFailure(()->ResourcePackManager.importPack(f,Uri.fromFile(f.pack(missing))),"图片缺失");
        }
    }

    private static void testImageCourse(File zip) throws Exception {
        try(Fixture f=new Fixture()) {
            ResourcePackManager.importPack(f,Uri.fromFile(zip));
            Map<String,JSONObject> catalog=f.catalog();
            equal(35,catalog.size(),"35 image lessons imported");
            int images=0;
            for(JSONObject item:catalog.values()) {
                File json=f.data(item.getString("file"));
                JSONObject document=new JSONObject(new String(Files.readAllBytes(json.toPath()),StandardCharsets.UTF_8));
                JSONObject figs=document.getJSONObject("figs");
                java.util.Iterator<String> keys=figs.keys();
                while(keys.hasNext()) {check(new File(json.getParentFile(),figs.getString(keys.next())).isFile(),"image installed beside lesson");images++;}
            }
            equal(700,images,"700 image references installed");
            ResourcePackManager.prepareCatalog(f);
            for(JSONObject item:catalog.values()) check(f.data(item.getString("file")).exists(),"GC retains installed lesson");
            for(String id:catalog.keySet()) ResourcePackManager.removeResourcePackItem(f,id);
            ResourcePackManager.prepareCatalog(f);
            equal(0,f.catalog().size(),"all course lessons removed");
        }
    }

    private static void testUpdates() throws Exception {
        try (Fixture f = new Fixture()) {
            JSONObject first = f.install(doc("a", "course", "one"), doc("b", "drill", "one"));
            response(first, 2, 0, "a", "b");
            Map<String, JSONObject> before = f.catalog();
            String a1 = before.get("a").getString("file");
            String b1 = before.get("b").getString("file");
            check(!a1.equals(path("a", "course")), "fresh import uses immutable path");
            equal(path("a", "course"), before.get("a").getString("sourceFile"), "logical path recorded");
            equal("fixture", before.get("a").getString("resourcePack"), "pack owner recorded");
            check(!f.preferences.getBoolean("active", false), "success clears diagnostic");
            response(f.install(doc("a", "course", "two"), doc("b", "drill", "two")), 0, 2, "a", "b");
            String a2 = f.catalog().get("a").getString("file");
            String b2 = f.catalog().get("b").getString("file");
            check(!a1.equals(a2) && !b1.equals(b2), "full update changes both physical URLs");
            payload(f, a1, "one"); payload(f, b1, "one");
            payload(f, a2, "two"); payload(f, b2, "two");
            response(f.install(doc("a", "course", "three"), doc("c", "drill", "three")), 1, 1, "a", "c");
            Map<String, JSONObject> incremental = f.catalog();
            equal(3, incremental.size(), "incremental update retains absent items");
            equal(b2, incremental.get("b").getString("file"), "untouched item keeps URL");
            String a3 = incremental.get("a").getString("file");
            check(!a2.equals(a3), "incremental update publishes new generation");
            payload(f, a3, "three"); payload(f, b2, "two");
            ResourcePackManager.prepareCatalog(f);
            check(!f.data(a1).exists() && !f.data(a2).exists() && !f.data(b1).exists(), "startup retires old versions");
            for (JSONObject row : incremental.values()) check(f.data(row.getString("file")).isFile(), "startup keeps active item");
            File[] extractionDirectories = f.cache.listFiles((parent, name) -> name.startsWith("zsst-pack-"));
            check(extractionDirectories != null, "cache is readable");
            equal(0, extractionDirectories.length, "successful imports clean extraction directories");
        }
    }

    private static void testFailures(final boolean catalogFailure) throws Exception {
        try (final Fixture f = new Fixture()) {
            f.install(doc("a", "course", "old"), doc("b", "drill", "old"));
            final Map<String, String> before = snapshot(f.files);
            final int[] copies = {0};
            final int[] publications = {0};
            final List<String> staged = new ArrayList<String>();
            final File blocked = new File(f.base, "blocked-catalog");
            write(new File(blocked, "keep"), "prevents rename over this directory");
            // Fail the real writer rename without relying on user permissions.
            ResourcePackManager.ImportIo io = new ResourcePackManager.ImportIo() {
                @Override public String stage(ResourcePackStorage generation, File source, String path) throws IOException {
                    copies[0]++;
                    if (!catalogFailure && copies[0] == 2) {
                        // Validation already passed. Fail the real second copy by removing its source.
                        if (!source.delete()) throw new AssertionError("cannot inject source loss");
                    }
                    String installed = ResourcePackManager.FILE_IO.stage(generation, source, path);
                    staged.add(installed);
                    return installed;
                }
                @Override public void publish(File catalog, LinkedHashMap<String, JSONObject> items)
                        throws IOException, org.json.JSONException {
                    publications[0]++;
                    check(catalogFailure, "copy failure must not reach publication");
                    equal(2, staged.size(), "both copies complete before catalog failure");
                    for (String p : staged) payload(f, p, "new");
                    ResourcePackManager.FILE_IO.publish(blocked, items);
                    throw new AssertionError("blocked catalog rename unexpectedly succeeded");
                }
            };
            final File zip = f.pack(doc("a", "course", "new"), doc("b", "drill", "new"));
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(zip), io),
                    catalogFailure ? "无法保存清单" : null);
            equal(2, copies[0], "failure occurs during second copy or later");
            equal(catalogFailure ? 1 : 0, publications[0], "publication boundary count");
            equal(before, snapshot(f.files), "failure preserves catalog bytes and all old payloads; rolls back generation");
            File[] extractionDirectories = f.cache.listFiles((parent, name) -> name.startsWith("zsst-pack-"));
            check(extractionDirectories != null, "cache is readable after failure");
            equal(0, extractionDirectories.length, "failure removes extraction stage");
            check(f.preferences.getBoolean("active", false), "failure leaves recovery marker");
            for (File file : f.base.listFiles()) check(!file.getName().startsWith("blocked-catalog.tmp-"), "failed writer removes temp catalog");
            ResourcePackManager.prepareCatalog(f);
            check(!f.preferences.getBoolean("active", false), "startup clears interrupted-import marker after recovery");
            equal(before, snapshot(f.files), "startup after failure preserves active installation");
            response(f.install(doc("a", "course", "retry")), 0, 1, "a");
            payload(f, f.catalog().get("b").getString("file"), "old");
        }
    }

    private static void testConflicts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.install(doc("a", "course", "old"));
            f.install(doc("a", "course", "updated")); // existing file is now a generation URL
            Map<String, String> before = snapshot(f.files);
            JSONObject other = doc("x", "course", "new");
            File zip = f.pack(new String[]{path("a", "course")}, other);
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(zip)), "与已安装资料冲突");
            equal(before, snapshot(f.files), "sourceFile conflict preserves existing installation");
            JSONObject samePathItem = new JSONObject().put("id", "y").put("kind", "course").put("file", path("x", "course"));
            File internal = f.packWithExtraItem(samePathItem, doc("x", "course", "new"));
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(internal)), "共用同一文件");
            equal(before, snapshot(f.files), "intra-pack logical conflict publishes nothing");
            // Also protect legacy catalogs which have no sourceFile yet.
            write(f.catalogFile(), new JSONArray().put(new JSONObject().put("id", "legacy").put("kind", "course")
                    .put("file", path("x", "course"))).toString());
            Map<String, String> legacy = snapshot(f.files);
            File legacyZip = f.pack(doc("x", "course", "new"));
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(legacyZip)), "与已安装资料冲突");
            equal(legacy, snapshot(f.files), "legacy file fallback detects conflict");
        }
    }

    private static void testStartup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.install(doc("a", "course", "active"));
            write(f.data("data/papers/legacy.json"), "old");
            write(f.data("data/courses/.zsst-generation-orphan/abandoned.json"), "partial");
            write(f.data("data/papers/crash.tmp-123"), "partial");
            File local = f.data("data/local/keep.json"); write(local, doc("keep", "drill", "local").toString());
            File note = f.data("data/papers/keep.txt"); write(note, "note");
            File stage = new File(f.cache, "zsst-pack-00000000-0000-0000-0000-000000000000");
            write(new File(stage, "resource-pack.json"), "partial");
            File unrelated = new File(f.cache, "other-cache"); write(unrelated, "keep");
            ResourcePackManager.prepareCatalog(f);
            check(!f.data("data/papers/legacy.json").exists(), "startup removes legacy payload");
            check(!f.data("data/courses/.zsst-generation-orphan").exists(), "startup removes orphan generation");
            check(!f.data("data/papers/crash.tmp-123").exists(), "startup removes interrupted copy");
            check(!stage.exists(), "startup removes interrupted extraction stage");
            check(local.isFile() && note.isFile() && unrelated.isFile(), "startup keeps local docs and unrelated files");
            payload(f, f.catalog().get("a").getString("file"), "active");
            Map<String, String> before = snapshot(f.files);
            ResourcePackManager.prepareCatalog(f);
            equal(before, snapshot(f.files), "startup is idempotent");
        }
    }

    private static void testCorruptCatalogs() throws Exception {
        String[] bad = {"[", "{}", "[7]", "[] trailing", "[{\"file\":\"data/papers/keep.json\"}]",
                "[{\"id\":\"\",\"file\":\"data/papers/keep.json\"}]",
                "[{\"id\":\"same\",\"file\":\"data/papers/keep.json\"},{\"id\":\"same\",\"file\":\"data/papers/other.json\"}]", "[{\"id\":\"bad\"}]",
                "[{\"id\":\"bad\",\"file\":\"../escape.json\"}]",
                "[{\"id\":\"ok\",\"file\":\"data/papers/keep.json\"},{\"id\":\"bad\",\"file\":\"data/local/keep.json\"}]"};
        for (String catalog : bad) {
            try (Fixture f = new Fixture()) {
                ResourcePackManager.ensureCatalog(f);
                write(f.catalogFile(), catalog);
                write(f.data("data/papers/keep.json"), "valuable");
                write(f.data("data/courses/.zsst-generation-orphan/keep.json"), "valuable");
                File stage = new File(f.cache, "zsst-pack-00000000-0000-0000-0000-000000000000");
                write(new File(stage, "keep"), "valuable");
                Map<String, String> before = snapshot(f.files);
                expectFailure(() -> ResourcePackManager.prepareCatalog(f), null);
                equal(before, snapshot(f.files), "invalid catalog forbids all payload GC: " + catalog);
                check(stage.isDirectory(), "invalid catalog does not reach stage cleanup");
            }
        }
    }

    private static void testValidation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.install(doc("a", "course", "old"));
            Map<String, String> before = snapshot(f.files);
            File zip = f.packWithExtraItem(new JSONObject().put("id", "a").put("kind", "course")
                    .put("file", path("a", "course")), doc("a", "course", "new"));
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(zip)), "重复 ID");
            equal(before, snapshot(f.files), "duplicate ID rolls back before publication");
            JSONObject badIdentity = doc("a", "course", "new").put("id", new JSONObject());
            File invalid = f.pack(new String[]{path("a", "course")}, badIdentity);
            expectFailure(() -> ResourcePackManager.importPack(f, Uri.fromFile(invalid)), "不一致");
            equal(before, snapshot(f.files), "complex document identity is rejected");
        }
    }

    private static void testDiagnostics() throws Exception {
        try (Fixture f = new Fixture()) {
            ResourcePackManager.clearImportDiagnostic(f);
            f.preferences.commits = 0; f.preferences.stages.clear();
            JSONObject[] many = new JSONObject[120];
            for (int i = 0; i < many.length; i++) many[i] = doc("p" + i, "drill", "small");
            f.install(many);
            // Virtual clock remains fixed: all per-entry, per-document and per-copy progress is suppressed.
            List<String> phases = Arrays.asList("准备本地资源目录", "解压 ZIP 资源包", "流式读取资源包清单",
                    "校验资源文件大小与 SHA-256", "核对 ZIP 内的文件清单", "读取本机已安装资料清单",
                    "写入资源包新版本", "提交资源包清单");
            equal(phases, f.preferences.stages, "full import forces phases while throttling hundreds of progress events");
            equal(9, f.preferences.commits, "8 phase writes plus success clear");
            check(!f.preferences.getBoolean("active", false), "success has no interrupted stage");
            // Advance the clock from the resolver while extraction is running; at least one progress write is due.
            f.preferences.stages.clear();
            f.advanceOnOpen = true;
            f.install(doc("p0", "drill", "updated"));
            check(f.preferences.stages.size() == 9, "elapsed interval allows exactly one extraction progress write");
            check(f.preferences.stages.get(2).startsWith("解压 ZIP 第"), "due progress records actual extraction position");
            ResourcePackManager.recordImportStage(f, "forced failure location");
            equal("forced failure location", f.preferences.getString("stage", null), "forced stage bypasses throttle");
            ResourcePackManager.prepareCatalog(f);
            check(!f.preferences.getBoolean("active", false), "startup recovery clears active marker");
            f.preferences.throwOnCommit = true;
            response(f.install(doc("p0", "drill", "diagnostics unavailable")), 0, 1, "p0");
            payload(f, f.catalog().get("p0").getString("file"), "diagnostics unavailable");
        }
    }

    private static JSONObject doc(String id, String kind, String revision) throws Exception {
        return new JSONObject().put("id", id).put("kind", kind).put("revision", revision)
                .put("ignored", new JSONObject().put("nested", new JSONArray().put(true).put(JSONObject.NULL)));
    }
    private static String path(String id, String kind) { return "data/" + ("course".equals(kind) ? "courses/" : "papers/") + id + ".json"; }
    private static void payload(Fixture f, String path, String revision) throws IOException, org.json.JSONException {
        equal(revision, new JSONObject(read(f.data(path))).getString("revision"), "payload content: " + path);
    }
    private static void response(JSONObject result, int added, int updated, String... ids) throws Exception {
        check(result.getBoolean("ok"), "import succeeds");
        equal("fixture", result.getString("packId"), "result pack ID");
        equal(added, result.getInt("added"), "added count"); equal(updated, result.getInt("updated"), "updated count");
        equal(ids.length, result.getInt("total"), "incoming total");
        JSONArray changed = result.getJSONArray("items"); equal(ids.length, changed.length(), "changed IDs count");
        for (int i = 0; i < ids.length; i++) equal(ids[i], changed.getString(i), "changed ID order");
    }
    private static void expectFailure(Task task, String message) throws Exception {
        try { task.run(); } catch (Exception expected) {
            if (message != null) check(expected.getMessage() != null && expected.getMessage().contains(message), "expected failure " + message + ", got " + expected);
            else checks++;
            return;
        }
        throw new AssertionError("expected failure");
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message + " (expected " + expected + ", got " + actual + ")");
    }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static void write(File file, String text) throws IOException { file.getParentFile().mkdirs(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static Map<String, String> snapshot(File root) throws Exception {
        Map<String, String> result = new TreeMap<String, String>(); snapshot(root, root, result); return result;
    }
    private static void snapshot(File root, File node, Map<String, String> result) throws Exception {
        if (node.isDirectory()) { for (File file : node.listFiles()) snapshot(root, file, result); }
        else result.put(root.toURI().relativize(node.toURI()).getPath(), read(node));
    }
    private static void delete(File file) throws IOException {
        if (file.isDirectory()) for (File child : file.listFiles()) delete(child);
        Files.deleteIfExists(file.toPath());
    }

    static final class Fixture extends Context implements AutoCloseable {
        final File base = Files.createTempDirectory("zsst-manager-test-").toFile();
        final File files = new File(base, "files");
        final File cache = new File(base, "cache");
        final Preferences preferences = new Preferences();
        boolean advanceOnOpen;
        int packs;
        Fixture() throws Exception { files.mkdirs(); cache.mkdirs(); SystemClock.setElapsedRealtime(0); ResourcePackManager.clearImportDiagnostic(this); }
        @Override public File getFilesDir() { return files; }
        @Override public File getCacheDir() { return cache; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!"zsst_resource_pack_import_diagnostics".equals(name)) throw new AssertionError("unexpected preferences namespace");
            return preferences;
        }
        @Override public ContentResolver getContentResolver() { return new ContentResolver() {
            @Override public InputStream openInputStream(Uri uri) throws IOException {
                if (advanceOnOpen) SystemClock.setElapsedRealtime(1000);
                return new FileInputStream(uri.hostFile());
            }
        }; }
        File data(String path) { return new File(ResourcePackManager.dataRoot(this), path); }
        File catalogFile() { return data("data/items.json"); }
        Map<String, JSONObject> catalog() throws Exception {
            JSONArray array = new JSONArray(read(catalogFile())); Map<String, JSONObject> rows = new LinkedHashMap<String, JSONObject>();
            for (int i = 0; i < array.length(); i++) { JSONObject row = array.getJSONObject(i); rows.put(row.getString("id"), row); }
            return rows;
        }
        JSONObject install(JSONObject... documents) throws Exception { return ResourcePackManager.importPack(this, Uri.fromFile(pack(documents))); }
        File pack(JSONObject... documents) throws Exception { return buildPack(null, null, documents); }
        File pack(String[] paths, JSONObject... documents) throws Exception { return buildPack(paths, null, documents); }
        File packWithExtraItem(JSONObject item, JSONObject... documents) throws Exception { return buildPack(null, item, documents); }
        File buildPack(String[] paths, JSONObject extra, JSONObject... documents) throws Exception {
            File zip = new File(base, "pack-" + packs++ + ".zip"); JSONArray items = new JSONArray(); JSONArray records = new JSONArray();
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
                for (int i = 0; i < documents.length; i++) {
                    JSONObject doc = documents[i]; String id = doc.optString("id", ""); String kind = doc.optString("kind", "drill");
                    // Invalid document IDs can be tested independently of valid catalog IDs.
                    String logical = paths == null ? path(id, kind) : paths[i];
                    String itemId = paths != null && !(doc.opt("id") instanceof String) ? "a" : id;
                    byte[] bytes = doc.toString().getBytes(StandardCharsets.UTF_8);
                    StringBuilder hash = new StringBuilder();
                    for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
                    records.put(new JSONObject().put("path", logical).put("size", bytes.length).put("sha256", hash.toString()));
                    items.put(new JSONObject().put("id", itemId).put("kind", kind).put("file", logical));
                    out.putNextEntry(new ZipEntry(logical)); out.write(bytes); out.closeEntry();
                }
                if (extra != null) items.put(extra);
                JSONObject manifest = new JSONObject().put("format", ResourcePackManager.FORMAT).put("version", 1)
                        .put("packId", "fixture").put("title", "导入回归").put("files", records).put("items", items);
                out.putNextEntry(new ZipEntry("resource-pack.json")); out.write(manifest.toString().getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
            return zip;
        }
        @Override public void close() throws Exception { preferences.throwOnCommit = false; ResourcePackManager.clearImportDiagnostic(this); delete(base); }
    }

    private static final class Preferences implements SharedPreferences {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        final List<String> stages = new ArrayList<String>();
        int commits;
        boolean throwOnCommit;
        @Override public boolean getBoolean(String key, boolean fallback) { return values.containsKey(key) ? (Boolean) values.get(key) : fallback; }
        @Override public String getString(String key, String fallback) { return values.containsKey(key) ? (String) values.get(key) : fallback; }
        @Override public Editor edit() { return new Editor() {
            final Map<String, Object> pending = new LinkedHashMap<String, Object>(); boolean clear;
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                commits++;
                if (throwOnCommit) throw new IllegalStateException("diagnostics unavailable");
                if (clear) values.clear(); values.putAll(pending);
                if (pending.containsKey("stage")) stages.add((String) pending.get("stage"));
                return true;
            }
        }; }
    }
}


