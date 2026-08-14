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
  while IFS="=" read -r KEY VAL || [ -n "$KEY" ]; do
    KEY=$(echo "$KEY" | tr -d "\r" | sed "s/^[[:space:]]*//;s/[[:space:]]*$//")
    VAL=$(echo "$VAL" | tr -d "\r" | sed "s/^[[:space:]]*//;s/[[:space:]]*$//")
    [ -z "$KEY" ] && continue
    case "$KEY" in \#*) continue ;; esac
    case "$KEY" in *_*) ;; *) continue ;; esac
    SEC="${KEY%%_*}"
    TKEY="${KEY#*_}"
    [ -z "$SEC" ] || [ -z "$TKEY" ] && continue
    case "$VAL" in true|True|TRUE) VAL=true ;; false|False|FALSE) VAL=false ;; esac
    SEC="$SEC" TKEY="$TKEY" NEWVAL="$VAL" CONF="$CONF_ABS" python3 - <<'PY'
import os, re
path = os.environ["CONF"]
sec = os.environ["SEC"]
key = os.environ["TKEY"]
newval = os.environ["NEWVAL"]
lines = open(path, encoding="utf-8").read().splitlines()
out = []
insec = False
changed = False
line_re = re.compile(r"^([ \t]*)" + re.escape(key) + r"([ \t]*=[ \t]*)(.*)$")
for line in lines:
    stripped = line.strip()
    if stripped.startswith("[") and stripped.endswith("]"):
        insec = stripped == "[" + sec + "]"
    if insec:
        m = line_re.match(line)
        if m:
            indent, eq, rest = m.group(1), m.group(2), m.group(3)
            vm = re.match(r'^("[^"]*"|[^#]+?)([ \t]*#.*)?$', rest)
            if vm:
                old, comment = vm.group(1).strip(), vm.group(2) or ""
                if old.startswith('"'):
                    esc = newval.replace("\\", "\\\\").replace('"', '\\"')
                    line = indent + key + eq + '"' + esc + '"' + comment
                else:
                    line = indent + key + eq + newval + comment
                changed = True
    out.append(line)
open(path, "w", encoding="utf-8").write("\n".join(out) + "\n")
if changed:
    print("Applied %s.%s=%s" % (sec, key, newval))
PY
  done < "$SETTINGS"
  echo Finished applying amp-settings.cfg
fi
cd "$ROOT/2009scape/Server"
exec "$JAVA" -Dnashorn.args=--no-deprecation-warning -jar ../builddir/server.jar "$CONF_REL"
"""

p = Path("apply-and-start.sh")
p.write_text(script, encoding="utf-8")
p.chmod(p.stat().st_mode | 0o111)
print("Installed apply-and-start.sh")
