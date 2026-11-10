param([switch]$SkipBuild, [switch]$OpenConsole)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw '请先设置 JAVA_HOME 指向 JDK 21。'
}
if (-not $SkipBuild -and -not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw '请先安装 Maven 并将它加入 PATH。'
}

$originalPassword = $env:JDDA_DB_PASSWORD
$password = Get-JddaDatabasePassword -Optional
$credential = $null
if (-not $password) {
    if (-not (Test-JddaDefaultDataSource)) {
        throw '自定义 PostgreSQL 连接需要在当前进程设置 JDDA_DB_PASSWORD。'
    }
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $jarPath = Join-Path $projectRoot 'platform/control-center/target/control-center-0.1.0-SNAPSHOT.jar'
    if ($SkipBuild -and (Get-JddaServiceProcess $jarPath)) {
        throw '控制服务已经在运行。请使用它的部署凭据，或去掉 -SkipBuild 以重新启动并验证数据库登录。'
    }
    $secret = Read-Host 'PostgreSQL 应用账号 jdda_local 的密码' -AsSecureString
    if (-not $secret.Length) { throw '请输入 PostgreSQL 应用账号密码。' }
    $credential = [pscredential]::new('jdda_local', $secret)
}

try {
    if ($credential) { $env:JDDA_DB_PASSWORD = $credential.GetNetworkCredential().Password }
    & (Join-Path $PSScriptRoot 'Start-Platform.ps1') -SkipBuild:$SkipBuild
    if ($credential) { Write-JddaSettings 'jdda-local.credential.xml' $credential }
} finally {
    $env:JDDA_DB_PASSWORD = $originalPassword
}

Write-Host '部署完成。首次配置模型可运行 scripts/Set-PlatformModel.ps1。'
if ($OpenConsole) { & (Join-Path $PSScriptRoot 'Open-PlatformConsole.ps1') }
