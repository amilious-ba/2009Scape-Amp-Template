# 2009Scape AMP Helper

Small Java 11 utility for the AMP Generic template:

- **seed** — `default.conf` → `amp-settings.cfg` (once)
- **apply** — `amp-settings.cfg` → `default.conf` (only logs real changes; secrets redacted)
- **db** — connect / require database / import rewritten `global.sql` if empty
- **prepare** — seed + apply + db (use this before starting the game server)

## Build

```bash
cd amp-helper
mvn -q package
# output: target/amp-helper-1.0.0.jar (shaded, includes MySQL Connector/J)
```

Copy the jar to the instance root (or a fixed path) as `amp-helper.jar`.

## CLI

```bash
java -jar amp-helper.jar prepare \
  --conf config/default.conf \
  --settings config/amp-settings.cfg \
  --source 2009scape/Server/worldprops/default.conf \
  --sql 2009scape/Server/db_exports/global.sql

java -jar amp-helper.jar seed  --conf config/default.conf --settings config/amp-settings.cfg --source ...
java -jar amp-helper.jar apply --conf config/default.conf --settings config/amp-settings.cfg
java -jar amp-helper.jar db    --conf config/default.conf --sql 2009scape/Server/db_exports/global.sql
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | OK |
| 1 | Config / IO |
| 2 | DB connect / credentials |
| 3 | Database missing |
| 4 | Schema import failed |

## AMP start sketch

```bash
/usr/lib/jvm/temurin-11-jdk-amd64/bin/java -jar amp-helper.jar prepare \
  --conf config/default.conf \
  --settings config/amp-settings.cfg \
  --source 2009scape/Server/worldprops/default.conf \
  --sql 2009scape/Server/db_exports/global.sql \
&& cd 2009scape/Server \
&& exec /usr/lib/jvm/temurin-11-jdk-amd64/bin/java -Dnashorn.args=--no-deprecation-warning \
     -jar ../builddir/server.jar ../../config/default.conf
```

Requires only **Java 11** on the host (no Python, no mysql client).
