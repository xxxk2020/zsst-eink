package android.net;

import java.io.File;

public final class Uri {
    private final File file;
    private Uri(File file) { this.file = file; }
    public static Uri fromFile(File file) { return new Uri(file); }
    public File hostFile() { return file; }
    public String getScheme() { return "file"; }
}

