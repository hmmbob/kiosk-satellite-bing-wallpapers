// SPDX-License-Identifier: Apache-2.0
package kiosk.plugins.bing;

import java.io.*;
import java.util.*;

/** Single I/O owner. A successful feed is authoritative, even before downloads finish. */
final class Cache {
    static final int MAX_IMAGES = 8;
    static final int MAX_INDEX = 32768;
    // Eight stored JPEGs, one atomic-write JPEG, and two copies of the small index.
    static final long MAX_STORAGE_BYTES = (MAX_IMAGES + 1L) * Bing.MAX_IMAGE + 2L * MAX_INDEX;
    final File root;
    private final Map<String, byte[]> memory = new HashMap<>();
    private Set<String> allowed = Collections.emptySet();

    static final class Snapshot {
        final String source; final long updated; final List<Bing.Photo> photos;
        Snapshot(String source, long updated, List<Bing.Photo> photos) {
            this.source = source; this.updated = updated; this.photos = photos;
        }
    }
    Cache(File directory) throws IOException {
        root = directory == null ? null : directory.getCanonicalFile();
        if (root != null && !root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create image cache");
    }
    byte[] get(Bing.Photo p) throws IOException {
        byte[] bytes;
        if (root == null) bytes = memory.get(p.key);
        else {
            File file = new File(root, p.key + ".jpg");
            restoreBackup(file);
            if (!file.isFile()) return null;
            if (!file.getCanonicalFile().equals(file)) throw new IOException("Invalid cache path");
            try (InputStream in = new FileInputStream(file)) { bytes = Http.read(in, Bing.MAX_IMAGE); }
        }
        return Bing.jpeg(bytes) ? bytes : null;
    }
    void put(Bing.Photo p, byte[] bytes) throws IOException {
        if (!allowed.contains(p.key)) throw new IOException("Image is not in the current feed");
        if (bytes.length > Bing.MAX_IMAGE || !Bing.jpeg(bytes)) throw new IOException("Invalid cached image");
        if (root == null) { memory.put(p.key, bytes); return; }
        checkBudget(bytes.length);
        atomic(new File(root, p.key + ".jpg"), bytes);
    }
    Snapshot load(Config c) throws IOException {
        if (root == null) return null;
        File index = new File(root, "index.bin");
        restoreBackup(index);
        if (!index.isFile()) return null;
        if (index.length() > MAX_INDEX) throw new IOException("Invalid cache index size");
        try (DataInputStream in = new DataInputStream(new FileInputStream(index))) {
            if (in.readInt() != 1) throw new IOException("Unknown cache format");
            String source = in.readUTF(); long updated = in.readLong();
            int width = in.readInt(), count = in.readInt();
            if ((width != 1280 && width != 1920) || count < 0 || count > MAX_IMAGES) throw new IOException("Invalid cache index");
            List<Bing.Photo> photos = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                Bing.Photo p = new Bing.Photo(in.readUTF(), in.readUTF(), in.readUTF(), width);
                if (!seen.add(p.key)) throw new IOException("Duplicate cached image");
                try { if (get(p) != null) photos.add(p); } catch (IOException ignored) { /* Redownload damaged images. */ }
            }
            if (photos.size() != count) updated = 0;
            return new Snapshot(source, updated, photos);
        }
    }
    /** Reconcile BEFORE writing: failed downloads/commits cannot accumulate stale images. */
    void commit(Config c, List<Bing.Photo> feed, long now) throws IOException {
        if (feed.size() > Math.min(MAX_IMAGES, c.count)) throw new IOException("Too many images in cache feed");
        prune(feed);
        if (root == null) return;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeUTF(c.sourceKey()); out.writeLong(now); out.writeInt(c.width); out.writeInt(feed.size());
            for (Bing.Photo p : feed) { out.writeUTF(p.base); out.writeUTF(p.date); out.writeUTF(p.caption); }
        }
        if (bytes.size() > MAX_INDEX) throw new IOException("Cache index too large");
        checkBudget(bytes.size());
        atomic(new File(root, "index.bin"), bytes.toByteArray());
    }
    /** Retain current-feed JPEGs; remove obsolete images and interrupted-write files. */
    void prune(List<Bing.Photo> feed) throws IOException {
        Set<String> keep = new HashSet<>();
        for (Bing.Photo p : feed) if (!keep.add(p.key)) throw new IOException("Duplicate image in feed");
        if (keep.size() > MAX_IMAGES) throw new IOException("Too many images in cache");
        allowed = Collections.emptySet(); // Cleanup failure must prevent further writes.
        memory.keySet().retainAll(keep);
        if (root != null) {
            for (File file : files()) {
                String name = file.getName();
                boolean photo = name.matches("[a-f0-9]{64}\\.jpg");
                boolean journal = name.matches("(?:index\\.bin|[a-f0-9]{64}\\.jpg)\\.(?:tmp|bak)");
                if ((photo && (!keep.contains(name.substring(0, 64)) || file.length() > Bing.MAX_IMAGE)) || journal
                        || (name.equals("index.bin") && file.length() > MAX_INDEX)) delete(file);
            }
            checkBudget(0);
        }
        allowed = keep;
    }
    private File[] files() throws IOException {
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Cannot inspect image cache");
        for (File file : files) {
            if (!file.getCanonicalFile().equals(file) || !file.isFile()) throw new IOException("Unexpected entry in image cache");
        }
        return files;
    }
    private void checkBudget(long extra) throws IOException {
        long bytes = extra;
        for (File file : files()) {
            if (file.length() > MAX_STORAGE_BYTES - bytes) throw new IOException("Image cache size limit reached");
            bytes += file.length();
        }
        if (bytes > MAX_STORAGE_BYTES) throw new IOException("Image cache size limit reached");
    }
    private static void delete(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove obsolete cache file: " + file.getName());
    }
    static void atomic(File target, byte[] bytes) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        delete(temp);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
            // Android/Linux replaces atomically. Windows JVM tests use a recoverable backup step.
            if (!temp.renameTo(target)) {
                delete(backup);
                if (!target.exists() || !target.renameTo(backup)) throw new IOException("Cannot back up cache file");
                if (!temp.renameTo(target)) { backup.renameTo(target); throw new IOException("Cannot commit cache file"); }
            }
            delete(backup);
        } finally { delete(temp); }
    }
    private static void restoreBackup(File target) throws IOException {
        File backup = new File(target.getParentFile(), target.getName() + ".bak");
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) throw new IOException("Cannot recover cache");
    }
}
