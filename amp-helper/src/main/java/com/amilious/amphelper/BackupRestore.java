package com.amilious.amphelper;

import com.amilious.amphelper.Main.HelperException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore instance data to a single zip.
 * Default includes: config, players, eco/GE, botdata, serverstore, database dump.
 * Opt-out flags: --no-db --no-players --no-eco --no-bots --no-serverstore --no-config
 */
public final class BackupRestore {

    private BackupRestore() {}

    public static void backup(Path root, Path outZip, Map<String, String> flags) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new HelperException(1, "Instance root is not a directory: " + root);
        }

        Path conf = root.resolve("config/default.conf");
        if (!Files.isRegularFile(conf)) {
            throw new HelperException(1, "Missing config/default.conf under root: " + conf);
        }

        Map<String, String> cfg = ConfigTransformer.parseConf(conf);
        Path serverDir = resolveServerDir(root);
        Path dataRoot = resolveDataRoot(serverDir, cfg);

        boolean withConfig = !flag(flags, "no-config");
        boolean withPlayers = !flag(flags, "no-players");
        boolean withEco = !flag(flags, "no-eco");
        boolean withBots = !flag(flags, "no-bots");
        boolean withStore = !flag(flags, "no-serverstore");
        boolean withDb = !flag(flags, "no-db");

        Path parent = outZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        System.out.println("Backup root: " + root);
        System.out.println("Output: " + outZip.toAbsolutePath());

        List<String> included = new ArrayList<>();
        Path tmpDb = null;

        try {
            if (withDb) {
                tmpDb = Files.createTempFile("amp-helper-db-", ".sql");
                dumpDatabase(cfg, tmpDb);
                included.add("database");
            }

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(outZip)))) {

                writeManifest(zos, root, included, withConfig, withPlayers, withEco, withBots, withStore, withDb, cfg);

                if (withConfig) {
                    addFileIfExists(zos, root.resolve("config/default.conf"), "config/default.conf");
                    addFileIfExists(zos, root.resolve("config/amp-settings.cfg"), "config/amp-settings.cfg");
                    included.add("config");
                    System.out.println("Added config/");
                }

                if (withPlayers) {
                    Path players = resolvePath(serverDir, dataRoot, cfg, "paths.save_path", "data/players");
                    int n = addTree(zos, players, "players/");
                    System.out.println("Added players/ (" + n + " file(s) from " + players + ")");
                    included.add("players");
                }

                if (withEco) {
                    Path eco = resolvePath(serverDir, dataRoot, cfg, "paths.eco_data", "data/eco");
                    Path ge = resolvePath(serverDir, dataRoot, cfg, "paths.grand_exchange_data_path", null);
                    int n = addTree(zos, eco, "eco/");
                    if (ge != null && !ge.equals(eco) && Files.isDirectory(ge)) {
                        n += addTree(zos, ge, "eco-ge/");
                    }
                    System.out.println("Added eco/ (" + n + " file(s))");
                    included.add("eco");
                }

                if (withBots) {
                    Path bots = resolvePath(serverDir, dataRoot, cfg, "paths.bot_data", "data/botdata");
                    int n = addTree(zos, bots, "botdata/");
                    System.out.println("Added botdata/ (" + n + " file(s) from " + bots + ")");
                    included.add("bots");
                }

                if (withStore) {
                    Path store = resolvePath(serverDir, dataRoot, cfg, "paths.store_path", "data/serverstore");
                    int n = addTree(zos, store, "serverstore/");
                    System.out.println("Added serverstore/ (" + n + " file(s) from " + store + ")");
                    included.add("serverstore");
                }

                if (withDb && tmpDb != null && Files.size(tmpDb) > 0) {
                    addFile(zos, tmpDb, "database/dump.sql");
                    System.out.println("Added database/dump.sql (" + Files.size(tmpDb) + " bytes)");
                }
            }
        } finally {
            if (tmpDb != null) {
                try {
                    Files.deleteIfExists(tmpDb);
                } catch (IOException ignored) {
                }
            }
        }

        System.out.println("Backup complete: " + outZip.toAbsolutePath());
    }

    public static void restore(Path root, Path inZip, Map<String, String> flags) throws IOException {
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new HelperException(1, "Instance root is not a directory: " + root);
        }
        if (!Files.isRegularFile(inZip)) {
            throw new HelperException(1, "Backup zip not found: " + inZip);
        }

        Path conf = root.resolve("config/default.conf");
        Map<String, String> cfg = Files.isRegularFile(conf)
                ? ConfigTransformer.parseConf(conf)
                : Map.of();
        Path serverDir = resolveServerDir(root);
        Path dataRoot = resolveDataRoot(serverDir, cfg);

        boolean withConfig = !flag(flags, "no-config");
        boolean withPlayers = !flag(flags, "no-players");
        boolean withEco = !flag(flags, "no-eco");
        boolean withBots = !flag(flags, "no-bots");
        boolean withStore = !flag(flags, "no-serverstore");
        boolean withDb = !flag(flags, "no-db");

        System.out.println("Restore root: " + root);
        System.out.println("Input: " + inZip.toAbsolutePath());
        System.out.println("NOTE: Stop the game server before restore for consistent results.");

        Path tmpDir = Files.createTempDirectory("amp-helper-restore-");
        try {
            unzip(inZip, tmpDir);

            Path manifest = tmpDir.resolve("manifest.json");
            if (Files.isRegularFile(manifest)) {
                System.out.println("Manifest: " + Files.readString(manifest, StandardCharsets.UTF_8).strip());
            }

            if (withConfig) {
                Files.createDirectories(root.resolve("config"));
                Path bakConf = tmpDir.resolve("config/default.conf");
                Path bakIni = tmpDir.resolve("config/amp-settings.cfg");
                Path curConf = root.resolve("config/default.conf");
                Path curIni = root.resolve("config/amp-settings.cfg");

                // Merge: backup values win for shared keys; keys only on current are kept
                if (Files.isRegularFile(bakIni)) {
                    mergeIni(bakIni, curIni);
                    System.out.println("Merged config/amp-settings.cfg (backup values, kept newer keys)");
                }
                if (Files.isRegularFile(bakConf)) {
                    if (!Files.isRegularFile(curConf)) {
                        Files.copy(bakConf, curConf, StandardCopyOption.REPLACE_EXISTING);
                        System.out.println("Restored config/default.conf (no existing file)");
                    } else {
                        mergeToml(bakConf, curConf);
                        System.out.println("Merged config/default.conf (backup values, kept newer keys)");
                    }
                }

                if (Files.isRegularFile(curConf)) {
                    cfg = ConfigTransformer.parseConf(curConf);
                    dataRoot = resolveDataRoot(serverDir, cfg);
                }
            }

            if (withPlayers) {
                Path src = tmpDir.resolve("players");
                if (Files.isDirectory(src)) {
                    Path dest = resolvePath(serverDir, dataRoot, cfg, "paths.save_path", "data/players");
                    copyTreeReplace(src, dest);
                    System.out.println("Restored players -> " + dest);
                }
            }

            if (withEco) {
                Path src = tmpDir.resolve("eco");
                if (Files.isDirectory(src)) {
                    Path dest = resolvePath(serverDir, dataRoot, cfg, "paths.eco_data", "data/eco");
                    copyTreeReplace(src, dest);
                    System.out.println("Restored eco -> " + dest);
                }
                Path srcGe = tmpDir.resolve("eco-ge");
                if (Files.isDirectory(srcGe)) {
                    Path dest = resolvePath(serverDir, dataRoot, cfg, "paths.grand_exchange_data_path", "data/eco");
                    copyTreeReplace(srcGe, dest);
                    System.out.println("Restored eco-ge -> " + dest);
                }
            }

            if (withBots) {
                Path src = tmpDir.resolve("botdata");
                if (Files.isDirectory(src)) {
                    Path dest = resolvePath(serverDir, dataRoot, cfg, "paths.bot_data", "data/botdata");
                    copyTreeReplace(src, dest);
                    System.out.println("Restored botdata -> " + dest);
                }
            }

            if (withStore) {
                Path src = tmpDir.resolve("serverstore");
                if (Files.isDirectory(src)) {
                    Path dest = resolvePath(serverDir, dataRoot, cfg, "paths.store_path", "data/serverstore");
                    copyTreeReplace(src, dest);
                    System.out.println("Restored serverstore -> " + dest);
                }
            }

            if (withDb) {
                Path dump = tmpDir.resolve("database/dump.sql");
                if (Files.isRegularFile(dump)) {
                    // Prefer current conf credentials after config restore
                    if (Files.isRegularFile(root.resolve("config/default.conf"))) {
                        cfg = ConfigTransformer.parseConf(root.resolve("config/default.conf"));
                    }
                    importDatabase(cfg, dump);
                    System.out.println("Restored database from dump.sql");
                } else {
                    System.out.println("No database/dump.sql in archive — skipped DB");
                }
            }
        } finally {
            deleteTree(tmpDir);
        }

        System.out.println("Restore complete");
    }

    // --- database ---

    private static void dumpDatabase(Map<String, String> cfg, Path outFile) throws IOException {
        String host = cfg.getOrDefault("database.database_address", "127.0.0.1");
        String port = cfg.getOrDefault("database.database_port", "3306");
        String user = cfg.getOrDefault("database.database_username", "root");
        String password = cfg.getOrDefault("database.database_password", "");
        String dbname = cfg.getOrDefault("database.database_name", "global");

        System.out.println("Dumping database '" + dbname + "' as " + user + "@" + host + ":" + port + "...");

        // Prefer mysqldump if available
        List<String> cmd = new ArrayList<>();
        cmd.add("mysqldump");
        cmd.add("-h");
        cmd.add(host);
        cmd.add("-P");
        cmd.add(port);
        cmd.add("-u");
        cmd.add(user);
        if (password != null && !password.isEmpty()) {
            cmd.add("-p" + password);
        }
        cmd.add("--single-transaction");
        cmd.add("--routines");
        cmd.add("--add-drop-table");
        cmd.add("--databases");
        cmd.add(dbname);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        try {
            Process p = pb.start();
            try (InputStream in = p.getInputStream();
                 OutputStream fileOut = Files.newOutputStream(outFile)) {
                in.transferTo(fileOut);
            }
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code == 0 && Files.size(outFile) > 0) {
                System.out.println("mysqldump OK (" + Files.size(outFile) + " bytes)");
                return;
            }
            System.out.println("mysqldump failed or empty (exit " + code + ") — trying JDBC fallback");
            if (!err.isBlank()) {
                System.out.println(err.strip());
            }
        } catch (Exception e) {
            System.out.println("mysqldump not available (" + e.getMessage() + ") — JDBC fallback");
        }

        jdbcDump(host, port, user, password, dbname, outFile);
    }

    private static void jdbcDump(String host, String port, String user, String password, String dbname, Path outFile)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("-- amp-helper JDBC dump\n");
        sb.append("CREATE DATABASE IF NOT EXISTS `").append(dbname).append("`;\n");
        sb.append("USE `").append(dbname).append("`;\n\n");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + dbname
                + "?useSSL=false&allowPublicKeyRetrieval=true";
        try (Connection c = DriverManager.getConnection(url, user, password);
             Statement st = c.createStatement();
             ResultSet tables = st.executeQuery("SHOW TABLES")) {

            List<String> tableNames = new ArrayList<>();
            while (tables.next()) {
                tableNames.add(tables.getString(1));
            }

            for (String table : tableNames) {
                try (Statement st2 = c.createStatement();
                     ResultSet cr = st2.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
                    if (cr.next()) {
                        sb.append("DROP TABLE IF EXISTS `").append(table).append("`;\n");
                        sb.append(cr.getString(2)).append(";\n\n");
                    }
                }
                try (Statement st3 = c.createStatement();
                     ResultSet rs = st3.executeQuery("SELECT * FROM `" + table + "`")) {
                    int cols = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        sb.append("INSERT INTO `").append(table).append("` VALUES (");
                        for (int i = 1; i <= cols; i++) {
                            if (i > 1) {
                                sb.append(',');
                            }
                            Object v = rs.getObject(i);
                            if (v == null) {
                                sb.append("NULL");
                            } else if (v instanceof Number || v instanceof Boolean) {
                                sb.append(v);
                            } else {
                                String s = v.toString().replace("\\", "\\\\").replace("'", "''");
                                sb.append('\'').append(s).append('\'');
                            }
                        }
                        sb.append(");\n");
                    }
                    sb.append('\n');
                }
            }
        } catch (SQLException e) {
            throw new HelperException(2, "JDBC database dump failed.", e.getMessage());
        }

        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("JDBC dump OK (" + Files.size(outFile) + " bytes)");
    }

    private static void importDatabase(Map<String, String> cfg, Path dumpFile) throws IOException {
        String host = cfg.getOrDefault("database.database_address", "127.0.0.1");
        String port = cfg.getOrDefault("database.database_port", "3306");
        String user = cfg.getOrDefault("database.database_username", "root");
        String password = cfg.getOrDefault("database.database_password", "");
        String dbname = cfg.getOrDefault("database.database_name", "global");

        System.out.println("Importing database dump into '" + dbname + "' as " + user + "...");

        String raw = Files.readString(dumpFile, StandardCharsets.UTF_8);
        // Soft rewrite global -> configured name if present
        String rewritten = DatabaseChecker.rewriteSql(raw, dbname);

        String urlDb = "jdbc:mysql://" + host + ":" + port + "/" + dbname
                + "?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true";
        try (Connection c = DriverManager.getConnection(urlDb, user, password)) {
            c.setAutoCommit(true);
            // Clear existing schema so CREATE TABLE in older dumps does not fail
            dropAllTables(c, dbname);
            executeScript(c, rewritten);
        } catch (SQLException e) {
            throw new HelperException(4,
                    "Database restore failed (check user privileges on '" + dbname + "').",
                    e.getMessage() + "\nNeed DROP/CREATE/INSERT on `" + dbname + "`.*, e.g.:\n"
                            + "  GRANT ALL PRIVILEGES ON `" + dbname + "`.* TO '" + user + "'@'localhost';\n"
                            + "  FLUSH PRIVILEGES;");
        }
    }

    /** Drop every base table in the database so restore can recreate from dump. */
    private static void dropAllTables(Connection c, String dbname) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS=0");
        }
        List<String> tables = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='"
                             + dbname.replace("'", "''") + "' AND TABLE_TYPE='BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        try (Statement st = c.createStatement()) {
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS `" + table + "`");
                System.out.println("Dropped table `" + table + "` before import");
            }
            st.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        if (!tables.isEmpty()) {
            System.out.println("Cleared " + tables.size() + " existing table(s) in '" + dbname + "'");
        }
    }

    private static void executeScript(Connection c, String script) throws SQLException {
        List<String> statements = splitStatements(script);
        try (Statement st = c.createStatement()) {
            for (String sql : statements) {
                String s = sql.strip();
                if (s.isEmpty() || s.startsWith("--")) {
                    continue;
                }
                st.execute(s);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> list = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle;
                cur.append(ch);
            } else if (ch == '"' && !inSingle) {
                inDouble = !inDouble;
                cur.append(ch);
            } else if (ch == ';' && !inSingle && !inDouble) {
                list.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            list.add(cur.toString());
        }
        return list;
    }

    // --- paths ---

    private static Path resolveServerDir(Path root) {
        Path a = root.resolve("2009scape/Server");
        if (Files.isDirectory(a)) {
            return a;
        }
        Path b = root.resolve("Server");
        if (Files.isDirectory(b)) {
            return b;
        }
        return root.resolve("2009scape/Server");
    }

    private static Path resolveDataRoot(Path serverDir, Map<String, String> cfg) {
        String data = cfg.getOrDefault("paths.data_path", "data");
        Path p = Path.of(data);
        if (!p.isAbsolute()) {
            p = serverDir.resolve(data);
        }
        return p.normalize();
    }

    private static Path resolvePath(Path serverDir, Path dataRoot, Map<String, String> cfg,
                                    String key, String fallbackRel) {
        String raw = cfg.get(key);
        if (raw == null || raw.isBlank()) {
            if (fallbackRel == null) {
                return null;
            }
            raw = fallbackRel;
        }
        raw = raw.replace("@data", dataRoot.toString().replace('\\', '/'));
        Path p = Path.of(raw);
        if (!p.isAbsolute()) {
            p = serverDir.resolve(raw);
        }
        return p.normalize();
    }

    // --- zip helpers ---

    private static void writeManifest(ZipOutputStream zos, Path root, List<String> included,
                                      boolean config, boolean players, boolean eco, boolean bots,
                                      boolean store, boolean db, Map<String, String> cfg) throws IOException {
        String when = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC));
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"tool\": \"amp-helper\",\n");
        json.append("  \"created\": \"").append(when).append("\",\n");
        json.append("  \"root\": \"").append(escapeJson(root.toString())).append("\",\n");
        json.append("  \"database\": \"").append(escapeJson(cfg.getOrDefault("database.database_name", ""))).append("\",\n");
        json.append("  \"include\": {\n");
        json.append("    \"config\": ").append(config).append(",\n");
        json.append("    \"players\": ").append(players).append(",\n");
        json.append("    \"eco\": ").append(eco).append(",\n");
        json.append("    \"bots\": ").append(bots).append(",\n");
        json.append("    \"serverstore\": ").append(store).append(",\n");
        json.append("    \"database\": ").append(db).append("\n");
        json.append("  }\n");
        json.append("}\n");
        ZipEntry e = new ZipEntry("manifest.json");
        zos.putNextEntry(e);
        zos.write(json.toString().getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * Merge backup ini into current: backup values overwrite shared keys;
     * keys present only on current are kept. If current missing, copy backup.
     */
    private static void mergeIni(Path backupIni, Path currentIni) throws IOException {
        Map<String, String> bak = loadFlatIni(backupIni);
        if (!Files.isRegularFile(currentIni)) {
            Files.copy(backupIni, currentIni, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Map<String, String> cur = loadFlatIni(currentIni);
        // Preserve order: current keys first, then any backup-only keys
        Map<String, String> out = new LinkedHashMap<>(cur);
        int changed = 0;
        int added = 0;
        for (Map.Entry<String, String> e : bak.entrySet()) {
            String prev = out.put(e.getKey(), e.getValue());
            if (prev == null) {
                added++;
            } else if (!prev.equals(e.getValue())) {
                changed++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : out.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        Files.writeString(currentIni, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("  ini: " + changed + " updated, " + added + " added from backup, "
                + (out.size() - bak.size()) + " current-only kept");
    }

    private static Map<String, String> loadFlatIni(Path ini) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(ini)) {
            return map;
        }
        for (String line : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(s.substring(0, eq).strip(), s.substring(eq + 1).strip());
        }
        return map;
    }

    /**
     * Merge backup TOML into current: for each section.key in backup, set value on current
     * (preserving comments when possible). Keys only on current remain.
     * If a backup key is missing on current, append it to that section.
     */
    private static void mergeToml(Path backupConf, Path currentConf) throws IOException {
        Map<String, String> bak = ConfigTransformer.parseConf(backupConf);
        List<String> lines = new ArrayList<>(Files.readAllLines(currentConf, StandardCharsets.UTF_8));
        int updated = 0;
        int added = 0;

        for (Map.Entry<String, String> e : bak.entrySet()) {
            String flat = e.getKey(); // section.key
            int dot = flat.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            String sec = flat.substring(0, dot);
            String key = flat.substring(dot + 1);
            String newVal = e.getValue();

            boolean inSec = false;
            boolean found = false;
            int sectionStart = -1;
            int sectionEnd = lines.size();
            java.util.regex.Pattern lineRe = java.util.regex.Pattern.compile(
                    "^([ \\t]*)#?[ \\t]*" + java.util.regex.Pattern.quote(key) + "([ \\t]*=[ \\t]*)(.*)$");

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
                java.util.regex.Matcher m = lineRe.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                found = true;
                String indent = m.group(1);
                String eq = m.group(2);
                String rest = m.group(3);
                java.util.regex.Matcher vm = java.util.regex.Pattern
                        .compile("^(\"[^\"]*\"|[^#]*?)([ \\t]*#.*)?$")
                        .matcher(rest);
                String comment = "";
                if (vm.matches() && vm.group(2) != null) {
                    comment = vm.group(2);
                }
                boolean asString = !newVal.equals("true") && !newVal.equals("false")
                        && !newVal.matches("-?\\d+(\\.\\d+)?");
                String rendered;
                if (asString) {
                    String esc = newVal.replace("\\", "\\\\").replace("\"", "\\\"");
                    rendered = indent + key + eq + "\"" + esc + "\"" + comment;
                } else {
                    rendered = indent + key + eq + newVal + comment;
                }
                if (!line.equals(rendered)) {
                    lines.set(i, rendered);
                    updated++;
                }
                break;
            }
            if (!found && sectionStart >= 0) {
                boolean asString = !newVal.equals("true") && !newVal.equals("false")
                        && !newVal.matches("-?\\d+(\\.\\d+)?");
                String esc = newVal.replace("\\", "\\\\").replace("\"", "\\\"");
                String rendered = asString ? key + " = \"" + esc + "\"" : key + " = " + newVal;
                lines.add(sectionEnd, rendered);
                added++;
            }
        }
        Files.write(currentConf, lines, StandardCharsets.UTF_8);
        System.out.println("  conf: " + updated + " updated, " + added + " added from backup");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void addFileIfExists(ZipOutputStream zos, Path file, String entryName) throws IOException {
        if (Files.isRegularFile(file)) {
            addFile(zos, file, entryName);
        }
    }

    private static void addFile(ZipOutputStream zos, Path file, String entryName) throws IOException {
        ZipEntry e = new ZipEntry(entryName.replace('\\', '/'));
        zos.putNextEntry(e);
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static int addTree(ZipOutputStream zos, Path dir, String prefix) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int[] count = {0};
        Path base = dir;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = base.relativize(file).toString().replace('\\', '/');
                addFile(zos, file, prefix + rel);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest)) {
                    throw new HelperException(1, "Zip entry escapes target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private static void copyTreeReplace(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dest.resolve(rel.toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dest.resolve(rel.toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean flag(Map<String, String> flags, String name) {
        if (flags == null) {
            return false;
        }
        String v = flags.get(name);
        if (v == null) {
            return false;
        }
        if (v.equalsIgnoreCase("true") || v.isEmpty()) {
            return true;
        }
        return !v.equalsIgnoreCase("false");
    }
}
