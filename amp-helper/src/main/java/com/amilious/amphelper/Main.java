package com.amilious.amphelper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry for 2009Scape AMP helper.
 *
 * Commands:
 *   seed     --conf <default.conf> --settings <amp-settings.cfg> [--source <worldprops/default.conf>]
 *   apply    --conf <default.conf> --settings <amp-settings.cfg>
 *   db       --conf <default.conf> [--sql <global.sql>]
 *   prepare  --conf ... --settings ... [--source ...] [--sql ...]
 *   backup   --root <instance> --out <file.zip> [--no-db] [--no-players] [--no-eco] [--no-bots] [--no-serverstore] [--no-config]
 *   restore  --root <instance> --in  <file.zip> [--no-db] [--no-players] [--no-eco] [--no-bots] [--no-serverstore] [--no-config]
 *
 * Exit codes:
 *   0 OK
 *   1 Config / IO error
 *   2 DB connect / credentials
 *   3 Database missing
 *   4 Schema import failed
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length == 0 || isHelp(args[0])) {
            printUsage();
            System.exit(args.length == 0 ? 1 : 0);
        }

        String command = args[0].toLowerCase();
        Map<String, String> opts = parseOpts(args);

        try {
            switch (command) {
                case "seed":
                    runSeed(opts);
                    break;
                case "apply":
                    runApply(opts);
                    break;
                case "db":
                    runDb(opts);
                    break;
                case "prepare":
                    runPrepare(opts);
                    break;
                case "backup":
                    runBackup(opts);
                    break;
                case "restore":
                    runRestore(opts);
                    break;
                default:
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (HelperException e) {
            System.err.println("ERROR: " + e.getMessage());
            if (e.detail != null && !e.detail.isEmpty()) {
                System.err.println(e.detail);
            }
            System.exit(e.exitCode);
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void runSeed(Map<String, String> opts) throws Exception {
        Path conf = requiredPath(opts, "conf");
        Path settings = requiredPath(opts, "settings");
        Path source = opts.containsKey("source") ? Path.of(opts.get("source")) : null;
        ConfigTransformer.seed(conf, settings, source);
    }

    private static void runApply(Map<String, String> opts) throws Exception {
        Path conf = requiredPath(opts, "conf");
        Path settings = requiredPath(opts, "settings");
        ConfigTransformer.apply(conf, settings);
    }

    private static void runDb(Map<String, String> opts) throws Exception {
        Path conf = requiredPath(opts, "conf");
        Path sql = opts.containsKey("sql") ? Path.of(opts.get("sql")) : null;
        DatabaseChecker.check(conf, sql);
    }

    private static void runPrepare(Map<String, String> opts) throws Exception {
        Path conf = requiredPath(opts, "conf");
        Path settings = requiredPath(opts, "settings");
        Path source = opts.containsKey("source") ? Path.of(opts.get("source")) : null;
        Path sql = opts.containsKey("sql") ? Path.of(opts.get("sql")) : null;

        // Ensure conf exists (copy from source once)
        ConfigTransformer.ensureConf(conf, source);
        // Seed ini only if empty
        ConfigTransformer.seed(conf, settings, source);
        // Apply ini -> conf
        ConfigTransformer.apply(conf, settings);
        // DB check / import when auth flags set
        DatabaseChecker.check(conf, sql);
        // Port caveat (server binds fixed ports; AMP panel may show Not Listening)
        System.out.println("NOTE: Game binds TCP 43594 (game) and 43595 (world list).");
        System.out.println("NOTE: AMP port auto-increment does not change those binds — one game instance per host is recommended.");
        System.out.println("NOTE: AMP may show Main Game Port as Not Listening even when players can connect; check with: ss -tlnp | grep 4359");
    }

    private static void runBackup(Map<String, String> opts) throws Exception {
        Path root = requiredPath(opts, "root");
        Path out = requiredPath(opts, "out");
        BackupRestore.backup(root, out, opts);
    }

    private static void runRestore(Map<String, String> opts) throws Exception {
        Path root = requiredPath(opts, "root");
        Path in = requiredPath(opts, "in");
        BackupRestore.restore(root, in, opts);
    }

    private static Path requiredPath(Map<String, String> opts, String key) {
        if (!opts.containsKey(key) || opts.get(key).isBlank()) {
            throw new HelperException(1, "Missing required option --" + key);
        }
        return Path.of(opts.get(key));
    }

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String key = a.substring(2);
            String val = "true";
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                val = args[++i];
            }
            map.put(key, val);
        }
        return map;
    }

    private static boolean isHelp(String s) {
        return s.equals("-h") || s.equals("--help") || s.equals("help");
    }

    private static void printUsage() {
        System.out.println("2009Scape AMP Helper");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar amp-helper.jar seed    --conf <default.conf> --settings <amp-settings.cfg> [--source <worldprops/default.conf>]");
        System.out.println("  java -jar amp-helper.jar apply   --conf <default.conf> --settings <amp-settings.cfg>");
        System.out.println("  java -jar amp-helper.jar db      --conf <default.conf> [--sql <global.sql>]");
        System.out.println("  java -jar amp-helper.jar prepare --conf <default.conf> --settings <amp-settings.cfg> [--source ...] [--sql ...]");
        System.out.println("  java -jar amp-helper.jar backup  --root <instance> --out <file.zip> [--no-db] [--no-players] [--no-eco] [--no-bots] [--no-serverstore] [--no-config]");
        System.out.println("  java -jar amp-helper.jar restore --root <instance> --in  <file.zip> [--no-db] [--no-players] [--no-eco] [--no-bots] [--no-serverstore] [--no-config]");
        System.out.println();
        System.out.println("Backup default set: config, players, eco/GE, botdata, serverstore, database dump (from conf credentials).");
        System.out.println("Exit codes: 0=ok 1=config/io 2=db connect 3=db missing 4=import failed");
    }

    static final class HelperException extends RuntimeException {
        final int exitCode;
        final String detail;

        HelperException(int exitCode, String message) {
            this(exitCode, message, null);
        }

        HelperException(int exitCode, String message, String detail) {
            super(message);
            this.exitCode = exitCode;
            this.detail = detail;
        }
    }
}
