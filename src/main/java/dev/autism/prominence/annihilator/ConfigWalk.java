package dev.autism.prominence.annihilator;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

final class ConfigWalk {
    private static final Set<String> EXTENSIONS =
        Set.of("json", "json5", "jsonc", "toml", "snbt", "properties", "cfg");
    private static final Set<String> SKIPPED = Set.of(Annihilator.MOD_ID, "jei", "rei", "emi", "spark");
    private static final Set<String> TEMPLATE_JUNK = Set.of(
        ".ds_store", "thumbs.db", "desktop.ini", ".gitkeep", ".gitignore", ".gitattributes", ".hgignore");

    private ConfigWalk() {
    }

    static List<Path> list(Path config) throws IOException {
        return walk(config,
            dir -> !SKIPPED.contains(name(dir)),
            (file, attrs) -> EXTENSIONS.contains(extension(name(file))));
    }

    static List<Path> walkAllFiles(Path dir) throws IOException {
        return walk(dir,
            d -> !".git".equalsIgnoreCase(name(d)),
            (file, attrs) -> !isTemplateJunk(name(file)));
    }

    private static List<Path> walk(Path dir, Predicate<Path> enterDir,
                                   BiPredicate<Path, BasicFileAttributes> acceptFile) throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return files;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult preVisitDirectory(@NotNull Path current, @NotNull BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink() || attrs.isOther() || !enterDir.test(current)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                if (!attrs.isSymbolicLink() && !attrs.isOther() && attrs.isRegularFile() && acceptFile.test(file, attrs)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(java.util.Comparator.comparing(Path::toString));
        return files;
    }

    static boolean isTemplateJunk(String name) {
        return TEMPLATE_JUNK.contains(name) || name.endsWith(Repair.TMP_SUFFIX);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
    }
}
