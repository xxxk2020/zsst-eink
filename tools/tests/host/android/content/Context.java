package android.content;

import java.io.File;

/** Host-only surface used by ResourcePackManager; never included in the APK. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public Context getApplicationContext() { return this; }
    public File getNoBackupFilesDir() { File f = new File(getFilesDir(), "no-backup"); f.mkdirs(); return f; }
    public abstract File getFilesDir();
    public abstract File getCacheDir();
    public abstract ContentResolver getContentResolver();
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
