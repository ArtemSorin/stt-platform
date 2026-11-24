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
$previousModel = $env:WHISPER_MODEL
try {
    $modelForCompose = $previousModel
    if (-not $modelForCompose -and (Test-Path -LiteralPath '.env')) {
        $line = Get-Content -LiteralPath '.env' | Where-Object { $_ -match '^\s*WHISPER_MODEL\s*=' } | Select-Object -Last 1
        if ($line) { $modelForCompose = ($line -replace '^\s*WHISPER_MODEL\s*=\s*', '').Trim(' ', '"', "'") }
    }
    if ($modelForCompose -match '^[A-Za-z]:[\\/]' -or $modelForCompose -match '^\\\\') {
        Write-Warning 'WHISPER_MODEL указывает на путь Windows; для контейнера используется модель small.'
        $env:WHISPER_MODEL = 'small'
    }
    docker compose up -d --build
    if ($LASTEXITCODE -ne 0) {
        docker compose ps
        docker compose logs --tail=80 finalization-worker analytics whisper
        throw 'Сборка или запуск Compose завершились ошибкой.'
    }
    $deadline = [DateTime]::UtcNow.AddMinutes(15)
    do {
        $workerId = docker compose ps -q finalization-worker
        $health = if ($workerId) { docker inspect --format '{{.State.Health.Status}}' $workerId } else { '' }
        try { $gatewayReady = (Invoke-WebRequest -Uri 'http://127.0.0.1:8080/' -TimeoutSec 3).StatusCode -eq 200 }
        catch { $gatewayReady = $false }
        if ($health -eq 'healthy' -and $gatewayReady) {
            Write-Host 'Стенд готов: http://127.0.0.1:8080/'
            return
        }
        Start-Sleep -Seconds 5
    } while ([DateTime]::UtcNow -lt $deadline)
    docker compose ps
    docker compose logs --tail=80 finalization-worker stt-service analytics whisper
    throw 'Сервисы не стали готовы за 15 минут. Проверьте приведённые логи.'
} finally {
    $env:WHISPER_MODEL = $previousModel
}
