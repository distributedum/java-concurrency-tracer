#!/usr/bin/env bash
# Vertente TRANSPARENTE (agente): os alunos NÃO trocam ReentrantLock por
# TracedLock nem passam nomes. Requer JDK 24+ (a Class-File API é final no 24;
# recomendado o próximo LTS quando disponível).
#
# Uso: ./build-agent.sh <ficheiro-demo.java> <ClasseComMain>
# Ex.: ./build-agent.sh demo/BoundedBufferRaw.java BoundedBufferRaw
set -euo pipefail

SRC="${1:-demo/BoundedBufferRaw.java}"
MAIN="${2:-BoundedBufferRaw}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

ver="$("$JAVA" -version 2>&1 | head -1)"
echo "» JDK: $ver"

echo "» a compilar runtime + agente…"
mkdir -p out
# -g é importante: sem a tabela de variáveis locais, locks/condições guardados
# em variáveis LOCAIS caem no fallback Tipo@Classe:linha (campos são sempre ok).
# O runtime vai para bytecode 21 e o agente para 24: assim O MESMO jar serve as duas
# vias (biblioteca em JDK 21+, agente em JDK 24+).
"$JAVAC" --release 21 -g -encoding UTF-8 -d out src/pt/sd/trace/*.java
"$JAVAC" --release 24 -g -encoding UTF-8 -cp out -d out src/pt/sd/trace/agent/*.java

echo "» a empacotar sdtrace-agent.jar…"
cat > .agent-mf.txt <<'EOF'
Manifest-Version: 1.0
Premain-Class: pt.sd.trace.agent.Agent
Can-Retransform-Classes: false
EOF
"${JAVAC%javac}jar" cfm sdtrace-agent.jar .agent-mf.txt -C out pt
rm -f .agent-mf.txt

echo "» a compilar o exemplo ($SRC)…"
"$JAVAC" -g -encoding UTF-8 -cp out -d out "$SRC"

echo "» a correr $MAIN com -javaagent (instrumentação transparente)…"
"$JAVA" -javaagent:sdtrace-agent.jar -cp out "$MAIN"

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import re
tpl=open('viz/template.html').read(); trace=open('trace.json').read().strip()
open('viz/spacetime.html','w').write(
  re.sub(r'/\*__TRACE__\*/.*?/\*__END__\*/','/*__TRACE__*/'+trace+'/*__END__*/',tpl,flags=re.S))
print("» viz/spacetime.html actualizado")
PY
else
  echo "» python3 não encontrado: abra viz/spacetime.html e use “Abrir trace.json…”"
fi

echo "» pronto. Abra viz/spacetime.html no browser."
