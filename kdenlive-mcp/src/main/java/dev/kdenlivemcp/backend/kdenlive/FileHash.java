package dev.kdenlivemcp.backend.kdenlive;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kdenlive's {@code kdenlive:file_hash}: MD5 of the whole file if it is at most 2,000,000 bytes, otherwise of the
 * first and last 1,000,000 bytes (ProjectClip::calculateHash). Cached by path, size and mtime.
 */
public final class FileHash {

    private record Key(Path path, long size, long mtime) {
    }

    private static final Map<Key, String> CACHE = new ConcurrentHashMap<>();

    private FileHash() {
    }

    /** Returns the hash, or null when the file is missing. */
    public static String of(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Key key = new Key(file.toAbsolutePath(), Files.size(file), Files.getLastModifiedTime(file).toMillis());
            return CACHE.computeIfAbsent(key, k -> compute(k.path(), k.size()));
        } catch (IOException e) {
            return null;
        }
    }

    private static String compute(Path file, long size) {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            if (size > 2_000_000) {
                md5.update(read(raf, 0, 1_000_000));
                md5.update(read(raf, size - 1_000_000, 1_000_000));
            } else {
                md5.update(read(raf, 0, (int) size));
            }
            return HexFormat.of().formatHex(md5.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] read(RandomAccessFile raf, long pos, int len) throws IOException {
        byte[] b = new byte[len];
        raf.seek(pos);
        raf.readFully(b);
        return b;
    }
}
