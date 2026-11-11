param(
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$')][string]$ProjectId = 'jdda-root',
    [string]$BaseUrl,
    [string]$Model,
    [switch]$ConfigureModel,
    [switch]$Headless
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'MADPJ Root 的自动部署入口需要 Windows 和 PowerShell 7。' }
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
$root = Split-Path -Parent $PSScriptRoot
$controlUrl = Resolve-JddaControlUrl (Get-JddaLocalControlUrl)
$base = Resolve-JddaModelBaseUrl $BaseUrl
$selectedModel = if ($Model) { $Model } else { Get-JddaDefaultModel $base }
if ($Model -and ($Model.Trim() -cne $Model -or $Model -match '[\r\n]')) { throw '请输入有效的模型 ID。' }

$prior = @{}
foreach ($name in @('JAVA_HOME', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'JDDA_DB_PASSWORD',
        'JDDA_MODEL_BASE_URL', 'JDDA_MODEL')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$mutex = [Threading.Mutex]::new($false, ('Local\JDDA.Root.' + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value))
$held = $false
try {
    try { $held = $mutex.WaitOne([TimeSpan]::FromSeconds(30)) }
    catch [Threading.AbandonedMutexException] { $held = $true }
    if (-not $held) { throw '另一项 MADPJ 本机启动仍在进行，请稍后再次调用。' }

    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) {
        $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
        if (-not $javac) { throw '请安装 JDK 21 并设置 JAVA_HOME 或 PATH。' }
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javac.Source)
    }
    $javaHome = $env:JAVA_HOME
    $agentModule = Join-Path $root 'platform/agent-poc'
    $workerReady = (Test-Path -LiteralPath (Join-Path $agentModule 'target/classes/io/jdda/poc/ModelClient.class')) -and
        (Test-Path -LiteralPath (Join-Path $agentModule 'target/dependency') -PathType Container)
    $healthy = $false
    try {
        $health = Invoke-RestMethod -Uri "$controlUrl/actuator/health" -NoProxy -MaximumRedirection 0 -TimeoutSec 3
        $healthy = $health.status -ceq 'UP'
    } catch [System.Net.Http.HttpRequestException] {
        # A stopped local service will be started below.
    } catch [System.Threading.Tasks.TaskCanceledException] {
        # A stopped local service will be started below.
    }
    if (-not $healthy) {
        if (-not (Get-JddaDatabasePassword -Optional)) {
            if (-not (Test-JddaDefaultDataSource)) { throw '请为已指定的 PostgreSQL 连接配置应用账号密码。' }
            $database = & (Join-Path $PSScriptRoot 'Start-JddaRootDatabase.ps1')
            $env:SPRING_DATASOURCE_URL = $database.Url
            $env:SPRING_DATASOURCE_USERNAME = $database.Credential.UserName
            $env:JDDA_DB_PASSWORD = $database.Credential.GetNetworkCredential().Password
        }
        $jar = Join-Path $root 'platform/control-center/target/control-center-0.1.0-SNAPSHOT.jar'
        $bootJar = $false
        if (Test-Path -LiteralPath $jar -PathType Leaf) {
            try {
                $zip = [IO.Compression.ZipFile]::OpenRead($jar)
                try { $bootJar = $null -ne $zip.GetEntry('org/springframework/boot/loader/launch/JarLauncher.class') }
                finally { $zip.Dispose() }
            } catch [IO.InvalidDataException] {
                # Rebuild a partial or invalid cached artifact.
            }
        }
        & (Join-Path $PSScriptRoot 'Deploy-Platform.ps1') -SkipBuild:($bootJar -and $workerReady)
    } elseif (-not $workerReady) {
        Invoke-JddaWithoutModelSecrets {
            & mvn -B -ntp -f (Join-Path $agentModule 'pom.xml') package | Out-Host
            if ($LASTEXITCODE -ne 0) { throw 'MADPJ Agent 构建失败。' }
        }
    }

    $token = Get-JddaAdminToken -ControlUrl $controlUrl
    $headers = @{ Authorization = "Bearer $token" }
    $options = @{ Headers = $headers; NoProxy = $true; MaximumRedirection = 0; TimeoutSec = 10 }
    $projectUrl = "$controlUrl/api/v1/projects/$([uri]::EscapeDataString($ProjectId))"
    $project = Invoke-RestMethod -Uri $projectUrl @options -SkipHttpErrorCheck -StatusCodeVariable status
    if ($status -eq 404) {
        $headers['Idempotency-Key'] = 'jdda-root-skill-' + [Guid]::NewGuid().ToString('N')
        $null = Invoke-RestMethod -Method Post -Uri "$controlUrl/api/v1/projects" @options -ContentType 'application/json' `
            -Body (@{ projectId = $ProjectId } | ConvertTo-Json -Compress) -SkipHttpErrorCheck -StatusCodeVariable created
        if ($created -notin @(201, 409)) { throw "创建 MADPJ 项目失败（HTTP $created）。" }
        $project = Invoke-RestMethod -Uri $projectUrl @options
    } elseif ($status -ne 200) {
        throw "无法通过本机 MADPJ 管理凭据读取项目（HTTP $status）。"
    }
    $agent = @($project.agents | Where-Object { $_.roles -ccontains 'ROOT' })
    if ($project.projectId -cne $ProjectId -or $agent.Count -ne 1 -or $agent[0].parentId -or $agent[0].status -cne 'ACTIVE') {
        throw 'MADPJ 项目缺少唯一的有效 Root 身份。'
    }

    $needsKey = $ConfigureModel -or $BaseUrl -or $Model -or -not $selectedModel -or
        -not (Get-JddaModelKey -BaseUrl $base -Optional)
    $modelStatus = if ($needsKey) { 'awaiting-key' } else { 'configured' }
    if (-not $Headless) {
        & (Join-Path $PSScriptRoot 'Open-PlatformConsole.ps1') -ControlUrl $controlUrl
        if ($needsKey) {
            $env:JDDA_MODEL_BASE_URL = $base
            $env:JDDA_MODEL = $selectedModel
            $setup = Join-Path $PSScriptRoot 'Initialize-JddaRootModel.ps1'
            Invoke-JddaWithoutModelSecrets {
                Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile', '-NoExit', '-File', ('"' + $setup + '"')) `
                    -WorkingDirectory $root -WindowStyle Normal
            } -Additional @('JDDA_ADMIN_TOKEN', 'JDDA_DB_PASSWORD', 'JDDA_WORKER_TOKEN', 'SPRING_DATASOURCE_PASSWORD', 'PGPASSWORD',
                'JDDA_NACOS_USERNAME', 'JDDA_NACOS_PASSWORD')
        }
    }
    [pscustomobject]@{
        ProjectId = $ProjectId
        RootAgentId = $agent[0].agentId
        Role = 'ROOT'
        ConsoleUrl = $controlUrl
        ConsoleOpened = -not $Headless
        ModelStatus = $modelStatus
        JavaHome = $javaHome
    }
} finally {
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
    foreach ($name in $prior.Keys) {
        if ($null -eq $prior[$name]) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ("Env:" + $name) -Value $prior[$name] }
    }
}
