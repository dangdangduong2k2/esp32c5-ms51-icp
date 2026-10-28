param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$config = Get-Content -LiteralPath (Join-Path $Root 'work/local-config.json') -Raw | ConvertFrom-Json
$workDir = Join-Path $Root 'work'
if (Get-NetTCPConnection -State Listen -LocalPort 8443,8884 -ErrorAction SilentlyContinue) {
    throw 'Port 8443 or 8884 is already in use. Check the running test server before restarting.'
}
$env:PYTHONDONTWRITEBYTECODE='1'
$env:TEMP=Join-Path $workDir 'tmp'
$env:TMP=$env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
$broker = Start-Process -FilePath (Join-Path $config.broker 'mosquitto.exe') -ArgumentList @('-c',('"'+(Join-Path $workDir 'mosquitto.conf')+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workDir 'broker.log') -RedirectStandardError (Join-Path $workDir 'broker-error.log')
$api = Start-Process -FilePath $config.python -ArgumentList @(('"'+(Join-Path $PSScriptRoot 'run.py')+'"'),('"'+$Root+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $workDir 'api.log') -RedirectStandardError (Join-Path $workDir 'api-error.log')
@{broker=$broker.Id;api=$api.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $workDir 'pids.json')
Write-Output ('Admin: https://'+$config.host+':8443/ms51/connect/v1/admin/')
