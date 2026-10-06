package org.dual.replicate.core.storage.adapter.out.local;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Streams {

    private Streams() {
    }

    /** Al piu' {@code max} byte di {@code in}; {@code close} chiude {@code in}. */
    public static InputStream limit(InputStream in, long max) {
        return new FilterInputStream(in) {
            private long remaining = max;

            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = super.read();
                if (b >= 0) {
                    remaining--;
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = super.read(b, off, (int) Math.min(len, remaining));
                if (n > 0) {
                    remaining -= n;
                }
                return n;
            }

            @Override
            public long skip(long n) throws IOException {
                long skipped = super.skip(Math.min(n, remaining));
                remaining -= skipped;
                return skipped;
            }

            @Override
            public int available() throws IOException {
                return (int) Math.min(super.available(), remaining);
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }
}
