package dev.autism.prominence.annihilator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.autism.prominence.annihilator.TestFiles.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairTest {

    @Test
    void brokenFileWithTemplateIsBackedUpAndRestored(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path live = write(config.resolve("puffish_skills"), "foo.json", "{ truncated");
        write(config.resolve(Annihilator.MOD_ID).resolve("defaults").resolve("puffish_skills"),
            "foo.json", "{\"pack\": true}");

        Repair.run(dir);

        assertEquals("{\"pack\": true}", Files.readString(live));
        assertEquals(List.of("restore puffish_skills/foo.json"), logEntries(dir));
        assertEquals("{ truncated", Files.readString(onlyBackup(dir).resolve("puffish_skills/foo.json")));
    }

    @Test
    void brokenFileWithoutTemplateIsQuarantined(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path live = write(config, "orphan.toml", "[general\n");
        Path healthy = write(config, "healthy.toml", "[general]\nkey = 1\n");

        Repair.run(dir);

        assertFalse(Files.exists(live));
        assertTrue(Files.exists(healthy));
        assertEquals(List.of("quarantine orphan.toml"), logEntries(dir));
        assertEquals("[general\n", Files.readString(onlyBackup(dir).resolve("orphan.toml")));
    }

    @Test
    void brokenTemplateIsNotRestored(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path live = write(config.resolve("puffish_skills"), "foo.json", "{ broken");
        write(config.resolve(Annihilator.MOD_ID).resolve("defaults").resolve("puffish_skills"),
            "foo.json", "{ also broken");

        Repair.run(dir);

        assertFalse(Files.exists(live));
        assertEquals(List.of("corrupt-template puffish_skills/foo.json"), logEntries(dir));
        assertEquals("{ broken", Files.readString(onlyBackup(dir).resolve("puffish_skills/foo.json")));
    }

    @Test
    void cleanRunWritesScanOkLogAndCreatesNoBackups(@TempDir Path dir) throws Exception {
        write(dir.resolve("config"), "healthy.json", "{}");

        Repair.run(dir);

        assertEquals(List.of("scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void missingConfigDirectoryIsNotAnError(@TempDir Path dir) throws Exception {
        Repair.run(dir);

        assertEquals(List.of("scan-ok scanned=0"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void runWithActionsPrunesOldSnapshots(@TempDir Path dir) throws Exception {
        Path backupsDir = dir.resolve(Repair.BACKUPS_DIR);
        for (int i = 1; i <= Repair.MAX_BACKUPS; i++) {
            write(backupsDir.resolve(String.format("20200101_%06d", i)), "old.json", "{");
        }
        write(dir.resolve("config"), "broken.json", "{");

        Repair.run(dir);

        try (var stream = Files.list(backupsDir)) {
            List<Path> remaining = stream.sorted().toList();
            assertEquals(Repair.MAX_BACKUPS, remaining.size());
            assertEquals("20200101_000002", remaining.get(0).getFileName().toString());
            assertTrue(Files.exists(remaining.get(Repair.MAX_BACKUPS - 1).resolve("broken.json")));
        }
    }

    @Test
    void oldBackupsArePrunedBeyondLimit(@TempDir Path dir) throws Exception {
        Path backupsDir = dir.resolve(Repair.BACKUPS_DIR);
        for (int i = 1; i <= 25; i++) {
            String stamp = String.format("20260101_%06d", i);
            write(backupsDir.resolve(stamp).resolve("nested").resolve("deeper"), "test.txt", "dummy");
        }

        Repair.pruneOldBackups(backupsDir);

        try (var stream = Files.list(backupsDir)) {
            List<Path> remaining = stream.sorted().toList();
            assertEquals(Repair.MAX_BACKUPS, remaining.size());
            assertEquals("20260101_000006", remaining.get(0).getFileName().toString());
            assertEquals("20260101_000025", remaining.get(Repair.MAX_BACKUPS - 1).getFileName().toString());
        }
    }

    @Test
    void pruneLeavesNonSnapshotDirectoriesAlone(@TempDir Path dir) throws Exception {
        Path backupsDir = dir.resolve(Repair.BACKUPS_DIR);
        for (int i = 1; i <= Repair.MAX_BACKUPS + 3; i++) {
            write(backupsDir.resolve(String.format("20260101_%06d", i)), "old.json", "{");
        }
        Path custom = write(backupsDir.resolve("manual_keep"), "notes.txt", "do not delete");
        Path dashed = write(backupsDir.resolve("2026-01-01"), "x.json", "{");
        Path invalid = write(backupsDir.resolve("20201301_990000"), "bad.json", "{");

        Repair.pruneOldBackups(backupsDir);

        assertTrue(Files.exists(custom));
        assertTrue(Files.exists(dashed));
        assertTrue(Files.exists(invalid));
        try (var stream = Files.list(backupsDir)) {
            List<String> snapshots = stream
                .filter(Repair::isSnapshotDir)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
            assertEquals(Repair.MAX_BACKUPS, snapshots.size());
            assertEquals("20260101_000004", snapshots.get(0));
            assertEquals("20260101_000023", snapshots.get(Repair.MAX_BACKUPS - 1));
        }
    }

    @Test
    void dedicatedLogAppendsWithTimestampHeader(@TempDir Path dir) throws Exception {
        write(dir.resolve("config"), "healthy.json", "{}");

        Repair.run(dir);
        Repair.run(dir);

        List<String> lines = Files.readAllLines(dedicatedLog(dir));
        List<String> headers = lines.stream().filter(RepairTest::isLogHeader).toList();
        assertEquals(2, headers.size());
        assertEquals(List.of("scan-ok scanned=1", "scan-ok scanned=1"), logEntries(dir));
    }

    private static Path dedicatedLog(Path gameDir) {
        return gameDir.resolve("logs").resolve(Annihilator.MOD_ID + ".log");
    }

    private static List<String> logEntries(Path gameDir) throws Exception {
        return Files.readAllLines(dedicatedLog(gameDir)).stream()
            .filter(line -> !line.isEmpty() && !isLogHeader(line))
            .toList();
    }

    private static boolean isLogHeader(String line) {
        return line.startsWith("===== ") && line.endsWith(" =====");
    }

    private static Path onlyBackup(Path gameDir) throws Exception {
        try (var stamps = Files.list(gameDir.resolve(Repair.BACKUPS_DIR))) {
            return stamps.findFirst().orElseThrow();
        }
    }
}
