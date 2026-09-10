package dev.autism.prominence.annihilator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class OptionsMerge {
    private OptionsMerge() {
    }

    static boolean applies(String name) {
        return name.equals("options.txt");
    }

    static List<String> missingLines(String live, String template) {
        Set<String> present = new HashSet<>();
        for (String line : live.split("\r?\n", -1)) {
            String key = key(line);
            if (key != null) {
                present.add(key);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String line : template.split("\r?\n", -1)) {
            String key = key(line);
            if (key != null && present.add(key)) {
                missing.add(stripBom(line));
            }
        }
        return missing;
    }

    static String append(String live, List<String> lines) {
        String eol = live.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder(live);
        if (!live.isEmpty() && !live.endsWith("\n")) {
            out.append(eol);
        }
        for (String line : lines) {
            out.append(line).append(eol);
        }
        return out.toString();
    }

    private static String key(String line) {
        line = stripBom(line);
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon).strip();
        return key.isEmpty() || key.startsWith("#") ? null : key;
    }

    private static String stripBom(String line) {
        return line.startsWith("\uFEFF") ? line.substring(1) : line;
    }
}
