$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
$composeOverrides = @{}
try {
    foreach ($name in 'COMPOSE_FILE', 'COMPOSE_PATH_SEPARATOR', 'DOCKER_DEFAULT_PLATFORM') {
        $entry = Get-Item -Path "Env:$name" -ErrorAction SilentlyContinue
        if ($null -ne $entry) {
            $composeOverrides[$name] = $entry.Value
            Remove-Item -Path "Env:$name"
        }
    }

    docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Установите Docker Desktop или Docker Engine с Compose V2.' }

    $engineOs = docker info --format '{{.OSType}}'
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось подключиться к Docker. Запустите Docker Desktop или Docker Engine.' }
    if (($engineOs | Out-String).Trim() -ne 'linux') { throw 'Для проекта нужен Docker с Linux-контейнерами.' }

    if (-not (Test-Path -LiteralPath '.env')) {
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try {
            $secrets = foreach ($name in 'POSTGRES_PASSWORD', 'S3_ACCESS_KEY', 'S3_SECRET_KEY', 'INTERVIEW_EVENTS_TOKEN') {
                $bytes = New-Object byte[] 36
                $random.GetBytes($bytes)
                "$name=$([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant())"
            }
        }
        finally {
            $random.Dispose()
        }

        $envPath = Join-Path $projectRoot '.env'
        $tempPath = Join-Path $projectRoot ".env.$PID.tmp"
        [IO.File]::WriteAllLines($tempPath, [string[]]$secrets, (New-Object Text.UTF8Encoding($false)))
        if (Test-Path -LiteralPath $envPath) {
            Remove-Item -LiteralPath $tempPath -Force
        }
        else {
            Move-Item -LiteralPath $tempPath -Destination $envPath
        }
    }

    docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Проверка compose.yaml завершилась ошибкой.' }

    $previousWhisperModel = [Environment]::GetEnvironmentVariable('WHISPER_MODEL', 'Process')
    $modelForCompose = $previousWhisperModel
    if (-not $modelForCompose -and (Test-Path -LiteralPath '.env')) {
        $line = Get-Content -LiteralPath '.env' | Where-Object { $_ -match '^\s*WHISPER_MODEL\s*=' } | Select-Object -Last 1
        if ($line) { $modelForCompose = ($line -replace '^\s*WHISPER_MODEL\s*=\s*', '').Trim(' ', '"', "'") }
    }
    $isWindowsDrivePath = $modelForCompose.Length -gt 2 -and $modelForCompose -match '^[A-Za-z]:' -and (
        [int][char]$modelForCompose[2] -eq 92 -or [int][char]$modelForCompose[2] -eq 47
    )
    $isUncPath = $modelForCompose.Length -ge 2 -and [int][char]$modelForCompose[0] -eq 92 -and [int][char]$modelForCompose[1] -eq 92
    if ($isWindowsDrivePath -or $isUncPath) {
        Write-Warning 'WHISPER_MODEL содержит путь Windows; в контейнере будет использована модель small.'
        $env:WHISPER_MODEL = 'small'
    }

    docker compose up -d --build
    if ($null -eq $previousWhisperModel) { Remove-Item Env:WHISPER_MODEL -ErrorAction SilentlyContinue }
    else { $env:WHISPER_MODEL = $previousWhisperModel }
    if ($LASTEXITCODE -ne 0) {
        docker compose ps
        docker compose logs --tail=100 finalization-worker analytics whisper stt-service
        throw 'Сборка или запуск Compose завершились ошибкой.'
    }

    $deadline = (Get-Date).AddMinutes(20)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        $workerId = docker compose ps -q finalization-worker
        $workerHealth = if ($workerId) { docker inspect --format '{{.State.Health.Status}}' $workerId } else { '' }
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/status' -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200 -and $workerHealth -eq 'healthy') { $ready = $true; break }
        }
        catch { }
        Start-Sleep -Seconds 5
    }

    if (-not $ready) {
        docker compose ps
        docker compose logs --tail=100 finalization-worker stt-service analytics whisper
        throw 'STT или finalization-worker не стали готовы за 20 минут. Проверьте логи выше.'
    }

    docker compose ps
    Write-Host 'Стенд готов: http://127.0.0.1:8080/'
    Write-Host 'Логи: docker compose logs -f'
}
finally {
    foreach ($name in $composeOverrides.Keys) {
        Set-Item -Path "Env:$name" -Value $composeOverrides[$name]
    }
    Pop-Location
}
