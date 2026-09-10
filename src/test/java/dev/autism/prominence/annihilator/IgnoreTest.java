package dev.autism.prominence.annihilator;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IgnoreTest {

    @Test
    void pathPatternsAreRelativeToTheGameDirectory() {
        Ignore ignore = Ignore.parse(List.of(
            "# a comment",
            "",
            "config/weird.json",
            "/config/slashed/**",
            "config/somemod/",
            "config/**/cache.json",
            "config/one-level/*.toml",
            "config\\windows\\style.json"));

        assertEquals(6, ignore.size());
        assertTrue(ignore.matches("config/weird.json"));
        assertTrue(ignore.matches("CONFIG/Weird.JSON"));
        assertFalse(ignore.matches("config/sub/weird.json"));
        assertTrue(ignore.matches("config/slashed/a/b/c.json"));
        assertTrue(ignore.matches("config/somemod/client.json"));
        assertTrue(ignore.matches("config/somemod/deep/er.toml"));
        assertFalse(ignore.matches("config/somemod.json"));
        assertTrue(ignore.matches("config/cache.json"));
        assertTrue(ignore.matches("config/a/b/cache.json"));
        assertFalse(ignore.matches("config/xcache.json"));
        assertTrue(ignore.matches("config/one-level/a.toml"));
        assertFalse(ignore.matches("config/one-level/deeper/a.toml"));
        assertTrue(ignore.matches("config/windows/style.json"));
        assertTrue(ignore.matches("config\\windows\\style.json"));
        assertTrue(ignore.matches("config\\somemod\\client.json"));
        assertFalse(ignore.matches("options.txt"));
    }

    @Test
    void patternsWithoutSlashMatchFileNamesAnywhere() {
        Ignore ignore = Ignore.parse(List.of("*.sol.json", "servers.dat", "prefix-?.cfg"));

        assertTrue(ignore.matches("config/sortilege/enchanting.sol.json"));
        assertTrue(ignore.matches("servers.dat"));
        assertTrue(ignore.matches("config/prefix-a.cfg"));
        assertFalse(ignore.matches("config/prefix-ab.cfg"));
        assertFalse(ignore.matches("config/sortilege/enchanting.json"));
        assertFalse(ignore.matches("config/servers.dat.bak"));
    }

    @Test
    void emptyOrCommentOnlyFilesIgnoreNothing() {
        Ignore ignore = Ignore.parse(List.of("# nothing here", "   ", "/"));

        assertEquals(0, ignore.size());
        assertFalse(ignore.matches("config/anything.json"));
    }

    @Test
    void overlongPatternsAreIgnored() {
        Ignore ignore = Ignore.parse(List.of("a".repeat(Ignore.MAX_LINE + 1), "config/ok.json"));
        assertEquals(1, ignore.size());
        assertTrue(ignore.matches("config/ok.json"));
    }
}
