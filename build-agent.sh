#!/usr/bin/env bash
# TRANSPARENT (agent) path: students do NOT swap ReentrantLock for TracedLock
# and don't pass names. Requires JDK 24+ (the Class-File API is final in 24;
# the next LTS is recommended once available).
#
# Usage: ./build-agent.sh <demo-file.java> <ClassWithMain>
# E.g.:  ./build-agent.sh demo/BoundedBufferRaw.java BoundedBufferRaw
set -euo pipefail

SRC="${1:-demo/BoundedBufferRaw.java}"
MAIN="${2:-BoundedBufferRaw}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

ver="$("$JAVA" -version 2>&1 | head -1)"
echo "» JDK: $ver"

echo "» compiling runtime + agent…"
mkdir -p out
# -g matters: without the local variable table, locks/conditions held in
# LOCAL variables fall back to Type@Class:line (fields are always fine).
# The runtime targets bytecode 21 and the agent targets 24: this way THE SAME
# jar serves both paths (library on JDK 21+, agent on JDK 24+).
"$JAVAC" --release 21 -g -encoding UTF-8 -d out src/pt/sd/trace/*.java
"$JAVAC" --release 24 -g -encoding UTF-8 -cp out -d out src/pt/sd/trace/agent/*.java

echo "» packaging sdtrace-agent.jar…"
cat > .agent-mf.txt <<'EOF'
Manifest-Version: 1.0
Premain-Class: pt.sd.trace.agent.Agent
Can-Retransform-Classes: false
EOF
"${JAVAC%javac}jar" cfm sdtrace-agent.jar .agent-mf.txt -C out pt
rm -f .agent-mf.txt

echo "» compiling the example ($SRC)…"
"$JAVAC" -g -encoding UTF-8 -cp out -d out "$SRC"

echo "» running $MAIN with -javaagent (transparent instrumentation)…"
"$JAVA" -javaagent:sdtrace-agent.jar -cp out "$MAIN"

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
