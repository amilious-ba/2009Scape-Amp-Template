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

    private static final Set<String> SECRET_KEYS = Set.of(
            "secret_key",
            "database_password",
            "discord_ge_webhook",
            "discord_moderation_webhook",
            "openrsc_integration_webhook",
            "websocket_tls_keystore_password"
    );

    private static final String UI_BANNER =
            "# =============================================================================\n"
          + "# Managed by AMP — edit settings in the AMP web UI, not this file.\n"
          + "# Changes made only here may be overwritten when the instance starts.\n"
          + "# File: config/default.conf (protected; outside the git tree)\n"
          + "# =============================================================================\n";

    private ConfigTransformer() {}

    /** Copy source conf to protected conf if missing, then ensure UI banner. */
    public static void ensureConf(Path conf, Path source) throws IOException {
        if (!Files.isRegularFile(conf)) {
            if (source == null || !Files.isRegularFile(source)) {
                throw new HelperException(1, "Config missing and no --source to copy from: " + conf);
            }
            Files.createDirectories(conf.getParent() != null ? conf.getParent() : Path.of("."));
            Files.copy(source, conf);
            System.out.println("Copied default.conf from " + source);
        }
        ensureUiBanner(conf);
    }

    /** Prepend UI guidance comment if not already present. */
    public static void ensureUiBanner(Path conf) throws IOException {
        if (!Files.isRegularFile(conf)) {
            return;
        }
        String text = Files.readString(conf, StandardCharsets.UTF_8);
        if (text.contains("Managed by AMP")) {
            return;
        }
        // Strip a leading BOM if any
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        Files.writeString(conf, UI_BANNER + text, StandardCharsets.UTF_8);
        System.out.println("Added AMP UI notice to default.conf");
    }

    /**
     * Seed amp-settings from conf if missing/empty.
     * If settings already exists, merge in any keys present in conf but missing from settings
     * (does not overwrite existing values).
     */
    public static void seed(Path conf, Path settings, Path source) throws IOException {
        ensureConf(conf, source);

        if (!Files.isRegularFile(conf)) {
            throw new HelperException(1, "Cannot seed: conf not found: " + conf);
        }

        Map<String, String> fromConf = confToFlat(conf);
        Files.createDirectories(settings.getParent() != null ? settings.getParent() : Path.of("."));

        if (!Files.isRegularFile(settings) || Files.size(settings) == 0) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : fromConf.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(settings, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("Seeded amp-settings.cfg from default.conf (" + fromConf.size() + " keys)");
            int shown = 0;
            for (String k : fromConf.keySet()) {
                System.out.println(k + "=" + redact(keyOnly(k), fromConf.get(k)));
                if (++shown >= 8) {
                    break;
                }
            }
            return;
        }

        // Merge missing keys only
        Map<String, String> existing = loadSettings(settings);
        int added = 0;
        for (Map.Entry<String, String> e : fromConf.entrySet()) {
            if (!existing.containsKey(e.getKey())) {
                existing.put(e.getKey(), e.getValue());
                added++;
                System.out.println("Merged missing key " + e.getKey() + "=" + redact(keyOnly(e.getKey()), e.getValue()));
            }
        }
        if (added > 0) {
            StringBuilder full = new StringBuilder();
            for (Map.Entry<String, String> e : existing.entrySet()) {
                full.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(settings, full.toString(), StandardCharsets.UTF_8);
            System.out.println("Merged " + added + " missing key(s) into amp-settings.cfg");
        } else {
            System.out.println("amp-settings.cfg exists - no missing keys to merge");
        }
    }

    /**
     * Apply amp-settings into conf. Logs only real changes; redacts secrets.
     */
    public static void apply(Path conf, Path settings) throws IOException {
        if (!Files.isRegularFile(conf)) {
            throw new HelperException(1, "Conf missing: " + conf);
        }
        ensureUiBanner(conf);
        if (!Files.isRegularFile(settings)) {
            System.out.println("No amp-settings.cfg - skip apply");
            return;
        }

        Map<String, String> pending = loadSettings(settings);
        List<String> lines = new ArrayList<>(Files.readAllLines(conf, StandardCharsets.UTF_8));
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
            boolean found = false;
            int sectionStart = -1;
            int sectionEnd = lines.size();
            // Match active or commented key lines: optional #, then key =
            Pattern lineRe = Pattern.compile("^([ \\t]*)#?[ \\t]*" + Pattern.quote(key) + "([ \\t]*=[ \\t]*)(.*)$");
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String stripped = line.strip();
                if (stripped.startsWith("[") && stripped.endsWith("]")) {
                    if (inSec) {
                        sectionEnd = i;
                        break;
                    }
                    inSec = stripped.equals("[" + sec + "]");
                    if (inSec) {
                        sectionStart = i;
                    }
                    continue;
                }
                if (!inSec) {
                    continue;
                }
                Matcher m = lineRe.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                found = true;
                String indent = m.group(1);
                String eq = m.group(2);
                String rest = m.group(3);
                Matcher vm = Pattern.compile("^(\"[^\"]*\"|[^#]*?)([ \\t]*#.*)?$").matcher(rest);
                if (!vm.matches()) {
                    break;
                }
                String oldRaw = vm.group(1).strip();
                String comment = vm.group(2) != null ? vm.group(2) : "";
                boolean quoted = oldRaw.startsWith("\"") && oldRaw.endsWith("\"");
                String oldCmp = quoted && oldRaw.length() >= 2
                        ? oldRaw.substring(1, oldRaw.length() - 1)
                        : oldRaw;
                if (oldCmp.equals(newVal) && !line.strip().startsWith("#")
                        && !(mustQuoteString(sec, key) && !quoted)) {
                    break;
                }
                boolean asString = shouldWriteQuoted(sec, key, newVal, quoted);
                String rendered;
                if (asString) {
                    String esc = newVal.replace("\\", "\\\\").replace("\"", "\\\"");
                    rendered = indent + key + eq + "\"" + esc + "\"" + comment;
                } else {
                    rendered = indent + key + eq + newVal + comment;
                }
                lines.set(i, rendered);
                changes++;
                System.out.println("Applied " + sec + "." + key + "=" + redact(key, newVal));
                break;
            }
            if (!found && sectionStart >= 0) {
                // Insert new key at end of section (before next section or EOF)
                String esc = newVal.replace("\\", "\\\\").replace("\"", "\\\"");
                boolean asString = shouldWriteQuoted(sec, key, newVal, false);
                String rendered = asString
                        ? key + " = \"" + esc + "\""
                        : key + " = " + newVal;
                lines.add(sectionEnd, rendered);
                changes++;
                System.out.println("Added " + sec + "." + key + "=" + redact(key, newVal));
            }
        }

        int coerced = coerceParserStringKeys(lines);
        changes += coerced;

        Files.write(conf, lines, StandardCharsets.UTF_8);
        if (coerced > 0) {
            System.out.println("Quoted " + coerced + " conf value(s) required as strings by ServerConfigParser");
        }
        System.out.println("Finished applying amp-settings.cfg (" + changes + " change" + (changes == 1 ? "" : "s") + ")");
    }

    /**
     * Keys that ServerConfigParser reads with getString() even when numeric.
     * Bare TOML numbers become Long and crash getString (ClassCastException).
     */
    private static final java.util.Set<String> FORCE_STRING_KEYS = java.util.Set.of(
            "database.database_port",
            "database.database_name",
            "database.database_username",
            "database.database_password",
            "database.database_address",
            "world.world_id",
            "world.country_id",
            "world.name",
            "world.name_ge",
            "world.activity",
            "world.home_location",
            "world.new_player_location",
            "world.motw_identifier",
            "world.motw_text",
            "server.secret_key",
            "server.log_level",
            "server.msip",
            "server.connectivity_check_url",
            "server.websocket_tls_keystore_path",
            "server.websocket_tls_keystore_password",
            "integrations.discord_ge_webhook",
            "integrations.discord_moderation_webhook",
            "integrations.openrsc_integration_webhook",
            "integrations.discord_invite",
            "integrations.grafana_log_path"
    );

    private static boolean mustQuoteString(String sec, String key) {
        return FORCE_STRING_KEYS.contains(sec + "." + key);
    }

    private static boolean shouldWriteQuoted(String sec, String key, String newVal, boolean oldWasQuoted) {
        if (mustQuoteString(sec, key)) {
            return true;
        }
        if (oldWasQuoted) {
            return true;
        }
        if (newVal.equals("true") || newVal.equals("false")) {
            return false;
        }
        // pure numbers stay unquoted only for real numeric TOML fields (getLong/getBoolean)
        return !newVal.matches("-?\\d+(\\.\\d+)?");
    }

    /** Fix already-unquoted values that the server parser requires as strings. */
    private static int coerceParserStringKeys(List<String> lines) {
        int fixed = 0;
        String sec = "";
        Pattern lineRe = Pattern.compile("^([ \\t]*)([A-Za-z0-9_]+)([ \\t]*=[ \\t]*)([^#]+?)([ \\t]*#.*)?$");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.startsWith("[") && stripped.endsWith("]")) {
                sec = stripped.substring(1, stripped.length() - 1).strip();
                continue;
            }
            if (sec.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            Matcher m = lineRe.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(2);
            if (!mustQuoteString(sec, key)) {
                continue;
            }
            String valPart = m.group(4).strip();
            if (valPart.startsWith("\"") && valPart.endsWith("\"")) {
                continue;
            }
            // strip trailing junk
            String bare = valPart;
            String esc = bare.replace("\\", "\\\\").replace("\"", "\\\"");
            String comment = m.group(5) != null ? m.group(5) : "";
            lines.set(i, m.group(1) + key + m.group(3) + "\"" + esc + "\"" + comment);
            fixed++;
            System.out.println("Coerced " + sec + "." + key + " to quoted string");
        }
        return fixed;
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
