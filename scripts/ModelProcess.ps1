function Set-JddaModelProcessEnvironment([Diagnostics.ProcessStartInfo]$Process, [switch]$Offline) {
    foreach ($name in @('JDDA_ADMIN_TOKEN', 'JDDA_DB_PASSWORD', 'JDDA_WORKER_TOKEN', 'PGPASSWORD', 'SPRING_DATASOURCE_PASSWORD',
            'JDDA_NACOS_USERNAME', 'JDDA_NACOS_PASSWORD')) {
        $Process.Environment.Remove($name) | Out-Null
    }
    if ($Offline) {
        foreach ($name in @($Process.Environment.Keys)) {
            if ($name -eq 'DEEPSEEK_API_KEY' -or $name -match '^JDDA_(?:[A-Z]+_)?MODEL_API_KEY$') {
                $Process.Environment.Remove($name) | Out-Null
            }
        }
        return
    }
    $baseUrl = Resolve-JddaModelBaseUrl
    $model = Get-JddaDefaultModel -BaseUrl $baseUrl
    $key = Get-JddaModelKey -BaseUrl $baseUrl -Optional
    $Process.Environment['JDDA_MODEL_BASE_URL'] = $baseUrl
    if ($model) { $Process.Environment['JDDA_MODEL'] = $model }
    if ($key) { $Process.Environment['JDDA_MODEL_API_KEY'] = $key }
}
