#!/usr/bin/env bash
# Compila e roda sem precisar do Maven no dia a dia. Uso:
#   ./run.sh                          abre a janela vazia (use os botões Abrir)
#   ./run.sh a.xml b.xml              abre já comparando os dois
#   ./run.sh test                     roda as verificações do motor
#   ./run.sh demo                     abre os samples em CÓPIA (salvar não estraga o original)
#   ./run.sh render a.xml b.xml o.png gera um PNG da interface, sem abrir janela
set -euo pipefail
cd "$(dirname "$0")"

OUT=target/classes
LIB=target/lib

# A única dependência (java-diff-utils) é baixada UMA vez para target/lib. Depois
# disso o script só usa javac/java — sem Maven, sem rede. Se o jar sumir (target
# limpo), o Maven é chamado de novo automaticamente.
if [ -z "$(ls -A "$LIB" 2>/dev/null || true)" ]; then
  echo "Resolvendo dependências (uma vez)…" >&2
  mvn -q dependency:copy-dependencies -DoutputDirectory="$LIB" >&2
fi
CP="$OUT:$LIB/*"

# Ícone: PNG com cantos transparentes, derivado do logo.jpg. Regenerado só
# quando o jpg é mais novo — o PNG é artefato, o jpg é a fonte.
ICON_PNG=src/main/resources/logo.png
ICON_ICO=src/main/resources/logo.ico
if [ -f logo.jpg ] && { [ ! "$ICON_PNG" -nt logo.jpg ] || [ ! -f "$ICON_ICO" ]; }; then
  mkdir -p "$(dirname "$ICON_PNG")" target/tools
  javac -d target/tools tools/PrepIcon.java
  # PNG para a janela e o .icns do macOS; ICO porque o Windows não aceita nem um
  # nem outro como ícone de executável.
  java -cp target/tools PrepIcon logo.jpg "$ICON_PNG" "$ICON_ICO"
fi

mkdir -p "$OUT"
find src -name '*.java' -print0 | xargs -0 javac -cp "$CP" -d "$OUT" -encoding UTF-8
# javac não copia recursos; sem isto o ícone da janela não existe fora do Maven.
[ -d src/main/resources ] && cp -R src/main/resources/. "$OUT"/ 2>/dev/null || true

case "${1:-}" in
  test)
    exec java -cp "$CP" br.com.rivio.magediff.EngineSmokeTest
    ;;
  demo)
    # Cópia porque "Salvar" grava por cima: sem isso, o primeiro merge de teste
    # consome os arquivos de exemplo e a demo seguinte já não mostra nada.
    mkdir -p target/demo
    cp samples/query-conta-antes.xml samples/query-conta-depois.xml target/demo/
    exec java -cp "$CP" br.com.rivio.magediff.MageDiffApp \
      target/demo/query-conta-antes.xml target/demo/query-conta-depois.xml
    ;;
  render)
    shift
    left="$1"; right="$2"; out="$3"
    exec java -cp "$CP" br.com.rivio.magediff.MageDiffApp "$left" "$right" --render "$out"
    ;;
  *)
    exec java -cp "$CP" br.com.rivio.magediff.MageDiffApp "$@"
    ;;
esac
