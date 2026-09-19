#!/usr/bin/env bash
# Builds sdtrace-agent.jar, runs one of the demo classes with the agent
# attached, and re-embeds the resulting trace.json into viz/spacetime.html.
#
# Usage: ./run-demo.sh <demo-file.java> <ClassWithMain>
# E.g.:  ./run-demo.sh demo/BoundedBufferRaw.java BoundedBufferRaw
set -euo pipefail

SRC="${1:-demo/BoundedBufferRaw.java}"
MAIN="${2:-BoundedBufferRaw}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

./build.sh

echo "» compiling the demo ($SRC)…"
mkdir -p out
"$JAVAC" -g -encoding UTF-8 -cp sdtrace-agent.jar -d out "$SRC"

echo "» running $MAIN with -javaagent (transparent instrumentation)…"
"$JAVA" -javaagent:sdtrace-agent.jar -cp sdtrace-agent.jar:out "$MAIN"

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import re
tpl=open('viz/template.html').read(); trace=open('trace.json').read().strip()
open('viz/spacetime.html','w').write(
  re.sub(r'/\*__TRACE__\*/.*?/\*__END__\*/','/*__TRACE__*/'+trace+'/*__END__*/',tpl,flags=re.S))
print("» viz/spacetime.html updated")
PY
else
  echo "» python3 not found: open viz/spacetime.html and use “Open trace.json…”"
fi

echo "» done. Open viz/spacetime.html in your browser."
