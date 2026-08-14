#!/usr/bin/env bash
# Empacota como aplicativo nativo usando o jpackage do próprio JDK (17+).
#
#   ./package-app.sh            → target/app/MageDiff.app  (abre com duplo clique)
#   ./package-app.sh dmg        → target/app/MageDiff-1.0.0.dmg (instalador)
#
# O jpackage embute um runtime Java no app, então a máquina de destino NÃO
# precisa ter Java instalado — é o que separa "um jar que eu rodo no terminal"
# de um aplicativo que se entrega para alguém.
#
# --java-options em vez de System.setProperty no main: o launcher nativo do
# jpackage já inicializou o AppKit quando o main roda, e aí "menu na barra do
# sistema" chega tarde — o menu simplesmente não aparece em lugar nenhum.
#
# ⚠️ NUNCA passe --java-options com espaço no VALOR. O jpackage grava cada
# java-options numa linha do .cfg e quebra por espaço: "-Dfoo=A B" virou três
# opções ("-Dfoo=A", "B", "C"), a JVM recusou os argumentos soltos e o app morria
# antes de abrir janela, sem imprimir nada. O nome do app já vem do --name (vai
# para o CFBundleName no Info.plist); não precisa de -Dapple.awt.application.name.
#
# --add-modules + --jlink-options existem porque, sem eles, o jpackage embute um
# runtime completo: medido, 135 MB contra 56 MB. O app só precisa de java.desktop
# (Swing/AWT) e java.logging (o slf4j-nop encosta nela).
set -euo pipefail
cd "$(dirname "$0")"

TYPE="${1:-app-image}"
NAME="MageDiff"
VERSION=1.0.0
JAR="magediff-${VERSION}.jar"

# O fat jar é a entrada: com shade, tudo (JGit + slf4j) já está dentro dele.
mvn -q package

# Diretório de entrada com SÓ o fat jar. Apontar o jpackage para target/ inteiro
# faria ele copiar junto o original-*.jar (o jar sem as dependências) para dentro
# do app — peso morto e uma segunda cópia da classe principal.
# macOS só aceita .icns como ícone de bundle. Gerado a partir do logo.jpg com as
# ferramentas do próprio sistema — nada de commitar um binário derivado que
# ninguém sabe regenerar depois.
ICON=""
SOURCE_ICON=src/main/resources/logo.png   # PNG com alfa: jpg achataria os cantos
# Os ícones são derivados e ficam fora do git; num clone novo eles não existem.
if [ -f logo.jpg ] && [ ! -f "$SOURCE_ICON" ]; then
  mkdir -p src/main/resources target/tools
  javac -d target/tools tools/PrepIcon.java
  java -cp target/tools PrepIcon logo.jpg "$SOURCE_ICON" src/main/resources/logo.ico
fi
if [ -f "$SOURCE_ICON" ]; then
  ICONSET=target/logo.iconset
  rm -rf "$ICONSET"; mkdir -p "$ICONSET"
  for size in 16 32 64 128 256 512; do
    sips -z $size $size "$SOURCE_ICON" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null 2>&1
    double=$((size * 2))
    sips -z $double $double "$SOURCE_ICON" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null 2>&1
  done
  if iconutil -c icns "$ICONSET" -o target/logo.icns 2>/dev/null; then
    ICON="--icon target/logo.icns"
  fi
fi

STAGE=target/app-input
rm -rf "$STAGE" target/app
mkdir -p "$STAGE"
cp "target/$JAR" "$STAGE/"

jpackage \
  $ICON \
  --type "$TYPE" \
  --name "$NAME" \
  --app-version "$VERSION" \
  --input "$STAGE" \
  --main-jar "$JAR" \
  --main-class br.com.rivio.magediff.MageDiffApp \
  --dest target/app \
  --vendor Rivio \
  --description "Comparador e merge de dois arquivos" \
  --mac-package-identifier br.com.rivio.magediff \
  --java-options "-Dapple.laf.useScreenMenuBar=true" \
  --add-modules java.desktop,java.logging \
  --jlink-options "--strip-native-commands --no-header-files --no-man-pages --compress=2"

echo
echo "Pronto em target/app:"
ls -1 target/app
