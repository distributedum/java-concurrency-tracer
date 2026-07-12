#!/usr/bin/env bash
# Usage: ./build.sh <demo-file.java> <ClassWithMain>
# E.g.:  ./build.sh demo/BoundedBufferDemo.java BoundedBufferDemo
set -euo pipefail

SRC="${1:-demo/BoundedBufferDemo.java}"
MAIN="${2:-BoundedBufferDemo}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "» compiling…"
mkdir -p out
javac -g -encoding UTF-8 -d out src/pt/sd/trace/*.java "$SRC"

echo "» running ${MAIN}…"
java -cp out "$MAIN"

# Injects trace.json into the template, producing a ready-to-open spacetime.html.
# Uses python3 if present; otherwise leaves trace.json to be loaded by hand.
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import re
tpl=open('viz/template.html').read()
trace=open('trace.json').read().strip()
new=re.sub(r'/\*__TRACE__\*/.*?/\*__END__\*/','/*__TRACE__*/'+trace+'/*__END__*/',tpl,flags=re.S)
open('viz/spacetime.html','w').write(new)
print("» viz/spacetime.html updated with the new trace")
PY
else
  echo "» python3 not found: open viz/spacetime.html and use “Open trace.json…”"
fi

echo "» done. Open viz/spacetime.html in your browser."
