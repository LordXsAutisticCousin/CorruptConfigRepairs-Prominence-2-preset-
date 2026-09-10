package dev.autism.prominence.annihilator;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Repair {
    private static final Logger LOGGER = LoggerFactory.getLogger(Annihilator.MOD_ID);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter LOG_HEADER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final Pattern SNAPSHOT_PATTERN = Pattern.compile("^\\d{8}_\\d{6}$");
    static final String BACKUPS_DIR = Annihilator.MOD_ID + "_backups";
    static final String TMP_SUFFIX = ".cca-tmp";
    static final int MAX_BACKUPS = 20;
    static final int REPEAT_STREAK = 3;
    static final long MAX_LOG_SIZE = 1L << 20;

    private Repair() {
    }

    static void run(Path gameDir) {
        run(gameDir, findBundledRoot());
    }

    static Path findBundledRoot() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(Annihilator.MOD_ID)
                .flatMap(container -> container.findPath("default_configs")
                    .or(() -> container.findPath("config")))
                .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void run(Path gameDir, Path bundledRoot) {
        gameDir = gameDir.toAbsolutePath().normalize();
        Path config = gameDir.resolve("config");
        Path own = config.resolve(Annihilator.MOD_ID);
        Path ccaDefaults = own.resolve("defaults");
        Path overridesDir = own.resolve("overrides");
        Path backupsDir = gameDir.resolve(BACKUPS_DIR);
        Path snapshot = backupsDir.resolve(STAMP.format(LocalDateTime.now()));
        Manifest manifest = Manifest.load(own.resolve(Manifest.FILE_NAME));

        List<Path> ignoreFiles = new ArrayList<>();
        ignoreFiles.add(own.resolve(Ignore.FILE_NAME));
        ignoreFiles.add(config.resolve(Ignore.FILE_NAME));
        if (bundledRoot != null) {
            ignoreFiles.add(bundledRoot.resolve(Annihilator.MOD_ID).resolve(Ignore.FILE_NAME));
            ignoreFiles.add(bundledRoot.resolve(Ignore.FILE_NAME));
        }
        Ignore ignore = Ignore.load(ignoreFiles);
        if (ignore.size() > 0) {
            LOGGER.info("Loaded {} ignore pattern(s)", ignore.size());
        }
        Run run = new Run(gameDir, config, snapshot, manifest, ignore, List.of(ccaDefaults));

        try {
            for (Map.Entry<Path, Path> entry : collectOverrides(gameDir, config, overridesDir, bundledRoot).entrySet()) {
                if (run.handled.add(entry.getKey())) {
                    run.enforceOverride(entry.getKey(), entry.getValue());
                }
            }

            for (int pass = 0; pass < 2; pass++) {
                run.templatesSeeded = false;
                for (Map.Entry<Path, Path> entry : collectTemplates(gameDir, config, ccaDefaults, bundledRoot).entrySet()) {
                    if (run.handled.add(entry.getKey())) {
                        run.applyTemplate(entry.getKey(), entry.getValue());
                    }
                }
                if (!run.templatesSeeded) {
                    break;
                }
            }

            List<Path> files = ConfigWalk.list(config);
            run.scanned += files.size();
            for (Path target : run.handled) {
                if (!target.startsWith(config)) {
                    run.scanned++;
                }
            }

            for (Path file : files) {
                Path norm = file.toAbsolutePath().normalize();
                if (isProtected(gameDir, norm)
                    || run.handled.contains(norm)
                    || run.ignore.matches(run.manifestKey(norm))) {
                    continue;
                }
                if (!Integrity.isBroken(norm)) {
                    continue;
                }
                run.handleCorrupt(norm, null);
            }
        } catch (Exception e) {
            run.log.add("walk-fail " + e);
            LOGGER.error("Failed to walk config directory: {}", e.getMessage(), e);
        }

        if (run.log.isEmpty()) {
            LOGGER.info("Config scan complete. All {} configs healthy.", run.scanned);
            run.log.add("scan-ok scanned=" + run.scanned);
        } else {
            LOGGER.info("Config scan complete. {} actions taken across {} configs.", run.log.size(), run.scanned);
            if (run.snapshotWritten) {
                pruneOldBackups(backupsDir);
            }
        }

        try {
            manifest.save();
        } catch (Exception e) {
            LOGGER.warn("Failed to save {}: {}", Manifest.FILE_NAME, e.getMessage(), e);
        }
        writeLog(gameDir.resolve("logs").resolve(Annihilator.MOD_ID + ".log"), run.log);
    }

    private static final class Run {
        final Path gameDir;
        final Path config;
        final Path snapshot;
        final Manifest manifest;
        final Ignore ignore;
        final List<Path> templateRoots;
        final List<String> log = new ArrayList<>();
        final Set<Path> handled = new HashSet<>();
        int scanned;
        boolean snapshotWritten;
        boolean templatesSeeded;
        List<Path> previousSnapshots;

        Run(Path gameDir, Path config, Path snapshot, Manifest manifest, Ignore ignore, List<Path> templateRoots) {
            this.gameDir = gameDir;
            this.config = config;
            this.snapshot = snapshot;
            this.manifest = manifest;
            this.ignore = ignore;
            this.templateRoots = templateRoots;
        }

        boolean ignored(Path target, String key) {
            return ignore.matches(key) && Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        }

        boolean skipManaged(Path target, String name, String key) {
            if (isProtected(gameDir, target)) {
                return true;
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                skipDirectory(name);
                return true;
            }
            return ignored(target, key);
        }

        void enforceOverride(Path target, Path template) {
            String name = displayPath(target);
            String key = manifestKey(target);
            if (skipManaged(target, name, key)) {
                return;
            }
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            if (exists && Manifest.sameContent(target, template)) {
                manifest.put(key, Manifest.hash(template), template);
                return;
            }
            if (Integrity.isBroken(template)) {
                corruptTemplate(name, "Override template for {} is corrupt! Leaving live file untouched.");
                return;
            }
            if (exists && !backup(target, name)) {
                return;
            }
            if (copy(template, target, name, "override")) {
                log.add("override-apply " + name);
                LOGGER.info("Enforced override for {}", name);
                manifest.put(key, Manifest.hash(template), template);
            }
        }

        void applyTemplate(Path target, Path template) {
            String name = displayPath(target);
            String key = manifestKey(target);
            if (skipManaged(target, name, key)) {
                return;
            }

            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Integrity.isBroken(template)) {
                    corruptTemplate(name, "Default template for {} is corrupt! Skipping default creation.");
                    return;
                }
                if (copy(template, target, name, "default")) {
                    log.add("default-apply " + name);
                    LOGGER.info("Applied default for {}", name);
                    manifest.put(key, Manifest.hash(template), template);
                }
                return;
            }

            if (Integrity.isBroken(target)) {
                if (Manifest.sameContent(target, template)) {
                    manifest.put(key, Manifest.hash(template), template);
                    return;
                }
                handleCorrupt(target, template);
                return;
            }

            String templateHash = manifest.templateHash(key, template);
            if (templateHash == null) {
                return;
            }
            String recorded = manifest.get(key);
            if (templateHash.equals(recorded)) {
                return;
            }
            if (recorded == null || !recorded.equals(Manifest.hash(target))) {
                if (OptionsMerge.applies(ConfigWalk.name(target)) && gameDir.equals(target.getParent())) {
                    mergeOptions(target, template, name);
                }
                manifest.put(key, templateHash, template);
                return;
            }
            if (Integrity.isBroken(template)) {
                corruptTemplate(name, "Updated template for {} is corrupt! Keeping current file.");
                return;
            }
            if (backup(target, name) && copy(template, target, name, "update")) {
                log.add("default-update " + name);
                LOGGER.info("Updated {} to the pack's new default (file was unmodified)", name);
                manifest.put(key, templateHash, template);
            }
        }

        void handleCorrupt(Path target, Path template) {
            String name = displayPath(target);
            LOGGER.warn("Corrupt config detected: {}", name);
            warnIfRepeating(target, name);
            if (!backup(target, name)) {
                return;
            }
            if (template == null) {
                quarantine(name);
            } else {
                restore(template, target, name);
            }
        }

        void mergeOptions(Path target, Path template, String name) {
            if (Integrity.isBroken(template)) {
                corruptTemplate(name, "Default template for {} is corrupt! Not merging its options.");
                return;
            }
            try {
                BasicFileAttributes liveAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes templateAttrs = Files.readAttributes(template, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!liveAttrs.isRegularFile() || !templateAttrs.isRegularFile()
                    || liveAttrs.size() > Integrity.MAX_TEXT_SIZE
                    || templateAttrs.size() > Integrity.MAX_TEXT_SIZE) {
                    log.add("merge-fail " + name + " oversized-or-unreadable");
                    return;
                }
                @SuppressWarnings("ReadWriteStringCanBeUsed")
                String live = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
                @SuppressWarnings("ReadWriteStringCanBeUsed")
                String defaults = new String(Files.readAllBytes(template), StandardCharsets.UTF_8);
                List<String> missing = OptionsMerge.missingLines(live, defaults);
                if (missing.isEmpty()) {
                    return;
                }
                if (!backupCopy(target, name)) {
                    return;
                }
                writeAtomic(target, OptionsMerge.append(live, missing).getBytes(StandardCharsets.UTF_8));
                log.add("options-merge " + name + " added=" + missing.size());
                LOGGER.info("Added {} option(s) the pack defines but {} lacked (existing lines untouched)",
                    missing.size(), name);
            } catch (Exception e) {
                log.add("merge-fail " + name + " " + e);
                LOGGER.error("Failed to merge options into {}: {}", name, e.getMessage(), e);
            }
        }

        void warnIfRepeating(Path file, String name) {
            Path rel = backupRel(file);
            if (rel == null) {
                return;
            }
            int streak = 1;
            for (Path previous : previousSnapshots()) {
                Path prior = resolveInside(previous, rel);
                if (prior == null || !Files.exists(prior, LinkOption.NOFOLLOW_LINKS)) {
                    break;
                }
                streak++;
            }
            if (streak >= REPEAT_STREAK) {
                log.add("repeat-corrupt " + name + " streak=" + streak);
                LOGGER.error("{} has been found corrupt on {} consecutive launches. If its mod writes a format this mod "
                    + "does not understand, add it to config/{}/{} to stop it being reset", name, streak,
                    Annihilator.MOD_ID, Ignore.FILE_NAME);
            }
        }

        List<Path> previousSnapshots() {
            if (previousSnapshots == null) {
                Path backupsDir = snapshot.getParent();
                previousSnapshots = listSnapshotDirs(backupsDir).stream()
                    .filter(path -> !path.equals(snapshot))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .limit(MAX_BACKUPS)
                    .toList();
            }
            return previousSnapshots;
        }

        void restore(Path template, Path target, String name) {
            if (template == null || !Files.isRegularFile(template, LinkOption.NOFOLLOW_LINKS)) {
                quarantine(name);
                return;
            }
            if (Integrity.isBroken(template)) {
                corruptTemplate(name, "Default template for {} is also corrupt! Quarantining without restore.");
                return;
            }
            if (copy(template, target, name, "restore")) {
                log.add("restore " + name);
                LOGGER.info("Restored {} from clean template", name);
                manifest.put(manifestKey(target), Manifest.hash(template), template);
            }
        }

        void quarantine(String name) {
            log.add("quarantine " + name);
            LOGGER.info("Quarantined {} (no clean template found in defaults)", name);
        }

        boolean backup(Path file, String name) {
            return relocate(file, name, true);
        }

        boolean backupCopy(Path file, String name) {
            return relocate(file, name, false);
        }

        private boolean relocate(Path file, String name, boolean move) {
            Path rel = backupRel(file);
            Path dest = rel == null ? null : resolveInside(snapshot, rel);
            if (dest == null) {
                log.add("backup-fail " + name + " path-escape");
                return false;
            }
            try {
                ensureParent(dest);
                if (move) {
                    Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                snapshotWritten = true;
                return true;
            } catch (Exception e) {
                log.add("backup-fail " + name + " " + e);
                LOGGER.error("Failed to backup {}: {}", name, e.getMessage(), e);
                return false;
            }
        }

        boolean copy(Path template, Path target, String name, String action) {
            try {
                copyAtomic(template, target);
                for (Path root : templateRoots) {
                    if (target.getFileSystem() == root.getFileSystem() && target.startsWith(root)) {
                        templatesSeeded = true;
                        break;
                    }
                }
                return true;
            } catch (Exception e) {
                log.add(action + "-fail " + name + " " + e);
                LOGGER.error("Failed to write {} for {}: {}", action, name, e.getMessage(), e);
                return false;
            }
        }

        void corruptTemplate(String name, String message) {
            log.add("corrupt-template " + name);
            LOGGER.error(message, name);
        }

        void skipDirectory(String name) {
            log.add("skip-dir " + name);
            LOGGER.warn("Template {} points at a directory; leaving it alone", name);
        }

        private Path absInGame(Path target) {
            try {
                Path abs = target.toAbsolutePath().normalize();
                return abs.startsWith(gameDir) ? abs : null;
            } catch (Exception e) {
                return null;
            }
        }

        String displayPath(Path target) {
            Path abs = absInGame(target);
            if (abs != null) {
                if (abs.startsWith(config)) {
                    return slashes(config.relativize(abs));
                }
                return slashes(gameDir.relativize(abs));
            }
            Path name = target.getFileName();
            return name == null ? target.toString() : name.toString();
        }

        String manifestKey(Path target) {
            Path abs = absInGame(target);
            if (abs != null) {
                return slashes(gameDir.relativize(abs));
            }
            return displayPath(target);
        }

        Path backupRel(Path target) {
            Path abs = absInGame(target);
            if (abs == null) {
                return null;
            }
            if (abs.startsWith(config)) {
                return config.relativize(abs);
            }
            return gameDir.relativize(abs);
        }
    }

    private static Map<Path, Path> collectOverrides(Path gameDir, Path config, Path overridesDir, Path bundledRoot) {
        Map<Path, Path> map = new LinkedHashMap<>();
        collectGameRelative(gameDir, overridesDir, map);
        collectGameRelative(gameDir, config.resolve("overrides"), map);
        if (bundledRoot != null && Files.isDirectory(bundledRoot, LinkOption.NOFOLLOW_LINKS)) {
            collectGameRelative(gameDir, bundledRoot.resolve(Annihilator.MOD_ID).resolve("overrides"), map);
            collectGameRelative(gameDir, bundledRoot.resolve("overrides"), map);
        }
        return map;
    }

    private static Map<Path, Path> collectTemplates(Path gameDir, Path config, Path ccaDefaults, Path bundledRoot) {
        Map<Path, Path> map = new LinkedHashMap<>();
        collectDefaultsDir(gameDir, config, ccaDefaults, map);

        if (bundledRoot != null && Files.isDirectory(bundledRoot, LinkOption.NOFOLLOW_LINKS)) {
            collectDefaultsDir(gameDir, config, bundledRoot.resolve(Annihilator.MOD_ID).resolve("defaults"), map);
            collectDefaultsDir(gameDir, config, bundledRoot.resolve("defaults"), map);
            collectBundledDirect(gameDir, config, bundledRoot, map);
        }
        return map;
    }

    private static void collectDefaultsDir(Path gameDir, Path config, Path dir, Map<Path, Path> map) {
        if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            boolean gameLayout = Files.isDirectory(dir.resolve("config"), LinkOption.NOFOLLOW_LINKS);
            for (Path src : ConfigWalk.walkAllFiles(dir)) {
                Path rel = dir.relativize(src);
                Path target = resolveTemplateTarget(gameDir, config, rel, gameLayout);
                if (target != null && !isProtected(gameDir, target)) {
                    map.putIfAbsent(target, src);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to walk defaults directory: {}", e.getMessage(), e);
        }
    }

    private static void collectBundledDirect(Path gameDir, Path config, Path bundledRoot, Map<Path, Path> map) {
        try {
            boolean gameLayout = Files.isDirectory(bundledRoot.resolve("config"), LinkOption.NOFOLLOW_LINKS);
            for (Path src : ConfigWalk.walkAllFiles(bundledRoot)) {
                Path rel = bundledRoot.relativize(src);
                if (rel.getNameCount() > 0) {
                    String firstPart = rel.getName(0).toString().toLowerCase(Locale.ROOT);
                    if (firstPart.equals(Annihilator.MOD_ID)
                        || firstPart.equals("defaults") || firstPart.equals("overrides")
                        || firstPart.equals(Ignore.FILE_NAME) || firstPart.equals(Manifest.FILE_NAME)
                        || firstPart.equals("fabric.mod.json")) {
                        continue;
                    }
                }
                Path target = resolveTemplateTarget(gameDir, config, rel, gameLayout);
                if (target != null && !isProtected(gameDir, target)) {
                    map.putIfAbsent(target, src);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to walk direct bundled configs: {}", e.getMessage(), e);
        }
    }

    private static void collectGameRelative(Path gameDir, Path root, Map<Path, Path> map) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            for (Path src : ConfigWalk.walkAllFiles(root)) {
                Path target = resolveInside(gameDir, root.relativize(src));
                if (target != null && !isProtected(gameDir, target)) {
                    map.putIfAbsent(target, src);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to walk {}: {}", root.getFileName(), e.getMessage(), e);
        }
    }

    static Path resolveTemplateTarget(Path gameDir, Path config, Path rel, boolean gameLayout) {
        if (gameLayout) {
            if (!isGameLayoutPath(rel)) {
                return null;
            }
            return resolveInside(gameDir, rel);
        }
        return resolveInside(isRootRelative(rel) ? gameDir : config, rel);
    }

    static Path resolveInside(Path root, Path rel) {
        if (rel == null) {
            return null;
        }
        return resolveInside(root, slashes(rel));
    }

    static Path resolveInside(Path root, String relStr) {
        if (relStr == null || relStr.isEmpty()) {
            return null;
        }
        while (relStr.startsWith("/")) {
            relStr = relStr.substring(1);
        }
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(relStr).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            return null;
        }
        for (String part : relStr.split("/")) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                return null;
            }
        }
        return target;
    }

    static boolean isProtected(Path gameDir, Path target) {
        Path base = gameDir.toAbsolutePath().normalize();
        Path n = target.toAbsolutePath().normalize();
        if (!n.startsWith(base)) {
            return true;
        }
        if (n.startsWith(base.resolve(BACKUPS_DIR))) {
            return true;
        }
        Path own = base.resolve("config").resolve(Annihilator.MOD_ID);
        if (n.startsWith(own)) {
            return true;
        }
        return n.equals(base.resolve("logs").resolve(Annihilator.MOD_ID + ".log"));
    }

    static boolean isGameLayoutPath(Path rel) {
        String r = slashes(rel).toLowerCase(Locale.ROOT);
        if (r.equals("config") || r.startsWith("config/")) {
            return true;
        }
        return isRootRelative(rel);
    }

    private static boolean isRootRelative(Path rel) {
        String r = slashes(rel).toLowerCase(Locale.ROOT);
        int slash = r.indexOf('/');
        if (slash < 0) {
            return Integrity.isOptionsFile(r)
                || r.equals("optionsviveprofiles.txt")
                || r.equals("servers.dat")
                || r.equals("hotbar.nbt");
        }
        String top = r.substring(0, slash);
        return top.equals("resourcepacks")
            || top.equals("shaderpacks")
            || top.equals("datapacks");
    }

    private static String slashes(Path rel) {
        return rel.toString().replace('\\', '/');
    }

    static Path tmpBeside(Path target) {
        Path name = target.getFileName();
        String fileName = name == null ? "tmp" : name.toString();
        return target.resolveSibling("." + fileName + TMP_SUFFIX);
    }

    static void copyAtomic(Path template, Path target) throws IOException {
        atomicReplace(target, tmp -> Files.copy(template, tmp, StandardCopyOption.REPLACE_EXISTING));
    }

    static void writeAtomic(Path target, byte[] content) throws IOException {
        atomicReplace(target, tmp -> Files.write(tmp, content));
    }

    private static void atomicReplace(Path target, TmpWriter writer) throws IOException {
        ensureParent(target);
        Path tmp = tmpBeside(target);
        try {
            writer.write(tmp);
            moveAtomic(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void ensureParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @FunctionalInterface
    private interface TmpWriter {
        void write(Path tmp) throws IOException;
    }

    static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> listSnapshotDirs(Path backupsDir) {
        if (!Files.isDirectory(backupsDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupsDir)) {
            return stream
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(Repair::isSnapshotDir)
                .toList();
        } catch (IOException e) {
            LOGGER.warn("Failed to list backups: {}", e.getMessage(), e);
            return List.of();
        }
    }

    static void pruneOldBackups(Path backupsDir) {
        try {
            List<Path> dirs = listSnapshotDirs(backupsDir).stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
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
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
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

    private static void deleteRecursively(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
                LOGGER.warn("Failed to delete {}: {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeLog(Path file, List<String> log) {
        try {
            ensureParent(file);
            List<String> lines = new ArrayList<>(log.size() + 2);
            lines.add("===== " + LOG_HEADER_TIME.format(LocalDateTime.now()) + " =====");
            lines.addAll(log);
            lines.add("");
            boolean append = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) < MAX_LOG_SIZE;
            if (append) {
                Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.write(file, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write {}: {}", file, e.getMessage(), e);
        }
    }
}
