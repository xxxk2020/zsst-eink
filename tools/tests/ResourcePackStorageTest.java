package com.xxxk2020.zssteink;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Host-side filesystem regression tests; no Android SDK or emulator required. */
public final class ResourcePackStorageTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            crashDuringImport(new File(args[0]), Boolean.parseBoolean(args[1]));
            return;
        }
        File root = Files.createTempDirectory("zsst-storage-test").toFile();
        try {
            testFailedCopy(root);
            testCatalogFailure(root);
            testPublicationAndCleanup(root);
            testCrashRecovery(new File(root, "before-commit"), false);
            testCrashRecovery(new File(root, "after-commit"), true);
            testUnsafePaths(root);
            testCheckpoints();
            testImageLifecycle(new File(root,"images-only"));
            System.out.println("PASS: " + checks + " checks (copy/catalog failure, atomic publication, " +
                    "process death before/after commit, startup GC, path validation, checkpoint throttling)");
        } finally { delete(root); }
    }

    private static void testImageLifecycle(File root) throws Exception {
        File image=write(root,"input.jpg","image-bytes");
        File json=write(root,"input.json","{}");
        String doc,picture,unusedPicture;
        try(ResourcePackStorage storage=new ResourcePackStorage(root)) {
            doc=storage.stage(json,"data/courses/image-lesson.json");
            picture=storage.stage(image,"data/courses/images/shared.jpg");
            unusedPicture=storage.stage(image,"data/courses/images/unused.jpg");
            storage.markPublished();
        }
        ResourcePackStorage.cleanupExact(root,refs(doc,picture),new HashSet<String>());
        check(new File(root,picture).isFile(),"active image reference is retained");
        check(!new File(root,unusedPicture).exists(),"unreferenced image beside an active lesson is reclaimed");
        ResourcePackStorage.cleanupExact(root,new HashSet<String>(),new HashSet<String>());
        check(!new File(root,picture).exists(),"unused generation image reclaimed");
    }

    private static void testFailedCopy(File root) throws Exception {
        File old = write(root, "data/papers/old.json", "old");
        File source = write(root, "incoming.json", "new");
        String staged = null;
        boolean failed = false;
        try (ResourcePackStorage transaction = new ResourcePackStorage(root)) {
            staged = transaction.stage(source, "data/papers/old.json");
            equal("old", read(old), "staging leaves old payload intact");
            transaction.stage(new File(root, "missing.json"), "data/courses/second.json");
        } catch (IOException expected) { failed = true; }
        check(failed, "second copy fails");
        check(!new File(root, staged).exists(), "failed import removes staged payload");
        equal("old", read(old), "failed import preserves old payload");
    }

    private static void testCatalogFailure(File root) throws Exception {
        File source = write(root, "incoming.json", "new");
        File catalog = write(root, "data/items.json", "old catalog");
        String staged = null;
        try (ResourcePackStorage transaction = new ResourcePackStorage(root)) {
            staged = transaction.stage(source, "data/papers/old.json");
            // Fail catalog publication after every payload has been copied.
            throw new IOException("injected catalog write failure");
        } catch (IOException expected) { }
        equal("old catalog", read(catalog), "catalog failure preserves old catalog");
        check(!new File(root, staged).exists(), "catalog failure rolls back unpublished files");
        equal("old", read(new File(root, "data/papers/old.json")), "catalog failure preserves old file");
    }

    private static void testPublicationAndCleanup(File root) throws Exception {
        File source = write(root, "incoming.json", "new");
        File local = write(root, "data/local/keep.json", "local");
        String paper, course;
        try (ResourcePackStorage transaction = new ResourcePackStorage(root)) {
            paper = transaction.stage(source, "data/papers/old.json");
            course = transaction.stage(source, "data/courses/nested/course.json");
            transaction.markPublished();
        }
        equal("new", read(new File(root, paper)), "published paper survives close");
        equal("new", read(new File(root, course)), "published course survives close");
        // An incremental update retires only one member of the previous generation.
        String update;
        try (ResourcePackStorage transaction = new ResourcePackStorage(root)) {
            update = transaction.stage(source, "data/papers/old.json");
            transaction.markPublished();
        }
        check(!update.equals(paper), "same logical path gets a new immutable URL");
        ResourcePackStorage.cleanupExact(root, refs(update, course), new HashSet<String>());
        check(!new File(root, paper).exists(), "GC removes superseded payload");
        check(!new File(root, "data/papers/old.json").exists(), "GC removes superseded legacy payload");
        equal("new", read(new File(root, update)), "GC retains active update");
        equal("new", read(new File(root, course)), "GC retains untouched course");
        equal("local", read(local), "GC does not touch individual local documents");
        ResourcePackStorage.cleanupExact(root, refs(update, course), new HashSet<String>());
        equal("new", read(new File(root, update)), "GC is idempotent");
    }

    private static void testCrashRecovery(File root, boolean publish) throws Exception {
        root.mkdirs();
        File java = new File(new File(System.getProperty("java.home"), "bin"), "java");
        Process process = new ProcessBuilder(java.getPath(), "-cp", System.getProperty("java.class.path"),
                ResourcePackStorageTest.class.getName(), root.getPath(), String.valueOf(publish))
                .inheritIO().start();
        equal(Integer.valueOf(23), Integer.valueOf(process.waitFor()), "child is terminated without cleanup");
        String staged = read(new File(root, "staged-path"));
        check(new File(root, staged).isFile(), "process death leaves staged file on disk");
        String active = read(new File(root, "data/items.json"));
        ResourcePackStorage.cleanupExact(root, refs(active), new HashSet<String>());
        equal(publish ? "new" : "old", read(new File(root, active)), "startup preserves the active version");
        equal(Boolean.valueOf(publish), Boolean.valueOf(new File(root, staged).exists()),
                "startup retains committed generation and removes uncommitted generation");
    }

    private static void crashDuringImport(File root, boolean publish) throws Exception {
        write(root, "data/papers/old.json", "old");
        File source = write(root, "incoming.json", "new");
        File catalog = write(root, "data/items.json", "data/papers/old.json");
        ResourcePackStorage transaction = new ResourcePackStorage(root);
        String staged = transaction.stage(source, "data/papers/old.json");
        write(root, "staged-path", staged);
        if (publish) {
            File next = write(root, "data/items.next", staged);
            Files.move(next.toPath(),catalog.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // Kill in the window after catalog publication, before markPublished()/close().
        }
        Runtime.getRuntime().halt(23);
    }

    private static void testUnsafePaths(File root) throws Exception {
        File source = new File(root, "incoming.json");
        String[] paths = {"../escape.json", "data/papers/../../escape.json",
                "data/papers//a.json", "data/papers/./a.json", "data/local/a.json",
                "data/papers/a.txt", "data/papers/a\\b.json"};
        for (String path : paths) {
            boolean rejected = false;
            try (ResourcePackStorage transaction = new ResourcePackStorage(root)) {
                transaction.stage(source, path);
            } catch (IOException expected) { rejected = true; }
            check(rejected, "reject unsafe path " + path);
        }
    }

    private static void testCheckpoints() {
        ImportCheckpoint gate = new ImportCheckpoint();
        check(gate.shouldWrite(0, true), "critical phase always writes");
        int writes = 0;
        for (int ms = 1; ms <= 10000; ms++) if (gate.shouldWrite(ms, false)) writes++;
        equal(Integer.valueOf(10), Integer.valueOf(writes), "10,000 progress events cause only 10 writes");
        check(!gate.shouldWrite(10001, false), "suppress nearby progress");
        check(gate.shouldWrite(10001, true), "error/phase bypasses throttle");
        check(!gate.shouldWrite(11000, false), "force resets progress interval");
        check(gate.shouldWrite(11001, false), "write at the exact interval");
        gate.reset();
        check(gate.shouldWrite(11002, false), "next import starts with fresh checkpoint state");
        check(gate.shouldWrite(1, false), "clock reset never suppresses all future checkpoints");
    }

    private static Set<String> refs(String... paths) { return new HashSet<String>(Arrays.asList(paths)); }
    private static File write(File root, String path, String text) throws IOException {
        File file = new File(root, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected.equals(actual), message + " (expected " + expected + ", got " + actual + ")");
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}


