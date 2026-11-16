package dev.vivarium.cache;

import dev.vivarium.Vivarium;
import dev.vivarium.atlas.CacheAccess;
import dev.vivarium.codec.Bc7Encoder;
import dev.vivarium.config.Config;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Content-addressed disk cache of encoded sprites: {@code sprites.pack} holds deflated payloads back to back,
 * {@code sprites.idx} maps a 128-bit key to (offset, length, last use). The index is rewritten atomically;
 * anything unreadable is treated as a miss. Only JDK classes are used.
 */
public final class SpriteCache {
    private static final int INDEX_MAGIC = 0x42374958; // "B7IX"
    private static final int PAYLOAD_MAGIC = 0x42375350; // "B7SP"
    private static final int FORMAT = 1;

    public record Key(long hi, long lo) {}

    private record Entry(long offset, int length, long lastUsed) {}

    private static volatile SpriteCache instance;

    public static SpriteCache get() {
        SpriteCache c = instance;
        if (c == null) {
            synchronized (SpriteCache.class) {
                c = instance;
                if (c == null) instance = c = new SpriteCache(FMLPaths.GAMEDIR.get().resolve("vivarium-cache"));
            }
        }
        return c;
    }

    private final Path dir, pack, index;
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final Object ioLock = new Object();
    @Nullable
    private FileChannel readChannel;
    private boolean broken;

    private record Pending(Key key, byte[] payload) {}

    SpriteCache(Path dir) {
        this.dir = dir;
        this.pack = dir.resolve("sprites.pack");
        this.index = dir.resolve("sprites.idx");
        try {
            Files.createDirectories(dir);
            loadIndex();
        } catch (IOException e) {
            Vivarium.LOGGER.warn("Vivarium: cache unavailable ({}), encoding without it", e.toString());
            broken = true;
        }
    }

    private void loadIndex() throws IOException {
        if (!Files.exists(index) || !Files.exists(pack)) return;
        long packSize = Files.size(pack);
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(index)))) {
            if (in.readInt() != INDEX_MAGIC || in.readInt() != FORMAT) throw new IOException("bad index header");
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                Key k = new Key(in.readLong(), in.readLong());
                long off = in.readLong();
                int len = in.readInt();
                long used = in.readLong();
                if (off >= 0 && len > 0 && off + len <= packSize) entries.put(k, new Entry(off, len, used));
            }
        } catch (IOException e) {
            Vivarium.LOGGER.warn("Vivarium: cache index unreadable, starting fresh ({})", e.toString());
            entries.clear();
            Files.deleteIfExists(index);
            Files.deleteIfExists(pack);
        }
    }

    // ------------------------------------------------------------------ keys

    public static Key key(int[] header, int[][] levels) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer hb = ByteBuffer.allocate((header.length + 2) * 4).order(ByteOrder.LITTLE_ENDIAN);
            hb.putInt(FORMAT).putInt(Bc7Encoder.ENCODER_VERSION);
            for (int v : header) hb.putInt(v);
            md.update(hb.array());
            ByteBuffer buf = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
            for (int[] lvl : levels) {
                int i = 0;
                while (i < lvl.length) {
                    buf.clear();
                    int n = Math.min(lvl.length - i, buf.capacity() / 4);
                    buf.asIntBuffer().put(lvl, i, n);
                    md.update(buf.array(), 0, n * 4);
                    i += n;
                }
            }
            ByteBuffer d = ByteBuffer.wrap(md.digest());
            return new Key(d.getLong(), d.getLong());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ load / store

    /** Fills the sprite's BC7 blocks and deep levels from the cache. Returns false on a miss. */
    public boolean load(Key key, CacheAccess target) {
        if (broken) return false;
        Entry e = entries.get(key);
        if (e == null) return false;
        byte[] compressed = new byte[e.length];
        try {
            FileChannel ch = channel();
            ByteBuffer bb = ByteBuffer.wrap(compressed);
            long pos = e.offset;
            while (bb.hasRemaining()) {
                int r = ch.read(bb, pos + bb.position());
                if (r < 0) throw new IOException("short read");
            }
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(new java.io.ByteArrayInputStream(compressed)))) {
                if (in.readInt() != PAYLOAD_MAGIC || in.readLong() != key.hi || in.readLong() != key.lo) return false;
                int blockBytes = in.readInt();
                if (blockBytes != target.cacheBlockBytes()) return false;
                byte[] blocks = new byte[blockBytes];
                in.readFully(blocks);
                int deepCount = in.readInt();
                if (deepCount != target.cacheDeepCount()) return false;
                int[][] deep = new int[deepCount][];
                for (int i = 0; i < deepCount; i++) {
                    int n = in.readInt();
                    if (n != target.cacheDeepLength(i)) return false;
                    int[] px = new int[n];
                    for (int j = 0; j < n; j++) px[j] = in.readInt();
                    deep[i] = px;
                }
                target.cacheApply(blocks, deep);
            }
            entries.put(key, new Entry(e.offset, e.length, System.currentTimeMillis()));
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    public void store(Key key, CacheAccess source) {
        if (broken) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bos, def))) {
                out.writeInt(PAYLOAD_MAGIC);
                out.writeLong(key.hi);
                out.writeLong(key.lo);
                byte[] blocks = source.cacheBlocks();
                out.writeInt(blocks.length);
                out.write(blocks);
                int deepCount = source.cacheDeepCount();
                out.writeInt(deepCount);
                for (int i = 0; i < deepCount; i++) {
                    int[] px = source.cacheDeep(i);
                    out.writeInt(px.length);
                    for (int v : px) out.writeInt(v);
                }
            } finally {
                def.end();
            }
            pending.add(new Pending(key, bos.toByteArray()));
        } catch (IOException ignored) {
            // in-memory streams do not fail
        }
    }

    /** Appends pending entries and rewrites the index on the IO pool. */
    public void flushAsync() {
        if (broken || pending.isEmpty()) return;
        Util.ioPool().execute(this::flush);
    }

    private void flush() {
        synchronized (ioLock) {
            try {
                List<Pending> batch = new ArrayList<>();
                for (Pending p; (p = pending.poll()) != null; ) batch.add(p);
                if (batch.isEmpty()) return;
                try (FileChannel out = FileChannel.open(pack, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    long pos = out.size();
                    long now = System.currentTimeMillis();
                    for (Pending p : batch) {
                        if (entries.containsKey(p.key)) continue;
                        ByteBuffer bb = ByteBuffer.wrap(p.payload);
                        long start = pos;
                        while (bb.hasRemaining()) pos += out.write(bb);
                        entries.put(p.key, new Entry(start, p.payload.length, now));
                    }
                    out.force(false);
                }
                compactIfNeeded();
                writeIndex();
            } catch (IOException e) {
                Vivarium.LOGGER.warn("Vivarium: writing cache failed ({}), disabling it", e.toString());
                broken = true;
            }
        }
    }

    private void compactIfNeeded() throws IOException {
        long cap = (long) Config.get(Config.CACHE_MAX_MB) * 1024 * 1024;
        long packSize = Files.size(pack);
        long live = 0;
        for (Entry e : entries.values()) live += e.length;
        if (packSize <= cap && live * 2 >= packSize) return;
        // Keep the most recently used entries up to 75% of the cap.
        List<Map.Entry<Key, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<Key, Entry> me) -> me.getValue().lastUsed).reversed());
        Path tmp = dir.resolve("sprites.pack.tmp");
        Map<Key, Entry> kept = new ConcurrentHashMap<>();
        long budget = cap * 3 / 4;
        closeChannel();
        try (FileChannel in = FileChannel.open(pack, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long pos = 0;
            for (Map.Entry<Key, Entry> me : sorted) {
                Entry e = me.getValue();
                if (pos + e.length > budget) break;
                in.transferTo(e.offset, e.length, out);
                kept.put(me.getKey(), new Entry(pos, e.length, e.lastUsed));
                pos += e.length;
            }
        }
        Files.move(tmp, pack, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        entries.clear();
        entries.putAll(kept);
    }

    private void writeIndex() throws IOException {
        Path tmp = dir.resolve("sprites.idx.tmp");
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(INDEX_MAGIC);
            out.writeInt(FORMAT);
            List<Map.Entry<Key, Entry>> list = new ArrayList<>(entries.entrySet());
            out.writeInt(list.size());
            for (Map.Entry<Key, Entry> me : list) {
                out.writeLong(me.getKey().hi);
                out.writeLong(me.getKey().lo);
                out.writeLong(me.getValue().offset);
                out.writeInt(me.getValue().length);
                out.writeLong(me.getValue().lastUsed);
            }
        }
        Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private FileChannel channel() throws IOException {
        synchronized (ioLock) {
            if (readChannel == null || !readChannel.isOpen()) readChannel = FileChannel.open(pack, StandardOpenOption.READ);
            return readChannel;
        }
    }

    private void closeChannel() throws IOException {
        if (readChannel != null) {
            readChannel.close();
            readChannel = null;
        }
    }

}
