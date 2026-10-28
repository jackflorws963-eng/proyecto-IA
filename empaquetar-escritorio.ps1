param([switch]$ConPruebas)
$ErrorActionPreference = 'Stop'

function Ejecutar([string]$programa, [string[]]$argumentos, [string]$mensajeError) {
    $anterior = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $programa @argumentos 2>&1 | ForEach-Object { "$_" } | Out-Host }
    finally { $ErrorActionPreference = $anterior }
    if ($LASTEXITCODE -ne 0) { throw $mensajeError }
}

$raiz = $PSScriptRoot
$backend = Join-Path $raiz 'backend'
$dist = Join-Path $raiz 'dist'
$nombre = 'SmartStock IoT'
$icono = Join-Path $raiz 'instalador\smartstock.ico'

$pom = Get-Content (Join-Path $backend 'pom.xml') -Raw
if ($pom -notmatch '</parent>[\s\S]*?<version>([^<]+)</version>') { throw 'No se pudo leer la version del pom.xml' }
$version = $Matches[1]

$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jpackage.exe' } else { (Get-Command jpackage -ErrorAction SilentlyContinue).Source }
if (-not $jpackage -or -not (Test-Path $jpackage)) { throw 'No se encontro jpackage. Instale un JDK 21 y defina JAVA_HOME.' }

Write-Host "== SmartStock IoT $version ==" -ForegroundColor Cyan

Write-Host '1/4 Compilando el .jar...'
$objetivos = if ($ConPruebas) { @('-B', 'package') } else { @('-B', '-q', 'package', '-DskipTests') }
Push-Location $backend
try {
    Ejecutar (Join-Path $backend 'mvnw.cmd') $objetivos 'Fallo la compilacion con Maven.'
} finally { Pop-Location }

$entrada = Join-Path $backend 'target\escritorio'
Remove-Item $entrada -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $entrada | Out-Null
Copy-Item (Join-Path $backend 'target\smartstock-iot.jar') $entrada

$comunes = @(
    '--name', $nombre,
    '--app-version', $version,
    '--vendor', 'Brayan (BR)',
    '--copyright', "Copyright (c) $((Get-Date).Year) Brayan (BR)",
    '--description', 'Sistema Inteligente de Inventario mediante Internet de las Cosas',
    '--icon', $icono,
    '--input', $entrada,
    '--main-jar', 'smartstock-iot.jar',
    '--java-options', '-Xmx512m -Dfile.encoding=UTF-8',
    '--dest', $dist
)

Write-Host '2/4 Creando el programa (incluye Java)...'
$imagen = Join-Path $dist $nombre
Remove-Item $imagen -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dist -Force | Out-Null
Ejecutar $jpackage (@('--type', 'app-image') + $comunes) 'Fallo jpackage.'

Write-Host '3/4 Creando el ZIP portable...'
$zip = Join-Path $dist "SmartStock-IoT-$version-portable.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path $imagen -DestinationPath $zip

Write-Host '4/4 Creando el instalador...'
$iscc = @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe", "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
          "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { $iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source }
$wix = Get-Command candle.exe -ErrorAction SilentlyContinue

if ($iscc) {
    Ejecutar $iscc @('/Q', "/DVersion=$version", "/DOrigen=$imagen", "/DSalida=$dist",
                     (Join-Path $raiz 'instalador\smartstock.iss')) 'Fallo Inno Setup.'
} elseif ($wix) {
    Ejecutar $jpackage (@('--type', 'exe') + $comunes + @('--win-menu', '--win-menu-group', $nombre, '--win-shortcut',
        '--win-dir-chooser', '--win-per-user-install', '--win-upgrade-uuid', '6f1c7c1e-3b0a-4f43-9d0e-5b7a52c0b8a1')) `
        'Fallo jpackage (instalador).'
    Get-ChildItem $dist -Filter "$nombre-$version.exe" | Rename-Item -NewName "SmartStock-IoT-$version-instalador.exe" -Force
} else {
    Write-Warning 'No se encontro Inno Setup 6 ni WiX 3: se omitio el instalador .exe.'
    Write-Warning 'Instale Inno Setup con:  winget install JRSoftware.InnoSetup   y vuelva a ejecutar este script.'
}

Write-Host "Listo. Archivos en $dist" -ForegroundColor Green
Get-ChildItem $dist -File | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } | Format-Table -AutoSize
