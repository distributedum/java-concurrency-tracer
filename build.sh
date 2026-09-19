#!/usr/bin/env bash
# Builds sdtrace-agent.jar: a -javaagent (JDK 24+, java.lang.classfile).
#
# Usage: ./build.sh
set -euo pipefail

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
"$JAVAC" --release 24 -g -encoding UTF-8 -d out src/pt/sd/trace/*.java src/pt/sd/trace/agent/*.java

echo "» packaging sdtrace-agent.jar…"
cat > .agent-mf.txt <<'EOF'
Manifest-Version: 1.0
Premain-Class: pt.sd.trace.agent.Agent
Can-Retransform-Classes: false
EOF
"${JAVAC%javac}jar" cfm sdtrace-agent.jar .agent-mf.txt -C out pt
rm -f .agent-mf.txt

echo "» done. sdtrace-agent.jar is ready."
