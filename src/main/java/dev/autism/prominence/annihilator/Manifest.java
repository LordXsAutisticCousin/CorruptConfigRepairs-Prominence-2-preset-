package dev.autism.prominence.annihilator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class Manifest {
    static final String FILE_NAME = "applied.txt";
    static final long MAX_TRACKED_SIZE = 64L << 20;
    static final long MAX_MANIFEST_SIZE = 8L << 20;
    private static final String HEADER =
        "# sha256 size mtime path-relative-to-game-dir. Managed by CorruptConfigAnnihilator; do not edit.";

    private record Entry(String hash, long size, long mtime) {
    }

    private final Path file;
    private final Map<String, Entry> entries = new TreeMap<>();
    private boolean dirty;

    private Manifest(Path file) {
        this.file = file;
    }

    static Manifest load(Path file) {
        Manifest manifest = new Manifest(file);
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_MANIFEST_SIZE) {
                return manifest;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(" ", 4);
                if (parts.length < 4 || parts[0].isEmpty() || parts[3].isEmpty()) {
                    continue;
                }
                String key = parts[3].replace('\\', '/');
                if (isUnsafeKey(key) || isUnsafeHash(parts[0])) {
                    continue;
                }
                try {
                    long size = Long.parseLong(parts[1]);
                    long mtime = Long.parseLong(parts[2]);
                    if (size < -1 || mtime < -1) {
                        continue;
                    }
                    manifest.entries.put(key, new Entry(parts[0], size, mtime));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            manifest.entries.clear();
        }
        return manifest;
    }

    private static boolean isUnsafeHash(String hash) {
        if (hash.isEmpty() || hash.length() > 128) {
            return true;
        }
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '-' || c == '_';
            if (!ok) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsafeKey(String key) {
        if (key.isEmpty() || key.length() > 1024 || key.charAt(0) == '/' || key.charAt(0) == '\\') {
            return true;
        }
        if (key.indexOf('\0') >= 0 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0 || key.indexOf(':') >= 0) {
            return true;
        }
        if (key.equals(".") || key.equals("..")) {
            return true;
        }
        if (key.startsWith("./") || key.startsWith("../") || key.endsWith("/.") || key.endsWith("/..")) {
            return true;
        }
        return key.contains("/./") || key.contains("/../") || key.contains("//");
    }

    String get(String key) {
        Entry entry = entries.get(key);
        return entry == null ? null : entry.hash();
    }

    void put(String key, String hash, Path template) {
        if (hash == null || key == null || isUnsafeKey(key) || isUnsafeHash(hash)) {
            return;
        }
        long size = -1;
        long mtime = -1;
        if (template != null) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(template, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile()) {
                    return;
                }
                size = attrs.size();
                mtime = attrs.lastModifiedTime().toMillis();
            } catch (IOException ignored) {
            }
        }
        Entry next = new Entry(hash, size, mtime);
        if (!next.equals(entries.put(key, next))) {
            dirty = true;
        }
    }

    String templateHash(String key, Path template) {
        if (template == null) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry != null && entry.size() >= 0) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(template, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isRegularFile()
                    && attrs.size() == entry.size()
                    && attrs.lastModifiedTime().toMillis() == entry.mtime()) {
                    return entry.hash();
                }
            } catch (IOException ignored) {
            }
        }
        return hash(template);
    }

    void save() throws IOException {
        if (!dirty) {
            return;
        }
        List<String> lines = new ArrayList<>(entries.size() + 1);
        lines.add(HEADER);
        entries.forEach((key, entry) -> lines.add(entry.hash() + " " + entry.size() + " " + entry.mtime() + " " + key));
        Path tmp = Repair.tmpBeside(file);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try {
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Repair.moveAtomic(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
        dirty = false;
    }

    static String hash(Path file) {
        if (file == null) {
            return null;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.size() > MAX_TRACKED_SIZE) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > MAX_TRACKED_SIZE) {
                        return null;
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    static boolean sameContent(Path a, Path b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(b, LinkOption.NOFOLLOW_LINKS)
                && Files.mismatch(a, b) == -1L;
        } catch (IOException e) {
            return false;
        }
    }
}
