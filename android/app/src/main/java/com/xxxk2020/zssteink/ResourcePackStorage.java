package com.xxxk2020.zssteink;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Copy-on-write payloads. The catalog rename is the only publication point. */
final class ResourcePackStorage implements Closeable {
    private static final Map<String,Integer> READERS = new HashMap<>();
    private final File root;
    private final String generation = ".zsst-generation-" + UUID.randomUUID().toString();
    private final List<File> directories = new ArrayList<File>();
    private boolean published;

    ResourcePackStorage(File root) { this.root = root; }

    /** Keep a reader's immutable generation until its last image/JSON read has finished. */
    static Closeable retain(File root, File source) throws IOException {
        String base=root.getCanonicalPath()+File.separator;
        String path=source.getCanonicalPath();
        if(!path.startsWith(base))throw new IOException("资源路径越界");
        String relative=path.substring(base.length()).replace(File.separatorChar,'/');
        int marker=relative.indexOf("/.zsst-generation-");
        int end=marker<0?-1:relative.indexOf('/',marker+1);
        String prefix=end<0?relative.substring(0,relative.lastIndexOf('/')+1):relative.substring(0,end+1);
        final String key=base+prefix;
        synchronized(READERS) { Integer n=READERS.get(key);READERS.put(key,n==null?1:n+1); }
        return new Closeable() {
            private boolean closed;
            @Override public void close() {
                synchronized(READERS) {
                    if(closed)return;closed=true;Integer n=READERS.get(key);
                    if(n!=null) { if(n<=1)READERS.remove(key);else READERS.put(key,n-1); }
                }
            }
        };
    }

    static void requireSpace(File location,long bytes) throws IOException {
        File existing=location;
        while(existing!=null&&!existing.exists())existing=existing.getParentFile();
        if(existing!=null) {
            long free=existing.getUsableSpace();
            if(bytes>free-8L*1024*1024)throw new IOException("存储空间不足，请先清理本机资源；原题库已保留");
        }
    }

    String stage(File source, String path) throws IOException {
        String prefix;
        if (path.startsWith("data/courses/")) prefix = "data/courses/";
        else if (path.startsWith("data/papers/")) prefix = "data/papers/";
        else throw new IOException("资源路径不合法：" + path);
        if (!(path.endsWith(".json") || path.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)")) || path.indexOf('\\') >= 0) throw new IOException("资源路径不合法");
        for (String part : path.split("/", -1)) {
            if (part.length() == 0 || ".".equals(part) || "..".equals(part)) {
                throw new IOException("资源路径不安全：" + path);
            }
        }
        File dir = new File(root, prefix + generation);
        if (!directories.contains(dir)) directories.add(dir);
        String installed = prefix + generation + "/" + path.substring(prefix.length());
        File dest = new File(root, installed);
        if (!dest.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) {
            throw new IOException("资源路径越界");
        }
        File parent = dest.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建资源版本目录");
        // This path is not reachable from the active catalog. A partial copy is never visible.
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.getFD().sync();
        }
        return installed;
    }

    /** Call immediately after the durable catalog has been atomically renamed. */
    void markPublished() { published = true; }

    @Override public void close() {
        if (!published) for (File directory : directories) deleteTree(directory);
    }

    /** Exact native image references; unreadable documents and open readers pin whole generations. */
    static boolean cleanupExact(File root,Set<String> referenced,Set<String> conservative) {
        Set<String> protectedPrefixes=new HashSet<>(conservative);boolean deferred=false;
        try {
            String base=root.getCanonicalPath()+File.separator;
            synchronized(READERS) {
                for(String key:READERS.keySet())if(key.startsWith(base)) { protectedPrefixes.add(key.substring(base.length()));deferred=true; }
            }
        } catch(IOException unavailable) { return false; }
        boolean courses=prune(root,new File(root,"data/courses"),"data/courses",referenced,protectedPrefixes);
        boolean papers=prune(root,new File(root,"data/papers"),"data/papers",referenced,protectedPrefixes);
        return courses&&papers&&!deferred;
    }

    private static boolean prune(File root,File node,String path,Set<String> referenced,Set<String> protectedPrefixes) {
        for(String prefix:protectedPrefixes)if(path.startsWith(prefix))return true;
        try {
            if(!node.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))return false;
        } catch(IOException unavailable) { return false; }
        if(node.isDirectory()) {
            File[] children=node.listFiles();if(children==null)return false;
            boolean complete=true;
            for(File child:children)complete=prune(root,child,path+"/"+child.getName(),referenced,protectedPrefixes)&&complete;
            File[] remaining=node.listFiles();
            if(remaining==null)return !node.exists()&&complete;
            if(remaining.length==0&&!node.delete()&&node.exists())complete=false;
            return complete;
        }
        if(path.indexOf("/.zsst-generation-")<0&&path.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp)"))return true;
        if(!referenced.contains(path)&&(path.matches(".*\\.(json|jpg|jpeg|png|webp|gif|bmp)")||node.getName().contains(".tmp-")))
            return node.delete()||!node.exists();
        return true;
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete(); // cleanup is best-effort; startup GC retries interrupted/failed cleanup
    }
}


