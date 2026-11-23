package com.xxxk2020.zssteink;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Small durable maintenance receipts, separate from study/font data.
 * Source mutations invalidate before publication; metadata stamps are additional guards.
 */
final class NativeMaintenance {
    static final String RESOURCES="resources", READER="reader";
    // Bump the version when cleanup rules or the reader index format change.
    private static final int VERSION=1, MAX_RECEIPT_BYTES=8192;
    private NativeMaintenance() { }

    static File receiptFile(Context context,String area) {
        if(!RESOURCES.equals(area)&&!READER.equals(area))throw new IllegalArgumentException("Unknown maintenance area");
        return new File(new File(context.getCacheDir(),"native-maintenance-v1"),area+".json");
    }

    static boolean needsWork(Context context,String area) throws Exception {
        synchronized(NativeStorageLock.LOCK) {
            JSONObject receipt=read(context,area);
            return receipt.optInt("version")!=VERSION || !receipt.optBoolean("clean",false)
                    || !signature(context,area).equals(receipt.optString("signature",""));
        }
    }

    static void invalidate(Context context,String area) throws Exception {
        synchronized(NativeStorageLock.LOCK) {
            JSONObject receipt=read(context,area);
            if(!receipt.optBoolean("clean",false))return;
            receipt.put("clean",false);
            try { write(context,area,receipt); }
            catch(Exception failedWrite) {
                // If storage is full, removing both cached receipt generations also marks pending work.
                File file=receiptFile(context,area),backup=new File(file.getPath()+".bak");
                boolean removed=!file.exists()||file.delete();
                boolean removedBackup=!backup.exists()||backup.delete();
                if(!removed||!removedBackup)throw new IOException("无法记录待完成的资源维护，原资料未更改",failedWrite);
            }
        }
    }

    static void completed(Context context,String area,boolean complete) throws Exception {
        synchronized(NativeStorageLock.LOCK) {
            if(!complete){invalidate(context,area);return;}
            JSONObject previous=read(context,area);
            JSONObject receipt=new JSONObject().put("version",VERSION).put("clean",true)
                    .put("signature",signature(context,area))
                    .put("completedPasses",previous.optLong("completedPasses",0)+1);
            write(context,area,receipt);
        }
    }

    private static String signature(Context context,String area) {
        File root=ResourcePackManager.dataRoot(context);
        StringBuilder signature=new StringBuilder("maintenance-"+VERSION+"\n");
        if(RESOURCES.equals(area)) {
            stamp(signature,new File(root,"data/items.json"));
            stamp(signature,new File(root,"data/local"));
            stamp(signature,new File(root,"local-docs"));
            stamp(signature,new File(context.getFilesDir(),"local-docs"));
            stamp(signature,new File(root,"data/courses"));
            stamp(signature,new File(root,"data/papers"));
        } else {
            stamp(signature,new File(context.getCacheDir(),"native-reader-cache"));
        }
        return signature.toString();
    }

    private static void stamp(StringBuilder signature,File file) {
        signature.append(file.exists()?'1':'0').append(':')
                .append(file.isDirectory()?'d':'f').append(':')
                .append(file.length()).append(':').append(file.lastModified()).append('\n');
    }

    private static JSONObject read(Context context,String area) {
        File file=receiptFile(context,area);
        try(FileInputStream in=new AtomicFile(file).openRead()) {
            byte[] bytes=new byte[MAX_RECEIPT_BYTES];int length=0,n;
            while((n=in.read(bytes,length,bytes.length-length))>0) {
                length+=n;if(length==bytes.length)throw new IOException("Maintenance receipt too large");
            }
            return new JSONObject(new String(bytes,0,length,"UTF-8"));
        } catch(Exception missingOrDamaged) { return new JSONObject(); }
    }

    private static void write(Context context,String area,JSONObject receipt) throws Exception {
        File file=receiptFile(context,area),parent=file.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法记录资源维护状态");
        byte[] bytes=receipt.toString().getBytes("UTF-8");
        if(bytes.length>MAX_RECEIPT_BYTES)throw new IOException("资源维护记录过大");
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
        try {out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}
        catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}
    }
}

