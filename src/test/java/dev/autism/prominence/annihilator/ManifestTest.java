package dev.autism.prominence.annihilator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static dev.autism.prominence.annihilator.TestFiles.bytes;
import static dev.autism.prominence.annihilator.TestFiles.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {

    @Test
    void roundTripsSortedEntriesAndIgnoresGarbageLines(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applied.txt");
        Path options = write(dir, "options.txt", "fov:70.0\n");
        Path conf = write(dir, "conf.json", "{}");
        Files.setLastModifiedTime(options, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(conf, FileTime.fromMillis(2_000));

        Manifest manifest = Manifest.load(file);
        manifest.put("options.txt", "bbb", options);
        manifest.put("config/mod/conf.json", "aaa", conf);
        manifest.save();

        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.get(0).startsWith("#"));
        assertEquals(List.of("aaa 2 2000 config/mod/conf.json", "bbb 9 1000 options.txt"), lines.subList(1, lines.size()));

        Files.writeString(file, Files.readString(file) + "\n\ngarbage\nx notanumber 1 key\n# comment\n");
        Manifest reloaded = Manifest.load(file);
        assertEquals("aaa", reloaded.get("config/mod/conf.json"));
        assertEquals("bbb", reloaded.get("options.txt"));
        assertNull(reloaded.get("key"));
        assertNull(reloaded.get("missing"));
    }

    @Test
    void saveIsSkippedWhenNothingChanged(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applied.txt");
        Path template = write(dir, "t.json", "{}");
        Manifest manifest = Manifest.load(file);
        manifest.save();
        assertFalse(Files.exists(file));

        manifest.put("a", "1", template);
        manifest.save();
        Files.writeString(file, "# untouched marker\n");
        manifest.put("a", "1", template);
        manifest.save();
        assertEquals("# untouched marker\n", Files.readString(file));
    }

    @Test
    void templateHashIsServedFromRecordWhileFingerprintMatches(@TempDir Path dir) throws Exception {
        Path template = write(dir, "t.json", "{\"a\": 1}");
        Manifest manifest = Manifest.load(dir.resolve("applied.txt"));
        String real = Manifest.hash(template);
        manifest.put("k", "recorded-hash", template);

        assertEquals("recorded-hash", manifest.templateHash("k", template));
        assertEquals(real, manifest.templateHash("other-key", template));

        Files.writeString(template, "{\"a\": 22}");
        assertEquals(Manifest.hash(template), manifest.templateHash("k", template));

        Files.writeString(template, "{\"a\": 1}");
        Files.setLastModifiedTime(template, FileTime.fromMillis(123_456_000L));
        assertEquals(real, manifest.templateHash("k", template));
    }

    @Test
    void hashesAndComparesContent(@TempDir Path dir) throws Exception {
        Path a = write(dir, "a.json", "{\"x\": 1}");
        Path b = write(dir, "b.json", "{\"x\": 1}");
        Path c = write(dir, "c.json", "{\"x\": 2}");

        String hashA = Manifest.hash(a);
        assertEquals(hashA, Manifest.hash(b));
        assertNotEquals(hashA, Manifest.hash(c));
        assertNotNull(hashA);
        assertEquals(64, hashA.length());
        assertTrue(Manifest.sameContent(a, b));
        assertFalse(Manifest.sameContent(a, c));
        assertFalse(Manifest.sameContent(a, dir.resolve("missing.json")));
        assertNull(Manifest.hash(dir.resolve("missing.json")));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Manifest.hash(bytes(dir, "empty.bin", new byte[0])));
    }

    @Test
    void unsafeManifestKeysAndHashesAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("applied.txt");
        Path template = write(dir, "t.json", "{}");
        Files.writeString(file, String.join("\n",
            "# header",
            "abc 1 1 foo/./bar.json",
            "abc 1 1 ../escape.json",
            "abc 1 1 path//double.json",
            "not hex!! 1 1 ok.json",
            "deadbeef 1 1 config/ok.json",
            ""));
        Manifest loaded = Manifest.load(file);
        assertNull(loaded.get("foo/./bar.json"));
        assertNull(loaded.get("../escape.json"));
        assertNull(loaded.get("path//double.json"));
        assertNull(loaded.get("ok.json"));
        assertEquals("deadbeef", loaded.get("config/ok.json"));

        Manifest fresh = Manifest.load(dir.resolve("other.txt"));
        fresh.put("foo/./bar.json", "abc", template);
        fresh.put("../x", "abc", template);
        fresh.put("good.json", "abc def", template);
        fresh.put("good2.json", "abc", template);
        fresh.put(null, "abc", template);
        fresh.put("good3.json", null, template);
        fresh.put("good4.json", "abc", null);
        fresh.save();
        assertNull(fresh.get("foo/./bar.json"));
        assertNull(fresh.get("../x"));
        assertNull(fresh.get("good.json"));
        assertEquals("abc", fresh.get("good2.json"));
        assertEquals("abc", fresh.get("good4.json"));
        assertNull(fresh.templateHash("good2.json", null));
        assertNull(Manifest.hash(null));
        assertFalse(Manifest.sameContent(null, template));
        assertFalse(Manifest.sameContent(template, null));
    }
}
