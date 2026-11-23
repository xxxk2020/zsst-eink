package com.xxxk2020.zssteink;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Offline, on-demand storage for the native reader. No WebView or network access.
 * Original documents and study-progress files are never rewritten by this class.
 * All public I/O methods belong on the caller's background executor.
 */
public final class NativeRepository implements Closeable {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Object IO_LOCK = NativeStorageLock.LOCK;
    private static final int CACHE_VERSION = 2;
    private static final long MAX_SOURCE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RECORD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_STRING_CHARS = 24 * 1024 * 1024;
    private static final int MAX_ROWS = 10000;
    private static final int MAX_MATERIALS = 1000;
    private static final Pattern DATA_IMAGE = Pattern.compile(
            "data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+");
    private static final Pattern OPTION_PREFIX = Pattern.compile("^[A-Ha-h][.、)）]\\s*(.+)$");

    private final Context context;
    private final File dataRoot, cacheRoot, libraryRoot, oldLocalRoot;
    private volatile boolean closed;
    private final List<Issue> issues = new ArrayList<>();
    private String maintenanceWarning = "";
    private static final Map<String,Integer> OPEN_INDEXES = new HashMap<>();
    private final Map<String,Fingerprint> fingerprints = new LinkedHashMap<String,Fingerprint>(32,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Fingerprint> e) { return size()>64; }
    };

    static final class Issue {
        final String title, detail;
        final File removable;
        Issue(String title,String detail,File removable) { this.title=title;this.detail=detail;this.removable=removable; }
    }
    List<Issue> issues() { synchronized(IO_LOCK) { return new ArrayList<>(issues); } }
    private void issue(File file,Exception failure,boolean removable) throws IOException {
        interrupted();
        if(issues.size()<40)issues.add(new Issue(file.getName(),"无法读取，原文件已保留。"+(failure.getMessage()==null?"":failure.getMessage()),removable?file:null));
    }
    private JSONObject listMetadata(File file) throws Exception {
        try { return readOptional(file); }
        catch(Exception damaged) { issue(file,damaged,false);return new JSONObject(); }
    }
    void prepareStartup() {
        synchronized(IO_LOCK) {
            maintenanceWarning="";
            try { ResourcePackManager.prepareCatalog(context); }
            catch(Exception unavailable) { maintenanceWarning="资源清理未完成；原文件已保留"; }
            try { maintainCache(false); } catch(Exception ignored) { }
        }
    }
    void maintenance() {
        synchronized(IO_LOCK) {
            try { ResourcePackManager.cleanupNativeCatalog(context); } catch(Exception ignored) { }
            try { maintainCache(true); } catch(Exception ignored) { }
        }
    }
    void removeUnreadable(Issue issue) throws Exception {
        synchronized(IO_LOCK) {
            File source=allowedSource(issue.removable);boolean local=false;
            for(File root:new File[]{new File(dataRoot,"data/local"),oldLocalRoot,new File(dataRoot,"local-docs")})
                if(source.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))local=true;
            if(!local||!source.getName().endsWith(".json"))throw new IOException("不能在这里删除资料索引");
            // Recheck: a file repaired since listing must not be deleted as corrupt.
            boolean invalid=false;JSONObject imports=readOptional(new File(libraryRoot,"import-items.json"));
            try { summary(source,imports); }
            catch(Exception damaged) { interrupted();invalid=true; }
            if(!invalid)throw new IOException("资料已可读取，请刷新题库后再操作");
            NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
            NativeMaintenance.invalidate(context,NativeMaintenance.READER);
            if(!source.delete()&&source.exists())throw new IOException("删除失败，原文件已保留");
            fingerprints.remove(source.getCanonicalPath());maintenance();
        }
    }


    public static final class Item {
        public String id, title, kind, subject, course, teacher, unit, shape;
        public int count;
        public boolean hasAnswers;
        public File file;
        public JSONObject metadata;
    }

    public NativeRepository(Context context) {
        this.context = context.getApplicationContext();
        dataRoot = ResourcePackManager.dataRoot(this.context);
        cacheRoot = new File(this.context.getCacheDir(), "native-reader-cache");
        libraryRoot = new File(this.context.getFilesDir(), "native-library");
        oldLocalRoot = new File(this.context.getFilesDir(), "local-docs");
    }

    /** Catalog order is retained; native/local imports follow it in filename order. */
    public List<Item> list() throws Exception {
        synchronized (IO_LOCK) {
            requireOpen();
            issues.clear();
            if(maintenanceWarning.length()>0)issues.add(new Issue("资源维护",maintenanceWarning,null));
            JSONObject legacy = listMetadata(new File(libraryRoot, "legacy-items.json"));
            JSONObject subjects = listMetadata(new File(libraryRoot, "subjects.json"));
            JSONObject imports = listMetadata(new File(libraryRoot, "import-items.json"));
            LinkedHashMap<String, Item> result = new LinkedHashMap<String, Item>();
            Set<String> paths = new HashSet<String>();
            File catalog = new File(dataRoot, "data/items.json");
            if (catalog.isFile()) {
                try {
                checkSize(catalog, 16L * 1024L * 1024L);
                try (JsonReader reader = reader(catalog)) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (result.size() >= MAX_ROWS) throw new IOException("资料清单过大");
                        JSONObject meta = object(readValue(reader, null, 0, new int[2]));
                        try {
                        File file = child(dataRoot, meta.optString("file", ""));
                        if (!file.isFile()) continue;
                        String id = meta.optString("id", "");
                        JSONObject combined = mergeMetadata(legacy.optJSONObject(id), meta);
                        // Existing resource catalogs already contain n/ans/shape. Avoid
                        // scanning every large course merely to draw the library screen.
                        if (!combined.has("n") || !combined.has("shape"))
                            combined = merge(combined, summary(file, imports));
                        Item item = item(file, combined, subjects);
                        if (!result.containsKey(item.id)) result.put(item.id, item);
                        paths.add(file.getCanonicalPath());
                        } catch(Exception damaged) { issue(catalog,damaged,false); }
                    }
                    reader.endArray();
                    endDocument(reader);
                }
                } catch(Exception damaged) { issue(catalog,damaged,false); }
            }
            addLocalDirectory(new File(dataRoot, "data/local"), result, paths, legacy, subjects, imports);
            addLocalDirectory(oldLocalRoot, result, paths, legacy, subjects, imports);
            // Some older copies used a local-docs folder below resource-data.
            addLocalDirectory(new File(dataRoot, "local-docs"), result, paths, legacy, subjects, imports);
            return new ArrayList<Item>(result.values());
        }
    }

    private void addLocalDirectory(File root, Map<String, Item> result, Set<String> paths,
                                   JSONObject legacy, JSONObject subjects, JSONObject imports) throws Exception {
        File[] files = root.listFiles();
        if (files == null) return;
        List<File> sorted = new ArrayList<File>();
        Collections.addAll(sorted, files);
        Collections.sort(sorted);
        for (File file : sorted) {
            interrupted();
            if (!file.isFile() || !file.getName().endsWith(".json")) continue;
            if (paths.contains(file.getCanonicalPath())) continue;
            if (result.size() >= MAX_ROWS) throw new IOException("资料数量过多");
            try {
            JSONObject actual = summary(file, imports);
            String id = first(actual.optString("id"), basename(file));
            JSONObject saved = legacy.optJSONObject(id);
            if (saved == null && first(actual.optString("id")).length() == 0)
                saved = legacyForFile(legacy, file);
            Item item = item(file, mergeMetadata(saved, actual), subjects);
            // A resource-pack catalog is authoritative when an old duplicate remains.
            if (!result.containsKey(item.id)) result.put(item.id, item);
            paths.add(file.getCanonicalPath());
            } catch(Exception damaged) { issue(file,damaged,true); }
        }
    }

    public void remove(Item item) throws Exception {
        synchronized (IO_LOCK) {
            requireOpen();
            File source = allowedSource(item.file);
            // Removing a source must not read/hash its entire contents.
            NativeMaintenance.invalidate(context,NativeMaintenance.READER);
            if (item.metadata.optString("resourcePack", "").length() > 0) {
                ResourcePackManager.removeResourcePackItem(context, item.id);
            } else {
                boolean local = false;
                for (File root : new File[]{new File(dataRoot,"data/local"),oldLocalRoot,new File(dataRoot,"local-docs")})
                    if (source.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) local=true;
                if (!local) throw new IOException("此资料暂不支持删除");
                NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
                if (!source.delete() && source.exists()) throw new IOException("题库删除失败");
            }
            fingerprints.remove(source.getCanonicalPath());
            try { maintainCache(false); } catch(Exception cacheUnavailable) { interrupted(); }
        }
    }

    public Document open(Item item) throws Exception {
        synchronized (IO_LOCK) {
            requireOpen();
            if (item == null || item.file == null) throw new IOException("没有选择资料");
            File source = allowedSource(item.file);
            Fingerprint stamp = fingerprintForOpen(source);
            File index = index(source, stamp);
            JSONObject manifest = readObject(new File(index, "complete.json"), MAX_JSON_BYTES);
            JSONObject meta = mergeMetadata(item.metadata,
                    readObject(new File(index, "metadata.json"), MAX_JSON_BYTES));
            meta.put("n", manifest.getInt("questions"));
            meta.put("sec", manifest.getInt("sections"));
            meta.put("mat", manifest.getInt("materials"));
            meta.put("ans", manifest.getInt("answers"));
            Item fresh = item(source, meta, listMetadata(new File(libraryRoot, "subjects.json")));
            // Keep the caller's Item instance so later exports use the same identity.
            copyItem(fresh, item);
            Document document=new Document(context, item, dataRoot, index, manifest, item.metadata);
            if(inside(dataRoot,source))document.lease=ResourcePackStorage.retain(dataRoot,source);
            String path=index.getCanonicalPath();document.indexKey=path;OPEN_INDEXES.put(path,OPEN_INDEXES.containsKey(path)?OPEN_INDEXES.get(path)+1:1);
            try { maintainCache(false); } catch(Exception ignored) { }
            return document;
        }
    }

    /** Copy the source stream byte-for-byte, validate it, then publish a new source.
     * An existing ID with different contents is rejected, never silently overwritten.
     */
    public Item importDocument(Uri uri, String preferredSubject) throws Exception {
        synchronized (IO_LOCK) {
            requireOpen();
            if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme())))
                throw new IOException("请选择本地 JSON 文件");
            File local = new File(dataRoot, "data/local");
            mkdir(local);
            NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
            File temp = new File(local, ".native-import-" + UUID.randomUUID() + ".tmp");
            File validationIndex = null;
            try {
                InputStream opened = context.getContentResolver().openInputStream(uri);
                if (opened == null) throw new IOException("无法打开所选文件");
                try (InputStream in = opened; FileOutputStream out = new FileOutputStream(temp)) {
                    copyLimited(in, out, MAX_SOURCE_BYTES, null);
                    out.getFD().sync();
                }
                Fingerprint stamp = fingerprint(temp);
                validationIndex = index(temp, stamp);
                JSONObject meta = readObject(new File(validationIndex, "metadata.json"), MAX_JSON_BYTES);
                String id = first(meta.optString("id", ""));
                if (id.trim().length() == 0) id = "local-" + stamp.hash.substring(0, 24);
                if (id.length() > 280) throw new IOException("资料 ID 过长，无法兼容现有进度记录");
                for (Item existing : list()) {
                    if (!id.equals(existing.id)) continue;
                    if (!stamp.hash.equals(fingerprint(existing.file).hash))
                        throw new IOException("已有同 ID 的不同资料，未覆盖原文件：" + id);
                    if (validSubject(preferredSubject)) setSubject(existing, preferredSubject);
                    return existing;
                }
                String filename = ResourcePackManager.isValidLocalId(id) ? id : "id-" + hash(id);
                File destination = child(local, filename + ".json");
                if (destination.exists()) throw new IOException("目标文件已存在，未覆盖原文件");
                // Sources without IDs need an overlay because their bytes must remain intact.
                meta.put("id", id);
                JSONObject imports = readOptional(new File(libraryRoot, "import-items.json"));
                imports.put(destination.getName(), compactMetadata(meta));
                if (!temp.renameTo(destination)) throw new IOException("无法保存导入文件");
                try { writeAtomic(new File(libraryRoot, "import-items.json"), imports); }
                catch(Exception failedMetadata) {
                    // This file was just created by this transaction; existing files are never replaced.
                    if(!destination.delete())throw new IOException("导入索引未保存；新文件已保留，可从题库管理",failedMetadata);
                    throw failedMetadata;
                }
                fingerprints.put(destination.getCanonicalPath(),stamp);
                // Reuse the completed validation index on first open. Promotion is
                // optional: the immutable original remains usable if cache I/O fails.
                try {
                    JSONObject manifest = readObject(new File(validationIndex, "complete.json"), MAX_JSON_BYTES);
                    manifest.put("source", destination.getCanonicalPath());
                    writeJson(new File(validationIndex, "complete.json"), manifest, MAX_JSON_BYTES);
                    File promoted = new File(cacheRoot, cacheKey(destination, stamp));
                    if (!promoted.exists() && validationIndex.renameTo(promoted)) validationIndex = null;
                } catch (Exception cacheUnavailable) { interrupted(); }
                Item imported = item(destination, meta, new JSONObject());
                if (validSubject(preferredSubject)) setSubject(imported, preferredSubject);
                return imported;
            } finally {
                if (temp.exists()) temp.delete();
                if (validationIndex != null) deleteTree(validationIndex);
            }
        }
    }

    public void setSubject(Item item, String subject) throws Exception {
        synchronized (IO_LOCK) {
            requireOpen();
            if (item == null || item.id == null || !validSubject(subject))
                throw new IOException("科目请选择行测或申论");
            JSONObject subjects = readOptional(new File(libraryRoot, "subjects.json"));
            subjects.put(item.id, subject);
            writeAtomic(new File(libraryRoot, "subjects.json"), subjects);
            item.subject = subject;
            if (item.metadata == null) item.metadata = new JSONObject();
            item.metadata.put("subject", subject);
        }
    }

    @Override public void close() { closed = true; }
    private void requireOpen() throws IOException {
        if (closed) throw new IOException("资料库已关闭");
        interrupted();
    }

    public static final class Document implements Closeable {
        public final Item item;
        public final JSONObject metadata;
        private final File directory, resourceRoot;
        private final Context appContext;
        private final JSONObject manifest, figures;
        private final Set<String> formulas = new HashSet<String>();
        private volatile boolean closed;
        private Closeable lease;
        private String indexKey;

        private Document(Context appContext, Item item, File resourceRoot, File directory, JSONObject manifest, JSONObject metadata) throws Exception {
            this.appContext=appContext;
            this.item = item;
            this.resourceRoot = resourceRoot;
            this.directory = directory;
            this.manifest = manifest;
            this.metadata = metadata;
            figures = readObject(new File(directory, "figures.json"), MAX_JSON_BYTES);
            Object formula = metadata.opt("formulaImages");
            if (formula instanceof JSONObject) {
                Iterator<String> keys = ((JSONObject) formula).keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!((JSONObject) formula).optBoolean(key, false)) continue;
                    formulas.add(key);
                    formulas.add(figures.optString(key, key));
                }
            } else if (formula instanceof JSONArray) {
                JSONArray array = (JSONArray) formula;
                for (int i = 0; i < array.length(); i++) {
                    String key = array.optString(i);
                    formulas.add(key);
                    formulas.add(figures.optString(key, key));
                }
            }
        }

        public int questionCount() { return manifest.optInt("questions"); }
        public boolean hasFileImages() {
            for(Iterator<String> keys=figures.keys();keys.hasNext();) {
                String value=figures.optString(keys.next(),"");
                if(!value.startsWith("data:")&&!value.startsWith("@native:")&&imageExtension(value)) return true;
            }
            return false;
        }
        public int sectionCount() { return manifest.optInt("sections"); }
        public int materialCount() { return manifest.optInt("materials"); }
        public int explicitMaterialCount() { return manifest.optBoolean("legacyMaterials") ? 0 : materialCount(); }

        public JSONObject question(int index) throws Exception {
            requireOpen();
            checkIndex(index, questionCount());
            return readObject(new File(directory, "q-" + index + ".json"), MAX_RECORD_BYTES);
        }

        public JSONObject section(int index) throws Exception {
            requireOpen();
            checkIndex(index, sectionCount());
            return readObject(new File(directory, "s-" + index + ".json"), MAX_RECORD_BYTES);
        }

        /** Material browsing must not retain every long passage just to open one. */
        public JSONObject material(int index) throws Exception {
            requireOpen();
            checkIndex(index, materialCount());
            String prefix = manifest.optBoolean("legacyMaterials") ? "l-" : "m-";
            return readObject(new File(directory, prefix + index + ".json"), MAX_RECORD_BYTES);
        }

        public Object materialNumber(int index) throws Exception {
            requireOpen();
            checkIndex(index, materialCount());
            return manifest.getJSONArray("materialNumbers").get(index);
        }

        public int materialIndex(Object number) throws Exception {
            requireOpen();
            if (number == null || number == JSONObject.NULL) return -1;
            JSONArray numbers = manifest.getJSONArray("materialNumbers");
            for (int i = 0; i < numbers.length(); i++)
                if (String.valueOf(number).equals(String.valueOf(numbers.opt(i)))) return i;
            return -1;
        }

        public JSONObject sharedMaterial(int questionIndex) throws Exception {
            requireOpen();
            checkIndex(questionIndex, questionCount());
            JSONArray keys = manifest.optJSONArray("shared");
            if (keys == null || keys.isNull(questionIndex)) return null;
            String key = keys.getString(questionIndex);
            if (!key.matches("[0-9]{1,6}")) throw new IOException("材料索引损坏");
            return readObject(new File(directory, "g-" + key + ".json"), MAX_RECORD_BYTES);
        }

        /** Returns local bytes only. Relative references cannot escape source folders. */
        public File image(String reference) throws Exception {
            requireOpen();
            String ref = unwrapImage(reference);
            for (int i = 0; i < 8 && figures.has(ref); i++) {
                String next = figures.optString(ref, "");
                if (next.equals(ref)) break;
                ref = next;
            }
            if (ref.startsWith("@native:")) {
                String digest = ref.substring(8);
                if (!digest.matches("[a-f0-9]{64}")) return null;
                File image = new File(new File(directory, "images"), digest + ".img");
                return image.isFile() ? image : null;
            }
            // Normally extracted during indexing; retaining this fallback avoids
            // passing inline base64 to a renderer that accepts only File objects.
            if (ref.startsWith("data:image/")) {
                synchronized (IO_LOCK) {
                    NativeMaintenance.invalidate(appContext,NativeMaintenance.READER);
                    String saved = saveImage(directory, ref);
                    return new File(new File(directory, "images"), saved.substring(8) + ".img");
                }
            }
            if (ref.length() == 0 || ref.indexOf(':') >= 0 || ref.startsWith("/") || ref.startsWith("\\")) return null;
            File local;
            try { local = child(item.file.getParentFile(), ref); }
            catch (IOException unsafe) { return null; }
            if (local.isFile() && imageExtension(local.getName())) return local;
            if (ref.startsWith("data/")) {
                try { local = child(resourceRoot, ref); }
                catch (IOException unsafe) { return null; }
                if (local.isFile() && imageExtension(local.getName())) return local;
            }
            return null;
        }

        public boolean isFormula(String reference) {
            return formulas.contains(unwrapImage(reference));
        }

        @Override public void close() {
            synchronized(IO_LOCK) {
                if(closed)return;closed=true;
                try { if(lease!=null)lease.close(); } catch(IOException ignored) { }
                String path=indexKey;Integer count=OPEN_INDEXES.get(path);
                if(count!=null) { if(count<=1)OPEN_INDEXES.remove(path);else OPEN_INDEXES.put(path,count-1); }
            }
        }
        private void requireOpen() throws IOException {
            if (closed) throw new IOException("资料已关闭");
            interrupted();
        }
    }

    private File allowedSource(File source) throws Exception {
        File file = source.getCanonicalFile();
        if (!inside(dataRoot, file) && !inside(oldLocalRoot, file))
            throw new IOException("资料路径不在应用资料目录内");
        return file;
    }

    private JSONObject summary(File source, JSONObject imports) throws Exception {
        allowedSource(source);
        checkSize(source, MAX_SOURCE_BYTES);
        File small = new File(new File(cacheRoot, "summaries"), hash(source.getCanonicalPath()) + ".json");
        JSONObject cached = new JSONObject();
        if (small.isFile()) {
            try { cached = readOptional(small); }
            catch (Exception damagedCache) { interrupted(); }
        }
        JSONObject meta;
        if (cached.optLong("length", -1) == source.length()
                && cached.optLong("modified", -1) == source.lastModified()
                && cached.optInt("version") == CACHE_VERSION && cached.optJSONObject("metadata") != null) {
            meta = cached.getJSONObject("metadata");
        } else {
            Summary scan = new Summary();
            try (JsonReader reader = reader(source)) {
                scanDocument(reader, scan, 0);
                endDocument(reader);
            }
            meta = scan.finish();
            try {
                writeAtomic(small, new JSONObject().put("version", CACHE_VERSION)
                        .put("source",source.getCanonicalPath()).put("length", source.length()).put("modified", source.lastModified()).put("metadata", meta));
            } catch(Exception cacheUnavailable) { interrupted(); }
        }
        // Overlay is used only for imports whose original source had no ID/title.
        JSONObject imported = imports.optJSONObject(source.getName());
        JSONObject combined = mergeMetadata(imported, meta);
        if (first(combined.optString("id")).length() == 0 && imported != null)
            combined.put("id", imported.optString("id", ""));
        return combined;
    }

    private JSONObject legacyForFile(JSONObject legacy, File file) throws Exception {
        Iterator<String> ids = legacy.keys();
        String expected = file.getCanonicalPath();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject candidate = legacy.optJSONObject(id);
            if (candidate == null) continue;
            String path = candidate.optString("file", "");
            if (path.startsWith("local/")) path = "data/" + path;
            try {
                if (!child(dataRoot, path).getCanonicalPath().equals(expected)) continue;
            } catch (IOException invalidPath) { continue; }
            JSONObject matched = merge(null, candidate);
            if (first(matched.optString("id")).length() == 0) matched.put("id", id);
            return matched;
        }
        return null;
    }

    private static final class Summary {
        final JSONObject metadata = new JSONObject();
        final Map<String, Integer> depths = new HashMap<String, Integer>();
        int questions, sections, materials, answers;
        boolean choice, oldMaterial;
        JSONObject finish() throws Exception {
            metadata.put("n", questions).put("sec", sections).put("mat", materials).put("ans", answers);
            metadata.put("shape", shape(metadata, questions, sections, materials > 0 || oldMaterial, choice));
            return metadata;
        }
    }

    private static void scanDocument(JsonReader reader, Summary scan, int depth) throws Exception {
        if (depth > 8) throw new IOException("资料包装层级过深");
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            scanQuestions(reader, scan);
            return;
        }
        reader.beginObject();
        while (reader.hasNext()) {
            interrupted();
            String name = reader.nextName();
            if (isQuestions(name) && reader.peek() == JsonToken.BEGIN_ARRAY) scanQuestions(reader, scan);
            else if (("sections".equals(name) || "materials".equals(name)) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                int n = 0;
                reader.beginArray();
                while (reader.hasNext()) { reader.skipValue(); if (++n > MAX_ROWS) throw new IOException("资料条目过多"); }
                reader.endArray();
                if ("sections".equals(name)) scan.sections += n; else scan.materials += n;
            } else if (wrapper(name) && (reader.peek() == JsonToken.BEGIN_OBJECT || reader.peek() == JsonToken.BEGIN_ARRAY)) {
                scanDocument(reader, scan, depth + 1);
            } else if (metadataField(name)) {
                putAtDepth(scan.metadata, scan.depths, name, readValue(reader, null, 0, new int[2]), depth);
            } else reader.skipValue();
        }
        reader.endObject();
    }

    private static void scanQuestions(JsonReader reader, Summary scan) throws Exception {
        reader.beginArray();
        while (reader.hasNext()) {
            if (++scan.questions > MAX_ROWS) throw new IOException("题目数量过多");
            int answerIndex = -1, optionCount = 0;
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (("options".equals(name) || "oimgs".equals(name)) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray();
                    if (reader.hasNext()) scan.choice = true;
                    int options = 0;
                    while (reader.hasNext()) { reader.skipValue(); options++; }
                    reader.endArray();
                    optionCount = Math.max(optionCount, options);
                } else if ("answer".equals(name)) {
                    if (reader.peek() == JsonToken.STRING || reader.peek() == JsonToken.NUMBER) {
                        boolean numeric = reader.peek() == JsonToken.NUMBER;
                        String a = reader.nextString().trim();
                        if (a.matches("(?i)[A-H]")) answerIndex = "ABCDEFGH".indexOf(a.toUpperCase(java.util.Locale.US));
                        else if (numeric && a.matches("[0-7]")) answerIndex = Integer.parseInt(a);
                    } else reader.skipValue();
                } else if ("material".equals(name) || "mtext".equals(name) || "parentStem".equals(name) || "mimgs".equals(name)) {
                    if (reader.peek() == JsonToken.STRING) scan.oldMaterial |= reader.nextString().length() > 0;
                    else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                        scan.oldMaterial |= reader.hasNext();
                        while (reader.hasNext()) reader.skipValue();
                        reader.endArray();
                    } else reader.skipValue();
                } else reader.skipValue();
            }
            reader.endObject();
            if (answerIndex >= 0 && answerIndex < optionCount) scan.answers++;
        }
        reader.endArray();
    }

    private File index(File source, Fingerprint stamp) throws Exception {
        mkdir(cacheRoot);
        String key = cacheKey(source, stamp);
        File complete = new File(cacheRoot, key);
        if (new File(complete, "complete.json").isFile()) {
            try {
                JSONObject manifest = readObject(new File(complete, "complete.json"), MAX_JSON_BYTES);
                if (stamp.hash.equals(manifest.optString("sha256"))
                        && manifest.optInt("version") == CACHE_VERSION && completeIndex(complete, manifest)) return complete;
            } catch (Exception damagedCache) { interrupted(); }
        }
        NativeMaintenance.invalidate(context,NativeMaintenance.READER);
        File stage = new File(cacheRoot, ".stage-" + UUID.randomUUID());
        mkdir(stage);
        try {
            Builder builder = new Builder(stage);
            try (JsonReader reader = reader(source)) {
                parseDocument(reader, builder, 0);
                endDocument(reader);
            }
            if (source.length() != stamp.length || source.lastModified() != stamp.modified)
                throw new IOException("资料在读取时发生变化，请重新打开");
            // Check the consumed source again before publishing. A same-size replacement
            // with a preserved timestamp must not acquire the old cache identity.
            if (!stamp.hash.equals(fingerprint(source).hash))
                throw new IOException("资料在读取时发生变化，请重新打开");
            JSONObject manifest = builder.finish();
            manifest.put("version", CACHE_VERSION).put("source", source.getCanonicalPath())
                    .put("sha256", stamp.hash).put("length", stamp.length).put("modified", stamp.modified);
            writeJson(new File(stage, "metadata.json"), builder.metadata, MAX_JSON_BYTES);
            writeJson(new File(stage, "figures.json"), builder.figures, MAX_JSON_BYTES);
            // The marker is the final write. The directory becomes visible only once
            // all records and image files have been flushed and closed.
            writeJson(new File(stage, "complete.json"), manifest, MAX_JSON_BYTES);
            if (complete.exists()) deleteTree(complete);
            if (!stage.renameTo(complete)) throw new IOException("无法完成资料索引");
            return complete;
        } finally {
            if (stage.exists()) deleteTree(stage);
        }
    }

    private static final class Builder {
        final File directory;
        final JSONObject metadata = new JSONObject(), figures = new JSONObject();
        final Map<String, Integer> depths = new HashMap<String, Integer>();
        int questions, sections, materials, answers;
        int metadataChars;
        boolean choice;
        boolean sawQuestions, sawSections, sawMaterials;

        Builder(File directory) { this.directory = directory; }

        void rows(JsonReader reader, String name) throws Exception {
            boolean question = isQuestions(name), section = "sections".equals(name);
            if ((question && sawQuestions) || (section && sawSections) || (!question && !section && sawMaterials))
                throw new IOException("资料含多份同类内容，请分别导入");
            if (question) sawQuestions = true; else if (section) sawSections = true; else sawMaterials = true;
            int n = 0;
            reader.beginArray();
            while (reader.hasNext()) {
                interrupted();
                if (n >= (question || section ? MAX_ROWS : MAX_MATERIALS)) throw new IOException("资料条目过多");
                JSONObject value = object(readValue(reader, this, 0, new int[2]));
                if (!value.has("no") || value.isNull("no")) value.put("no", n + 1);
                // Do not renumber existing q.no, even for duplicate or nonnumeric IDs:
                // old answers/stars are keyed by that exact original value.
                if (question) {
                    normalizeQuestion(value);
                    if (isChoice(value)) choice = true;
                    if (hasAnswer(value)) answers++;
                } else if (!value.has("text") && value.has("content")) value.put("text", value.opt("content"));
                writeJson(new File(directory, (question ? "q-" : section ? "s-" : "m-") + n + ".json"), value, MAX_RECORD_BYTES);
                n++;
            }
            reader.endArray();
            if (question) questions = n; else if (section) sections = n; else materials = n;
        }

        JSONObject finish() throws Exception {
            if (questions == 0 && sections == 0 && materials == 0) throw new IOException("文件中没有题目或材料");
            JSONArray shared = new JSONArray();
            Map<String, Integer> materialNos = new HashMap<String, Integer>();
            for (int i = 0; i < materials; i++) {
                JSONObject material = readObject(new File(directory, "m-" + i + ".json"), MAX_RECORD_BYTES);
                String no = String.valueOf(material.opt("no"));
                if (!materialNos.containsKey(no)) materialNos.put(no, i);
            }
            Map<String, Group> groups = new LinkedHashMap<String, Group>();
            Map<String, Integer> oldMaterials = new LinkedHashMap<String, Integer>();
            List<Group> byQuestion = new ArrayList<Group>();
            for (int i = 0; i < questions; i++) {
                interrupted();
                JSONObject q = readObject(new File(directory, "q-" + i + ".json"), MAX_RECORD_BYTES);
                JSONArray parts = parts(q, materialNos);
                if (materials == 0 && (text(q.opt("material")).length() > 0 || length(q.optJSONArray("mimgs")) > 0)) {
                    JSONObject row = new JSONObject().put("text", q.opt("material") == null ? "" : q.opt("material"))
                            .put("imgs", array(q.optJSONArray("mimgs")));
                    String signature = hash(row.toString());
                    if (!oldMaterials.containsKey(signature)) {
                        int at = oldMaterials.size();
                        if (at >= MAX_MATERIALS) throw new IOException("资料材料过多");
                        JSONArray refs = q.optJSONArray("mats");
                        row.put("no", refs != null && refs.length() > 0 ? refs.opt(0) : at + 1);
                        writeJson(new File(directory, "l-" + at + ".json"), row, MAX_RECORD_BYTES);
                        oldMaterials.put(signature, at);
                    }
                }
                Group group = null;
                if (isChoice(q) && parts.length() > 0) {
                    String signature = hash(parts.toString());
                    group = groups.get(signature);
                    if (group == null) {
                        group = new Group(i);
                        groups.put(signature, group);
                        writeJson(new File(directory, "g-" + i + ".json"), joinedMaterial(parts, i), MAX_RECORD_BYTES);
                    }
                    group.count++;
                }
                byQuestion.add(group);
            }
            for (Group group : byQuestion) shared.put(group != null && group.count > 1 ? String.valueOf(group.first) : JSONObject.NULL);
            for (Group group : groups.values()) {
                File file = new File(directory, "g-" + group.first + ".json");
                if (group.count < 2) { file.delete(); continue; }
                JSONObject row = readObject(file, MAX_RECORD_BYTES);
                row.put("label", "共享材料 · " + group.count + " 题");
                writeJson(file, row, MAX_RECORD_BYTES);
            }
            int actualMaterials = materials == 0 ? oldMaterials.size() : materials;
            JSONArray materialNumbers = new JSONArray();
            String materialPrefix = materials == 0 ? "l-" : "m-";
            for (int i = 0; i < actualMaterials; i++) {
                JSONObject material = readObject(new File(directory, materialPrefix + i + ".json"), MAX_RECORD_BYTES);
                materialNumbers.put(material.opt("no"));
            }
            metadata.put("shape", shape(metadata, questions, sections, actualMaterials > 0, choice))
                    .put("n", questions).put("sec", sections).put("mat", actualMaterials).put("ans", answers);
            return new JSONObject().put("questions", questions).put("sections", sections)
                    .put("materials", actualMaterials).put("legacyMaterials", materials == 0 && actualMaterials > 0)
                    .put("materialNumbers", materialNumbers).put("answers", answers).put("shared", shared);
        }

        JSONArray parts(JSONObject q, Map<String, Integer> materialNos) throws Exception {
            JSONArray out = new JSONArray();
            String own = text(q.opt("material"));
            JSONArray ownImages = q.optJSONArray("mimgs"), refs = q.optJSONArray("mats");
            if (own.length() > 0 || length(ownImages) > 0) {
                out.put(new JSONObject().put("text", own).put("imgs", array(ownImages)));
            } else if (length(refs) > 0 && materials > 0) {
                for (int i = 0; i < refs.length(); i++) {
                    Integer at = materialNos.get(String.valueOf(refs.opt(i)));
                    if (at == null) continue;
                    JSONObject row = readObject(new File(directory, "m-" + at + ".json"), MAX_RECORD_BYTES);
                    out.put(new JSONObject().put("text", text(row.opt("text"))).put("imgs", array(row.optJSONArray("imgs"))));
                }
            } else if (text(q.opt("parentStem")).length() > 0) {
                out.put(new JSONObject().put("text", text(q.opt("parentStem"))).put("imgs", new JSONArray()));
            }
            return out;
        }

        String extract(String value) throws Exception {
            if (value.startsWith("data:image/") && value.indexOf(";base64,") > 0)
                return saveImage(directory, value);
            Matcher match = DATA_IMAGE.matcher(value);
            if (!match.find()) return value;
            StringBuffer replaced = new StringBuffer();
            do { match.appendReplacement(replaced, Matcher.quoteReplacement(saveImage(directory, match.group()))); }
            while (match.find());
            match.appendTail(replaced);
            return replaced.toString();
        }
    }

    private static final class Group {
        final int first;
        int count;
        Group(int first) { this.first = first; }
    }

    private static void parseDocument(JsonReader reader, Builder builder, int depth) throws Exception {
        if (depth > 8) throw new IOException("资料包装层级过深");
        if (reader.peek() == JsonToken.BEGIN_ARRAY) { builder.rows(reader, "questions"); return; }
        reader.beginObject();
        while (reader.hasNext()) {
            interrupted();
            String name = reader.nextName();
            JsonToken token = reader.peek();
            if ((isQuestions(name) || "sections".equals(name) || "materials".equals(name)) && token == JsonToken.BEGIN_ARRAY) {
                builder.rows(reader, name);
            } else if ("figs".equals(name) && token == JsonToken.BEGIN_OBJECT) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if (builder.figures.length() >= MAX_ROWS * 4) throw new IOException("图片数量过多");
                    String key = reader.nextName();
                    Object value = readValue(reader, builder, 0, new int[2]);
                    if (value instanceof String) builder.figures.put(key, value);
                    else if (value instanceof JSONObject) {
                        JSONObject obj = (JSONObject) value;
                        builder.figures.put(key, first(obj.optString("data"), obj.optString("src"), obj.optString("url")));
                    }
                }
                reader.endObject();
            } else if (wrapper(name) && (token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY)) {
                parseDocument(reader, builder, depth + 1);
            } else {
                // Retain original document metadata, including module/source IDs and
                // formulaImages. Content arrays and huge image strings live separately.
                Object value = readValue(reader, builder, 0, new int[2]);
                builder.metadataChars += value == null ? 0 : value.toString().length();
                if (builder.metadataChars > MAX_JSON_BYTES / 2) throw new IOException("资料元数据过大");
                putAtDepth(builder.metadata, builder.depths, name, value, depth);
            }
        }
        reader.endObject();
    }

    private static JSONObject joinedMaterial(JSONArray parts, int first) throws Exception {
        StringBuilder text = new StringBuilder();
        JSONArray images = new JSONArray();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            String body = part.optString("text", "");
            if (text.length() > 0) text.append("\n\n");
            text.append(body);
            JSONArray refs = part.optJSONArray("imgs");
            for (int j = 0; refs != null && j < refs.length(); j++) {
                String ref = refs.optString(j, "");
                if (ref.length() == 0 || !seen.add(ref)) continue;
                images.put(ref);
                if (!body.contains("[[img:" + ref + "]]")) text.append("\n[[img:").append(ref).append("]]");
            }
        }
        return new JSONObject().put("text", text.toString()).put("images", images)
                .put("key", String.valueOf(first)).put("label", "共享材料");
    }

    private static void normalizeQuestion(JSONObject q) throws Exception {
        if (!q.has("stem")) q.put("stem", text(q.opt("q")));
        else if (q.opt("stem") instanceof JSONArray) q.put("stem", text(q.opt("stem")));
        JSONArray options = q.optJSONArray("options");
        JSONArray optionImages = q.optJSONArray("oimgs");
        // Image-only options are still selectable answers. Older imports may omit
        // options entirely, or stop its text array before the last image choice.
        if (length(optionImages) > 0) {
            if (options == null) options = new JSONArray();
            while (options.length() < optionImages.length()) options.put("");
            q.put("options", options);
        }
        if (options != null) for (int i = 0; i < options.length(); i++) {
            Object option = options.opt(i);
            if (option instanceof String) {
                String value = ((String) option).trim();
                Matcher prefix = OPTION_PREFIX.matcher(value);
                if (prefix.matches() && prefix.group(1).trim().length() > 0)
                    options.put(i, prefix.group(1).trim());
            }
        }
        if (!q.has("material") && q.has("mtext")) q.put("material", q.opt("mtext"));
        if (q.has("img") && !q.isNull("img")) {
            JSONArray images = new JSONArray().put(q.opt("img"));
            JSONArray existing = q.optJSONArray("imgs");
            for (int i = 0; existing != null && i < existing.length(); i++) images.put(existing.opt(i));
            q.put("imgs", images);
        }
        Object answer = q.opt("answer");
        if (answer instanceof Number) {
            double number = ((Number) answer).doubleValue();
            if (number == Math.rint(number) && number >= 0 && number < 8)
                q.put("answer", String.valueOf("ABCDEFGH".charAt((int) number)));
            else if (!q.has("ref")) q.put("ref", String.valueOf(answer));
        } else if (answer instanceof String) {
            String a = ((String) answer).trim();
            if (a.matches("(?i)[A-H]")) q.put("answer", a.toUpperCase(java.util.Locale.US));
            else if (a.length() > 0 && !q.has("ref")) q.put("ref", a);
        }
    }

    private static boolean isChoice(JSONObject q) {
        return length(q.optJSONArray("options")) > 0 || length(q.optJSONArray("oimgs")) > 0;
    }

    private static boolean hasAnswer(JSONObject q) {
        String answer = q.optString("answer", "");
        int index = answer.length() == 1 ? "ABCDEFGH".indexOf(answer) : -1;
        return index >= 0 && index < length(q.optJSONArray("options"));
    }

    private static String shape(JSONObject meta, int questions, int sections, boolean material, boolean choice) {
        if (questions == 0 && sections > 0) return "note";
        if (!choice && (material || "shenlun".equals(meta.optString("shape")) || "申论".equals(meta.optString("subject")))) return "shenlun";
        return "drill";
    }

    private static Item item(File file, JSONObject meta, JSONObject subjects) throws Exception {
        Item item = new Item();
        item.file = file.getCanonicalFile();
        item.id = first(meta.optString("id"), basename(file));
        item.title = first(meta.optString("title"), meta.optString("name"), item.id);
        item.kind = "course".equals(meta.optString("kind")) ? "course" : "drill";
        item.shape = first(meta.optString("shape"), "drill");
        item.subject = first(subjects.optString(item.id), meta.optString("subject"),
                "shenlun".equals(item.shape) ? "申论" : "行测");
        if (!validSubject(item.subject)) item.subject = "shenlun".equals(item.shape) ? "申论" : "行测";
        item.course = first(meta.optString("course"), meta.optString("courseTitle"), meta.optString("group"));
        item.teacher = meta.optString("teacher", "");
        item.unit = first(meta.optString("unit"), meta.optString("category"));
        item.count = meta.optInt("n", meta.optInt("count", meta.optInt("questionCount", 0)));
        item.hasAnswers = meta.optInt("ans", 0) > 0 || meta.optBoolean("hasAnswers", false);
        item.metadata = merge(null, meta);
        item.metadata.put("id", item.id).put("title", item.title).put("kind", item.kind)
                .put("subject", item.subject).put("shape", item.shape);
        return item;
    }

    private static void copyItem(Item source, Item target) {
        target.id = source.id; target.title = source.title; target.kind = source.kind;
        target.subject = source.subject; target.course = source.course; target.teacher = source.teacher;
        target.unit = source.unit; target.shape = source.shape; target.count = source.count;
        target.hasAnswers = source.hasAnswers; target.file = source.file; target.metadata = source.metadata;
    }

    private static Object readValue(JsonReader reader, Builder images, int depth, int[] budget) throws Exception {
        if (depth > 40 || ++budget[0] > 100000) throw new IOException("JSON 条目层级或大小超限");
        interrupted();
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JSONObject value = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.length() > 4096) throw new IOException("JSON 字段名过长");
                    value.put(key, readValue(reader, images, depth + 1, budget));
                }
                reader.endObject();
                return value;
            }
            case BEGIN_ARRAY: {
                JSONArray value = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) value.put(readValue(reader, images, depth + 1, budget));
                reader.endArray();
                return value;
            }
            case STRING: {
                String value = reader.nextString();
                if (value.length() > MAX_STRING_CHARS) throw new IOException("单段文字或图片过大");
                String normalized = images == null ? value : images.extract(value);
                budget[1] += normalized.length();
                if (budget[1] > MAX_RECORD_BYTES) throw new IOException("单条 JSON 内容过大");
                return normalized;
            }
            case NUMBER: {
                String value = reader.nextString();
                if (value.length() > 128) throw new IOException("JSON 数字过长");
                try { return Long.valueOf(value); }
                catch (NumberFormatException integer) {
                    double number = Double.parseDouble(value);
                    if (Double.isInfinite(number) || Double.isNaN(number)) throw new IOException("无效 JSON 数字");
                    return number;
                }
            }
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new IOException("无效 JSON 内容");
        }
    }

    private static String saveImage(File directory, String data) throws Exception {
        int comma = data.indexOf(',');
        if (!data.startsWith("data:image/") || comma < 0 || !data.substring(0, comma).endsWith(";base64"))
            throw new IOException("仅支持内嵌 base64 图片");
        if (data.length() - comma > MAX_STRING_CHARS) throw new IOException("单张图片过大");
        File folder = new File(directory, "images");
        mkdir(folder);
        File temp = new File(folder, ".image-" + UUID.randomUUID());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            try (InputStream in = new Base64InputStream(new AsciiInput(data, comma + 1), Base64.DEFAULT);
                 FileOutputStream out = new FileOutputStream(temp)) {
                long count = copyLimited(in, out, MAX_IMAGE_BYTES, digest);
                if (count == 0) throw new IOException("图片为空");
                out.getFD().sync();
            }
            String hash = hex(digest.digest());
            File destination = new File(folder, hash + ".img");
            if (!destination.exists() && !temp.renameTo(destination)) throw new IOException("无法保存图片缓存");
            return "@native:" + hash;
        } finally { if (temp.exists()) temp.delete(); }
    }

    /** Decode a single scalar without an additional base64 byte[] allocation. */
    private static final class AsciiInput extends InputStream {
        final String value;
        int position;
        AsciiInput(String value, int position) { this.value = value; this.position = position; }
        @Override public int read() throws IOException {
            if (position == value.length()) return -1;
            char c = value.charAt(position++);
            if (c > 127) throw new IOException("无效 base64 图片");
            return c;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (position == value.length()) return -1;
            int count = Math.min(length, value.length() - position);
            for (int i = 0; i < count; i++) bytes[offset + i] = (byte) read();
            return count;
        }
    }

    private Fingerprint fingerprintForOpen(File source) throws Exception {
        String path=source.getCanonicalPath();Fingerprint cached=fingerprints.get(path);
        // Sources are immutable inside app-private storage. Always hash on first open
        // in a process, and hash again when a size/timestamp changes.
        if(cached!=null&&cached.length==source.length()&&cached.modified==source.lastModified())return cached;
        Fingerprint fresh=fingerprint(source);fingerprints.put(path,fresh);return fresh;
    }

    private static JSONObject compactMetadata(JSONObject meta) throws Exception {
        JSONObject result=new JSONObject();
        for(Iterator<String> keys=meta.keys();keys.hasNext();) {
            String key=keys.next();
            if(metadataField(key)||"n".equals(key)||"sec".equals(key)||"mat".equals(key)||"ans".equals(key))result.put(key,meta.get(key));
        }
        return result;
    }

    private void maintainCache(boolean force) throws Exception {
        if(!force&&!NativeMaintenance.needsWork(context,NativeMaintenance.READER))return;
        NativeMaintenance.invalidate(context,NativeMaintenance.READER);
        NativeMaintenance.completed(context,NativeMaintenance.READER,trimCache());
    }
    private boolean trimCache() throws Exception {
        if(!cacheRoot.isDirectory())return true;
        List<File> candidates=new ArrayList<>();long bytes=0;boolean complete=true;
        File[] children=cacheRoot.listFiles();if(children==null)return false;
        for(File entry:children) {
            interrupted();
            if(!entry.getCanonicalPath().startsWith(cacheRoot.getCanonicalPath()+File.separator)){complete=false;continue;}
            if(entry.getName().startsWith(".stage-")) { deleteTree(entry);continue; }
            if("summaries".equals(entry.getName())) {
                File[] summaries=entry.listFiles();if(summaries!=null)for(File small:summaries) {
                    try {
                        JSONObject stamp=readOptional(small);String path=stamp.optString("source","");
                        File source=path.length()==0?null:allowedSource(new File(path));
                        if((source==null||!source.isFile())&&!small.delete()&&small.exists())complete=false;
                    } catch(Exception damaged) { interrupted();if(!small.delete()&&small.exists())complete=false; }
                }
                continue;
            }
            if(!entry.isDirectory()||!entry.getName().matches("[0-9a-f]{64}"))continue;
            boolean pinned=OPEN_INDEXES.containsKey(entry.getCanonicalPath()),valid=false;
            try {
                JSONObject manifest=readObject(new File(entry,"complete.json"),MAX_JSON_BYTES);
                File source=allowedSource(new File(manifest.getString("source")));
                valid=source.isFile()&&source.length()==manifest.optLong("length",-1)
                        &&source.lastModified()==manifest.optLong("modified",-1)&&manifest.optInt("version")==CACHE_VERSION;
                Fingerprint known=fingerprints.get(source.getCanonicalPath());
                if(known!=null&&!known.hash.equals(manifest.optString("sha256")))valid=false;
            } catch(Exception damaged) { interrupted(); }
            if(!pinned&&!valid) { deleteTree(entry);continue; }
            if(pinned&&!valid)complete=false;
            bytes+=treeBytes(entry);if(!pinned)candidates.add(entry);
        }
        Collections.sort(candidates,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(File entry:candidates) {
            if(bytes<=32L*1024*1024)break;
            long size=treeBytes(entry);deleteTree(entry);bytes-=size;
        }
        return complete&&bytes<=32L*1024*1024;
    }
    private static long treeBytes(File file) {
        if(!file.isDirectory())return file.length();
        File[] children=file.listFiles();long bytes=0;
        if(children!=null)for(File child:children)bytes+=treeBytes(child);
        return bytes;
    }

    private static final class Fingerprint {
        String hash;
        long length, modified;
    }

    private static String cacheKey(File source, Fingerprint stamp) throws Exception {
        return hash(source.getCanonicalPath() + "\n" + stamp.hash + "\n" + CACHE_VERSION);
    }

    private static Fingerprint fingerprint(File file) throws Exception {
        checkSize(file, MAX_SOURCE_BYTES);
        Fingerprint result = new Fingerprint();
        result.length = file.length(); result.modified = file.lastModified();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32768];
            int n;
            long count = 0;
            while ((n = input.read(buffer)) != -1) {
                interrupted();
                count += n;
                if (count > MAX_SOURCE_BYTES) throw new IOException("资料超过 128 MiB");
                digest.update(buffer, 0, n);
            }
            if (count != result.length || file.length() != result.length || file.lastModified() != result.modified)
                throw new IOException("资料在读取时发生变化");
        }
        result.hash = hex(digest.digest());
        return result;
    }

    private static long copyLimited(InputStream input, FileOutputStream output, long limit, MessageDigest digest) throws Exception {
        byte[] buffer = new byte[16384];
        long total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            interrupted();
            total += n;
            if (total > limit) throw new IOException("文件或图片超过本机大小限制");
            output.write(buffer, 0, n);
            if (digest != null) digest.update(buffer, 0, n);
        }
        return total;
    }

    private static JsonReader reader(File file) throws IOException {
        JsonReader reader = new JsonReader(new BoundedJsonReader(new InputStreamReader(
                new BufferedInputStream(new FileInputStream(file)), UTF8)));
        reader.setLenient(false);
        return reader;
    }

    private static JSONObject readObject(File file, long limit) throws Exception {
        checkSize(file, limit);
        try (JsonReader reader = reader(file)) {
            JSONObject value = object(readValue(reader, null, 0, new int[2]));
            endDocument(reader);
            return value;
        }
    }

    private static JSONObject readOptional(File file) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return new JSONObject();
        // openRead restores a backup left by an interrupted AtomicFile write.
        try (InputStream input = atomic.openRead();
             JsonReader reader = new JsonReader(new BoundedJsonReader(new InputStreamReader(input, UTF8)))) {
            checkSize(file, MAX_JSON_BYTES);
            JSONObject value = object(readValue(reader, null, 0, new int[2]));
            endDocument(reader);
            return value;
        }
    }

    private static void writeAtomic(File file, JSONObject value) throws Exception {
        mkdir(file.getParentFile());
        String json = value.toString();
        if (json.length() > MAX_JSON_BYTES / 2) throw new IOException("资料元数据过大");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            OutputStreamWriter writer = new OutputStreamWriter(stream, UTF8);
            writer.write(json); writer.flush();
            atomic.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) atomic.failWrite(stream);
            throw error;
        }
    }

    /** Bound a token before JsonReader allocates its String, including skipped fields. */
    private static final class BoundedJsonReader extends FilterReader {
        private boolean quoted, escaped;
        private int scalarChars, depth;
        BoundedJsonReader(Reader input) { super(input); }
        private void inspect(char c) throws IOException {
            if (quoted) {
                if (++scalarChars > MAX_STRING_CHARS) throw new IOException("单段文字或图片过大");
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') { quoted = false; scalarChars = 0; }
            } else if (c == '"') { quoted = true; scalarChars = 0; }
            else if (c == '{' || c == '[') {
                scalarChars = 0;
                if (++depth > 48) throw new IOException("JSON 层级过深");
            } else if (c == '}' || c == ']') { depth--; scalarChars = 0; }
            else if (Character.isWhitespace(c) || c == ',' || c == ':') scalarChars = 0;
            else if (++scalarChars > 128) throw new IOException("JSON 值过长");
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1) inspect((char) value);
            return value;
        }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            interrupted();
            int count = super.read(buffer, offset, length);
            for (int i = 0; i < count; i++) inspect(buffer[offset + i]);
            return count;
        }
    }

    private static boolean completeIndex(File directory, JSONObject manifest) throws Exception {
        if (!new File(directory, "metadata.json").isFile() || !new File(directory, "figures.json").isFile()) return false;
        int questions = manifest.getInt("questions"), sections = manifest.getInt("sections"), materials = manifest.getInt("materials");
        if (questions < 0 || sections < 0 || materials < 0 || questions > MAX_ROWS || sections > MAX_ROWS || materials > MAX_MATERIALS) return false;
        JSONArray materialNumbers = manifest.optJSONArray("materialNumbers");
        if (materialNumbers == null || materialNumbers.length() != materials) return false;
        for (int i = 0; i < questions; i++) if (!new File(directory, "q-" + i + ".json").isFile()) return false;
        for (int i = 0; i < sections; i++) if (!new File(directory, "s-" + i + ".json").isFile()) return false;
        String prefix = manifest.optBoolean("legacyMaterials") ? "l-" : "m-";
        for (int i = 0; i < materials; i++) if (!new File(directory, prefix + i + ".json").isFile()) return false;
        JSONArray shared = manifest.optJSONArray("shared");
        if (shared == null || shared.length() != questions) return false;
        for (int i = 0; i < shared.length(); i++) if (!shared.isNull(i)) {
            String key = shared.getString(i);
            if (!key.matches("[0-9]{1,6}") || !new File(directory, "g-" + key + ".json").isFile()) return false;
        }
        return true;
    }

    private static void writeJson(File file, JSONObject value, int limit) throws Exception {
        String json = value.toString();
        byte[] bytes = json.getBytes(UTF8);
        if (bytes.length > limit) throw new IOException("单条资料文本过大，请拆分后导入");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes); out.getFD().sync();
        }
    }

    private static JSONObject object(Object value) throws IOException {
        if (!(value instanceof JSONObject)) throw new IOException("JSON 条目必须是对象");
        return (JSONObject) value;
    }

    private static JSONObject merge(JSONObject lower, JSONObject upper) throws Exception {
        JSONObject result = new JSONObject();
        if (lower != null) {
            Iterator<String> keys = lower.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, lower.opt(key)); }
        }
        if (upper != null) {
            Iterator<String> keys = upper.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, upper.opt(key)); }
        }
        return result;
    }

    private static JSONObject mergeMetadata(JSONObject lower, JSONObject upper) throws Exception {
        JSONObject result = merge(lower, upper);
        if (lower == null) return result;
        // Empty optional labels in old JSON must not erase a useful migrated title,
        // course grouping or identity. Counts/modules still come from the source.
        for (String field : new String[]{"id", "title", "name", "kind", "subject", "course", "courseTitle", "group", "teacher", "unit", "category"}) {
            if (first(result.optString(field)).length() == 0 && first(lower.optString(field)).length() > 0)
                result.put(field, lower.get(field));
        }
        return result;
    }

    private static void putAtDepth(JSONObject object, Map<String, Integer> depths,
                                   String key, Object value, int depth) throws Exception {
        Integer previous = depths.get(key);
        if (previous == null || depth >= previous) {
            object.put(key, value); depths.put(key, depth);
        }
    }

    private static boolean metadataField(String name) {
        return ("|id|title|name|kind|shape|subject|course|courseTitle|group|teacher|unit|category|date|pages|collectionPath|officialOrder|source|")
                .contains("|" + name + "|");
    }
    private static boolean wrapper(String name) {
        return "data".equals(name) || "doc".equals(name) || "document".equals(name)
                || "paper".equals(name) || "result".equals(name);
    }
    private static boolean isQuestions(String name) { return "questions".equals(name) || "questionList".equals(name); }
    private static boolean validSubject(String value) { return "行测".equals(value) || "申论".equals(value); }
    private static int length(JSONArray value) { return value == null ? 0 : value.length(); }
    private static JSONArray array(JSONArray value) { return value == null ? new JSONArray() : value; }
    private static String first(String... values) {
        for (String value : values) if (value != null && value.length() > 0 && !"null".equals(value)) return value;
        return "";
    }
    private static String text(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONArray) {
            StringBuilder joined = new StringBuilder();
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) joined.append('\n');
                joined.append(text(array.opt(i)));
            }
            return joined.toString();
        }
        return String.valueOf(value);
    }
    private static String unwrapImage(String reference) {
        String value = reference == null ? "" : reference.trim();
        if (value.startsWith("[[img:") && value.endsWith("]]")) return value.substring(6, value.length() - 2);
        return value;
    }
    private static boolean imageExtension(String value) {
        return value.toLowerCase(java.util.Locale.US).matches(".*\\.(png|jpe?g|gif|webp|bmp|img)$");
    }
    private static String basename(File file) {
        String name = file.getName();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
    private static void checkIndex(int index, int count) throws IOException {
        if (index < 0 || index >= count) throw new IOException("题目或章节位置超出范围");
    }
    private static void endDocument(JsonReader reader) throws Exception {
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("JSON 文件尾部存在多余内容");
    }
    private static void checkSize(File file, long limit) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("资料不存在或为空");
        if (file.length() > limit) throw new IOException("资料文件超过本机大小限制");
    }
    private static void mkdir(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("无法创建资料缓存目录");
    }
    private static File child(File root, String relative) throws IOException {
        if (relative == null || relative.length() == 0 || relative.indexOf(':') >= 0 || relative.indexOf('\0') >= 0)
            throw new IOException("资料路径无效");
        String normalized = relative.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.startsWith("/")) throw new IOException("资料路径无效");
        for (String part : normalized.split("/")) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)) throw new IOException("资料路径越界");
        }
        File result = new File(root, normalized).getCanonicalFile();
        if (!inside(root, result)) throw new IOException("资料路径越界");
        return result;
    }
    private static boolean inside(File root, File file) throws IOException {
        return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }
    private static String hash(String value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF8)));
    }
    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = alphabet[(bytes[i] >>> 4) & 15];
            out[2 * i + 1] = alphabet[bytes[i] & 15];
        }
        return new String(out);
    }
    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消");
    }
    /** Called only for this class's generated cache/staging paths, never sources. */
    private static void deleteTree(File directory) throws IOException {
        File canonical = directory.getCanonicalFile();
        File[] children = canonical.listFiles();
        if (children != null) for (File child : children) {
            // Refuse a symlink escape even though this is app-private cache storage.
            if (!inside(canonical, child)) throw new IOException("缓存路径越界");
            if (child.isDirectory()) deleteTree(child);
            else if (!child.delete() && child.exists()) throw new IOException("无法清理临时缓存");
        }
        if (!canonical.delete() && canonical.exists()) throw new IOException("无法清理临时缓存");
    }
}


