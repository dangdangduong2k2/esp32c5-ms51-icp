param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$pidFile=Join-Path $Root 'work/pids.json'
if (!(Test-Path -LiteralPath $pidFile)) { exit 0 }
$ids=Get-Content -LiteralPath $pidFile -Raw | ConvertFrom-Json
foreach ($processId in @($ids.broker,$ids.api)) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if ($process -and $process.CommandLine -and $process.CommandLine.Contains($Root)) {
        Stop-Process -Id $processId
    }
}
