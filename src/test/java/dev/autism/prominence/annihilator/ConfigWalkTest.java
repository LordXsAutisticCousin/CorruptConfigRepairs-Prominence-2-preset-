package dev.autism.prominence.annihilator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static dev.autism.prominence.annihilator.TestFiles.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigWalkTest {

    @Test
    void skipsSpammyDirectoriesAndForeignExtensions(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        write(config, "live.json", "{}");
        write(config.resolve("jei"), "ignored.json", "{}");
        write(config.resolve("spark"), "ignored.json", "{}");
        write(config.resolve(Annihilator.MOD_ID).resolve("defaults"), "live.json", "{}");
        write(config, "notes.txt", "text");

        assertEquals(List.of(config.resolve("live.json")), ConfigWalk.list(config));
    }

    @Test
    void templateWalkSkipsJunkButKeepsEverythingElse(@TempDir Path dir) throws Exception {
        Path templates = dir.resolve("defaults");
        Path options = write(templates, "options.txt", "fov:70.0\n");
        Path nested = write(templates.resolve("config").resolve("mod"), "conf.json", "{}");
        Path readme = write(templates.resolve("config").resolve("mod"), "readme.md", "docs");
        write(templates, ".gitkeep", "");
        write(templates, ".gitignore", "");
        write(templates, ".gitattributes", "");
        write(templates, ".hgignore", "");
        write(templates.resolve(".git"), "HEAD", "ref");
        write(templates, "Thumbs.db", "junk");
        write(templates.resolve("config"), ".DS_Store", "junk");
        write(templates, "desktop.ini", "junk");
        write(templates, ".options.txt" + Repair.TMP_SUFFIX, "leftover");

        assertEquals(List.of(nested, readme, options), ConfigWalk.walkAllFiles(templates));
        assertTrue(ConfigWalk.walkAllFiles(dir.resolve("absent")).isEmpty());
    }

    @Test
    void returnsSortedPathsAndHandlesMissingDirectory(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config");
        Path zeta = write(config, "zeta.toml", "");
        Path alpha = write(config.resolve("alpha"), "DEEP.JSON", "");
        Path beta = write(config, "beta.properties", "");

        assertEquals(List.of(alpha, beta, zeta), ConfigWalk.list(config));
        assertEquals("deep.json", ConfigWalk.name(alpha));
        assertEquals("json", ConfigWalk.extension(ConfigWalk.name(alpha)));
        assertTrue(ConfigWalk.list(dir.resolve("absent")).isEmpty());
    }
}
