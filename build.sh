#!/usr/bin/env bash
# Uso: ./build.sh <ficheiro-demo.java> <ClasseComMain>
# Ex.: ./build.sh demo/BoundedBufferDemo.java BoundedBufferDemo
set -euo pipefail

SRC="${1:-demo/BoundedBufferDemo.java}"
MAIN="${2:-BoundedBufferDemo}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

echo "» a compilar…"
mkdir -p out
javac -g -encoding UTF-8 -d out src/pt/sd/trace/*.java "$SRC"

echo "» a correr ${MAIN}…"
java -cp out "$MAIN"

# Injecta o trace.json no template, produzindo um spacetime.html pronto a abrir.
# Usa python3 se existir; caso contrário, deixa o trace.json para carregar à mão.
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import re
tpl=open('viz/template.html').read()
trace=open('trace.json').read().strip()
new=re.sub(r'/\*__TRACE__\*/.*?/\*__END__\*/','/*__TRACE__*/'+trace+'/*__END__*/',tpl,flags=re.S)
open('viz/spacetime.html','w').write(new)
print("» viz/spacetime.html actualizado com o novo trace")
PY
else
  echo "» python3 não encontrado: abra viz/spacetime.html e use “Abrir trace.json…”"
fi

echo "» pronto. Abra viz/spacetime.html no browser."
