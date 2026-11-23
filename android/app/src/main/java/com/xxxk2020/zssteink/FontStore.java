package com.xxxk2020.zssteink;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Validated local font imports; existing font selections remain readable. */
final class FontStore {
    static final long MAX_BYTES = 60L * 1024 * 1024;
    static final String SYSTEM = "system", LEGACY = "legacy";

    static final class Entry {
        final String id, name;
        final File file;
        Entry(String id, String name, File file) { this.id = id; this.name = name; this.file = file; }
    }
    static final class Imported {
        final Entry entry;
        final Typeface face;
        Imported(Entry entry, Typeface face) { this.entry = entry; this.face = face; }
    }

    private FontStore() {}

    static File dir(Context c) { return new File(c.getFilesDir(), "user-font"); }
    static File file(Context c) { return new File(dir(c), "font.bin"); }
    private static File nameFile(Context c) { return new File(dir(c), "name.txt"); }
    private static File library(Context c) { return new File(dir(c), "library"); }

    /** Metadata only. Call on the IO queue; no Typeface is created while listing choices. */
    static synchronized List<Entry> list(Context c) {
        List<Entry> result = new ArrayList<Entry>();
        result.add(new Entry(SYSTEM, "系统字体", null));
        if (file(c).isFile()) result.add(new Entry(LEGACY, name(c) + "（原导入）", file(c)));
        List<Entry> imported = new ArrayList<Entry>();
        File[] files = library(c).listFiles();
        if (files != null) for (File font : files) {
            String filename = font.getName();
            if (font.isFile() && filename.matches("[0-9a-f]{64}\\.font")) {
                String hash = filename.substring(0, 64);
                imported.add(new Entry("font:" + hash, readName(new File(library(c), hash + ".name"), "导入字体 " + hash.substring(0, 6)), font));
            }
        }
        Collections.sort(imported, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                int byName = a.name.compareToIgnoreCase(b.name);
                return byName == 0 ? a.id.compareTo(b.id) : byName;
            }
        });
        result.addAll(imported); return result;
    }

    static synchronized Entry entry(Context c, String id) throws IOException {
        if (SYSTEM.equals(id)) return new Entry(SYSTEM, "系统字体", null);
        if (LEGACY.equals(id) && file(c).isFile()) return new Entry(LEGACY, name(c) + "（原导入）", file(c));
        if (id != null && id.matches("font:[0-9a-f]{64}")) {
            String hash = id.substring(5); File font = new File(library(c), hash + ".font");
            if (font.isFile()) return new Entry(id, readName(new File(library(c), hash + ".name"), "导入字体 " + hash.substring(0, 6)), font);
        }
        throw new IOException("字体文件不存在，请重新导入或选择系统字体");
    }

    /** Validate before publishing a new content-addressed font entry. */
    static synchronized Imported importNativeFont(Context c, Uri uri) throws IOException {
        File directory = library(c);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建字体目录");
        File temporary = File.createTempFile("import-", ".tmp", directory);
        try {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException error) { throw new IOException("无法校验字体文件"); }
            InputStream raw = c.getContentResolver().openInputStream(uri);
            if (raw == null) throw new IOException("无法读取所选字体");
            try (InputStream in = new BufferedInputStream(raw); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768], header = new byte[4]; int headerSize = 0; long total = 0; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (n == 0) continue;
                    if (headerSize < 4) { int take = Math.min(4 - headerSize, n); System.arraycopy(buffer, 0, header, headerSize, take); headerSize += take; }
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("字体文件超过 60MB");
                    if (headerSize == 4 && !nativeMagic(header)) throw new IOException("请选择 TTF 或 OTF 字体文件");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                }
                if (headerSize < 4) throw new IOException("字体文件为空或不完整");
                out.getFD().sync();
            }
            final Typeface face;
            try { face = Typeface.createFromFile(temporary); }
            catch (RuntimeException error) { throw new IOException("此字体无法用于原生界面，原字体已保留"); }
            catch (OutOfMemoryError error) { throw new IOException("此字体占用内存过大，请选择较小的字体文件"); }
            if (face == null) throw new IOException("此字体无法用于原生界面，原字体已保留");
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) { int unsigned = value & 255; hex.append("0123456789abcdef".charAt(unsigned >>> 4)).append("0123456789abcdef".charAt(unsigned & 15)); }
            String hash = hex.toString(); File destination = new File(directory, hash + ".font");
            if (!destination.isFile() && !temporary.renameTo(destination)) throw new IOException("无法保存字体文件");
            File titleFile = new File(directory, hash + ".name");
            String display = displayName(c, uri);
            if (display.length() > 512) display = display.substring(0, 512);
            if (!titleFile.isFile()) {
                File titleTemporary = null;
                try {
                    titleTemporary = File.createTempFile("name-", ".tmp", directory);
                    try (FileOutputStream out = new FileOutputStream(titleTemporary)) { out.write(display.getBytes("UTF-8")); out.getFD().sync(); }
                    titleTemporary.renameTo(titleFile);
                } catch (IOException ignored) { /* The font remains usable when only its display name could not be saved. */ }
                finally { if (titleTemporary != null) titleTemporary.delete(); }
            }
            return new Imported(new Entry("font:" + hash, readName(titleFile, display), destination), face);
        } finally { temporary.delete(); }
    }

    private static boolean nativeMagic(byte[] b) {
        return (b[0] == 0 && b[1] == 1 && b[2] == 0 && b[3] == 0)
                || (b[0] == 'O' && b[1] == 'T' && b[2] == 'T' && b[3] == 'O')
                || (b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e');
    }

    private static String readName(File file, String fallback) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[2048]; int n = in.read(bytes);
            if (n > 0) { String value = new String(bytes, 0, n, "UTF-8").trim(); if (value.length() > 0) return value; }
        } catch (IOException ignored) { }
        return fallback;
    }

    static synchronized String name(Context c) {
        if (!file(c).isFile()) return "";
        try {
            File f = nameFile(c);
            if (!f.isFile()) return "自定义字体";
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[(int) Math.min(f.length(), 512)];
                int n = in.read(b);
                return n > 0 ? new String(b, 0, n, "UTF-8") : "自定义字体";
            } finally { in.close(); }
        } catch (IOException e) { return "自定义字体"; }
    }

    private static String displayName(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && n.trim().length() > 0) return n.trim();
            }
        } catch (Exception ignored) {
        } finally { if (cur != null) cur.close(); }
        String p = uri.getLastPathSegment();
        return p == null || p.length() == 0 ? "自定义字体" : p;
    }
}

