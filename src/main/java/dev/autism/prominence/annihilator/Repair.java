package dev.autism.prominence.annihilator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Repair {
    private static final Logger LOGGER = LoggerFactory.getLogger(Annihilator.MOD_ID);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LOG_HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Pattern SNAPSHOT_PATTERN = Pattern.compile("^\\d{8}_\\d{6}$");
    static final String BACKUPS_DIR = Annihilator.MOD_ID + "_backups";
    static final int MAX_BACKUPS = 20;

    private Repair() {
    }

    static void run(Path gameDir) {
        Path config = gameDir.resolve("config");
        Path templates = config.resolve(Annihilator.MOD_ID).resolve("defaults");
        Path backupsDir = gameDir.resolve(BACKUPS_DIR);
        Path snapshot = backupsDir.resolve(STAMP.format(LocalDateTime.now()));
        List<String> log = new ArrayList<>();
        int scanned = 0;
        boolean snapshotWritten = false;

        try {
            List<Path> files = ConfigWalk.list(config);
            scanned = files.size();
            for (Path file : files) {
                if (!Integrity.isBroken(file)) {
                    continue;
                }
                Path rel = config.relativize(file);
                String name = rel.toString().replace('\\', '/');
                LOGGER.warn("Corrupt config detected: {}", name);
                if (backup(file, snapshot.resolve(rel), name, log)) {
                    snapshotWritten = true;
                    restore(templates.resolve(rel), file, name, log);
                }
            }
        } catch (Exception e) {
            log.add("walk-fail " + e);
            LOGGER.error("Failed to walk config directory: {}", e.getMessage(), e);
        }

        if (log.isEmpty()) {
            LOGGER.info("Config scan complete. All {} configs healthy.", scanned);
            log.add("scan-ok scanned=" + scanned);
        } else {
            LOGGER.info("Config scan complete. {} actions taken across {} configs.", log.size(), scanned);
            if (snapshotWritten) {
                pruneOldBackups(backupsDir);
            }
        }

        writeLog(gameDir.resolve("logs").resolve(Annihilator.MOD_ID + ".log"), log);
    }

    private static boolean backup(Path file, Path target, String name, List<String> log) {
        try {
            Files.createDirectories(target.getParent());
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            log.add("backup-fail " + name + " " + e);
            LOGGER.error("Failed to backup {}: {}", name, e.getMessage(), e);
            return false;
        }
    }

    private static void restore(Path template, Path file, String name, List<String> log) {
        if (!Files.isRegularFile(template)) {
            log.add("quarantine " + name);
            LOGGER.info("Quarantined {} (no clean template found in defaults)", name);
            return;
        }
        if (Integrity.isBroken(template)) {
            log.add("corrupt-template " + name);
            LOGGER.error("Default template for {} is also corrupt! Quarantining without restore.", name);
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.copy(template, file, StandardCopyOption.REPLACE_EXISTING);
            log.add("restore " + name);
            LOGGER.info("Restored {} from clean template", name);
        } catch (Exception e) {
            log.add("restore-fail " + name + " " + e);
            LOGGER.error("Failed to restore clean template for {}: {}", name, e.getMessage(), e);
        }
    }

    static void pruneOldBackups(Path backupsDir) {
        if (!Files.isDirectory(backupsDir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> stream = Files.list(backupsDir)) {
            List<Path> dirs = stream
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(Repair::isSnapshotDir)
                .sorted(Comparator
                    .comparingLong(Repair::lastModifiedMillis)
                    .thenComparing(path -> path.getFileName().toString()))
                .toList();
            if (dirs.size() <= MAX_BACKUPS) {
                return;
            }
            int toDelete = dirs.size() - MAX_BACKUPS;
            int pruned = 0;
            for (int i = 0; i < toDelete; i++) {
                Path old = dirs.get(i);
                try {
                    deleteRecursively(old);
                    pruned++;
                } catch (Exception e) {
                    LOGGER.warn("Failed to delete old backup {}: {}", old.getFileName(), e.getMessage(), e);
                }
            }
            if (pruned > 0) {
                LOGGER.info("Pruned {} old backup snapshot(s), keeping newest {}", pruned, MAX_BACKUPS);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to prune old backups: {}", e.getMessage(), e);
        }
    }

    static boolean isSnapshotDir(Path dir) {
        String name = dir.getFileName().toString();
        if (!SNAPSHOT_PATTERN.matcher(name).matches()) {
            return false;
        }
        try {
            STAMP.parse(name);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void writeLog(Path file, List<String> log) {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>(log.size() + 2);
            lines.add("===== " + LOG_HEADER_TIME.format(LocalDateTime.now()) + " =====");
            lines.addAll(log);
            lines.add("");
            Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOGGER.error("Failed to write {}: {}", file, e.getMessage(), e);
        }
    }
}
