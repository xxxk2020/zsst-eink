package com.xxxk2020.zssteink;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONTokener;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.OutputStream;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Validates and installs a zsst-resource-pack v1 zip into app-private storage. */
final class ResourcePackManager {
    static final String FORMAT = "zsst-resource-pack";
    static final int VERSION = 1;
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 350L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 5000;

    private ResourcePackManager() {}

    private static final String IMPORT_DIAG_PREFS = "zsst_resource_pack_import_diagnostics";

    private static final ImportCheckpoint IMPORT_CHECKPOINT = new ImportCheckpoint();

    static void recordImportStage(Context context, String stage) {
        recordImportStage(context, stage, true);
    }

    private static synchronized void recordImportStage(Context context, String stage, boolean force) {
        if (!IMPORT_CHECKPOINT.shouldWrite(SystemClock.elapsedRealtime(), force)) return;
        try {
            context.getSharedPreferences(IMPORT_DIAG_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("active", true)
                    .putString("stage", stage)
                    .commit();
        } catch (Exception ignored) {
            // Diagnostics must never prevent an import from proceeding.
        }
    }

    static synchronized void clearImportDiagnostic(Context context) {
        IMPORT_CHECKPOINT.reset();
        try {
            context.getSharedPreferences(IMPORT_DIAG_PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        } catch (Exception ignored) {}
    }

    static File dataRoot(Context context) {
        return new File(context.getFilesDir(), "resource-data");
    }

    static void ensureCatalog(Context context) throws IOException {
        File root = dataRoot(context);
        File data = new File(root, "data");
        if (!data.exists() && !data.mkdirs()) throw new IOException("无法创建资源目录");
        File catalog = new File(data, "items.json");
        if (!catalog.isFile()) writeAtomic(catalog, "[]\n".getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    /** Unchanged startup reads receipts and shallow directory metadata, never paper payloads. */
    static void prepareCatalog(Context context) throws Exception {
        synchronized(PACK_LOCK) {
            ensureCatalog(context);
            File[] cached=context.getCacheDir().listFiles();
            boolean interrupted=context.getSharedPreferences(IMPORT_DIAG_PREFS,Context.MODE_PRIVATE).getBoolean("active",false);
            if(cached!=null)for(File file:cached)if(file.getName().matches("zsst-pack-[0-9a-f-]{36}"))interrupted=true;
            if(interrupted||NativeMaintenance.needsWork(context,NativeMaintenance.RESOURCES)) {
                cleanupNativeCatalog(context);
                if(cached!=null)for(File file:cached)if(file.getName().matches("zsst-pack-[0-9a-f-]{36}"))deleteRecursively(file);
                clearImportDiagnostic(context);
            }
        }
    }
    static void cleanupNativeCatalog(Context context) throws Exception {
        synchronized(PACK_LOCK) {
            NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
            NativeMaintenance.invalidate(context,NativeMaintenance.READER);
            File root=dataRoot(context);
            // Read and validate the whole catalog before deleting any payloads.
            LinkedHashMap<String,JSONObject> catalog=readCatalog(new File(root,"data/items.json"));
            Set<String> references=new HashSet<>(),conservative=new HashSet<>();
            boolean temporaryFilesCleared=true;
            for(JSONObject item:catalog.values()) {
                String path=safeDataPath(item.getString("file"));references.add(path);
                try { collectPictures(child(root,path),path,references); }
                catch(Exception damaged) {
                    // If a document cannot be inspected, preserve its entire generation.
                    int marker=path.indexOf("/.zsst-generation-"),end=marker<0?-1:path.indexOf('/',marker+1);
                    conservative.add(end<0?path.substring(0,path.lastIndexOf('/')+1):path.substring(0,end+1));
                }
            }
            // Standalone documents can also point to shared assets by a data/... path.
            for(File directory:new File[]{new File(root,"data/local"),new File(root,"local-docs"),new File(context.getFilesDir(),"local-docs")}) {
                File[] locals=directory.listFiles();if(locals==null)continue;
                for(File source:locals)if(source.isFile()) {
                    if(source.getName().matches("[.]native-import-[0-9a-f-]{36}[.]tmp")) {
                        if(!source.delete()&&source.exists())temporaryFilesCleared=false;
                    } else if(source.getName().endsWith(".json")) {
                        try { collectPictures(source,"data/local/"+source.getName(),references); }
                        catch(Exception unreadable) { conservative.add("data/courses/");conservative.add("data/papers/"); }
                    }
                }
            }
            if(Thread.currentThread().isInterrupted())throw new IOException("操作已取消");
            boolean complete=ResourcePackStorage.cleanupExact(root,references,conservative);
            NativeMaintenance.completed(context,NativeMaintenance.RESOURCES,complete&&temporaryFilesCleared);
        }
    }
    private static final java.util.regex.Pattern HTML_IMAGE=java.util.regex.Pattern.compile(
            "<img[^>]*\\bsrc\\s*=\\s*['\"]([^'\"]+)['\"]",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static void collectPictures(File source,String path,Set<String> references) throws Exception {
        if(!source.isFile()||source.length()>MAX_FILE_BYTES)throw new IOException("资料不可读取");
        try(JsonReader reader=NativeProgressJson.open(new FileInputStream(source),(int)MAX_FILE_BYTES)) {
            collectPictureValues(reader,path,references,0,new int[1]);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("资料末尾无效");
        }
    }
    private static void collectPictureValues(JsonReader reader,String path,Set<String> references,int depth,int[] nodes) throws Exception {
        if(Thread.currentThread().isInterrupted())throw new IOException("操作已取消");
        if(depth>24||++nodes[0]>300000)throw new IOException("资料结构过大");
        if(reader.peek()==JsonToken.BEGIN_OBJECT) {
            reader.beginObject();while(reader.hasNext()) { reader.nextName();collectPictureValues(reader,path,references,depth+1,nodes); }reader.endObject();
        } else if(reader.peek()==JsonToken.BEGIN_ARRAY) {
            reader.beginArray();while(reader.hasNext())collectPictureValues(reader,path,references,depth+1,nodes);reader.endArray();
        } else if(reader.peek()==JsonToken.STRING) {
            String value=reader.nextString();
            if(value.startsWith("data:image/"))return;
            java.util.regex.Matcher html=HTML_IMAGE.matcher(value);
            while(html.find())addPictureReference(path,html.group(1),references);
            if(value.matches("(?i).*[.](jpg|jpeg|png|webp|gif|bmp)"))addPictureReference(path,value,references);
        } else reader.skipValue();
    }
    private static void addPictureReference(String path,String ref,Set<String> references) throws IOException {
        if(ref.startsWith("data:")||ref.indexOf(':')>=0)return;
        if(ref.startsWith("data/"))references.add(safeDataPath(ref));
        else if(path.startsWith("data/courses/")||path.startsWith("data/papers/"))references.add(safeDataPath(path.substring(0,path.lastIndexOf('/')+1)+ref));
    }

    /** 导入、删除、分类与进度索引共用锁，防止后台任务相互覆盖。 */
    private static final Object PACK_LOCK = NativeStorageLock.LOCK;

    // Package-private I/O boundary for deterministic host-side failure injection. The
    // public app path always uses the real filesystem implementation below.
    interface ImportIo {
        String stage(ResourcePackStorage generation, File source, String path) throws IOException;
        void publish(File catalog, LinkedHashMap<String, JSONObject> items)
                throws IOException, JSONException;
    }

    static final ImportIo FILE_IO = new ImportIo() {
        @Override public String stage(ResourcePackStorage generation, File source, String path)
                throws IOException {
            return generation.stage(source, path);
        }
        @Override public void publish(File catalog, LinkedHashMap<String, JSONObject> items)
                throws IOException, JSONException {
            writeAtomicCatalog(catalog, items);
        }
    };

    static JSONObject importPack(Context context, Uri uri) throws Exception {
        return importPack(context, uri, FILE_IO);
    }

    static JSONObject importPack(Context context, Uri uri, ImportIo io) throws Exception {
        synchronized (PACK_LOCK) { return importPackLocked(context, uri, io); }
    }

    private static JSONObject importPackLocked(Context context, Uri uri, ImportIo io) throws Exception {
        recordImportStage(context, "准备本地资源目录");
        ensureCatalog(context);
        File stage = new File(context.getCacheDir(), "zsst-pack-" + UUID.randomUUID().toString());
        if (!stage.mkdirs()) throw new IOException("无法创建导入临时目录");
        try {
            recordImportStage(context, "解压 ZIP 资源包");
            extractZip(context, context.getContentResolver(), uri, stage);

            File manifestFile = new File(stage, "resource-pack.json");
            if (!manifestFile.isFile()) throw new IOException("资源包缺少根目录 resource-pack.json");
            recordImportStage(context, "流式读取资源包清单");
            PackManifest manifest = readPackManifest(manifestFile, stage);
            String packId = manifest.packId;
            String title = manifest.title;
            LinkedHashMap<String, FileRecord> declaredFiles = manifest.files;
            List<JSONObject> incomingItems = manifest.items;
            if (declaredFiles.isEmpty() || incomingItems.isEmpty()) {
                throw new IOException("资源包没有课程清单或数据文件");
            }

            recordImportStage(context, "校验资源文件大小与 SHA-256");
            for (FileRecord rec : declaredFiles.values()) {
                File disk = rec.file;
                if (!disk.isFile()) throw new IOException("资源包缺少文件：" + rec.path);
                if (disk.length() != rec.size) throw new IOException("文件大小校验失败：" + rec.path);
                if (!rec.hash.equals(sha256(disk))) throw new IOException("文件校验失败：" + rec.path);
            }

            // Reject payload files that were not declared by the manifest.
            recordImportStage(context, "核对 ZIP 内的文件清单");
            for (String found : listDataJson(stage)) {
                if (!declaredFiles.containsKey(found)) throw new IOException("清单未声明资源文件：" + found);
            }

            recordImportStage(context, "读取本机已安装资料清单");
            LinkedHashMap<String, JSONObject> existing =
                    readCatalog(new File(dataRoot(context), "data/items.json"));
            Map<String, String> pathOwners = new HashMap<String, String>();
            for (JSONObject old : existing.values()) {
                String f = old.optString("sourceFile", old.optString("file", ""));
                String id = old.optString("id", "");
                if (f.length() > 0) pathOwners.put(f, id);
            }

            Set<String> seenIds = new HashSet<String>();
            Map<String, String> incomingPathOwners = new HashMap<String, String>();
            int added = 0;
            int updated = 0;
            JSONArray changedIds = new JSONArray();
            for (int i = 0; i < incomingItems.size(); i++) {
                JSONObject src = incomingItems.get(i);
                String id = src.optString("id", "");
                String kind = src.optString("kind", "");
                String file = safeDataPath(src.optString("file", ""));
                if (id.length() == 0 || id.length() > 240 || id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0) {
                    throw new IOException("清单中存在不合法的资料 ID");
                }
                if (!"course".equals(kind) && !"drill".equals(kind)) throw new IOException("不支持的资料类型：" + kind);
                if (!file.endsWith(".json")) throw new IOException("资料必须引用 JSON 文件");
                if (!declaredFiles.containsKey(file)) throw new IOException("资料引用了未登记文件：" + file);
                if (!seenIds.add(id)) throw new IOException("资源包内存在重复 ID：" + id);
                String priorOwner = pathOwners.get(file);
                if (priorOwner != null && !priorOwner.equals(id)) throw new IOException("文件路径与已安装资料冲突：" + file);
                String packOwner = incomingPathOwners.get(file);
                if (packOwner != null && !packOwner.equals(id)) throw new IOException("资源包内多个资料共用同一文件：" + file);
                incomingPathOwners.put(file, id);

                FileRecord rec = declaredFiles.get(file);
                // Persist a checkpoint before parsing large JSON files; after a process death
                // the next launch can show where the previous import stopped.
                if (rec.size >= 1024L * 1024L || i % 10 == 0) {
                    recordImportStage(context, "校验课程数据 " + (i + 1) + "/" + incomingItems.size() +
                            "：" + new File(file).getName(), false);
                }
                DocumentIdentity identity = readDocumentIdentity(rec.file);
                validatePictureReferences(rec.file, file, declaredFiles);
                if (!id.equals(identity.id)) throw new IOException("资料 ID 与 JSON 本体不一致：" + file);
                if (!kind.equals(identity.kind)) {
                    throw new IOException("资料类型与 JSON 本体不一致：" + file);
                }
                // Reuse the item parsed by the streaming manifest reader; do not stringify/reparse it.
                src.put("resourcePack", packId);
                if (existing.containsKey(id)) updated++; else added++;
                changedIds.put(id);
            }

            // Immutable generation paths keep every file referenced by the old catalog intact.
            // On process death, startup GC removes generations absent from the active catalog.
            recordImportStage(context, "写入资源包新版本");
            File root = dataRoot(context);
            long stagedBytes=0;for(FileRecord rec:declaredFiles.values())stagedBytes+=rec.size;
            ResourcePackStorage.requireSpace(root,stagedBytes);
            NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
            try (ResourcePackStorage generation = new ResourcePackStorage(root)) {
                Map<String, String> installedPaths = new HashMap<String, String>();
                for (FileRecord rec : declaredFiles.values()) {
                    recordImportStage(context, "写入资源文件：" + new File(rec.path).getName(), false);
                    installedPaths.put(rec.path, io.stage(generation, rec.file, rec.path));
                }
                for (JSONObject item : incomingItems) {
                    String sourceFile = item.getString("file");
                    item.put("sourceFile", sourceFile);
                    item.put("file", installedPaths.get(sourceFile));
                    existing.put(item.getString("id"), item);
                }
                // Construct the response before publication, leaving no fallible JSON work after it.
                JSONObject result = new JSONObject();
                result.put("ok", true);
                result.put("packId", packId);
                result.put("title", title);
                result.put("added", added);
                result.put("updated", updated);
                result.put("total", incomingItems.size());
                result.put("items", changedIds);
                recordImportStage(context, "提交资源包清单");
                io.publish(new File(root, "data/items.json"), existing);
                generation.markPublished();
                clearImportDiagnostic(context);
                return result;
            }
        } finally {
            deleteRecursively(stage);
        }
    }

    /** Remove one resource-pack paper from the local catalog. */
    static JSONObject removeResourcePackItem(Context context, String id) throws Exception {
        synchronized (PACK_LOCK) { return removeResourcePackItemLocked(context, id); }
    }

    private static JSONObject removeResourcePackItemLocked(Context context, String id) throws Exception {
        ensureCatalog(context);
        if (id == null || id.length() == 0 || id.length() > 240 ||
                id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0) {
            throw new IOException("试卷 ID 不合法");
        }

        File root = dataRoot(context);
        File catalog = new File(root, "data/items.json");
        LinkedHashMap<String, JSONObject> existing = readCatalog(catalog);
        JSONObject target = existing.get(id);
        if (target == null) throw new IOException("试卷不存在或已被删除");
        if (!"drill".equals(target.optString("kind", "")) && !"course".equals(target.optString("kind", ""))) {
            throw new IOException("不支持的资料类型");
        }
        if (target.optString("resourcePack", "").trim().length() == 0) {
            throw new IOException("这不是资源包中的本地试卷");
        }

        String relative = safeDataPath(target.optString("file", ""));
        if (!(relative.startsWith("data/papers/") || relative.startsWith("data/courses/")) || !relative.endsWith(".json")) {
            throw new IOException("试卷数据路径不合法");
        }
        File payload = child(root, relative);

        boolean referenced = false;
        for (Map.Entry<String, JSONObject> entry : existing.entrySet()) {
            if (id.equals(entry.getKey())) continue;
            if (relative.equals(entry.getValue().optString("file", ""))) {
                referenced = true;
                break;
            }
        }

        NativeMaintenance.invalidate(context,NativeMaintenance.RESOURCES);
        existing.remove(id);
        writeAtomicCatalog(catalog, existing);

        boolean cleanupFailed = false;
        if (!referenced && payload.exists() && (!payload.isFile() || !payload.delete())) {
            cleanupFailed = true;
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("id", id);
        result.put("cleanupFailed", cleanupFailed);
        return result;
    }

    private static void extractZip(Context context, ContentResolver resolver, Uri uri, File stage) throws IOException {
        String currentEntry = "打开所选 ZIP 文件";
        try {
            recordImportStage(context, currentEntry, false);
            InputStream raw = resolver.openInputStream(uri);
            if (raw == null) throw new IOException("无法读取所选文件");
            long total = 0;
            int count = 0;
            Set<String> names = new HashSet<String>();
            // Small buffers are intentional for very low-memory Android devices.
            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(raw, 8192))) {
                ZipEntry entry;
                byte[] buf = new byte[8192];long spaceCheckpoint=0;
                ResourcePackStorage.requireSpace(stage,1024*1024);
                while ((entry = zis.getNextEntry()) != null) {
                    if (++count > MAX_ENTRIES) throw new IOException("资源包内文件数量过多");
                    String name = safeZipPath(entry.getName());
                    currentEntry = "解压 ZIP 第 " + count + " 项：" + shortEntryName(name);
                    recordImportStage(context, currentEntry, false);
                    if (entry.isDirectory()) {
                        File dir = child(stage, name);
                        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建临时目录");
                        zis.closeEntry();
                        continue;
                    }
                    if (!"resource-pack.json".equals(name) && !(name.startsWith("data/") && name.matches(".*\\.(json|jpg|jpeg|png|webp|gif|bmp)"))) {
                        throw new IOException("资源包包含不允许的文件：" + name);
                    }
                    if (!names.add(name)) throw new IOException("资源包存在重复路径：" + name);
                    if ("resource-pack.json".equals(name) && entry.getSize() > 8L * 1024L * 1024L) {
                        throw new IOException("资源包清单超过 8 MiB");
                    }
                    File target = child(stage, name);
                    File parent = target.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("无法创建资源目录");
                    long entryBytes = 0;
                    long nextCheckpoint = 4L * 1024L * 1024L;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 8192)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) {
                            entryBytes += n;
                            total += n;
                            if(total>=spaceCheckpoint) {
                                ResourcePackStorage.requireSpace(stage,1024*1024);
                                spaceCheckpoint=total+1024*1024;
                            }
                            if (entryBytes > MAX_FILE_BYTES) throw new IOException("单个资源文件超过 64 MiB：" + name);
                            if (total > MAX_TOTAL_BYTES) throw new IOException("资源包解压后超过 350 MiB");
                            out.write(buf, 0, n);
                            if (entryBytes >= nextCheckpoint) {
                                currentEntry = "解压 ZIP 第 " + count + " 项：" + shortEntryName(name) +
                                        "（当前文件 " + (entryBytes / (1024L * 1024L)) + " MiB）";
                                recordImportStage(context, currentEntry, false);
                                nextCheckpoint = entryBytes + 4L * 1024L * 1024L;
                            }
                        }
                    }
                    zis.closeEntry();
                    currentEntry = "已解压第 " + count + " 项（累计 " +
                            (total / (1024L * 1024L)) + " MiB），准备下一项";
                    recordImportStage(context, currentEntry, false);
                }
            }
        } catch (OutOfMemoryError oom) {
            recordImportStage(context, "ZIP 解压期间内存不足；" + currentEntry);
            // Convert recoverable Java heap exhaustion into an ordinary import error, so the
            // UI can show guidance instead of relying on the uncaught-Error path.
            throw new IOException("设备可用内存不足，无法完成 ZIP 解压。请尝试更小的资源包。最后位置：" + currentEntry, oom);
        }
    }

    private static String shortEntryName(String name) {
        if (name == null) return "(未知文件)";
        return name.length() > 72 ? "…" + name.substring(name.length() - 71) : name;
    }

    private static List<String> listDataJson(File stage) throws IOException {
        List<String> out = new ArrayList<String>();
        File data = new File(stage, "data");
        collectJson(stage, data, out);
        return out;
    }

    private static void collectJson(File stage, File node, List<String> out) throws IOException {
        if (!node.exists()) return;
        File[] children = node.listFiles();
        if (children == null) throw new IOException("无法读取临时资源目录");
        for (File f : children) {
            if (f.isDirectory()) collectJson(stage, f, out);
            else if (f.getName().matches(".*\\.(json|jpg|jpeg|png|webp|gif|bmp)")) {
                String rel = stage.toURI().relativize(f.toURI()).getPath();
                out.add(rel);
            }
        }
    }

    private static LinkedHashMap<String, JSONObject> readCatalog(File file) throws IOException, JSONException {
        LinkedHashMap<String, JSONObject> map = new LinkedHashMap<String, JSONObject>();
        if (!file.isFile()) return map;
        if (file.length() > 16L * 1024L * 1024L) throw new IOException("本机资料清单超过 16 MiB");
        // Read one entry at a time. Do not keep a full UTF-8 String and JSONArray alongside
        // the map, which would multiply peak memory on low-RAM Kindles.
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.Charset.forName("UTF-8")))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new JSONException("本机资料清单必须是数组");
            reader.beginArray();
            while (reader.hasNext()) {
                Object value = readJsonValue(reader);
                if (!(value instanceof JSONObject)) throw new JSONException("本机资料清单中存在非对象项");
                JSONObject obj = (JSONObject) value;
                String id = obj.optString("id", "");
                if (id.length() == 0 || map.containsKey(id)) {
                    // Silently dropping entries would make startup GC delete their payloads.
                    throw new JSONException("本机资料清单含有空或重复 ID");
                }
                map.put(id, obj);
            }
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("本机资料清单含有额外内容");
        }
        return map;
    }

    private static PackManifest readPackManifest(File file, File stage) throws IOException, JSONException {
        if (file.length() > 8L * 1024L * 1024L) throw new IOException("资源包清单超过 8 MiB");
        PackManifest manifest = new PackManifest();
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.Charset.forName("UTF-8")))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new JSONException("资源包清单顶层必须是对象");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("items".equals(name)) {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new JSONException("资源包 items 必须是数组");
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (manifest.items.size() >= MAX_ENTRIES) throw new IOException("资源包资料数量过多");
                        Object value = readJsonValue(reader);
                        if (!(value instanceof JSONObject)) throw new JSONException("资源包资料清单项必须是对象");
                        manifest.items.add((JSONObject) value);
                    }
                    reader.endArray();
                } else if ("files".equals(name)) {
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new JSONException("资源包 files 必须是数组");
                    reader.beginArray();
                    while (reader.hasNext()) {
                        Object value = readJsonValue(reader);
                        if (!(value instanceof JSONObject)) throw new JSONException("资源文件清单项必须是对象");
                        JSONObject row = (JSONObject) value;
                        String path = safeDataPath(row.optString("path", ""));
                        if (!path.matches(".*\\.(json|jpg|jpeg|png|webp|gif|bmp)")) throw new IOException("不支持的资源文件：" + path);
                        if (manifest.files.containsKey(path)) throw new IOException("资源包内有重复文件：" + path);
                        long size = row.optLong("size", -1L);
                        String hash = row.optString("sha256", "").toLowerCase(java.util.Locale.US);
                        if (size < 0 || size > MAX_FILE_BYTES || !hash.matches("[0-9a-f]{64}")) {
                            throw new IOException("资源文件校验信息不完整：" + path);
                        }
                        File disk = child(stage, path);
                        manifest.files.put(path, new FileRecord(path, disk, size, hash));
                    }
                    reader.endArray();
                } else {
                    Object value = readJsonValue(reader);
                    if ("format".equals(name)) manifest.format = scalarString(value);
                    else if ("version".equals(name) && value instanceof Number) manifest.version = ((Number) value).intValue();
                    else if ("packId".equals(name)) manifest.packId = scalarString(value);
                    else if ("title".equals(name)) manifest.title = scalarString(value);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new JSONException("资源包清单含有额外内容");
        }
        if (!FORMAT.equals(manifest.format)) throw new IOException("资源包格式不正确");
        if (manifest.version != VERSION) throw new IOException("不支持的资源包版本");
        if (!manifest.packId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) throw new IOException("资源包 packId 不合法");
        if (manifest.title.length() == 0) manifest.title = manifest.packId;
        return manifest;
    }

    private static String scalarString(Object value) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject || value instanceof JSONArray) return "";
        return String.valueOf(value);
    }

    private static JSONObject readJsonObject(JsonReader reader) throws IOException, JSONException {
        JSONObject object = new JSONObject();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            object.put(name, readJsonValue(reader));
        }
        reader.endObject();
        return object;
    }

    private static JSONArray readJsonArray(JsonReader reader) throws IOException, JSONException {
        JSONArray array = new JSONArray();
        reader.beginArray();
        while (reader.hasNext()) array.put(readJsonValue(reader));
        reader.endArray();
        return array;
    }

    /** Parse one JSON value at a time so sibling objects do not coexist in a parser tree. */
    private static Object readJsonValue(JsonReader reader) throws IOException, JSONException {
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                return readJsonObject(reader);
            case BEGIN_ARRAY:
                return readJsonArray(reader);
            case STRING:
                return reader.nextString();
            case NUMBER:
                return new JSONTokener(reader.nextString()).nextValue();
            case BOOLEAN:
                return Boolean.valueOf(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default:
                throw new JSONException("JSON 中存在意外的结束标记");
        }
    }

    private static final class PackManifest {
        String format = "";
        int version = -1;
        String packId = "";
        String title = "";
        final LinkedHashMap<String, FileRecord> files = new LinkedHashMap<String, FileRecord>();
        final List<JSONObject> items = new ArrayList<JSONObject>();
    }

    /**
     * Streams a resource document and reads only its top-level id/kind fields.
     * Unknown fields (including large embedded images and question arrays) are skipped
     * without building a full in-memory JSON object tree.
     */
    private static DocumentIdentity readDocumentIdentity(File file) throws IOException {
        if (file.length() > MAX_FILE_BYTES) {
            throw new IOException("JSON 文件过大：" + file.getName());
        }
        String id = "";
        String kind = "drill";
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                new FileInputStream(file), java.nio.charset.Charset.forName("UTF-8")))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                throw new IOException("资料 JSON 顶层必须是对象：" + file.getName());
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("id".equals(name)) {
                    String value = readIdentityValue(reader);
                    id = value == null ? "" : value;
                } else if ("kind".equals(name)) {
                    String value = readIdentityValue(reader);
                    kind = value == null ? "drill" : value;
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("资料 JSON 含有额外内容：" + file.getName());
            }
        }
        return new DocumentIdentity(id, kind);
    }

    /** Returns scalar text; JSON null maps to null, while complex values use an invalid sentinel. */
    private static String readIdentityValue(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) return reader.nextString();
        if (token == JsonToken.BOOLEAN) return String.valueOf(reader.nextBoolean());
        if (token == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        reader.skipValue();
        // Complex values are not missing/null scalars and must not receive a fallback.
        return "\\u0000invalid-json-scalar\\u0000";
    }

    private static final class DocumentIdentity {
        final String id;
        final String kind;

        DocumentIdentity(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }
    }

    private static String safeZipPath(String raw) throws IOException {
        String p = raw.replace('\\', '/');
        while (p.endsWith("/") && p.length() > 0) p = p.substring(0, p.length() - 1);
        if (p.length() == 0 || p.startsWith("/") || p.indexOf('\0') >= 0 || p.indexOf(':') >= 0) {
            throw new IOException("资源包路径不合法");
        }
        String[] parts = p.split("/");
        for (String part : parts) if (part.length() == 0 || ".".equals(part) || "..".equals(part)) {
            throw new IOException("资源包路径不安全：" + raw);
        }
        return p;
    }

    private static void validatePictureReferences(File file, String path, Map<String, FileRecord> files) throws Exception {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (!"figs".equals(reader.nextName()) || reader.peek() != JsonToken.BEGIN_OBJECT) {reader.skipValue();continue;}
                reader.beginObject();
                while (reader.hasNext()) {
                    reader.nextName();
                    if (reader.peek() != JsonToken.STRING) {reader.skipValue();continue;}
                    String ref=reader.nextString();
                    if (ref.startsWith("data:image/")) continue;
                    String full=safeDataPath(path.substring(0,path.lastIndexOf('/')+1)+ref);
                    if (!ref.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)") || !files.containsKey(full))
                        throw new IOException("课次图片缺失或路径无效："+ref);
                }
                reader.endObject();
            }
            reader.endObject();
        }
    }

    private static String safeDataPath(String raw) throws IOException {
        String p = safeZipPath(raw);
        if (!p.matches(".*\\.(json|jpg|jpeg|png|webp|gif|bmp)")) throw new IOException("不支持的资源文件：" + p);
        if (!(p.startsWith("data/courses/") || p.startsWith("data/papers/"))) {
            throw new IOException("资源路径必须位于 data/courses/ 或 data/papers/：" + p);
        }
        return p;
    }

    private static File child(File root, String relative) throws IOException {
        File out = new File(root, relative).getCanonicalFile();
        String base = root.getCanonicalPath() + File.separator;
        if (!out.getPath().startsWith(base)) throw new IOException("路径越界：" + relative);
        return out;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        byte[] bytes = digest.digest();
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return s.toString();
    }

    private static void writeAtomicCatalog(File dest, LinkedHashMap<String, JSONObject> catalog)
            throws IOException, JSONException {
        File parent = dest.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("无法创建目标目录");
        File temp = new File(parent, dest.getName() + ".tmp-" + UUID.randomUUID().toString());
        try {
            FileOutputStream catalogOut = new FileOutputStream(temp);
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                    catalogOut, java.nio.charset.Charset.forName("UTF-8")))) {
                writer.write('[');
                boolean first = true;
                for (JSONObject item : catalog.values()) {
                    if (!first) writer.write(',');
                    first = false;
                    writer.write(item.toString());
                }
                writer.write(']');
                writer.write('\n');
                writer.flush();
                catalogOut.getFD().sync();
            }
            replaceFile(temp,dest);
        } finally {
            temp.delete();
        }
    }

    // Native single-document imports retain these stable, safe IDs.
    static boolean isValidLocalId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static void writeAtomic(File dest, byte[] bytes) throws IOException {
        File parent = dest.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("无法创建目标目录");
        File temp = new File(parent, dest.getName() + ".tmp-" + UUID.randomUUID().toString());
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes); out.flush();
            try { out.getFD().sync(); } catch (IOException ignored) { }   // 落盘后再改名，断电不留半截文件
        }
        try { replaceFile(temp,dest); } finally { temp.delete(); }
    }

    private static void replaceFile(File from,File to)throws IOException {
        try { android.system.Os.rename(from.getAbsolutePath(),to.getAbsolutePath()); }
        catch(android.system.ErrnoException failure) { throw new IOException("无法保存清单",failure); }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] items = file.listFiles();
            if (items != null) for (File child : items) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class FileRecord {
        final String path; final File file; final long size; final String hash;
        FileRecord(String path, File file, long size, String hash) {
            this.path = path; this.file = file; this.size = size; this.hash = hash;
        }
    }
}


