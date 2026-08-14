from pathlib import Path
import re

cfg = Path("config")
cfg.mkdir(parents=True, exist_ok=True)
conf = cfg / "default.conf"
src = Path("2009scape/Server/worldprops/default.conf")
if not conf.is_file() and src.is_file():
    conf.write_bytes(src.read_bytes())
    print("Copied default.conf")

settings = cfg / "amp-settings.cfg"
if (not settings.is_file() or settings.stat().st_size == 0) and conf.is_file():
    text = conf.read_text(encoding="utf-8")
    sec = ""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if s.startswith("[") and s.endswith("]"):
            sec = s[1:-1].strip()
            continue
        if "=" not in s or not sec:
            continue
        k, _, rest = s.partition("=")
        k = k.strip()
        rest = rest.strip()
        if rest.startswith('"'):
            m = re.match(r'^"([^"]*)"', rest)
            val = m.group(1) if m else rest.strip('"')
        else:
            val = rest.split("#")[0].strip()
        out.append(f"{sec}_{k}={val}")
    settings.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"Seeded amp-settings.cfg from default.conf ({len(out)} keys)")
    print("\n".join(out[:8]))
elif settings.is_file() and settings.stat().st_size > 0:
    print("amp-settings.cfg exists - leaving unchanged")
else:
    print("WARNING: could not seed amp-settings.cfg")

# Single Python apply pass: only log real changes; redact secrets
script = r"""#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
CONF_ABS="$ROOT/config/default.conf"
SETTINGS="$ROOT/config/amp-settings.cfg"
JAVA=/usr/lib/jvm/temurin-11-jdk-amd64/bin/java
CONF_REL="../../config/default.conf"
if [ ! -f "$CONF_ABS" ]; then
  echo "ERROR: $CONF_ABS missing"
  exit 1
fi
if [ -f "$SETTINGS" ]; then
  CONF="$CONF_ABS" SETTINGS="$SETTINGS" python3 - <<'PY'
import os, re

path = os.environ["CONF"]
settings_path = os.environ["SETTINGS"]
SECRET_KEYS = {"secret_key", "database_password"}

def redact(key, val):
    if key in SECRET_KEYS or key.endswith("_password") or key.endswith("secret_key"):
        return "***"
    return val

# Load settings: section_key=value
pending = []  # (sec, key, newval)
with open(settings_path, encoding="utf-8") as f:
    for raw in f:
        line = raw.strip().strip("\r")
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, _, v = line.partition("=")
        k, v = k.strip(), v.strip()
        if "_" not in k:
            continue
        sec, key = k.split("_", 1)
        if not sec or not key:
            continue
        if v.lower() in ("true", "false"):
            v = v.lower()
        pending.append((sec, key, v))

lines = open(path, encoding="utf-8").read().splitlines()
changes = 0

for sec, key, newval in pending:
    insec = False
    line_re = re.compile(r"^([ \t]*)" + re.escape(key) + r"([ \t]*=[ \t]*)(.*)$")
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            insec = stripped == "[" + sec + "]"
            continue
        if not insec:
            continue
        m = line_re.match(line)
        if not m:
            continue
        indent, eq, rest = m.group(1), m.group(2), m.group(3)
        vm = re.match(r'^("[^"]*"|[^#]+?)([ \t]*#.*)?$', rest)
        if not vm:
            break
        old_raw, comment = vm.group(1).strip(), vm.group(2) or ""
        # Normalize old for comparison
        if old_raw.startswith('"') and old_raw.endswith('"'):
            old_cmp = old_raw[1:-1]
            quoted = True
        else:
            old_cmp = old_raw
            quoted = False
        if old_cmp == newval:
            break  # no change
        if quoted:
            esc = newval.replace("\\", "\\\\").replace('"', '\\"')
            lines[i] = indent + key + eq + '"' + esc + '"' + comment
        else:
            lines[i] = indent + key + eq + newval + comment
        changes += 1
        print("Applied %s.%s=%s" % (sec, key, redact(key, newval)))
        break

open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("Finished applying amp-settings.cfg (%d change%s)" % (changes, "" if changes == 1 else "s"))
PY
fi
cd "$ROOT/2009scape/Server"
exec "$JAVA" -Dnashorn.args=--no-deprecation-warning -jar ../builddir/server.jar "$CONF_REL"
"""

p = Path("apply-and-start.sh")
p.write_text(script, encoding="utf-8")
p.chmod(p.stat().st_mode | 0o111)
print("Installed apply-and-start.sh")
