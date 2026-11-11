function Get-JddaSettingsPath([string]$Name) {
    if (-not $env:LOCALAPPDATA) { throw '本机凭据存储需要 Windows LOCALAPPDATA。' }
    Join-Path (Join-Path $env:LOCALAPPDATA 'JDDA') $Name
}

function Read-JddaSettings([string]$Name) {
    if (-not $IsWindows) { return }
    $path = Get-JddaSettingsPath $Name
    if (Test-Path -LiteralPath $path) { Import-Clixml -LiteralPath $path }
}

function Write-JddaSettings([string]$Name, $Value, [switch]$CreateOnly) {
    if (-not $IsWindows) { throw '本机加密凭据存储仅支持 Windows。请通过进程环境注入凭据。' }
    $path = Get-JddaSettingsPath $Name
    $directory = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    $temporary = Join-Path $directory ('.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $Value | Export-Clixml -LiteralPath $temporary
        [IO.File]::Move($temporary, $path, -not $CreateOnly)
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
    }
}

function Confirm-JddaUrl([string]$Value, [switch]$AllowPath) {
    $uri = $null
    if ($Value -match '[\s\\?#]' -or -not [uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('http', 'https') -or -not $uri.Host -or $uri.UserInfo -or
        ($uri.Scheme -eq 'http' -and -not $uri.IsLoopback) -or
        (-not $AllowPath -and $uri.AbsolutePath -ne '/') -or
        ($AllowPath -and (($uri.Scheme -eq 'http' -and $uri.Host -notin @('localhost', '127.0.0.1', '[::1]')) -or
            $uri.AbsolutePath.TrimEnd('/') -match '/chat/completions$'))) {
        throw '地址必须使用 HTTPS。本机回环可使用 HTTP。控制地址填写站点根地址，模型地址填写 API 基础路径。'
    }
    $uri.GetLeftPart([UriPartial]::Path).TrimEnd('/')
}

function Get-JddaControlPort {
    $value = if ($env:SERVER_PORT) { $env:SERVER_PORT } elseif ($env:JDDA_PORT) { $env:JDDA_PORT } else { '18080' }
    $port = 0
    if (-not [int]::TryParse($value, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
        throw 'MADPJ 端口必须是 1 到 65535 之间的整数。'
    }
    $port
}

function Resolve-JddaControlUrl([string]$Override) {
    $address = if ($Override) { $Override } elseif ($env:JDDA_CONTROL_URL) { $env:JDDA_CONTROL_URL }
        else { "http://127.0.0.1:$(Get-JddaControlPort)" }
    Confirm-JddaUrl $address
}

function Get-JddaLocalControlUrl {
    $address = if ($env:SERVER_ADDRESS) { $env:SERVER_ADDRESS } elseif ($env:JDDA_BIND_ADDRESS) { $env:JDDA_BIND_ADDRESS } else { '127.0.0.1' }
    $ip = $null
    if (-not [Net.IPAddress]::TryParse($address, [ref]$ip)) { throw 'MADPJ 绑定地址必须是本机 IP 地址。' }
    if ($ip.Equals([Net.IPAddress]::Any)) { $ip = [Net.IPAddress]::Loopback }
    if ($ip.Equals([Net.IPAddress]::IPv6Any)) { $ip = [Net.IPAddress]::IPv6Loopback }
    ([UriBuilder]::new('http', $ip.ToString(), (Get-JddaControlPort))).Uri.AbsoluteUri.TrimEnd('/')
}

function Get-JddaAdminToken([string]$ControlUrl, [switch]$Create) {
    $endpoint = if ($ControlUrl) { [uri](Resolve-JddaControlUrl $ControlUrl) }
    if ($env:JDDA_ADMIN_TOKEN) { return $env:JDDA_ADMIN_TOKEN }
    if ($endpoint -and (-not $endpoint.IsLoopback -or $endpoint.Port -ne (Get-JddaControlPort))) {
        throw '连接其他控制服务实例需要在当前进程设置该实例的 JDDA_ADMIN_TOKEN。'
    }
    $credential = Read-JddaSettings 'jdda-admin.credential.xml'
    if (-not $credential -and $Create) {
        $token = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $credential = [pscredential]::new('local-admin', (ConvertTo-SecureString $token -AsPlainText -Force))
        try {
            Write-JddaSettings 'jdda-admin.credential.xml' $credential -CreateOnly
        } catch [IO.IOException] {
            $credential = Read-JddaSettings 'jdda-admin.credential.xml'
            if (-not $credential) { throw }
        }
    }
    if (-not $credential) { throw '请先部署控制服务，或设置 JDDA_ADMIN_TOKEN。' }
    $credential.GetNetworkCredential().Password
}

function Test-JddaDefaultDataSource {
    (-not $env:SPRING_DATASOURCE_URL -or $env:SPRING_DATASOURCE_URL -ceq 'jdbc:postgresql://127.0.0.1:5432/jdda_local') -and
        (-not $env:SPRING_DATASOURCE_USERNAME -or $env:SPRING_DATASOURCE_USERNAME -ceq 'jdda_local')
}

function Get-JddaDatabasePassword([switch]$Optional) {
    if ($env:SPRING_DATASOURCE_PASSWORD) { return $env:SPRING_DATASOURCE_PASSWORD }
    if ($env:JDDA_DB_PASSWORD) { return $env:JDDA_DB_PASSWORD }
    if (Test-JddaDefaultDataSource) {
        $credential = Read-JddaSettings 'jdda-local.credential.xml'
        if ($credential) { return $credential.GetNetworkCredential().Password }
    }
    if (-not $Optional) { throw '请先配置 PostgreSQL 应用账号的 JDDA_DB_PASSWORD，再部署 MADPJ。' }
}

function Resolve-JddaModelBaseUrl([string]$Override) {
    $address = if ($Override) { $Override } elseif ($env:JDDA_MODEL_BASE_URL) { $env:JDDA_MODEL_BASE_URL }
        else { (Read-JddaSettings 'model.credential.xml').BaseUrl }
    if (-not $address) { $address = 'https://api.deepseek.com' }
    Confirm-JddaUrl $address -AllowPath
}

function Test-JddaDeepSeekBase([string]$BaseUrl) {
    $BaseUrl -cin @('https://api.deepseek.com', 'https://api.deepseek.com/v1')
}

function Get-JddaDefaultModel([string]$BaseUrl) {
    $BaseUrl = Resolve-JddaModelBaseUrl $BaseUrl
    if ($env:JDDA_MODEL) { return $env:JDDA_MODEL }
    $settings = Read-JddaSettings 'model.credential.xml'
    if ($settings.BaseUrl -ceq $BaseUrl -and $settings.Model) { return $settings.Model }
    if (Test-JddaDeepSeekBase $BaseUrl) {
        if ($env:DEEPSEEK_MODEL) { return $env:DEEPSEEK_MODEL }
        return 'deepseek-flash'
    }
}

function Get-JddaModelKey([string]$BaseUrl, [switch]$Optional) {
    $BaseUrl = Resolve-JddaModelBaseUrl $BaseUrl
    if ($env:JDDA_MODEL_API_KEY) { return $env:JDDA_MODEL_API_KEY }
    $settings = Read-JddaSettings 'model.credential.xml'
    if ($settings.BaseUrl -ceq $BaseUrl -and $settings.ApiCredential) {
        return $settings.ApiCredential.GetNetworkCredential().Password
    }
    if (Test-JddaDeepSeekBase $BaseUrl) {
        $key = $env:DEEPSEEK_API_KEY
        if (-not $key -and $IsWindows) { $key = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User') }
        if ($key) { return $key }
        $legacy = Read-JddaSettings 'deepseek.credential.xml'
        if ($legacy) { return $legacy.GetNetworkCredential().Password }
    }
    if (-not $Optional) { throw '请运行 scripts/Set-PlatformModel.ps1，或设置 JDDA_MODEL_API_KEY。' }
}

function Invoke-JddaWithoutModelSecrets([scriptblock]$Action, [string[]]$Additional) {
    $secrets = Get-ChildItem Env: | Where-Object {
        $_.Name -match '^(?:DEEPSEEK_API_KEY|JDDA_(?:[A-Z]+_)?MODEL_API_KEY)$' -or $_.Name -in $Additional
    }
    try {
        foreach ($secret in $secrets) { Remove-Item -LiteralPath ("Env:" + $secret.Name) }
        & $Action
    } finally {
        foreach ($secret in $secrets) { Set-Item -LiteralPath ("Env:" + $secret.Name) -Value $secret.Value }
    }
}

function Get-JddaServiceProcess([string]$JarPath) {
    $owners = Get-NetTCPConnection -LocalPort (Get-JddaControlPort) -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($owner in $owners) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$owner"
        if ($process.Name -ne 'java.exe' -or -not $process.CommandLine -or
            $process.CommandLine.Replace('/', '\').IndexOf($JarPath.Replace('/', '\'), [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "MADPJ 端口 $(Get-JddaControlPort) 已由其他进程使用。"
        }
        $process
    }
}
