param(
    [Parameter(Mandatory=$true)][string]$WorkRoot,
    [string]$IdfPath='C:\idf\v6.0\esp-idf',
    [string]$IdfToolsPath='C:\Espressif'
)
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$WorkRoot=[IO.Path]::GetFullPath($WorkRoot)
if([IO.Path]::GetPathRoot($WorkRoot) -ne 'D:\'){throw 'Choose a work directory on D:.'}
New-Item -ItemType Directory -Force $WorkRoot,"$WorkRoot\tmp" | Out-Null
$env:TEMP="$WorkRoot\tmp";$env:TMP=$env:TEMP
$env:IDF_PATH=$IdfPath;$env:IDF_TOOLS_PATH=$IdfToolsPath
$env:IDF_PYTHON_ENV_PATH="$IdfToolsPath\tools\python\v6.0\venv"
$env:IDF_PYTHON_CHECK_CONSTRAINTS='no'
$env:IDF_COMPONENT_CACHE_PATH="$WorkRoot\component-cache"
$env:PATH="$env:IDF_PYTHON_ENV_PATH\Scripts;$env:PATH"
. "$IdfPath\export.ps1"
if(!(Test-Path "$WorkRoot\sdkconfig")){
    Copy-Item -LiteralPath "$repo\sdkconfig.defaults" -Destination "$WorkRoot\sdkconfig"
}elseif(!(Select-String -LiteralPath "$WorkRoot\sdkconfig" -Pattern '^CONFIG_BT_NIMBLE_ENABLED=y$' -Quiet)){
    throw 'This directory contains an older configuration. Use a new WorkRoot for BLE.'
}
# PowerShell 5 treats native stderr warnings as errors under Stop.
$ErrorActionPreference='Continue'
idf.py -C $repo -B "$WorkRoot\build" -D "SDKCONFIG=$WorkRoot\sdkconfig" build
exit $LASTEXITCODE
