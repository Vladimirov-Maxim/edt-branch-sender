# Сборка бандла без Maven: компиляция против jar-ов установленного EDT (p2-пул) и упаковка.
# Запуск: powershell -File build.ps1 [-Install]
param(
    [switch]$Install,
    [string]$Pool = "$env:USERPROFILE\.p2\pool\plugins",
    [string]$EdtDir = "$env:LOCALAPPDATA\1C\1cedtstart\installations\1C_EDT 2025.2\1cedt"
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$jdk = Get-ChildItem "C:\Program Files\1C\1CE\components" -Directory -Filter "axiom-jdk-full-17*" | Select-Object -First 1
if (-not $jdk) { throw "Не найден JDK 17 из комплекта 1C:EDT" }
$javac = Join-Path $jdk.FullName "bin\javac.exe"
$jar = Join-Path $jdk.FullName "bin\jar.exe"

$version = (Select-String -Path "$root\bundle\META-INF\MANIFEST.MF" -Pattern '^Bundle-Version:\s*(\S+)').Matches[0].Groups[1].Value
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force $classes | Out-Null

$cp = (Get-ChildItem $Pool -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$argFile = Join-Path $build "javac.args"
$lines = @('--release', '17', '-encoding', 'UTF-8', '-nowarn', '-d', ('"' + $classes.Replace('\', '/') + '"'), '-cp', ('"' + $cp.Replace('\', '/') + '"')) + $sources
[IO.File]::WriteAllLines($argFile, $lines)
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Ошибка компиляции" }

Copy-Item "$root\bundle\plugin.xml" $classes
$bundle = 'edtbranchsender'
$out = Join-Path $root "dist\${bundle}_$version.jar"
New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
if (Test-Path $out) { Remove-Item $out }
& $jar --create --file $out --manifest "$root\bundle\META-INF\MANIFEST.MF" -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Ошибка упаковки" }
Write-Output "Собрано: $out"

# Готовый jar хранится в репозитории (plugin\), чтобы ставить без сборки.
$release = Join-Path $root "plugin"
New-Item -ItemType Directory -Force $release | Out-Null
Get-ChildItem $release -Filter "${bundle}_*.jar" | Remove-Item
Copy-Item $out $release

if ($Install) {
    $dropins = Join-Path $EdtDir "dropins"
    New-Item -ItemType Directory -Force $dropins | Out-Null
    try {
        Get-ChildItem $dropins -Filter "${bundle}_*.jar" | Remove-Item -ErrorAction Stop
        Copy-Item $out $dropins -ErrorAction Stop
    } catch {
        throw "Не удалось заменить плагин в dropins — закройте EDT и повторите установку. ($($_.Exception.Message))"
    }
    Write-Output "Установлено в $dropins (нужен перезапуск EDT)"
}
