package android.util;
import java.io.*;
/** Host implementation of the pre-API-30 backup/restore protocol, with write-failure injection. */
public final class AtomicFile {
    private final File base,backup;
    public static String failWriteSuffix;
    public AtomicFile(File base){this.base=base;backup=new File(base.getPath()+".bak");}
    public File getBaseFile(){return base;}
    public FileInputStream openRead()throws FileNotFoundException {
        if(backup.exists()){base.delete();backup.renameTo(base);}
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite()throws IOException {
        if(failWriteSuffix!=null&&base.getPath().endsWith(failWriteSuffix))throw new IOException("injected write failure");
        if(base.exists()) {
            if(!backup.exists()){if(!base.renameTo(backup))throw new IOException("backup failed");}
            else base.delete();
        }
        File parent=base.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("mkdir failed");
        return new FileOutputStream(base);
    }
    public void finishWrite(FileOutputStream out){try{out.getFD().sync();out.close();backup.delete();}catch(IOException e){throw new RuntimeException(e);}}
    public void failWrite(FileOutputStream out){try{out.close();}catch(IOException ignored){}base.delete();backup.renameTo(base);}
}

