param(
    [string]$BaseUrl,
    [string]$Model,
    [securestring]$ApiKey
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')

$BaseUrl = Resolve-JddaModelBaseUrl $BaseUrl
if (-not $Model) { $Model = Get-JddaDefaultModel $BaseUrl }
if ([string]::IsNullOrWhiteSpace($Model) -or $Model.Trim() -cne $Model -or $Model -match '[\r\n]') {
    throw '请使用 -Model 填写模型 ID。'
}
if (-not $ApiKey) { $ApiKey = Read-Host '模型 API Key' -AsSecureString }
if (-not $ApiKey.Length) { throw '请输入有效的 API Key。' }
$settings = @{
    BaseUrl = $BaseUrl
    Model = $Model
    ApiCredential = [pscredential]::new('model-api', $ApiKey)
}
Write-JddaSettings 'model.credential.xml' $settings
Write-Host "已保存模型配置 $Model（$BaseUrl）。"
