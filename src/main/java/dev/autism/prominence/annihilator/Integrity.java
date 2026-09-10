package dev.autism.prominence.annihilator;

import com.electronwill.nightconfig.toml.TomlParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import java.util.Set;

import static com.fasterxml.jackson.core.JsonToken.START_ARRAY;
import static com.fasterxml.jackson.core.JsonToken.START_OBJECT;
import static com.google.gson.stream.JsonToken.BEGIN_ARRAY;
import static com.google.gson.stream.JsonToken.BEGIN_OBJECT;
import static com.google.gson.stream.JsonToken.END_DOCUMENT;

final class Integrity {
    private static final Set<String> CUSTOM_PARSER = Set.of("balm-client.toml", "balm-common.toml");
    private static final String SORTILEGE = ".sol.json";
    private static final Set<String> OPTIONS_FILES = Set.of("options.txt", "optionsof.txt", "optionsshaders.txt");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "json", "json5", "jsonc", "toml", "snbt", "properties", "cfg", "txt", "conf", "ini", "yml", "yaml",
        "xml", "css", "md", "js", "ts", "zs", "lang", "mcmeta", "csv", "html", "sh", "bat", "log");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
        "dat", "nbt", "zip", "jar", "gz", "tar", "png", "gif", "jpg", "jpeg", "webp", "ico",
        "ogg", "mp3", "wav", "ttf", "otf", "bin", "class", "dll", "so", "dylib");
    static final long MAX_TEXT_SIZE = 16L << 20;
    private static final JsonFactory JSON5 = JsonFactory.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
        .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
        .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
        .enable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
        .enable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
        .enable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
        .enable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
        .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .build();

    private Integrity() {
    }

    static boolean isBroken(Path file) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return true;
        }
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
            return true;
        }
        String name = ConfigWalk.name(file);
        String ext = ConfigWalk.extension(name);
        if (BINARY_EXTENSIONS.contains(ext)) {
            return attrs.size() == 0;
        }
        if (!TEXT_EXTENSIONS.contains(ext)) {
            return false;
        }
        if (attrs.size() > MAX_TEXT_SIZE) {
            return false;
        }
        String text;
        try {
            text = readTextCapped(file);
        } catch (IOException e) {
            return true;
        }
        if (text == null) {
            return false;
        }
        if (text.indexOf('\0') >= 0) {
            return true;
        }
        if (CUSTOM_PARSER.contains(name) || name.endsWith(SORTILEGE)) {
            return false;
        }
        String body = text.startsWith("\uFEFF") ? text.substring(1) : text;
        return switch (ext) {
            case "json" -> fails(() -> parseJson(body)) && fails(() -> parseJson5(body));
            case "json5", "jsonc" -> fails(() -> parseJson5(body));
            case "toml" -> fails(() -> parseToml(body));
            case "properties" -> fails(() -> new Properties().load(new StringReader(body)));
            case "txt" -> isOptionsFile(name) && isBrokenOptions(body);
            default -> false;
        };
    }

    private static String readTextCapped(Path file) throws IOException {
        int cap = (int) MAX_TEXT_SIZE + 1;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(cap);
            if (buf.length > MAX_TEXT_SIZE) {
                return null;
            }
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    static boolean isOptionsFile(String name) {
        return OPTIONS_FILES.contains(name);
    }

    private static boolean isBrokenOptions(String body) {
        if (body.trim().isEmpty()) {
            return true;
        }
        for (String line : body.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.indexOf(':') < 0 && trimmed.indexOf('=') < 0) {
                return true;
            }
        }
        return false;
    }

    private static void parseJson(String body) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(body))) {
            reader.setLenient(true);
            var root = reader.peek();
            if (root != BEGIN_OBJECT && root != BEGIN_ARRAY) {
                throw new IOException("JSON root must be an object or array");
            }
            reader.skipValue();
            if (reader.peek() != END_DOCUMENT) {
                throw new IOException("Trailing content after JSON document");
            }
        }
    }

    private static void parseToml(String body) {
        new TomlParser().setLenientWithBareKeys(true).parse(new StringReader(body));
    }

    private static void parseJson5(String body) throws IOException {
        try (var parser = JSON5.createParser(body)) {
            var root = parser.nextToken();
            if (root != START_OBJECT && root != START_ARRAY) {
                throw new IOException("JSON5 root must be an object or array");
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw new IOException("Trailing content after JSON5 document");
            }
        }
    }

    private static boolean fails(Check check) {
        try {
            check.run();
            return false;
        } catch (Exception | StackOverflowError e) {
            return true;
        }
    }

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }
}
