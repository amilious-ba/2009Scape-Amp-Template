package com.amilious.amphelper;

import com.amilious.amphelper.Main.HelperException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Hard-fail DB prepare: connect, require database, import schema if empty.
 */
public final class DatabaseChecker {

    private DatabaseChecker() {}

    public static void check(Path conf, Path sqlFile) throws IOException {
        Map<String, String> cfg = ConfigTransformer.parseConf(conf);

        boolean useAuth = isTrue(cfg.getOrDefault("server.use_auth", "false"));
        boolean persist = isTrue(cfg.getOrDefault("server.persist_accounts", "false"));
        if (!useAuth && !persist) {
            System.out.println("DB check skipped (use_auth and persist_accounts both false)");
            return;
        }

        String host = cfg.getOrDefault("database.database_address", "127.0.0.1");
        String port = cfg.getOrDefault("database.database_port", "3306");
        String user = cfg.getOrDefault("database.database_username", "root");
        String password = cfg.getOrDefault("database.database_password", "");
        String dbname = cfg.getOrDefault("database.database_name", "global");

        System.out.println("DB check: connecting to " + host + ":" + port + " as " + user + " (database=" + dbname + ")...");

        // 1) Connect to server (no default database)
        try (Connection c = connect(host, port, user, password, null)) {
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
        } catch (SQLException e) {
            throw new HelperException(2,
                    "Cannot connect to database server (server down or credentials invalid).",
                    shortMsg(e));
        }
        System.out.println("DB server connection OK");

        // 2) Database exists?
        boolean exists;
        try (Connection c = connect(host, port, user, password, null);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"
                             + escapeSql(dbname) + "'")) {
            exists = rs.next();
        } catch (SQLException e) {
            throw new HelperException(2, "Could not query database list (permissions?).", shortMsg(e));
        }

        if (!exists) {
            throw new HelperException(3,
                    "Database '" + dbname + "' does not exist.",
                    "Create it on the host as a privileged user, for example:\n"
                            + "  CREATE DATABASE `" + dbname + "`;\n"
                            + "  GRANT ALL ON `" + dbname + "`.* TO '" + user + "'@'localhost' IDENTIFIED BY '...';\n"
                            + "  FLUSH PRIVILEGES;\n"
                            + "Then start the instance again.");
        }
        System.out.println("Database '" + dbname + "' exists");

        // 3) Tables?
        List<String> tables = listTables(host, port, user, password, dbname);
        if (!tables.isEmpty()) {
            System.out.println("Schema OK (" + tables.size() + " table(s) found)");
            return;
        }

        System.out.println("Database '" + dbname + "' is empty — importing schema...");

        Path sql = resolveSql(sqlFile, conf);
        if (sql == null || !Files.isRegularFile(sql)) {
            throw new HelperException(4,
                    "Schema file not found (expected Server/db_exports/global.sql).",
                    "Clone/build may be incomplete. Run Update, then try again.");
        }

        String raw = Files.readString(sql, StandardCharsets.UTF_8);
        String rewritten = rewriteSql(raw, dbname);

        try (Connection c = connect(host, port, user, password, null)) {
            c.setAutoCommit(true);
            executeSqlScript(c, rewritten);
        } catch (SQLException e) {
            // Retry with database selected (if CREATE DATABASE not allowed)
            try (Connection c = connect(host, port, user, password, dbname)) {
                c.setAutoCommit(true);
                executeSqlScript(c, rewritten);
            } catch (SQLException e2) {
                throw new HelperException(4,
                        "Could not import schema into '" + dbname + "'.",
                        "The database user may lack CREATE/INSERT privileges, or the SQL failed.\n"
                                + shortMsg(e2));
            }
        }

        tables = listTables(host, port, user, password, dbname);
        if (tables.isEmpty()) {
            throw new HelperException(4,
                    "Import reported success but no tables found in '" + dbname + "'.");
        }
        System.out.println("Imported schema into '" + dbname + "' (" + tables.size() + " table(s))");
    }

    private static Connection connect(String host, String port, String user, String password, String db)
            throws SQLException {
        String url = "jdbc:mysql://" + host + ":" + port + "/"
                + (db != null ? db : "")
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return DriverManager.getConnection(url, user, password);
    }

    private static List<String> listTables(String host, String port, String user, String password, String db)
            throws HelperException {
        List<String> tables = new ArrayList<>();
        try (Connection c = connect(host, port, user, password, db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW TABLES")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new HelperException(2,
                    "Could not list tables in '" + db + "' (permissions?).",
                    shortMsg(e));
        }
        return tables;
    }

    private static Path resolveSql(Path sqlFile, Path conf) {
        if (sqlFile != null && Files.isRegularFile(sqlFile)) {
            return sqlFile;
        }
        // Walk up from conf: config/default.conf -> instance root
        Path root = conf.toAbsolutePath().getParent();
        if (root != null && root.getFileName() != null && root.getFileName().toString().equals("config")) {
            root = root.getParent();
        }
        if (root == null) {
            return sqlFile;
        }
        Path[] candidates = {
                root.resolve("2009scape/Server/db_exports/global.sql"),
                root.resolve("2009scape/db_exports/global.sql"),
                root.resolve("Server/db_exports/global.sql"),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return sqlFile;
    }

    static String rewriteSql(String raw, String dbname) {
        String out = raw.replace("`global`", "`" + dbname + "`");
        out = Pattern.compile("CREATE\\s+DATABASE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?global`?",
                Pattern.CASE_INSENSITIVE).matcher(out).replaceAll("CREATE DATABASE IF NOT EXISTS `" + dbname + "`");
        out = Pattern.compile("USE\\s+`?global`?", Pattern.CASE_INSENSITIVE)
                .matcher(out).replaceAll("USE `" + dbname + "`");
        return out;
    }

    /** Split on semicolons outside of strings (simple) and execute. */
    private static void executeSqlScript(Connection c, String script) throws SQLException {
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

    private static boolean isTrue(String v) {
        if (v == null) {
            return false;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static String escapeSql(String s) {
        return s.replace("'", "''");
    }

    private static String shortMsg(SQLException e) {
        String m = e.getMessage();
        if (m == null) {
            return e.toString();
        }
        return m.length() > 400 ? m.substring(0, 400) : m;
    }
}
