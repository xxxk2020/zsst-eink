package com.xxxk2020.zssteink;

import android.net.Uri;
import java.io.File;

/** Exercise the published examples through the actual importer and native document index. */
public final class ResourcePackExampleTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        try (ResourcePackManagerTest.Fixture context = new ResourcePackManagerTest.Fixture();
             NativeRepository repository = new NativeRepository(context)) {
            ResourcePackManager.importPack(context, Uri.fromFile(new File(args[0])));
            check(repository.list().size() == 2, "both original example documents imported");
            NativeRepository.Item choice = NativeStorageTest.find(repository, "demo-choice-v1");
            File portable = new File(context.base, "portable.json");
            byte[] originalImage;
            try (NativeRepository.Document doc = repository.open(choice)) {
                check(doc.questionCount() == 3, "all choice questions indexed");
                check(doc.question(0).getString("answer").equals("D"), "answer preserved");
                check(doc.question(0).getString("explain").contains("30"), "explanation preserved");
                check(doc.sharedMaterial(0) != null && doc.sharedMaterial(1) != null, "shared material recognized");
                File image = doc.image("diagram");
                check(image != null && image.isFile() && image.length() > 0, "independent PNG resolved after import");
                originalImage = java.nio.file.Files.readAllBytes(image.toPath());
                new NativePortablePaper(doc).write(portable);
            }
            try (ResourcePackManagerTest.Fixture destination = new ResourcePackManagerTest.Fixture();
                 NativeRepository imported = new NativeRepository(destination)) {
                NativeTransfer.importFile(destination, Uri.fromFile(portable), "行测", imported, new NativeProgress(destination));
                try (NativeRepository.Document doc = imported.open(NativeStorageTest.find(imported, choice.id))) {
                    check(doc.questionCount() == 3 && doc.question(0).getString("answer").equals("D"), "portable export preserves questions and answers");
                    check(doc.question(0).getString("explain").contains("30") && doc.sharedMaterial(1) != null, "portable export preserves explanations and shared material");
                    String reference = doc.question(2).getJSONArray("imgs").getString(0);
                    File image = doc.image(reference);
                    check(image != null && java.util.Arrays.equals(originalImage, java.nio.file.Files.readAllBytes(image.toPath())), "portable export embeds an identical image and reimports offline");
                }
            }
            NativeRepository.Item writing = NativeStorageTest.find(repository, "demo-writing-v1");
            check("shenlun".equals(writing.shape), "writing document classified");
            try (NativeRepository.Document doc = repository.open(writing)) {
                check(doc.materialCount() == 1 && doc.questionCount() == 1, "material and writing prompt indexed");
                check(doc.question(0).getString("ref").length() > 0, "writing reference answer preserved");
            }
            repository.maintenance();
            try (NativeRepository.Document doc = repository.open(choice)) {
                check(doc.image("diagram") != null, "maintenance retains the active independent image");
            }
        }
        System.out.println("PASS: original choice/writing examples, shared materials, independent PNG import and portable export round trip");
    }
}
