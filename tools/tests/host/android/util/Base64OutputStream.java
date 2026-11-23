package android.util;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Host equivalent for streaming portable-document export, including NO_CLOSE. */
public final class Base64OutputStream extends FilterOutputStream {
    public Base64OutputStream(OutputStream output, final int flags) {
        super(java.util.Base64.getEncoder().wrap(new FilterOutputStream(output) {
            @Override public void close() throws IOException {
                if ((flags & Base64.NO_CLOSE) == 0) super.close();
                else flush();
            }
        }));
    }
}
