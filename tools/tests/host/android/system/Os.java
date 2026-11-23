package android.system;
import java.nio.file.*;
/** Host equivalent of Android's POSIX rename, including atomic replacement. */
public final class Os {
    public static void rename(String from,String to)throws ErrnoException {
        try { Files.move(Paths.get(from),Paths.get(to),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(java.io.IOException failure){throw new ErrnoException("rename",failure);}
    }
}
