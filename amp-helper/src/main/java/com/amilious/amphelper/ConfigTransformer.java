package com.amilious.amphelper;

import com.amilious.amphelper.Main.HelperException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TOML-ish default.conf &lt;-&gt; flat section_key=value amp-settings.cfg
 */
public final class ConfigTransformer {

    private static final Set<String> SECRET_KEYS = Set.of("secret_key", "database_password");

    private ConfigTransformer() {}

    /** Copy source conf to protected conf if missing. */
    public static void ensureConf(Path conf, Path source) throws IOException {
        if (Files.isRegularFile(conf)) {
            return;
        }
        if (source == null || !Files.isRegularFile(source)) {
            throw new HelperException(1, "Config missing and no --source to copy from: " + conf);
        }
        Files.createDirectories(conf.getParent() != null ? conf.getParent() : Path.of("."));
        Files.copy(source, conf);
        System.out.println("Copied default.conf from " + source);
    }

    /**
     * Seed amp-settings from conf only if settings file is missing or empty.
     */
    public static void seed(Path conf, Path settings, Path source) throws IOException {
        ensureConf(conf, source);

        if (Files.isRegularFile(settings) && Files.size(settings) > 0) {
            System.out.println("amp-settings.cfg exists - leaving unchanged");
            return;
        }

        if (!Files.isRegularFile(conf)) {
            throw new HelperException(1, "Cannot seed: conf not found: " + conf);
        }

        Map<String, String> flat = confToFlat(conf);
        Files.createDirectories(settings.getParent() != null ? settings.getParent() : Path.of("."));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(settings, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Seeded amp-settings.cfg from default.conf (" + flat.size() + " keys)");
        int shown = 0;
        for (String k : flat.keySet()) {
            System.out.println(k + "=" + redact(keyOnly(k), flat.get(k)));
            if (++shown >= 8) {
                break;
            }
        }
    }

    /**
     * Apply amp-settings into conf. Logs only real changes; redacts secrets.
     */
    public static void apply(Path conf, Path settings) throws IOException {
        if (!Files.isRegularFile(conf)) {
            throw new HelperException(1, "Conf missing: " + conf);
        }
        if (!Files.isRegularFile(settings)) {
            System.out.println("No amp-settings.cfg - skip apply");
            return;
        }

        Map<String, String> pending = loadSettings(settings);
        List<String> lines = Files.readAllLines(conf, StandardCharsets.UTF_8);
        int changes = 0;

        for (Map.Entry<String, String> e : pending.entrySet()) {
            String flatKey = e.getKey();
            int us = flatKey.indexOf('_');
            if (us <= 0) {
                continue;
            }
            String sec = flatKey.substring(0, us);
            String key = flatKey.substring(us + 1);
            String newVal = e.getValue();

            boolean inSec = false;
            Pattern lineRe = Pattern.compile("^([ \\t]*)" + Pattern.quote(key) + "([ \\t]*=[ \\t]*)(.*)$");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String stripped = line.strip();
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    inSec = stripped.equals("[" + sec + "]");
                    continue;
                }
                if (!inSec) {
                    continue;
                }
                Matcher m = lineRe.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String indent = m.group(1);
                String eq = m.group(2);
                String rest = m.group(3);
                Matcher vm = Pattern.compile("^(\"[^\"]*\"|[^#]+?)([ \\t]*#.*)?$").matcher(rest);
                if (!vm.matches()) {
                    break;
                }
                String oldRaw = vm.group(1).strip();
                String comment = vm.group(2) != null ? vm.group(2) : "";
                boolean quoted = oldRaw.startsWith("\"") && oldRaw.endsWith("\"");
                String oldCmp = quoted ? oldRaw.substring(1, oldRaw.length() - 1) : oldRaw;
                if (oldCmp.equals(newVal)) {
                    break;
                }
                if (quoted) {
                    String esc = newVal.replace("\\", "\\\\").replace("\"", "\\\"");
                    lines.set(i, indent + key + eq + "\"" + esc + "\"" + comment);
                } else {
                    lines.set(i, indent + key + eq + newVal + comment);
                }
                changes++;
                System.out.println("Applied " + sec + "." + key + "=" + redact(key, newVal));
                break;
            }
        }

        Files.write(conf, lines, StandardCharsets.UTF_8);
        System.out.println("Finished applying amp-settings.cfg (" + changes + " change" + (changes == 1 ? "" : "s") + ")");
    }

    static Map<String, String> confToFlat(Path conf) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        String sec = "";
        for (String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            if (s.startsWith("[") && s.endsWith("]")) {
                sec = s.substring(1, s.length() - 1).strip();
                continue;
            }
            if (!s.contains("=") || sec.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            String k = s.substring(0, eq).strip();
            String rest = s.substring(eq + 1).strip();
            String val;
            if (rest.startsWith("\"")) {
                Matcher m = Pattern.compile("^\"([^\"]*)\"").matcher(rest);
                val = m.find() ? m.group(1) : rest.replace("\"", "");
            } else {
                int hash = rest.indexOf('#');
                val = (hash >= 0 ? rest.substring(0, hash) : rest).strip();
            }
            out.put(sec + "_" + k, val);
        }
        return out;
    }

    static Map<String, String> loadSettings(Path settings) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(settings, StandardCharsets.UTF_8)) {
            String line = raw.strip().replace("\r", "");
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            int eq = line.indexOf('=');
            String k = line.substring(0, eq).strip();
            String v = line.substring(eq + 1).strip();
            if (!k.contains("_")) {
                continue;
            }
            if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                v = v.toLowerCase(Locale.ROOT);
            }
            map.put(k, v);
        }
        return map;
    }

    /** Parse conf into section.key -> value (for DB checker). */
    static Map<String, String> parseConf(Path conf) throws IOException {
        Map<String, String> data = new LinkedHashMap<>();
        String sec = "";
        for (String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            if (s.startsWith("[") && s.endsWith("]")) {
                sec = s.substring(1, s.length() - 1).strip();
                continue;
            }
            if (!s.contains("=") || sec.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            String k = s.substring(0, eq).strip();
            String rest = s.substring(eq + 1).strip();
            String val;
            if (rest.startsWith("\"")) {
                Matcher m = Pattern.compile("^\"([^\"]*)\"").matcher(rest);
                val = m.find() ? m.group(1) : rest.replace("\"", "");
            } else {
                int hash = rest.indexOf('#');
                val = (hash >= 0 ? rest.substring(0, hash) : rest).strip();
            }
            data.put(sec + "." + k, val);
        }
        return data;
    }

    private static String redact(String key, String val) {
        if (SECRET_KEYS.contains(key) || key.endsWith("_password") || key.endsWith("secret_key")) {
            return "***";
        }
        return val;
    }

    private static String keyOnly(String sectionKey) {
        int us = sectionKey.indexOf('_');
        return us > 0 ? sectionKey.substring(us + 1) : sectionKey;
    }
}
