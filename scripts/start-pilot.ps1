#Requires -Version 7
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
docker info --format '{{.OSType}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Запустите Docker Desktop с Linux-контейнерами.' }
if (-not (Test-Path -LiteralPath '.env')) {
    $secrets = foreach ($name in 'POSTGRES_PASSWORD', 'S3_ACCESS_KEY', 'S3_SECRET_KEY', 'INTERVIEW_EVENTS_TOKEN') {
        $bytes = [byte[]]::new(36)
        [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        "$name=$([Convert]::ToHexString($bytes).ToLowerInvariant())"
    }
    [IO.File]::WriteAllLines((Join-Path (Get-Location) '.env'), $secrets, [Text.UTF8Encoding]::new($false))
}
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Сборка или запуск Compose завершились ошибкой.' }
Write-Host 'Откройте http://127.0.0.1:8080/ после загрузки сервисов.'
