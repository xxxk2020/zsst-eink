package android.util;
import java.io.*;
public final class Base64InputStream extends FilterInputStream {
    public Base64InputStream(InputStream in,int flags){super(java.util.Base64.getMimeDecoder().wrap(in));}
}

