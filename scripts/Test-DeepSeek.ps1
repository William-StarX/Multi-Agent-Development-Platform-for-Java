$ErrorActionPreference = 'Stop'
$apiKey = $env:DEEPSEEK_API_KEY
if (-not $apiKey) { $apiKey = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User') }
$credentialPath = Join-Path $env:LOCALAPPDATA 'JDDA/deepseek.credential.xml'
if (-not $apiKey -and (Test-Path -LiteralPath $credentialPath)) {
    $apiKey = (Import-Clixml -LiteralPath $credentialPath).GetNetworkCredential().Password
}
if (-not $apiKey) { throw 'Configure DEEPSEEK_API_KEY or the local encrypted DeepSeek credential first.' }
$model = $env:DEEPSEEK_MODEL
if (-not $model) { $model = 'deepseek-flash' }
$body = @{
    model = $model
    messages = @(@{ role = 'user'; content = 'Reply with exactly JDDA_OK and no other text.' })
    thinking = @{ type = 'disabled' }
    max_tokens = 32
    stream = $false
} | ConvertTo-Json -Depth 5

$response = Invoke-RestMethod -Method Post -Uri 'https://api.deepseek.com/chat/completions' -TimeoutSec 30 -Headers @{
    Authorization = "Bearer $apiKey"
} -ContentType 'application/json' -Body $body
$content = $response.choices[0].message.content
if (-not $content -or $content.Trim() -ne 'JDDA_OK') { throw 'Model smoke response did not match JDDA_OK.' }
[pscustomobject]@{
    Passed = $true
    Model = $response.model
    PromptTokens = $response.usage.prompt_tokens
    CompletionTokens = $response.usage.completion_tokens
}
