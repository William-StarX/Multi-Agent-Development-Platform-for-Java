param([ValidateSet('ROOT','MIDDLE','SCOPE','WORKER','BUGFIX')][string]$Role = 'WORKER')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'PlatformSettings.ps1')
. (Join-Path $PSScriptRoot 'ModelProcess.ps1')
$module = Join-Path (Split-Path -Parent $PSScriptRoot) 'platform/agent-poc'
$classes = Join-Path $module 'target/classes'
$libraries = Join-Path $module 'target/dependency'
if (-not (Test-Path -LiteralPath $libraries -PathType Container)) {
    throw 'Deploy the platform before testing the model connection.'
}
$info = [Diagnostics.ProcessStartInfo]::new((Join-Path $env:JAVA_HOME 'bin/java.exe'))
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
foreach ($argument in @('-cp', "$classes;$libraries/*", 'io.jdda.poc.ModelClient', $Role)) { $info.ArgumentList.Add($argument) }
Set-JddaModelProcessEnvironment $info
$process = [Diagnostics.Process]::Start($info)
$output = $process.StandardOutput.ReadToEndAsync()
try {
    if (-not $process.WaitForExit(45000)) { throw 'The model connection test timed out.' }
    if ($process.ExitCode -ne 0) { throw 'The model connection test failed.' }
    $expected = "JDDA_OK $Role"
    if (($output.GetAwaiter().GetResult() -split '\r?\n') -cnotcontains $expected) {
        throw 'The model connection test did not confirm the expected status.'
    }
    Write-Output $expected
} finally {
    if (-not $process.HasExited) { $process.Kill($true) }
    $process.Dispose()
}
