$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("stt pilot test кириллица " + [Guid]::NewGuid().ToString('N'))
$scriptRoot = Join-Path $testRoot 'scripts'
New-Item -ItemType Directory -Path $scriptRoot -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-pilot.ps1') -Destination $scriptRoot
$scriptPath = Join-Path $scriptRoot 'start-pilot.ps1'
$originalComposeFile = [Environment]::GetEnvironmentVariable('COMPOSE_FILE', 'Process')
$originalComposeSeparator = [Environment]::GetEnvironmentVariable('COMPOSE_PATH_SEPARATOR', 'Process')
$originalDefaultPlatform = [Environment]::GetEnvironmentVariable('DOCKER_DEFAULT_PLATFORM', 'Process')
$originalWhisperModel = [Environment]::GetEnvironmentVariable('WHISPER_MODEL', 'Process')
$global:dockerCalls = New-Object System.Collections.Generic.List[string]
$global:composeWhisperModels = New-Object System.Collections.Generic.List[string]
$global:composeOverrideSeen = $false

function global:docker {
    $global:dockerCalls.Add(($args -join ' '))
    if ($env:COMPOSE_FILE -or $env:COMPOSE_PATH_SEPARATOR -or $env:DOCKER_DEFAULT_PLATFORM) { $global:composeOverrideSeen = $true }
    if ($args.Count -ge 1 -and $args[0] -eq 'info') { 'linux' }
    if ($args.Count -ge 3 -and $args[0] -eq 'compose' -and $args[1] -eq 'ps') { 'fake-container-id' }
    if ($args.Count -ge 1 -and $args[0] -eq 'inspect') { 'healthy' }
    if ($args.Count -ge 2 -and $args[0] -eq 'compose' -and $args[1] -eq 'up') {
        $global:composeWhisperModels.Add($env:WHISPER_MODEL)
    }
    $global:LASTEXITCODE = 0
}

function global:Invoke-WebRequest {
    [pscustomobject]@{ StatusCode = 200 }
}

function global:Start-Sleep { }

try {
    $env:COMPOSE_FILE = 'compose.mac-amd64.yaml'
    $env:COMPOSE_PATH_SEPARATOR = ':'
    $env:DOCKER_DEFAULT_PLATFORM = 'linux/amd64'
    $env:WHISPER_MODEL = 'C:\models\whisper'
    & $scriptPath
    $envPath = Join-Path $testRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) { throw 'PowerShell launch did not create .env.' }
    $lines = [IO.File]::ReadAllLines($envPath)
    if ($lines.Count -ne 4) { throw 'Expected four generated secrets.' }
    foreach ($name in 'POSTGRES_PASSWORD', 'S3_ACCESS_KEY', 'S3_SECRET_KEY', 'INTERVIEW_EVENTS_TOKEN') {
        if (-not ($lines | Where-Object { $_ -match "^${name}=[0-9a-f]{72}$" })) {
            throw "Generated secret $name has an invalid format."
        }
    }
    if (-not ($global:dockerCalls -contains 'compose config --quiet')) { throw 'Compose config was not validated.' }
    if (-not ($global:dockerCalls -contains 'compose up -d --build')) { throw 'Compose build/start was not called.' }
    if (-not ($global:dockerCalls -contains 'compose ps -q finalization-worker')) { throw 'Worker readiness was not checked.' }
    if (-not ($global:dockerCalls -contains 'inspect --format {{.State.Health.Status}} fake-container-id')) { throw 'Worker health was not queried.' }
    if ($global:composeWhisperModels[0] -ne 'small') { throw 'A Windows model path was passed through to the Linux container.' }
    if ($global:composeOverrideSeen) { throw 'Inherited Compose architecture overrides were not cleared.' }
    if ($env:COMPOSE_FILE -ne 'compose.mac-amd64.yaml' -or $env:DOCKER_DEFAULT_PLATFORM -ne 'linux/amd64' -or $env:WHISPER_MODEL -ne 'C:\models\whisper') {
        throw 'The launch script did not restore the calling PowerShell environment.'
    }

    $before = (Get-FileHash -LiteralPath $envPath -Algorithm SHA256).Hash
    $global:dockerCalls.Clear()
    $global:composeWhisperModels.Clear()
    $global:composeOverrideSeen = $false
    & $scriptPath
    $after = (Get-FileHash -LiteralPath $envPath -Algorithm SHA256).Hash
    if ($before -ne $after) { throw 'An existing .env file was changed.' }
    if ($global:composeOverrideSeen) { throw 'Inherited Compose architecture overrides were not cleared on restart.' }

    Remove-Item Env:WHISPER_MODEL -ErrorAction SilentlyContinue
    Add-Content -LiteralPath $envPath -Value 'WHISPER_MODEL=C:\models\whisper'
    $before = (Get-FileHash -LiteralPath $envPath -Algorithm SHA256).Hash
    $global:dockerCalls.Clear()
    $global:composeWhisperModels.Clear()
    & $scriptPath
    $after = (Get-FileHash -LiteralPath $envPath -Algorithm SHA256).Hash
    if ($global:composeWhisperModels[0] -ne 'small') { throw 'A Windows WHISPER_MODEL path from .env was passed through to the Linux container.' }
    if ($before -ne $after) { throw 'The .env file changed while resolving its Windows model path.' }
    Write-Host 'Проверка PowerShell-запуска пройдена.'
}
finally {
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item Function:\docker -ErrorAction SilentlyContinue
    Remove-Item Function:\Invoke-WebRequest -ErrorAction SilentlyContinue
    Remove-Item Function:\Start-Sleep -ErrorAction SilentlyContinue
    Remove-Variable dockerCalls -Scope Global -ErrorAction SilentlyContinue
    Remove-Variable composeWhisperModels -Scope Global -ErrorAction SilentlyContinue
    Remove-Variable composeOverrideSeen -Scope Global -ErrorAction SilentlyContinue
    foreach ($entry in @(
        @{ Name = 'COMPOSE_FILE'; Value = $originalComposeFile },
        @{ Name = 'COMPOSE_PATH_SEPARATOR'; Value = $originalComposeSeparator },
        @{ Name = 'DOCKER_DEFAULT_PLATFORM'; Value = $originalDefaultPlatform }
    )) {
        if ($null -eq $entry.Value) {
            Remove-Item -Path "Env:$($entry.Name)" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item -Path "Env:$($entry.Name)" -Value $entry.Value
        }
    }
    if ($null -eq $originalWhisperModel) { Remove-Item Env:WHISPER_MODEL -ErrorAction SilentlyContinue }
    else { Set-Item Env:WHISPER_MODEL -Value $originalWhisperModel }
}
