package dev.autism.prominence.annihilator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class Ignore {
    static final String FILE_NAME = "ignore.txt";
    static final Ignore NONE = new Ignore(List.of(), List.of());
    static final int MAX_LINE = 1024;
    static final int MAX_PATTERNS = 1024;
    static final long MAX_FILE_SIZE = 1L << 20;

    private final List<Pattern> paths;
    private final List<Pattern> names;

    private Ignore(List<Pattern> paths, List<Pattern> names) {
        this.paths = paths;
        this.names = names;
    }

    static Ignore load(Path file) {
        return load(List.of(file));
    }

    static Ignore load(List<Path> files) {
        List<String> allLines = new ArrayList<>();
        for (Path file : files) {
            try {
                if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_FILE_SIZE) {
                    allLines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }
        return allLines.isEmpty() ? NONE : parse(allLines);
    }

    static Ignore parse(List<String> lines) {
        List<Pattern> paths = new ArrayList<>();
        List<Pattern> names = new ArrayList<>();
        for (String raw : lines) {
            if (paths.size() + names.size() >= MAX_PATTERNS) {
                break;
            }
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.length() > MAX_LINE) {
                continue;
            }
            line = line.replace('\\', '/');
            int lead = 0;
            while (lead < line.length() && line.charAt(lead) == '/') {
                lead++;
            }
            if (lead > 0) {
                line = line.substring(lead);
            }
            if (line.endsWith("/")) {
                line = line + "**";
            }
            if (line.isEmpty() || line.equals("**") || line.contains("\0")) {
                continue;
            }
            (line.indexOf('/') < 0 ? names : paths).add(glob(line));
        }
        return new Ignore(List.copyOf(paths), List.copyOf(names));
    }

    int size() {
        return paths.size() + names.size();
    }

    boolean matches(String relative) {
        if (paths.isEmpty() && names.isEmpty()) {
            return false;
        }
        if (relative == null || relative.isEmpty()) {
            return false;
        }
        if (relative.indexOf('\\') >= 0) {
            relative = relative.replace('\\', '/');
        }
        for (Pattern path : paths) {
            if (path.matcher(relative).matches()) {
                return true;
            }
        }
        if (!names.isEmpty()) {
            int slash = relative.lastIndexOf('/');
            String name = slash < 0 ? relative : relative.substring(slash + 1);
            for (Pattern pattern : names) {
                if (pattern.matcher(name).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    static Pattern glob(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
                        regex.append("(?:.*/)?");
                        i++;
                    } else {
                        regex.append(".*");
                    }
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
