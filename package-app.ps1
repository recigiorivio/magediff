# Empacota o MageDiff como aplicativo do Windows, usando o jpackage do JDK 17+.
#
#   .\package-app.ps1              -> target\app\MageDiff\MageDiff.exe (pasta pronta)
#   .\package-app.ps1 msi          -> instalador .msi   (exige o WiX Toolset 3.x)
#   .\package-app.ps1 exe          -> instalador .exe   (exige o WiX Toolset 3.x)
#
# POR QUE ESTE ARQUIVO EXISTE, E NÃO UM BUILD ÚNICO:
# o jpackage NÃO faz cross-compile. Rodando no macOS ele só aceita app-image, dmg
# e pkg — pedir msi lá responde "Invalid or unsupported type: [msi]". O jar gordo
# (target\magediff-1.0.0.jar) é o MESMO nos dois sistemas; só o empacotamento é
# por plataforma. Então este script precisa rodar EM UMA MÁQUINA WINDOWS.
#
# ⚠️ Escrito no macOS e NÃO testado no Windows — os passos são os mesmos do
# package-app.sh, mas trate a primeira execução como um teste.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$Type    = if ($args.Count -gt 0) { $args[0] } else { "app-image" }
$Name    = "MageDiff"
$Version = "1.0.0"
$Jar     = "magediff-$Version.jar"

# O jar gordo é a entrada: com o shade, JGit e slf4j já estão dentro dele.
mvn -q package

# Diretório de entrada com SÓ o jar gordo. Apontar para target\ inteiro copiaria
# o original-*.jar (sem dependências) para dentro do app — peso morto e uma
# segunda cópia da classe principal.
$Stage = "target\app-input"
Remove-Item -Recurse -Force $Stage, "target\app" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
Copy-Item "target\$Jar" $Stage

$IconArgs = @()
if (Test-Path "src\main\resources\logo.ico") {
    $IconArgs = @("--icon", "src\main\resources\logo.ico")
}

# --win-console fica DE FORA: é um app gráfico, e com console o Windows abre uma
# janela de terminal preta atrás da janela do programa.
jpackage `
  --type $Type `
  --name $Name `
  --app-version $Version `
  --input $Stage `
  --main-jar $Jar `
  --main-class br.com.rivio.magediff.MageDiffApp `
  --dest target\app `
  --vendor Rivio `
  --description "Comparador e merge de dois arquivos" `
  --add-modules java.desktop,java.logging `
  --jlink-options "--strip-native-commands --no-header-files --no-man-pages --compress=2" `
  @IconArgs

Write-Host ""
Write-Host "Pronto em target\app:"
Get-ChildItem target\app | Select-Object -ExpandProperty Name
