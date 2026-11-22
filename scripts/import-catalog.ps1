param(
    [Parameter(Mandatory=$true)][string]$Questions,
    [Parameter(Mandatory=$true)][string]$Followups,
    [string]$AnalyticsUrl = 'http://127.0.0.1:8083'
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:INTERVIEW_EVENTS_TOKEN) -or $env:INTERVIEW_EVENTS_TOKEN.Length -lt 32) {
    throw 'Set INTERVIEW_EVENTS_TOKEN to the same internal secret as analytics-service (at least 32 characters).'
}
$requestBody = @{
    questions = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Questions), [System.Text.Encoding]::UTF8)
    followups = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Followups), [System.Text.Encoding]::UTF8)
} | ConvertTo-Json -Compress
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($requestBody)
Invoke-RestMethod -Method Post -Uri "$($AnalyticsUrl.TrimEnd('/'))/internal/archive/catalog" `
    -Headers @{ 'X-Internal-Token' = $env:INTERVIEW_EVENTS_TOKEN } -ContentType 'application/json; charset=utf-8' -Body $bodyBytes
