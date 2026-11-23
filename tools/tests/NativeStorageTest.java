package com.xxxk2020.zssteink;

import android.net.Uri;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class NativeStorageTest {
    private static int checks;
    interface Task {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        testBrokenFiles();
        testImportRollback();
        testProgress();
        testCacheAndOpenReader();
        testImages();
        testProgressJson();
        File lowSpace=new File("unused-space-fixture") {
            @Override public boolean exists(){return true;}
            @Override public long getUsableSpace(){return 8L*1024*1024-1;}
        };
        fails(()->ResourcePackStorage.requireSpace(lowSpace,1024),"low storage rejects staging without filling a real disk");
        System.out.println("PASS: "+checks+" native checks (bad-file isolation, import rollback, shared writes, resume snapshots, pinned readers/images, bounded progress parsing)");
    }
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static void fails(Task task,String message)throws Exception {
        boolean failed=false;try{task.run();}catch(Exception expected){failed=true;}check(failed,message);
    }
    static File write(File file,String data)throws Exception {
        file.getParentFile().mkdirs();Files.write(file.toPath(),data.getBytes(StandardCharsets.UTF_8));return file;
    }
    static JSONObject doc(String id)throws Exception {
        return new JSONObject().put("id",id).put("kind","drill").put("title",id).put("subject","行测")
                .put("questions",new JSONArray().put(new JSONObject().put("no",1).put("text","question")
                .put("options",new JSONArray(Arrays.asList("A","B","C","D"))).put("answer",0)));
    }
    static NativeRepository.Item find(NativeRepository repo,String id)throws Exception {
        for(NativeRepository.Item item:repo.list())if(id.equals(item.id))return item;throw new AssertionError("missing "+id);
    }
    static void testBrokenFiles()throws Exception {
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            repo.prepareStartup();
            write(f.data("data/local/good.json"),doc("good").toString());
            File bad=write(f.data("data/local/bad.json"),"{broken");
            write(f.data("data/local/empty.json"),"");
            File huge=f.data("data/local/huge.json");
            try(RandomAccessFile file=new RandomAccessFile(huge,"rw")){file.setLength(128L*1024*1024+1);}
            check(repo.list().size()==1,"three bad local files do not hide the valid document");
            check(repo.issues().size()==3,"bad files are reported");
            check(bad.isFile(),"listing preserves damaged bytes");
            NativeRepository.Issue issue=null;for(NativeRepository.Issue row:repo.issues())if(row.title.equals("bad.json"))issue=row;
            write(bad,doc("repaired").toString());
            final NativeRepository.Issue repaired=issue;
            fails(()->repo.removeUnreadable(repaired),"a repaired file cannot be deleted as corrupt");
            check(bad.isFile(),"repaired source survives stale issue");
            write(bad,"{broken");repo.list();
            for(NativeRepository.Issue row:repo.issues())if(row.title.equals("bad.json")){repo.removeUnreadable(row);break;}
            check(!bad.exists(),"explicit damaged-file deletion works");
            check(find(repo,"good")!=null,"healthy library remains usable");
            write(new File(f.files,"native-library/subjects.json"),"{broken");
            check(repo.list().size()==1,"corrupt classification index does not block the library");
            write(f.catalogFile(),"{broken");
            check(repo.list().size()==1,"corrupt catalog still permits local files");
            File orphan=write(f.data("data/courses/.zsst-generation-orphan/a.json"),"{}");
            fails(()->ResourcePackManager.cleanupNativeCatalog(f),"damaged catalog blocks source GC");
            check(orphan.exists(),"invalid catalog never authorizes payload deletion");
            File legacy=write(new File(f.files,"local-docs/retained.json"),doc("retained").toString());
            try(NativeRepository.Document document=repo.open(find(repo,"retained"))) {
                check(document.questionCount()==1&&legacy.isFile(),"already installed documents outside resource-data still open without migration");
            }
        }
    }
    static void testImportRollback()throws Exception {
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            repo.prepareStartup();File input=write(new File(f.base,"source.json"),doc("imported").toString());
            AtomicFile.failWriteSuffix="import-items.json";
            try{fails(()->repo.importDocument(Uri.fromFile(input),"行测"),"failed overlay write rejects import");}
            finally{AtomicFile.failWriteSuffix=null;}
            check(!f.data("data/local/imported.json").exists(),"failed overlay publication rolls back only its new source");
            NativeRepository.Item item=repo.importDocument(Uri.fromFile(input),"行测");
            check(item.file.exists(),"valid import survives publication");
            check(repo.list().size()==1,"import is discoverable");
            check(!new String(Files.readAllBytes(new File(f.files,"native-library/import-items.json").toPath()),StandardCharsets.UTF_8).contains("questions"),"overlay contains identity metadata rather than a full question document");
        }
    }
    static void testProgress()throws Exception {
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            repo.prepareStartup();NativeRepository.Item item=repo.importDocument(Uri.fromFile(write(new File(f.base,"p.json"),doc("paper").toString())),"行测");
            File directory=new File(f.files,"study-progress");
            StudyProgressStore a=StudyProgressStore.shared(directory),b=StudyProgressStore.shared(new File(directory,"."));
            check(a==b,"canonical paths share a live progress store");
            a.apply(new JSONObject().put("zskg_ans_paper_drill","{\"1\":0}").toString());
            b.apply(new JSONObject().put("zskg_pos_paper_drill","{\"i\":0,\"t\":100}").toString());
            check(a.snapshot("zskg_ans_paper_drill").has("zskg_pos_paper_drill"),"second writer is visible to first");
            NativeProgress progress=new NativeProgress(f);
            NativeProgress.LibraryState state=progress.libraryState(item);
            check("drill".equals(state.mode)&&state.lastUsed==100&&state.state.answers.optInt("1",-1)==0,"resume snapshot preserves drill choices and time");
            progress.savePosition(item.id,"practice",new JSONObject().put("i",0).put("t",200));
            state=progress.libraryState(item);
            check("practice".equals(state.mode)&&state.lastUsed==200,"newer practice session resumes");
            progress.savePosition(item.id,"read",new JSONObject().put("qi",0).put("t",300));
            item.shape="shenlun";state=progress.libraryState(item);
            check("read".equals(state.mode)&&state.lastUsed==300&&state.summary.contains("第 1 题"),"shenlun reading position is retained");
            JSONObject snapshot=a.snapshot("zskg_pos_paper_drill");snapshot.put("extra","must-not-escape");
            check(!a.snapshot("zskg_pos_paper_drill").has("extra"),"callers cannot mutate cached progress");
            // Two independent API facades update different classification entries.
            NativeProgress other=new NativeProgress(f);ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                Future<?> one=pool.submit(()->{try{repo.setSubject(item,"申论");}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> two=pool.submit(()->{try{other.importProgress(new JSONObject().put("app","zskg").put("kind","progress").put("keys",new JSONObject())
                        .put("nativeLibrary",new JSONObject().put("subjects",new JSONObject().put("other","行测"))));}catch(Exception e){throw new RuntimeException(e);}});
                one.get(5,TimeUnit.SECONDS);two.get(5,TimeUnit.SECONDS);
            } finally {pool.shutdownNow();}
            JSONObject subjects=new JSONObject(new String(Files.readAllBytes(new File(f.files,"native-library/subjects.json").toPath()),StandardCharsets.UTF_8));
            check("申论".equals(subjects.optString("paper"))&&"行测".equals(subjects.optString("other")),"classification import and document edits retain both entries");
        }
    }
    static void testCacheAndOpenReader()throws Exception {
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            repo.prepareStartup();NativeRepository.Item item=repo.importDocument(Uri.fromFile(write(new File(f.base,"p.json"),doc("reader").toString())),"行测");
            File cache=new File(f.cache,"native-reader-cache");
            File abandoned=write(new File(cache,".stage-old/partial.json"),"{}");
            File font=write(new File(f.files,"user-font/keep.ttf"),"font-marker");
            NativeRepository.Document document=repo.open(item);
            repo.maintenance();check(!abandoned.exists(),"abandoned staging index reclaimed");
            check(document.question(0).optInt("no")==1,"maintenance retains an open index");
            // Simulate an out-of-process source replacement; pin the reader's old index.
            write(item.file,doc("reader").put("title","changed length").toString());
            repo.maintenance();check(document.questionCount()==1&&document.question(0).optInt("no")==1,"open reader survives stale-index cleanup");
            document.close();repo.maintenance();
            int indexes=0;for(File file:cache.listFiles())if(file.getName().matches("[0-9a-f]{64}"))indexes++;
            check(indexes==0,"closed stale indexes are removed");
            check("font-marker".equals(new String(Files.readAllBytes(font.toPath()),StandardCharsets.UTF_8)),"cache cleanup never touches fonts");
        }
    }
    static File imagePack(ResourcePackManagerTest.Fixture f)throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();
        for(String id:Arrays.asList("one","two")) {
            JSONObject paper=doc(id).put("kind","course").put("figs",new JSONObject().put("shared","images/shared.jpg").put("own","images/"+id+".jpg"));
            if("two".equals(id)) {
                paper.remove("figs");
                paper.getJSONArray("questions").getJSONObject(0).put("text","<img src=\"images/shared.jpg\">").put("imgs",new JSONArray().put("images/two.jpg"));
            }
            files.put("data/courses/"+id+".json",paper.toString().getBytes(StandardCharsets.UTF_8));
        }
        for(String id:Arrays.asList("shared","one","two"))files.put("data/courses/images/"+id+".jpg",new byte[]{1,2,3});
        JSONArray items=new JSONArray(),records=new JSONArray();
        for(String id:Arrays.asList("one","two"))items.put(new JSONObject().put("id",id).put("kind","course").put("file","data/courses/"+id+".json"));
        File zip=new File(f.base,"images.zip");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
            for(Map.Entry<String,byte[]> entry:files.entrySet()) {
                StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(entry.getValue()))hash.append(String.format(Locale.US,"%02x",b&255));
                records.put(new JSONObject().put("path",entry.getKey()).put("size",entry.getValue().length).put("sha256",hash.toString()));
                out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("resource-pack.json"));out.write(new JSONObject().put("format",ResourcePackManager.FORMAT).put("version",1).put("packId","images")
                    .put("title","images").put("items",items).put("files",records).toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();
        }
        return zip;
    }
    static void testImages()throws Exception {
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            ResourcePackManager.importPack(f,Uri.fromFile(imagePack(f)));
            NativeRepository.Item one=find(repo,"one"),two=find(repo,"two");
            File folder=one.file.getParentFile(),onlyOne=new File(folder,"images/one.jpg"),shared=new File(folder,"images/shared.jpg");
            NativeRepository.Document reader=repo.open(one);
            repo.remove(one);repo.maintenance();
            check(onlyOne.isFile()&&shared.isFile(),"an open document pins its image generation");
            check(reader.image("own")!=null&&reader.question(0)!=null,"removed source remains readable until reader closes");
            reader.close();
            String sharedPath=f.catalog().get("two").getString("file");
            String oldAsset=sharedPath.substring(0,sharedPath.lastIndexOf('/')+1)+"images/one.jpg";
            File local=write(f.data("data/local/local-reference.json"),doc("local-reference").put("figs",new JSONObject().put("f",oldAsset)).toString());
            repo.maintenance();check(onlyOne.isFile(),"standalone document references retain shared generation assets");
            local.delete();repo.maintenance();
            check(!onlyOne.exists(),"deleted lesson's exclusive image is reclaimed");
            check(shared.isFile()&&new File(folder,"images/two.jpg").isFile(),"remaining lesson retains shared and own images");
            File unknown=write(new File(folder,"images/unused.jpg"),"unused");
            write(two.file,"{broken");repo.maintenance();
            check(unknown.exists(),"unreadable remaining JSON conservatively pins its generation");
            write(two.file,doc("two").put("kind","course").put("figs",new JSONObject().put("f","images/shared.jpg")).toString());
            repo.remove(two);repo.maintenance();check(!shared.exists(),"last deleted lesson releases the generation");
        }
    }
    static void testProgressJson()throws Exception {
        String valid=new JSONObject().put("app","zskg").put("kind","progress").put("keys",new JSONObject().put("zskg_ans_p_drill","{\"1\":0}")).toString();
        check(NativeProgressJson.read(new ByteArrayInputStream(valid.getBytes(StandardCharsets.UTF_8))).getJSONObject("keys").has("zskg_ans_p_drill"),"streamed progress preserves keys");
        try(ResourcePackManagerTest.Fixture f=new ResourcePackManagerTest.Fixture();NativeRepository repo=new NativeRepository(f)) {
            NativeProgress progress=new NativeProgress(f);
            File input=write(new File(f.base,"progress.json"),valid);
            NativeTransfer.importFile(f,Uri.fromFile(input),"行测",repo,progress);
            check(progress.values("p").optString("zskg_ans_p_drill").equals("{\"1\":0}"),"file picker path imports streamed progress");
            String before=progress.values("p").toString();write(input,valid+" {}");
            fails(()->NativeTransfer.importFile(f,Uri.fromFile(input),"行测",repo,progress),"malformed progress is rejected before any writes");
            check(before.equals(progress.values("p").toString()),"rejected progress leaves current record unchanged");
        }
        fails(()->NativeProgressJson.read(new ByteArrayInputStream((valid+" {}").getBytes(StandardCharsets.UTF_8))),"trailing progress garbage rejected before writes");
        char[] huge=new char[2*1024*1024+1];Arrays.fill(huge,'a');
        fails(()->NativeProgressJson.read(new ByteArrayInputStream(("{\"keys\":\""+new String(huge)+"\"}").getBytes(StandardCharsets.UTF_8))),"oversized token rejected");
        String deep="0";for(int i=0;i<18;i++)deep="["+deep+"]";final byte[] nested=("{\"x\":"+deep+"}").getBytes(StandardCharsets.UTF_8);
        fails(()->NativeProgressJson.read(new ByteArrayInputStream(nested)),"excessive nesting rejected");
    }
}

