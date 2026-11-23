package com.xxxk2020.zssteink;

/** One process-wide boundary for catalog, classification and progress read/modify/write. */
final class NativeStorageLock {
    static final Object LOCK = new Object();
    private NativeStorageLock() { }
}
