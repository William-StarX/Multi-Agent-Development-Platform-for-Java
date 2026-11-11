$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '首次自动配置数据库需要 Docker Desktop 或已配置的 PostgreSQL 应用账号。'
}
$engine = & docker info --format '{{.OSType}}' 2>$null
if ($LASTEXITCODE -ne 0) {
    $desktop = Join-Path $env:ProgramFiles 'Docker/Docker/Docker Desktop.exe'
    if (-not (Test-Path -LiteralPath $desktop -PathType Leaf)) { throw '请启动 Docker 的 Linux 容器引擎。' }
    Start-Process -FilePath $desktop -WindowStyle Hidden
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        Start-Sleep -Seconds 2
        $engine = & docker info --format '{{.OSType}}' 2>$null
        if ($LASTEXITCODE -eq 0) { break }
    } while ([DateTime]::UtcNow -lt $deadline)
}
if ($engine -cne 'linux') { throw 'MADPJ 自动配置数据库需要 Docker 的 Linux 容器引擎。' }

$profileName = 'jdda-root-db.credential.xml'
$databaseSettings = Read-JddaSettings $profileName
if (-not $databaseSettings) {
    $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $ownerPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $databaseSettings = @{
        Id = [Guid]::NewGuid().ToString('N').Substring(0, 12)
        App = [pscredential]::new('app', (ConvertTo-SecureString $appPassword -AsPlainText -Force))
        Owner = [pscredential]::new('postgres', (ConvertTo-SecureString $ownerPassword -AsPlainText -Force))
    }
    Write-JddaSettings $profileName $databaseSettings -CreateOnly
}
if ($databaseSettings.Id -cnotmatch '^[0-9a-f]{12}$' -or
        $databaseSettings.App.UserName -cne 'app' -or $databaseSettings.Owner.UserName -cne 'postgres') {
    throw 'MADPJ 本机数据库配置无法识别。'
}
$container = 'jdda-root-db-' + $databaseSettings.Id
$label = & docker inspect --type container --format '{{index .Config.Labels "io.jdda.root-database"}}' $container 2>$null
if ($LASTEXITCODE -ne 0) {
    $root = Split-Path -Parent $PSScriptRoot
    $image = (Get-Content -LiteralPath (Join-Path $root 'templates/java21/images.json') -Raw | ConvertFrom-Json).postgres
    $priorOwner = $env:POSTGRES_PASSWORD
    $priorApp = $env:APP_DB_PASSWORD
    try {
        $env:POSTGRES_PASSWORD = $databaseSettings.Owner.GetNetworkCredential().Password
        $env:APP_DB_PASSWORD = $databaseSettings.App.GetNetworkCredential().Password
        $create = @('create', '--platform', 'linux/amd64', '--name', $container, '--label', "io.jdda.root-database=$($databaseSettings.Id)",
            '--publish', '127.0.0.1::5432', '--volume', "$($container):/var/lib/postgresql",
            '--env', 'POSTGRES_USER=postgres', '--env', 'POSTGRES_DB=app', '--env', 'POSTGRES_PASSWORD', '--env', 'APP_DB_PASSWORD', $image)
        & docker @create | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '无法创建 MADPJ 的 PostgreSQL 容器。' }
    } finally {
        $env:POSTGRES_PASSWORD = $priorOwner
        $env:APP_DB_PASSWORD = $priorApp
    }
} elseif ($label -cne $databaseSettings.Id) {
    throw '同名数据库容器不属于当前 MADPJ 安装。'
}

$running = & docker inspect --format '{{.State.Running}}' $container
if ($LASTEXITCODE -ne 0) { throw '无法读取 MADPJ 数据库容器状态。' }
if ($running -cne 'true') {
    $template = Join-Path (Split-Path -Parent $PSScriptRoot) 'templates/java21/postgres-init.sh.in'
    & docker cp $template "$($container):/docker-entrypoint-initdb.d/10-app.sh" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法复制 PostgreSQL 初始化脚本。' }
    & docker start $container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '无法启动 MADPJ 的 PostgreSQL 容器。' }
}
$priorPgPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $databaseSettings.App.GetNetworkCredential().Password
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $probe = & docker exec -e PGPASSWORD $container psql -h 127.0.0.1 -U app -d app -Atqc 'SELECT 1' 2>$null
        if ($LASTEXITCODE -eq 0 -and $probe -ceq '1') { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($probe -cne '1') { throw "MADPJ 数据库未就绪。检查容器 $container 的启动日志。" }
} finally {
    $env:PGPASSWORD = $priorPgPassword
}
$port = & docker port $container '5432/tcp'
if ($LASTEXITCODE -ne 0 -or $port -cnotmatch '^127\.0\.0\.1:[0-9]+$') { throw 'MADPJ 数据库应使用本机回环端口。' }
[pscustomobject]@{ Url = "jdbc:postgresql://$port/app"; Credential = $databaseSettings.App }
