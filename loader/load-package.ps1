<#
.SYNOPSIS
    Загружает пакет изменений (zip из ветки пакетов) в базу 1С частичной загрузкой конфигурации.

.DESCRIPTION
    Пакет берётся либо из zip-файла (-Package), либо прямо из ветки пакетов git (-Repo и -Branch).
    Скрипт распаковывает пакет, показывает info.txt, собирает список файлов и запускает конфигуратор
    в пакетном режиме: /LoadConfigFromFiles -partial и, по умолчанию, /UpdateDBCfg.

    Пароль не сохраняется. Его можно передать параметром -Password или переменной окружения
    ONEC_PASSWORD (так он не попадёт в историю команд).

    Код возврата: 0 — загружено, 1 — ошибка (подробности в выводе и в журнале конфигуратора).

.EXAMPLE
    load-package.cmd -Package C:\packages\feature\TASK-123.zip -File C:\Bases\Test -User Admin

.EXAMPLE
    load-package.cmd -Repo https://git.example.com/group/project.git -Branch feature/TASK-123 -Server srv1c -Ref test_base -User Admin
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # zip-файл пакета
    [string]$Package,
    # репозиторий с веткой пакетов (URL или путь), вместо -Package
    [string]$Repo,
    # ветка задачи: пакет <ветка>.zip
    [string]$Branch,
    # ветка пакетов
    [string]$PackagesBranch = 'packages',

    # каталог файловой базы
    [string]$File,
    # сервер 1С и имя базы на нём
    [string]$Server,
    [string]$Ref,
    [string]$User,
    [string]$Password = $env:ONEC_PASSWORD,
    # код разрешения входа при установленной блокировке сеансов (/UC)
    [string]$UC,

    # путь к 1cv8.exe; по умолчанию — самая новая платформа в Program Files\1cv8
    [string]$Platform,
    # сохранить конфигурацию в .cf перед загрузкой: файл или каталог
    [string]$BackupCf,
    # не обновлять конфигурацию базы данных (/UpdateDBCfg)
    [switch]$NoUpdateDB,
    # журнал конфигуратора; по умолчанию — во временном каталоге
    [string]$Log,
    # оставить распакованный пакет
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'

if ($PSBoundParameters.Count -eq 0) {
    Get-Help $PSCommandPath -Detailed
    exit 1
}

function Fail([string]$message) {
    Write-Host "ОШИБКА: $message" -ForegroundColor Red
    exit 1
}

function Quote([string]$s) { '"' + $s + '"' }

function Find-Platform {
    $found = foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ } | Select-Object -Unique) {
        $dir = Join-Path $root '1cv8'
        if (-not (Test-Path $dir)) { continue }
        foreach ($sub in Get-ChildItem $dir -Directory) {
            $v = $null
            $exe = Join-Path $sub.FullName 'bin\1cv8.exe'
            if ([version]::TryParse($sub.Name, [ref]$v) -and (Test-Path $exe)) {
                [pscustomobject]@{ Version = $v; Exe = $exe }
            }
        }
    }
    $found | Sort-Object Version -Descending | Select-Object -First 1 -ExpandProperty Exe
}

# git пишет предупреждения и ход работы в stderr; в Windows PowerShell при Stop это стало бы ошибкой.
function Invoke-Git {
    $ErrorActionPreference = 'Continue'
    & git @args 2>&1 | ForEach-Object { "$_" } |
        Where-Object { $_ -and $_ -notmatch 'filtering not recognized|is ignored in local clones' } |
        ForEach-Object { Write-Host $_ }
}

function Invoke-Designer([string]$action) {
    if (Test-Path $Log) { Remove-Item $Log -Force }
    $arguments = "DESIGNER $script:connection $action /Out $(Quote $Log) /DisableStartupDialogs /DisableStartupMessages"
    $p = Start-Process -FilePath $Platform -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
    if (Test-Path $Log) {
        # Журнал конфигуратора — в кодировке ANSI или UTF-8 с BOM; ReadAllText различает их по BOM.
        $text = [IO.File]::ReadAllText($Log, [Text.Encoding]::Default).Trim()
        if ($text) { Write-Host $text }
    }
    $p.ExitCode
}

# --- Параметры ---

if ($Package -and $Repo) { Fail 'Укажите либо -Package, либо -Repo и -Branch.' }
if (-not $Package -and -not $Repo) { Fail 'Укажите пакет: -Package <zip> или -Repo <URL> -Branch <ветка задачи>.' }
if ($Repo -and -not $Branch) { Fail 'Вместе с -Repo укажите -Branch — ветку задачи.' }

if ($File -and $Server) { Fail 'Укажите либо -File, либо -Server и -Ref.' }
if ($File) {
    if (-not (Test-Path (Join-Path $File '1Cv8.1CD'))) { Fail "В каталоге нет файловой базы (1Cv8.1CD): $File" }
    $connection = '/F ' + (Quote $File)
    $baseLabel = Split-Path $File.TrimEnd('\') -Leaf
} elseif ($Server) {
    if (-not $Ref) { Fail 'Вместе с -Server укажите -Ref — имя базы на сервере.' }
    $connection = '/S ' + (Quote "$Server\$Ref")
    $baseLabel = $Ref
} else {
    Fail 'Укажите базу: -File <каталог> или -Server <сервер> -Ref <имя базы>.'
}
if ($User) { $connection += ' /N ' + (Quote $User) }
if ($Password) { $connection += ' /P ' + (Quote $Password) }
if ($UC) { $connection += ' /UC ' + (Quote $UC) }

if (-not $Platform) { $Platform = Find-Platform }
if (-not $Platform -or -not (Test-Path $Platform)) { Fail "Не найден 1cv8.exe: $Platform. Укажите -Platform." }

$work = Join-Path ([IO.Path]::GetTempPath()) ('load-package-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
if (-not $Log) { $Log = Join-Path $work 'designer.log' }

try {
    # --- Пакет ---

    if ($Repo) {
        # Имя пакета — как у плагина: ветка задачи, недопустимые в именах файлов символы заменены на _.
        $packagePath = ($Branch -replace '[\\:*?"<>|]', '_') + '.zip'
        $clone = Join-Path $work 'repo'
        Write-Host "Получаю $PackagesBranch/$packagePath из $Repo"
        $env:GIT_TERMINAL_PROMPT = '0'
        # Без содержимого файлов: из ветки пакетов скачивается только нужный zip.
        Invoke-Git clone --quiet --depth 1 --single-branch --branch $PackagesBranch --no-checkout --filter=blob:none $Repo $clone
        if ($LASTEXITCODE -ne 0) { Fail "не удалось получить ветку $PackagesBranch из $Repo" }
        Invoke-Git -C $clone checkout HEAD -- $packagePath
        if ($LASTEXITCODE -ne 0) { Fail "в ветке $PackagesBranch нет пакета $packagePath" }
        $commit = & git -C $clone log -1 '--format=%h %cd %s' '--date=format:%d.%m.%Y %H:%M' -- $packagePath
        Write-Host "Пакет: $commit"
        $Package = Join-Path $clone $packagePath
    }
    if (-not (Test-Path $Package)) { Fail "Не найден пакет: $Package" }

    $unpacked = Join-Path $work 'package'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $Package).Path, $unpacked)

    $xmlDir = Join-Path $unpacked 'xml'
    $info = Join-Path $unpacked 'info.txt'
    if (Test-Path $info) {
        Write-Host ''
        Write-Host ([IO.File]::ReadAllText($info, [Text.Encoding]::UTF8).TrimEnd())
        Write-Host ''
    }
    $files = @(if (Test-Path $xmlDir) { Get-ChildItem $xmlDir -Recurse -File | ForEach-Object FullName })
    if ($files.Count -eq 0) { Fail "в пакете нет файлов xml\: $Package" }

    # Список для частичной загрузки — абсолютные пути, UTF-8.
    $listFile = Join-Path $work 'list.txt'
    [IO.File]::WriteAllLines($listFile, [string[]]$files, (New-Object Text.UTF8Encoding $true))

    Write-Host "База: $baseLabel"
    Write-Host "Платформа: $Platform"
    Write-Host "Файлов к загрузке: $($files.Count)"

    # --- Загрузка ---

    if ($BackupCf) {
        if (Test-Path $BackupCf -PathType Container) {
            $BackupCf = Join-Path $BackupCf ("backup_${baseLabel}_" + (Get-Date -Format 'yyyyMMdd_HHmmss') + '.cf')
        }
        Write-Host "Сохраняю конфигурацию: $BackupCf"
        $code = Invoke-Designer ('/DumpCfg ' + (Quote $BackupCf))
        if ($code -ne 0) { Fail "не удалось сохранить конфигурацию (код $code). Загрузка не выполнялась." }
    }

    Write-Host 'Загружаю файлы...'
    $load = '/LoadConfigFromFiles ' + (Quote $xmlDir) + ' -listFile ' + (Quote $listFile) + ' -partial'
    if (-not $NoUpdateDB) { $load += ' /UpdateDBCfg' }
    $code = Invoke-Designer $load
    if ($code -ne 0) { Fail "загрузка завершилась с кодом $code. Журнал конфигуратора: $Log" }

    if ($NoUpdateDB) {
        Write-Host 'ГОТОВО: файлы загружены. Конфигурация базы данных НЕ обновлялась.' -ForegroundColor Green
    } else {
        Write-Host 'ГОТОВО: файлы загружены, конфигурация базы данных обновлена.' -ForegroundColor Green
    }
    $ok = $true
    exit 0
} finally {
    # При ошибке временный каталог (пакет, список, журнал) остаётся для разбора.
    if ($KeepTemp -or -not $ok) {
        Write-Host "Временный каталог: $work"
    } else {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
