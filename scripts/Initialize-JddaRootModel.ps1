param([securestring]$ApiKey)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')

$baseUrl = Resolve-JddaModelBaseUrl
$model = Get-JddaDefaultModel $baseUrl
if (-not $model) { $model = Read-Host '模型 ID' }
if ([string]::IsNullOrWhiteSpace($model) -or $model.Trim() -cne $model -or $model -match '[\r\n]') {
    throw '请输入有效的模型 ID。'
}
if (-not $ApiKey) {
    Write-Host "MADPJ 模型连接 $model（$baseUrl）"
    $ApiKey = Read-Host '模型 API Key' -AsSecureString
}
if (-not $ApiKey.Length) { throw '请输入模型 API Key。' }

$prior = @{}
foreach ($name in @('JDDA_MODEL_BASE_URL', 'JDDA_MODEL', 'JDDA_MODEL_API_KEY',
        'JDDA_ROOT_MODEL_BASE_URL', 'JDDA_ROOT_MODEL', 'JDDA_ROOT_MODEL_API_KEY')) {
    $prior[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:JDDA_MODEL_BASE_URL = $baseUrl
    $env:JDDA_MODEL = $model
    $env:JDDA_MODEL_API_KEY = [pscredential]::new('model-api', $ApiKey).GetNetworkCredential().Password
    $env:JDDA_ROOT_MODEL_BASE_URL = $null
    $env:JDDA_ROOT_MODEL = $null
    $env:JDDA_ROOT_MODEL_API_KEY = $null
    & (Join-Path $PSScriptRoot 'Test-PlatformModel.ps1') -Role ROOT | Out-Null
} finally {
    foreach ($name in $prior.Keys) {
        if ($null -eq $prior[$name]) { Remove-Item -LiteralPath ("Env:" + $name) -ErrorAction SilentlyContinue }
        else { Set-Item -LiteralPath ("Env:" + $name) -Value $prior[$name] }
    }
}

& (Join-Path $PSScriptRoot 'Set-PlatformModel.ps1') -BaseUrl $baseUrl -Model $model -ApiKey $ApiKey
Write-Host 'Root 模型连通性验证通过，可以开始使用 MADPJ。'
