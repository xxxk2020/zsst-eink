package com.xxxk2020.zssteink;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native access to the existing durable progress records. Call on a worker thread. */
final class NativeProgress {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final StudyProgressStore store;
    private final File directory;
    private final File archives;
    private final File libraryDirectory;
    private final AtomicFile importedIndex;

    static final class State {
        final JSONObject answers, position, result;
        final JSONArray done, stars;
        final boolean submitted;
        State(JSONObject answers, JSONObject position, JSONObject result, JSONArray done, JSONArray stars, boolean submitted) {
            this.answers = answers; this.position = position; this.result = result;
            this.done = done; this.stars = stars; this.submitted = submitted;
        }
    }

    NativeProgress(Context context) {
        File files = context.getApplicationContext().getFilesDir();
        directory = new File(files, "study-progress");
        store = StudyProgressStore.shared(directory);
        archives = new File(files, "native-sessions");
        libraryDirectory = new File(files, "native-library");
        importedIndex = new AtomicFile(new File(libraryDirectory, "legacy-items.json"));
    }

    static String answersKey(String id, String mode) { return "zskg_ans_" + id + "_" + mode; }
    static String positionKey(String id, String mode) { return "zskg_pos_" + id + "_" + mode; }

    JSONObject values(String id) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            return store.snapshot(positionKey(id, "drill"));
        }
    }

    State load(NativeRepository.Item item, String mode) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            JSONObject values = values(item.id);
            JSONObject stars = store.snapshot("zskg_stars");
            return new State(object(values.optString(answersKey(item.id, mode), "")),
                    position(values.optString(positionKey(item.id, mode), "")),
                    object(values.optString("zskg_result_" + item.id, "")),
                    array(values.optString("zskg_done_" + item.id, "")),
                    array(stars.optString("zskg_stars", "")),
                    "1".equals(values.optString("zskg_submit_" + item.id, "")));
        }
    }

    static final class LibraryState {
        final String mode, summary;
        final State state;
        final long lastUsed;
        LibraryState(String mode, String summary, State state, long lastUsed) {
            this.mode=mode;this.summary=summary;this.state=state;this.lastUsed=lastUsed;
        }
    }

    LibraryState libraryState(NativeRepository.Item item) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            JSONObject values=values(item.id);
            JSONObject drill=position(values.optString(positionKey(item.id,"drill"),""));
            JSONObject practice=position(values.optString(positionKey(item.id,"practice"),""));
            JSONObject result=object(values.optString("zskg_result_"+item.id,""));
            JSONObject drillAnswers=object(values.optString(answersKey(item.id,"drill"),""));
            JSONObject practiceAnswers=object(values.optString(answersKey(item.id,"practice"),""));
            int answeredDrill=countAnswers(drillAnswers),answeredPractice=countAnswers(practiceAnswers);
            boolean hasDrill=drill.length()>0||answeredDrill>0||result.length()>0;
            boolean hasPractice=practice.length()>0||answeredPractice>0;
            long td=Math.max(drill.optLong("t",0),result.optLong("time",0)),tp=practice.optLong("t",0);
            String mode;
            if("note".equals(item.shape)||"shenlun".equals(item.shape))mode="read";
            else if(!hasDrill&&!hasPractice)mode=item.hasAnswers?"practice":"drill";
            else if(!hasDrill||(hasPractice&&(tp>td||(tp==0&&td==0&&answeredPractice>0&&answeredDrill==0))))mode="practice";
            else mode="drill";
            State state=new State("drill".equals(mode)?drillAnswers:"practice".equals(mode)?practiceAnswers:object(values.optString(answersKey(item.id,mode),"")),
                    "drill".equals(mode)?drill:"practice".equals(mode)?practice:position(values.optString(positionKey(item.id,mode),"")),
                    result,array(values.optString("zskg_done_"+item.id,"")),new JSONArray(),"1".equals(values.optString("zskg_submit_"+item.id,"")));
            int answered=countAnswers(state.answers);String summary;
            if("read".equals(mode)) {
                if(state.position.has("si"))summary="阅读 · 第 "+(state.position.optInt("si",0)+1)+" 节";
                else if(state.position.has("qi"))summary="阅读 · 第 "+(state.position.optInt("qi",0)+1)+" 题";
                else summary=state.done.length()>0?"阅读 · 已练 "+state.done.length()+" 题":"未开始";
            } else {
                String prefix="practice".equals(mode)?"练习":"刷题";
                if("drill".equals(mode)&&state.submitted)summary=prefix+" · 已交卷 · 已答 "+answered+"/"+item.count;
                else if(answered>0)summary=prefix+" · 已答 "+answered+"/"+item.count;
                else if(state.position.has("i"))summary=prefix+" · 第 "+(state.position.optInt("i",0)+1)+" 题";
                else summary="未开始";
            }
            long lastUsed=Math.max(state.position.optLong("t",0),"drill".equals(mode)?result.optLong("time",0):0);
            if(lastUsed<=0)lastUsed=answered>0||result.length()>0||state.done.length()>0?2:state.position.length()>0?1:0;
            return new LibraryState(mode,summary,state,lastUsed);
        }
    }
    String resumeMode(NativeRepository.Item item) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            return libraryState(item).mode;
        }
    }

    void savePosition(String id, String mode, JSONObject position) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            store.apply(new JSONObject().put(positionKey(id, mode), position.toString()).toString());
        }
    }

    void saveAnswer(String id, String mode, JSONObject answers, JSONObject position) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            store.apply(new JSONObject().put(answersKey(id, mode), answers.toString())
                    .put(positionKey(id, mode), position.toString()).toString());
        }
    }

    void submit(String id, JSONObject result, JSONObject position) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            store.apply(new JSONObject().put("zskg_submit_" + id, "1")
                    .put("zskg_result_" + id, result.toString())
                    .put(positionKey(id, "drill"), position.toString()).toString());
        }
    }

    void saveDone(String id, JSONArray done, JSONObject position) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            store.apply(new JSONObject().put("zskg_done_" + id, done.toString())
                    .put(positionKey(id, "read"), position.toString()).toString());
        }
    }

    JSONArray favorites() throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            String raw=store.snapshot("zskg_stars").optString("zskg_stars", "");
            try { return raw.length()==0?new JSONArray():new JSONArray(raw); }
            catch (Exception invalid) { throw new IOException("收藏记录暂不可读取，原记录已保留"); }
        }
    }

    JSONArray toggleStar(NativeRepository.Item item, Object questionNo) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            JSONObject values = store.snapshot("zskg_stars");
            JSONArray existing = array(values.optString("zskg_stars", "")), next = new JSONArray(); boolean found = false;
            for (int i = 0; i < existing.length(); i++) {
                JSONObject star = existing.optJSONObject(i); if (star == null) continue;
                if (item.id.equals(star.optString("id")) && String.valueOf(questionNo).equals(String.valueOf(star.opt("no")))) found = true;
                else next.put(star);
            }
            if (!found) next.put(new JSONObject().put("id", item.id).put("title", item.title).put("no", questionNo).put("kind", item.kind).put("subject", item.subject));
            store.apply(new JSONObject().put("zskg_stars", next.toString()).toString());
            return next;
        }
    }

    /** Explicit per-paper deletion only. Never called by normal resource removal or installation. */
    void removeDocumentRecords(String id) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            if(id==null||id.length()==0)throw new IOException("资料标识无效");
            JSONObject current=values(id),changes=new JSONObject();
            for(Iterator<String> it=current.keys();it.hasNext();){String key=it.next();if(!("doc:"+id).equals(StudyProgressStore.group(key)))throw new IOException("记录归属不一致，未清除");changes.put(key,JSONObject.NULL);}
            // Always replace both durable generations, so a fallback cannot restore deleted answers.
            changes.put(positionKey(id,"drill"),JSONObject.NULL);
            JSONArray stars=favorites(),kept=new JSONArray();boolean changed=false;
            for(int i=0;i<stars.length();i++){JSONObject star=stars.optJSONObject(i);if(star!=null&&id.equals(star.optString("id")))changed=true;else kept.put(stars.get(i));}
            List<File> owned=new ArrayList<>();File[] files=archives.listFiles();
            if(files!=null)for(File file:files){if(!file.isFile()||!file.getName().endsWith(".json"))continue;JSONObject record;
                try{record=new JSONObject(read(file,MAX_BYTES));}catch(Exception invalid){throw new IOException("历史记录无法核对归属，未清除，请先备份",invalid);}
                if(id.equals(record.optString("id")))owned.add(file);
            }
            // Clear both durable generations so recovery cannot resurrect deleted records.
            store.apply(changes.toString());store.apply(changes.toString());
            if(changed){String update=new JSONObject().put("zskg_stars",kept.toString()).toString();store.apply(update);store.apply(update);}
            for(File file:owned)if(!file.delete()&&file.exists())throw new IOException("本卷历史记录未完全删除，请重试");
        }
    }

    /** Archiving must succeed before any old answer is reset. Each archive is immutable. */
    void beginNewRound(NativeRepository.Item item, String mode) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            JSONObject old = values(item.id);
            if (old.length() > 0) archive(item.id, mode, "new-round", old);
            JSONObject changes = new JSONObject().put(answersKey(item.id, mode), JSONObject.NULL)
                    .put(positionKey(item.id, mode), JSONObject.NULL);
            if ("drill".equals(mode)) changes.put("zskg_submit_" + item.id, JSONObject.NULL).put("zskg_result_" + item.id, JSONObject.NULL);
            if ("read".equals(mode)) changes.put("zskg_done_" + item.id, JSONObject.NULL);
            store.apply(changes.toString());
        }
    }

    JSONObject exportProgress(List<NativeRepository.Item> items) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            JSONObject keys = new JSONObject(), index = readIndex(); Set<String> seen = new LinkedHashSet<String>();
            if (items != null) for (NativeRepository.Item item : items) {
                JSONObject metadata = item.metadata == null ? new JSONObject().put("id", item.id).put("title", item.title) : copy(item.metadata);
                metadata.put("subject", item.subject); index.put(item.id, metadata);
                mergeInto(keys, values(item.id)); seen.add("doc:" + item.id);
            }
            // Keep progress for removed documents so reimporting their IDs can resume study.
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(".json")) continue;
                JSONObject envelope;
                try { envelope = new JSONObject(read(file, 2 * 1024 * 1024)); } catch (Exception invalid) { continue; }
                String group = envelope.optString("group", "");
                if (seen.contains(group)) continue;
                if (group.startsWith("doc:") && group.length() > 4) {
                    mergeInto(keys, values(group.substring(4))); seen.add(group);
                }
            }
            mergeInto(keys, store.snapshot("zskg_stars"));
            if (index.length() > 0) keys.put("zskg_items", index.toString());
            return new JSONObject().put("app", "zskg").put("kind", "progress").put("v", 1)
                    .put("at", System.currentTimeMillis()).put("keys", keys)
                    .put("nativeLibrary", new JSONObject().put("subjects", readMap("subjects.json")).put("importItems", readMap("import-items.json")));
        }
    }

    void importProgress(JSONObject pack) throws Exception {
        synchronized (NativeStorageLock.LOCK) {
            if (pack == null || !"zskg".equals(pack.optString("app")) || !"progress".equals(pack.optString("kind")) || pack.optJSONObject("keys") == null)
                throw new IOException("这不是纸上刷题的进度包");
            JSONObject incoming = pack.getJSONObject("keys"), normalized = new JSONObject();
            JSONObject nativeLibrary = pack.optJSONObject("nativeLibrary");
            if (pack.has("nativeLibrary") && nativeLibrary == null) throw new IOException("原生资料索引无效");
            if (nativeLibrary != null) {
                for (String name : new String[]{"subjects", "importItems"}) {
                    if (!nativeLibrary.has(name)) continue;
                    JSONObject map = nativeLibrary.optJSONObject(name); if (map == null) throw new IOException("原生资料索引无效");
                    for (Iterator<String> it = map.keys(); it.hasNext();) {
                        Object value = map.get(it.next());
                        if ("subjects".equals(name) ? !"行测".equals(value) && !"申论".equals(value) : !(value instanceof JSONObject)) throw new IOException("原生资料分类无效");
                    }
                }
            }
            // Validate the whole accepted payload before changing any local record.
            for (Iterator<String> it = incoming.keys(); it.hasNext();) {
                String key = it.next(); if (incoming.isNull(key) || !isProgressKey(key)) continue;
                Object value = incoming.get(key); String raw = value instanceof String ? (String)value : value.toString();
                validateImported(key, raw); normalized.put(key, raw);
            }
            Map<String, JSONObject> groups = new LinkedHashMap<String, JSONObject>();
            for (Iterator<String> it = normalized.keys(); it.hasNext();) {
                String key = it.next(); String theirs = normalized.getString(key);
                if ("zskg_items".equals(key)) continue;
                String group;
                try { group = StudyProgressStore.group(key); } catch (IOException unsupported) { continue; }
                JSONObject current = store.snapshot(key);
                String merged = key.startsWith("zskg_submit_") ? mergeSubmission(key, current, normalized, theirs)
                        : key.startsWith("zskg_ans_") ? mergeAnswers(key,current,normalized,theirs)
                        : mergeValue(key, current.optString(key, ""), theirs);
                if (merged.equals(current.optString(key, ""))) continue;
                JSONObject changes = groups.get(group);
                if (changes == null) { changes = new JSONObject(); groups.put(group, changes); }
                changes.put(key, merged);
            }
            int applied=0;
            try {
                for (Map.Entry<String, JSONObject> group : groups.entrySet()) {
                    // Commit all keys of one document together, retaining its previous session first.
                    if (group.getKey().startsWith("doc:")) {
                        String id = group.getKey().substring(4); JSONObject old = values(id);
                        if (old.length() > 0) archive(id, "import", "merge-progress", old);
                    }
                    store.apply(group.getValue().toString());applied++;
                }
                if (normalized.has("zskg_items")) { JSONObject index = readIndex(); mergeInto(index, new JSONObject(normalized.getString("zskg_items"))); writeIndex(index); }
                if (nativeLibrary != null) {
                    if (nativeLibrary.has("subjects")) mergeMap("subjects.json", nativeLibrary.getJSONObject("subjects"));
                    if (nativeLibrary.has("importItems")) mergeMap("import-items.json", nativeLibrary.getJSONObject("importItems"));
                }
            } catch(Exception failure) {
                throw new IOException("进度导入未完成，已处理 "+applied+" 组记录；已保存记录和历史备份仍保留，可重试",failure);
            } catch(OutOfMemoryError tooLarge) {
                throw new IOException("内存不足，已处理 "+applied+" 组记录；已保存记录仍保留，请分批导入",tooLarge);
            }
        }
    }

    private void archive(String id, String mode, String reason, JSONObject keys) throws Exception {
        if (!archives.isDirectory() && !archives.mkdirs()) throw new IOException("无法创建历史记录目录，原作答已保留");
        JSONObject pack = new JSONObject().put("app", "zskg").put("kind", "native-session").put("v", 1)
                .put("at", System.currentTimeMillis()).put("id", id).put("mode", mode).put("reason", reason).put("keys", keys);
        String name = digest(id).substring(0, 20) + "-" + mode + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString() + ".json";
        File target = new File(archives, name), temporary = new File(archives, name + ".tmp");
        byte[] bytes = pack.toString().getBytes("UTF-8");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            if (target.exists() || !temporary.renameTo(target)) throw new IOException("历史记录未保存，原作答已保留");
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private JSONObject readIndex() throws Exception {
        try (FileInputStream in = importedIndex.openRead()) { return new JSONObject(read(in, MAX_BYTES)); }
        catch (java.io.FileNotFoundException missing) { return new JSONObject(); }
    }
    private void writeIndex(JSONObject index) throws Exception {
        File parent = importedIndex.getBaseFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建本机资料索引目录");
        FileOutputStream stream = null;
        try { stream = importedIndex.startWrite(); stream.write(index.toString().getBytes("UTF-8")); importedIndex.finishWrite(stream); }
        catch (Exception e) { if (stream != null) importedIndex.failWrite(stream); throw e; }
    }
    private JSONObject readMap(String name) throws Exception {
        AtomicFile file = new AtomicFile(new File(libraryDirectory, name));
        try (FileInputStream in = file.openRead()) { return new JSONObject(read(in, MAX_BYTES)); }
        catch (java.io.FileNotFoundException missing) { return new JSONObject(); }
    }
    private void mergeMap(String name, JSONObject incoming) throws Exception {
        JSONObject values = readMap(name); mergeInto(values, incoming);
        if (!libraryDirectory.isDirectory() && !libraryDirectory.mkdirs()) throw new IOException("无法保存资料分类");
        AtomicFile file = new AtomicFile(new File(libraryDirectory, name)); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(values.toString().getBytes("UTF-8")); file.finishWrite(out); }
        catch (Exception error) { if (out != null) file.failWrite(out); throw error; }
    }
    private static boolean isProgressKey(String key) {
        if ("zskg_items".equals(key) || "zskg_stars".equals(key)) return true;
        for (String prefix : new String[]{"zskg_pos_", "zskg_ans_", "zskg_submit_", "zskg_done_", "zskg_result_"}) if (key.startsWith(prefix)) return true;
        return false;
    }
    private static String mergeValue(String key, String mine, String theirs) throws Exception {
        if (key.startsWith("zskg_ans_")) { JSONObject merged = object(mine); mergeInto(merged, object(theirs)); return merged.toString(); }
        if ("zskg_stars".equals(key)) {
            JSONArray merged = new JSONArray(); Set<String> seen = new LinkedHashSet<String>();
            for (JSONArray list : new JSONArray[]{array(mine), array(theirs)}) for (int i = 0; i < list.length(); i++) {
                JSONObject star = list.optJSONObject(i); if (star == null || !star.has("id")) continue;
                String id = star.optString("id") + "\u0000" + String.valueOf(star.opt("no"));
                if (seen.add(id)) merged.put(star);
            }
            return merged.toString();
        }
        if (key.startsWith("zskg_done_")) {
            Map<String, Object> merged = new LinkedHashMap<String, Object>();
            for (JSONArray list : new JSONArray[]{array(mine), array(theirs)}) for (int i = 0; i < list.length(); i++) if (!list.isNull(i)) merged.put(String.valueOf(list.get(i)), list.get(i));
            JSONArray result = new JSONArray(); for (Object value : merged.values()) result.put(value); return result.toString();
        }
        if (key.startsWith("zskg_pos_")) return position(mine).optLong("t", 0) > position(theirs).optLong("t", 0) ? mine : theirs;
        if (key.startsWith("zskg_result_")) return object(mine).optLong("time", 0) > object(theirs).optLong("time", 0) ? mine : theirs;
        return theirs;
    }
    private static String mergeAnswers(String key,JSONObject mine,JSONObject incoming,String theirs)throws Exception {
        String suffix=key.substring("zskg_ans_".length());
        String positionKey="zskg_pos_"+suffix;
        long oldTime=position(mine.optString(positionKey,"")).optLong("t",0);
        long newTime=position(incoming.optString(positionKey,"")).optLong("t",0);
        // Missing answers may be merged; conflicts keep the newer session's choice.
        JSONObject merged=object(theirs),existing=object(mine.optString(key,""));
        if(oldTime>=newTime)mergeInto(merged,existing);
        else {mergeInto(existing,merged);merged=existing;}
        return merged.toString();
    }
    private static String mergeSubmission(String key, JSONObject mine, JSONObject incoming, String theirs) {
        String id = key.substring("zskg_submit_".length()), existing = mine.optString(key, "");
        long oldTime = Math.max(position(mine.optString(positionKey(id, "drill"), "")).optLong("t", 0), object(mine.optString("zskg_result_" + id, "")).optLong("time", 0));
        long newTime = Math.max(position(incoming.optString(positionKey(id, "drill"), "")).optLong("t", 0), object(incoming.optString("zskg_result_" + id, "")).optLong("time", 0));
        if (oldTime > newTime || (oldTime == newTime && "1".equals(existing) && !"1".equals(theirs))) return existing;
        return theirs;
    }
    private static void validateImported(String key, String raw) throws Exception {
        if (raw.length() > 2 * 1024 * 1024) throw new IOException("单项进度过大：" + key);
        if (key.startsWith("zskg_submit_")) { if (!"1".equals(raw) && !"0".equals(raw)) throw new IOException("交卷标记无效"); return; }
        if (key.startsWith("zskg_pos_")) {
            JSONObject value;
            if (!raw.trim().startsWith("{")) { try { if (Long.parseLong(raw.trim()) < 0) throw new NumberFormatException(); return; } catch (NumberFormatException e) { throw new IOException("阅读位置无效"); } }
            value = new JSONObject(raw);
            for (String field : new String[]{"i","qi","mi","si","t","nativeQuestionY"}) validateNumber(value, field);
            if (value.has("view") && !"q".equals(value.optString("view")) && !"mat".equals(value.optString("view")) && !"note".equals(value.optString("view"))) throw new IOException("阅读页面类型无效");
            for (String field : new String[]{"scroll","open","materialPositions"}) if (value.has(field) && !(value.get(field) instanceof JSONObject)) throw new IOException("阅读位置内容无效");
            return;
        }
        if (key.startsWith("zskg_ans_")) {
            JSONObject value = new JSONObject(raw);
            for (Iterator<String> keys = value.keys(); keys.hasNext();) {
                Object answer = value.get(keys.next()); if (answer == JSONObject.NULL) continue;
                if (answer instanceof Number) { double n = ((Number)answer).doubleValue(); if (n == Math.rint(n) && n >= 0 && n < 8) continue; }
                if (answer instanceof String && ((String)answer).matches("[A-Ha-h0-7]")) continue;
                throw new IOException("作答记录含无效选项");
            }
            return;
        }
        if (key.startsWith("zskg_result_")) {
            JSONObject value = new JSONObject(raw);
            for (String field : new String[]{"time","total","known","unknown","correct","answered"}) validateNumber(value, field);
            if (value.has("score") && !value.isNull("score")) { validateNumber(value, "score"); if (value.getDouble("score") > 100) throw new IOException("成绩无效"); }
            return;
        }
        if (key.startsWith("zskg_done_")) {
            JSONArray value = new JSONArray(raw);
            for (int i = 0; i < value.length(); i++) if (!(value.get(i) instanceof String) && !(value.get(i) instanceof Number)) throw new IOException("已练题号无效");
            return;
        }
        if ("zskg_stars".equals(key)) {
            JSONArray value = new JSONArray(raw);
            for (int i = 0; i < value.length(); i++) {
                JSONObject star = value.optJSONObject(i);
                if (star == null || !(star.opt("id") instanceof String) || star.optString("id").length() == 0 || (! (star.opt("no") instanceof String) && !(star.opt("no") instanceof Number))) throw new IOException("收藏记录无效");
            }
            return;
        }
        if ("zskg_items".equals(key)) {
            JSONObject value = new JSONObject(raw);
            for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!(value.get(keys.next()) instanceof JSONObject)) throw new IOException("资料索引无效");
        }
    }
    private static void validateNumber(JSONObject value, String field) throws Exception {
        if (!value.has(field)) return; Object number = value.get(field);
        if (!(number instanceof Number)) throw new IOException("进度字段无效：" + field);
        double n = ((Number)number).doubleValue(); if (Double.isNaN(n) || Double.isInfinite(n) || n < 0) throw new IOException("进度字段无效：" + field);
    }
    static int countAnswers(JSONObject answers) {
        int count = 0; for (Iterator<String> it = answers.keys(); it.hasNext();) if (!answers.isNull(it.next())) count++; return count;
    }
    static JSONObject position(String raw) {
        JSONObject result = object(raw);
        if (result.length() == 0 && raw != null && raw.trim().length() > 0) try { result.put("i", Integer.parseInt(raw.trim())); } catch (Exception ignored) {}
        return result;
    }
    static JSONObject object(String raw) { try { return new JSONObject(raw); } catch (Exception ignored) { return new JSONObject(); } }
    static JSONArray array(String raw) { try { return new JSONArray(raw); } catch (Exception ignored) { return new JSONArray(); } }
    static JSONObject copy(JSONObject value) throws Exception { return value == null ? new JSONObject() : new JSONObject(value.toString()); }
    private static void mergeInto(JSONObject target, JSONObject source) throws Exception { for (Iterator<String> it = source.keys(); it.hasNext();) { String key = it.next(); target.put(key, source.get(key)); } }
    private static String read(File file, int limit) throws IOException { try (FileInputStream in = new FileInputStream(file)) { return read(in, limit); } }
    private static String read(FileInputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) { if (out.size() + n > limit) throw new IOException("进度包过大"); out.write(buffer, 0, n); }
        return new String(out.toByteArray(), "UTF-8");
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8")); StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.US, "%02x", b & 255)); return result.toString();
    }
}


