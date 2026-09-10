package dev.autism.prominence.annihilator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static dev.autism.prominence.annihilator.TestFiles.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void defaultsAppliedOnFirstLaunch(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "options.txt", "fov:70.0\nkey_key.jump:key.keyboard.space\n");
        write(defaults.resolve("rei"), "config.json5", "{\"version\": 1}");
        TestFiles.bytes(defaults, "servers.dat", new byte[]{0x0A, 0x00, 0x00, 0x00});

        Repair.run(dir);

        assertEquals("fov:70.0\nkey_key.jump:key.keyboard.space\n", Files.readString(dir.resolve("options.txt")));
        assertEquals("{\"version\": 1}", Files.readString(dir.resolve("config/rei/config.json5")));
        assertTrue(Files.exists(dir.resolve("servers.dat")));
        assertEquals(List.of(
            "default-apply options.txt",
            "default-apply rei/config.json5",
            "default-apply servers.dat"
        ), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void defaultsDoNotOverwriteExistingHealthyFiles(@TempDir Path dir) throws Exception {
        write(dir, "options.txt", "fov:110.0\nkey_key.jump:key.keyboard.left.shift\n");
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "options.txt", "fov:70.0\nkey_key.jump:key.keyboard.space\n");

        Repair.run(dir);

        assertEquals("fov:110.0\nkey_key.jump:key.keyboard.left.shift\n", Files.readString(dir.resolve("options.txt")));
        assertEquals(List.of("scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void corruptOptionsTxtIsBackedUpAndRestored(@TempDir Path dir) throws Exception {
        write(dir, "options.txt", "");
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "options.txt", "fov:70.0\n");

        Repair.run(dir);

        assertEquals("fov:70.0\n", Files.readString(dir.resolve("options.txt")));
        assertEquals(List.of("restore options.txt"), logEntries(dir));
        assertTrue(Files.exists(onlyBackup(dir).resolve("options.txt")));
    }

    @Test
    void corruptBinaryFileIsRestored(@TempDir Path dir) throws Exception {
        TestFiles.bytes(dir, "servers.dat", new byte[0]);
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        TestFiles.bytes(defaults, "servers.dat", new byte[]{0x0A, 0x00, 0x01});

        Repair.run(dir);

        assertEquals(3, Files.size(dir.resolve("servers.dat")));
        assertEquals(List.of("restore servers.dat"), logEntries(dir));
    }

    @Test
    void updatedTemplateRollsOutToFilesThePlayerNeverTouched(@TempDir Path dir) throws Exception {
        Path templates = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        Path template = write(templates.resolve("mod"), "conf.json", "{\"v\": 1}");
        Path live = dir.resolve("config/mod/conf.json");

        Repair.run(dir);
        assertEquals("{\"v\": 1}", Files.readString(live));

        Files.writeString(template, "{\"v\": 2, \"added\": true}");
        Repair.run(dir);

        assertEquals("{\"v\": 2, \"added\": true}", Files.readString(live));
        assertEquals(List.of("default-apply mod/conf.json", "default-update mod/conf.json"), logEntries(dir));
        assertEquals("{\"v\": 1}", Files.readString(onlyBackup(dir).resolve("mod/conf.json")));
    }

    @Test
    void updatedTemplateNeverTouchesPlayerModifiedFiles(@TempDir Path dir) throws Exception {
        Path templates = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        Path template = write(templates.resolve("mod"), "conf.json", "{\"v\": 1}");
        Path live = dir.resolve("config/mod/conf.json");

        Repair.run(dir);
        Files.writeString(live, "{\"v\": 1, \"mine\": true}");
        Files.writeString(template, "{\"v\": 2, \"added\": true}");
        Repair.run(dir);
        Files.writeString(template, "{\"v\": 3, \"added\": true, \"more\": 1}");
        Repair.run(dir);

        assertEquals("{\"v\": 1, \"mine\": true}", Files.readString(live));
        assertEquals(List.of("default-apply mod/conf.json", "scan-ok scanned=1", "scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void preExistingFileIdenticalToTemplateIsAdoptedForUpdates(@TempDir Path dir) throws Exception {
        Path templates = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        Path template = write(templates, "shipped.toml", "[a]\nb = 1\n");
        Path live = write(dir.resolve("config"), "shipped.toml", "[a]\nb = 1\n");

        Repair.run(dir);
        Files.writeString(template, "[a]\nb = 2\nc = 3\n");
        Repair.run(dir);

        assertEquals("[a]\nb = 2\nc = 3\n", Files.readString(live));
        assertEquals(List.of("scan-ok scanned=1", "default-update shipped.toml"), logEntries(dir));
    }

    @Test
    void preExistingDivergedFileIsRecordedOnceAndNeverUpdated(@TempDir Path dir) throws Exception {
        Path templates = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        Path template = write(templates.resolve("mod"), "conf.json", "{\"v\": 1}");
        Path live = write(dir.resolve("config").resolve("mod"), "conf.json", "{\n  \"v\": 1\n}\n");
        Path manifest = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve(Manifest.FILE_NAME);

        Repair.run(dir);
        assertTrue(Files.readString(manifest).contains(" config/mod/conf.json"));
        Files.writeString(template, "{\"v\": 2}");
        Repair.run(dir);
        Repair.run(dir);

        assertEquals("{\n  \"v\": 1\n}\n", Files.readString(live));
        assertEquals(List.of("scan-ok scanned=1", "scan-ok scanned=1", "scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void manifestIsOnlyWrittenWhenSomethingWasTracked(@TempDir Path dir) throws Exception {
        Path manifest = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve(Manifest.FILE_NAME);
        write(dir.resolve("config"), "healthy.json", "{}");
        Repair.run(dir);
        assertFalse(Files.exists(manifest));

        write(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults"), "options.txt", "fov:70.0\n");
        Repair.run(dir);

        assertTrue(Files.exists(manifest));
        assertTrue(Files.readString(manifest).contains(" options.txt"));
        assertEquals(List.of("scan-ok scanned=1", "default-apply options.txt"), logEntries(dir));
    }

    @Test
    void overridesAreAlwaysEnforcedButOnlyWhenContentDiffers(@TempDir Path dir) throws Exception {
        Path overrides = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("overrides");
        write(overrides.resolve("config").resolve("locked"), "rules.json", "{\"pvp\": false}");
        write(overrides, "servers.dat", "nbt");
        Path live = write(dir.resolve("config").resolve("locked"), "rules.json", "{\"pvp\": true}");

        Repair.run(dir);
        Repair.run(dir);

        assertEquals("{\"pvp\": false}", Files.readString(live));
        assertEquals("nbt", Files.readString(dir.resolve("servers.dat")));
        assertEquals(List.of(
            "override-apply locked/rules.json",
            "override-apply servers.dat",
            "scan-ok scanned=2"
        ), logEntries(dir));
        assertEquals("{\"pvp\": true}", Files.readString(onlyBackup(dir).resolve("locked/rules.json")));
    }

    @Test
    void overrideWinsOverDefaultForTheSameTarget(@TempDir Path dir) throws Exception {
        Path own = dir.resolve("config").resolve(Annihilator.MOD_ID);
        write(own.resolve("overrides").resolve("config"), "shared.json", "{\"from\": \"override\"}");
        write(own.resolve("defaults"), "shared.json", "{\"from\": \"default\"}");

        Repair.run(dir);

        assertEquals("{\"from\": \"override\"}", Files.readString(dir.resolve("config/shared.json")));
        assertEquals(List.of("override-apply shared.json"), logEntries(dir));
    }

    @Test
    void templatePointingAtDirectoryIsSkipped(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path inside = write(config.resolve("moddir"), "keep.json", "{}");
        write(config.resolve(Annihilator.MOD_ID).resolve("defaults"), "moddir", "not a directory");

        Repair.run(dir);

        assertTrue(Files.isDirectory(config.resolve("moddir")));
        assertTrue(Files.exists(inside));
        assertEquals(List.of("skip-dir moddir"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void unparseableFileIdenticalToTemplateIsNotTreatedAsCorrupt(@TempDir Path dir) throws Exception {
        byte[] binary = {0x01, 0x00, 0x02, 0x00};
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        TestFiles.bytes(defaults, "custom.cfg", binary);
        TestFiles.bytes(dir.resolve("config"), "custom.cfg", binary);

        Repair.run(dir);
        Repair.run(dir);

        assertEquals(List.of("scan-ok scanned=1", "scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void opaqueBinaryTemplatesAreProvisionedLikeAnyOther(@TempDir Path dir) throws Exception {
        byte[] binary = {0x00, 0x00, 0x01, 0x02};
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        TestFiles.bytes(defaults.resolve("prominent").resolve("dimensions").resolve("region"), "r.0.0.mca", binary);
        TestFiles.bytes(defaults.resolve("crash_assistant"), "logo.gif", binary);
        TestFiles.bytes(defaults, "CinderstoneStudios", binary);

        Repair.run(dir);

        assertTrue(Files.exists(dir.resolve("config/prominent/dimensions/region/r.0.0.mca")));
        assertTrue(Files.exists(dir.resolve("config/crash_assistant/logo.gif")));
        assertTrue(Files.exists(dir.resolve("config/CinderstoneStudios")));
        assertEquals(List.of(
            "default-apply CinderstoneStudios",
            "default-apply crash_assistant/logo.gif",
            "default-apply prominent/dimensions/region/r.0.0.mca"
        ), logEntries(dir));
    }

    @Test
    void nestedConfigFolderShippedInsideDefaultsIsAppliedOnTheSameLaunch(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults.resolve("config"), "mod.json", "{}");
        write(defaults, "options.txt", "fov:70.0\n");

        Repair.run(dir);

        assertEquals("fov:70.0\n", Files.readString(dir.resolve("options.txt")));
        assertEquals("{}", Files.readString(dir.resolve("config/mod.json")));
        assertEquals(List.of(
            "default-apply mod.json",
            "default-apply options.txt"
        ), logEntries(dir));
    }

    @Test
    void junkInTemplateFoldersIsNeverCopied(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, ".gitkeep", "");
        write(defaults, "Thumbs.db", "junk");
        write(defaults.resolve("config"), ".DS_Store", "junk");

        Repair.run(dir);

        assertFalse(Files.exists(dir.resolve(".gitkeep")));
        assertFalse(Files.exists(dir.resolve("Thumbs.db")));
        assertFalse(Files.exists(dir.resolve("config/.DS_Store")));
        assertEquals(List.of("scan-ok scanned=0"), logEntries(dir));
    }

    @Test
    void writesLeaveNoTemporaryFilesBehind(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "options.txt", "fov:70.0\n");
        write(defaults.resolve("config"), "broken.json", "{\"ok\": true}");
        write(dir.resolve("config"), "broken.json", "{");

        Repair.run(dir);

        try (var stream = Files.walk(dir)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().endsWith(Repair.TMP_SUFFIX)));
        }
        assertEquals(List.of("restore broken.json", "default-apply options.txt"), logEntries(dir));
    }

    @Test
    void ignoredFilesAreNeverTouchedButStillSeededWhenMissing(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path own = config.resolve(Annihilator.MOD_ID);
        write(own, Ignore.FILE_NAME, "# formats this pack knows are fine\nconfig/weird/\n*.sol.json\n");
        Path corruptWithTemplate = write(config.resolve("weird"), "a.json", "{ broken");
        write(own.resolve("defaults").resolve("weird"), "a.json", "{}");
        write(own.resolve("defaults").resolve("weird"), "b.json", "{\"seeded\": true}");
        Path overridden = write(config.resolve("weird"), "c.json", "{\"player\": true}");
        write(own.resolve("overrides").resolve("config").resolve("weird"), "c.json", "{\"pack\": true}");
        Path orphan = write(config.resolve("sortilege"), "x.sol.json", "\0\0garbage");
        Path notIgnored = write(config, "other.json", "{ broken too");

        Repair.run(dir);

        assertEquals("{ broken", Files.readString(corruptWithTemplate));
        assertEquals("{\"player\": true}", Files.readString(overridden));
        assertTrue(Files.exists(orphan));
        assertFalse(Files.exists(notIgnored));
        assertEquals("{\"seeded\": true}", Files.readString(config.resolve("weird/b.json")));
        assertEquals(List.of("default-apply weird/b.json", "quarantine other.json"), logEntries(dir));
    }

    @Test
    void repeatedCorruptionOfTheSameFileIsCalledOut(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path backupsDir = dir.resolve(Repair.BACKUPS_DIR);
        write(backupsDir.resolve("20200101_000001").resolve("mod"), "conf.json", "{ old");
        write(backupsDir.resolve("20200101_000002").resolve("mod"), "conf.json", "{ older");
        write(backupsDir.resolve("20200101_000002"), "unrelated.toml", "[x");
        write(backupsDir.resolve("20200101_000003").resolve("mod"), "conf.json", "{ oldest");
        write(config.resolve("mod"), "conf.json", "{ broken again");
        write(config.resolve(Annihilator.MOD_ID).resolve("defaults").resolve("mod"), "conf.json", "{}");
        write(config, "unrelated.toml", "[x");

        Repair.run(dir);

        assertEquals("{}", Files.readString(config.resolve("mod/conf.json")));
        assertEquals(List.of(
            "repeat-corrupt mod/conf.json streak=4",
            "restore mod/conf.json",
            "quarantine unrelated.toml"
        ), logEntries(dir));
    }

    @Test
    void aGapInTheSnapshotsBreaksTheStreak(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path backupsDir = dir.resolve(Repair.BACKUPS_DIR);
        write(backupsDir.resolve("20200101_000001"), "conf.json", "{ old");
        write(backupsDir.resolve("20200101_000002"), "other.json", "{ old");
        write(backupsDir.resolve("20200101_000003"), "conf.json", "{ old");
        write(config, "conf.json", "{ broken");

        Repair.run(dir);

        assertEquals(List.of("quarantine conf.json"), logEntries(dir));
    }

    @Test
    void optionsMissingFromThePlayersFileAreAppendedOncePerTemplateVersion(@TempDir Path dir) throws Exception {
        Path template = write(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults"), "options.txt",
            "version:3465\nfov:0.0\nkey_key.newmod.open:key.keyboard.g\n");
        Path live = write(dir, "options.txt", "version:3465\r\nfov:1.0\r\nkey_key.jump:key.keyboard.space\r\n");

        Repair.run(dir);
        assertEquals("version:3465\r\nfov:1.0\r\nkey_key.jump:key.keyboard.space\r\nkey_key.newmod.open:key.keyboard.g\r\n",
            Files.readString(live));
        assertEquals("version:3465\r\nfov:1.0\r\nkey_key.jump:key.keyboard.space\r\n",
            Files.readString(onlyBackup(dir).resolve("options.txt")));

        Files.writeString(live, "version:3465\r\nfov:1.0\r\n");
        Repair.run(dir);
        assertEquals("version:3465\r\nfov:1.0\r\n", Files.readString(live));

        Files.writeString(template, "version:3465\nfov:0.0\nkey_key.newmod.open:key.keyboard.g\nkey_key.other:key.keyboard.h\n");
        Repair.run(dir);
        assertEquals("version:3465\r\nfov:1.0\r\nkey_key.newmod.open:key.keyboard.g\r\nkey_key.other:key.keyboard.h\r\n",
            Files.readString(live));

        assertEquals(List.of(
            "options-merge options.txt added=1",
            "scan-ok scanned=1",
            "options-merge options.txt added=2"
        ), logEntries(dir));
    }

    @Test
    void optionsMergeIsANoOpWhenNothingIsMissing(@TempDir Path dir) throws Exception {
        write(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults"), "options.txt", "version:3465\nfov:0.0\n");
        Path live = write(dir, "options.txt", "fov:1.0\nversion:3465\n");

        Repair.run(dir);
        Repair.run(dir);

        assertEquals("fov:1.0\nversion:3465\n", Files.readString(live));
        assertEquals(List.of("scan-ok scanned=1", "scan-ok scanned=1"), logEntries(dir));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void optionsMergeOnlyAppliesToTheGameRootOptionsFile(@TempDir Path dir) throws Exception {
        write(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults").resolve("somemod"), "options.txt", "a:1\nb:2\n");
        Path live = write(dir.resolve("config").resolve("somemod"), "options.txt", "a:1\n");

        Repair.run(dir);

        assertEquals("a:1\n", Files.readString(live));
        assertEquals(List.of("scan-ok scanned=0"), logEntries(dir));
    }

    @Test
    void rootRelativeOptionsFilesInDefaultsWithoutConfigDir(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "optionsof.txt", "ofFastRender:false\n");
        write(defaults, "optionsshaders.txt", "shaderPack=Complementary.zip\n");
        write(defaults, "mod.json", "{\"mod\": true}");

        Repair.run(dir);

        assertEquals("ofFastRender:false\n", Files.readString(dir.resolve("optionsof.txt")));
        assertEquals("shaderPack=Complementary.zip\n", Files.readString(dir.resolve("optionsshaders.txt")));
        assertEquals("{\"mod\": true}", Files.readString(dir.resolve("config/mod.json")));
        assertEquals(List.of(
            "default-apply mod.json",
            "default-apply optionsof.txt",
            "default-apply optionsshaders.txt"
        ), logEntries(dir));
    }

    @Test
    void flatDefaultsDoNotTreatOptionalTxtOrSavesOrModsAsGameRoot(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "optional.txt", "not options\n");
        write(defaults.resolve("saves").resolve("world"), "level.dat", "world");
        write(defaults.resolve("mods"), "extra.jar", "jar");
        write(defaults, "servers.dat", "nbt");

        Repair.run(dir);

        assertEquals("not options\n", Files.readString(dir.resolve("config/optional.txt")));
        assertEquals("world", Files.readString(dir.resolve("config/saves/world/level.dat")));
        assertEquals("jar", Files.readString(dir.resolve("config/mods/extra.jar")));
        assertEquals("nbt", Files.readString(dir.resolve("servers.dat")));
        assertFalse(Files.exists(dir.resolve("optional.txt")));
        assertFalse(Files.exists(dir.resolve("saves")));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void resolveInsideRejectsEscapesAndProtectedPaths(@TempDir Path dir) throws Exception {
        Path game = Files.createDirectories(dir.resolve("game")).toAbsolutePath().normalize();
        assertNull(Repair.resolveInside(game, Path.of("..", "outside.txt")));
        assertNull(Repair.resolveInside(game, Path.of(".")));
        assertNull(Repair.resolveInside(game, Path.of("a", "..", "b.txt")));
        assertEquals(game.resolve("options.txt").normalize(), Repair.resolveInside(game, Path.of("options.txt")));
        assertTrue(Repair.isProtected(game, game.resolve(Repair.BACKUPS_DIR).resolve("x")));
        assertTrue(Repair.isProtected(game, game.resolve("config").resolve(Annihilator.MOD_ID).resolve(Manifest.FILE_NAME)));
        assertTrue(Repair.isProtected(game, game.resolve("config").resolve(Annihilator.MOD_ID).resolve(Ignore.FILE_NAME)));
        assertTrue(Repair.isProtected(game, game.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults").resolve("x.json")));
        assertTrue(Repair.isProtected(game, dir.resolve("outside.txt")));
        assertFalse(Repair.isProtected(game, game.resolve("config").resolve("mod.json")));
    }

    @Test
    void templatesCannotWriteIntoBackupsOrOwnConfigTree(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults.resolve("config").resolve("mod"), "ok.json", "{}");
        write(defaults.resolve(Repair.BACKUPS_DIR).resolve("20200101_000001"), "evil.json", "{\"evil\":true}");
        write(defaults.resolve("config").resolve(Annihilator.MOD_ID), Manifest.FILE_NAME, "garbage");
        write(defaults.resolve("config").resolve(Annihilator.MOD_ID), Ignore.FILE_NAME, "should-not-apply");
        write(defaults, "LICENSE", "skip me");
        write(defaults, "readme.md", "also skip");

        Repair.run(dir);

        assertEquals("{}", Files.readString(dir.resolve("config/mod/ok.json")));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR).resolve("20200101_000001").resolve("evil.json")));
        assertFalse(Files.exists(dir.resolve("LICENSE")));
        assertFalse(Files.exists(dir.resolve("readme.md")));
        assertFalse(Files.exists(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve(Ignore.FILE_NAME)));
        String applied = Files.readString(dir.resolve("config").resolve(Annihilator.MOD_ID).resolve(Manifest.FILE_NAME));
        assertFalse(applied.startsWith("garbage"));
        assertTrue(applied.startsWith("#"));
    }

    @Test
    void gameLayoutDefaultsOnlyMapConfigAndRootAllowlist(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults.resolve("config").resolve("mod"), "a.json", "{}");
        write(defaults, "options.txt", "fov:70.0\n");
        write(defaults.resolve("datapacks").resolve("pack"), "pack.mcmeta", "{}");
        write(defaults, "stray.txt", "nope");
        write(defaults.resolve("saves").resolve("world"), "level.dat", "world");

        Repair.run(dir);

        assertEquals("{}", Files.readString(dir.resolve("config/mod/a.json")));
        assertEquals("fov:70.0\n", Files.readString(dir.resolve("options.txt")));
        assertEquals("{}", Files.readString(dir.resolve("datapacks/pack/pack.mcmeta")));
        assertFalse(Files.exists(dir.resolve("stray.txt")));
        assertFalse(Files.exists(dir.resolve("saves")));
        assertFalse(Files.exists(dir.resolve("config/stray.txt")));
    }

    @Test
    void gameLayoutDefaultsSeedNestedConfigAndOptions(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults.resolve("config").resolve("mod"), "a.json", "{}");
        write(defaults, "options.txt", "fov:80.0\n");

        Repair.run(dir);

        assertEquals("{}", Files.readString(dir.resolve("config/mod/a.json")));
        assertEquals("fov:80.0\n", Files.readString(dir.resolve("options.txt")));
        assertEquals(List.of(
            "default-apply mod/a.json",
            "default-apply options.txt"
        ), logEntries(dir));
    }

    @Test
    void oversizedOptionsMergeIsSkippedWithoutReadingHugeFiles(@TempDir Path dir) throws Exception {
        Path defaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(defaults, "options.txt", "fov:70.0\nextra:1\n");
        Path live = write(dir, "options.txt", "fov:90.0\n");
        byte[] padding = new byte[(int) Integrity.MAX_TEXT_SIZE + 1];
        Files.write(live, padding);

        Repair.run(dir);

        assertEquals(Integrity.MAX_TEXT_SIZE + 1, Files.size(live));
        assertTrue(logEntries(dir).stream().anyMatch(line -> line.startsWith("merge-fail options.txt")));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void oversizedLogIsRotatedInsteadOfGrowingForever(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("logs").resolve(Annihilator.MOD_ID + ".log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "x".repeat((int) Repair.MAX_LOG_SIZE));
        write(dir.resolve("config"), "healthy.json", "{}");

        Repair.run(dir);

        String body = Files.readString(log);
        assertFalse(body.contains("xxx"));
        assertTrue(body.contains("scan-ok"));
        assertTrue(Files.size(log) < Repair.MAX_LOG_SIZE);
    }

    @Test
    void bundledDefaultsAreAppliedWhenDiskTemplateMissing(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        Path bundledDefaults = bundled.resolve("default_configs").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(bundledDefaults.resolve("puffish_skills"), "foo.json", "{\"bundled\": true}");
        write(bundledDefaults, "options.txt", "fov:95.0\n");

        Repair.run(dir, bundled.resolve("default_configs"));

        assertEquals("{\"bundled\": true}", Files.readString(dir.resolve("config/puffish_skills/foo.json")));
        assertEquals("fov:95.0\n", Files.readString(dir.resolve("options.txt")));
        assertTrue(logEntries(dir).contains("default-apply puffish_skills/foo.json"));
        assertTrue(logEntries(dir).contains("default-apply options.txt"));
    }

    @Test
    void diskTemplateTakesPrecedenceOverBundledTemplate(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        Path diskDefaults = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(diskDefaults.resolve("mod"), "cfg.json", "{\"disk\": true}");

        Path bundledDefaults = bundled.resolve("default_configs").resolve(Annihilator.MOD_ID).resolve("defaults");
        write(bundledDefaults.resolve("mod"), "cfg.json", "{\"bundled\": true}");

        Repair.run(dir, bundled.resolve("default_configs"));

        assertEquals("{\"disk\": true}", Files.readString(dir.resolve("config/mod/cfg.json")));
    }

    @Test
    void bundledOverridesAreEnforced(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        write(dir, "servers.dat", "old_server_data");
        Path bundledOverrides = bundled.resolve("default_configs").resolve(Annihilator.MOD_ID).resolve("overrides");
        write(bundledOverrides, "servers.dat", "forced_server_data");

        Repair.run(dir, bundled.resolve("default_configs"));

        assertEquals("forced_server_data", Files.readString(dir.resolve("servers.dat")));
        assertTrue(logEntries(dir).contains("override-apply servers.dat"));
    }

    @Test
    void diskOverrideTakesPrecedenceOverBundledOverride(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        Path diskOverrides = dir.resolve("config").resolve(Annihilator.MOD_ID).resolve("overrides");
        write(diskOverrides, "servers.dat", "disk_override_data");

        Path bundledOverrides = bundled.resolve("default_configs").resolve(Annihilator.MOD_ID).resolve("overrides");
        write(bundledOverrides, "servers.dat", "bundled_override_data");

        Repair.run(dir, bundled.resolve("default_configs"));

        assertEquals("disk_override_data", Files.readString(dir.resolve("servers.dat")));
    }

    @Test
    void bundledIgnoreFileIsRespected(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        Path bundledRoot = bundled.resolve("default_configs");
        write(bundledRoot.resolve(Annihilator.MOD_ID), Ignore.FILE_NAME, "config/weird/\n");
        Path live = write(dir.resolve("config").resolve("weird"), "bad.json", "{ unparseable");

        Repair.run(dir, bundledRoot);

        assertTrue(Files.exists(live));
        assertEquals("{ unparseable", Files.readString(live));
        assertFalse(Files.exists(dir.resolve(Repair.BACKUPS_DIR)));
    }

    @Test
    void bundledDirectConfigsAreApplied(@TempDir Path dir, @TempDir Path bundled) throws Exception {
        Path bundledRoot = bundled.resolve("default_configs");
        write(bundledRoot, "MouseTweaks.cfg", "WheelScroll=true\n");
        write(bundledRoot.resolve("somemod"), "settings.toml", "key = 1\n");

        Repair.run(dir, bundledRoot);

        assertEquals("WheelScroll=true\n", Files.readString(dir.resolve("config/MouseTweaks.cfg")));
        assertEquals("key = 1\n", Files.readString(dir.resolve("config/somemod/settings.toml")));
    }

    @Test
    void bundledConfigsFromZipFileSystemWorkProperly(@TempDir Path dir, @TempDir Path tempDir) throws Exception {
        Path zipFile = tempDir.resolve("mod_bundle.jar");
        java.net.URI uri = java.net.URI.create("jar:" + zipFile.toUri());
        try (java.nio.file.FileSystem zipFs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
            Path root = zipFs.getPath("/default_configs");
            Path defaults = root.resolve(Annihilator.MOD_ID).resolve("defaults");
            Files.createDirectories(defaults.resolve("zipmod"));
            Files.writeString(defaults.resolve("zipmod/config.json"), "{\"from_zip\": true}");

            Repair.run(dir, root);
        }

        assertEquals("{\"from_zip\": true}", Files.readString(dir.resolve("config/zipmod/config.json")));
        assertTrue(logEntries(dir).contains("default-apply zipmod/config.json"));
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
