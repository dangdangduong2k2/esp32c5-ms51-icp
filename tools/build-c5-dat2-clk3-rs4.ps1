param(
    [Parameter(Mandatory=$true)][string]$WorkRoot,
    [string]$IdfPath = 'C:\idf\v6.0\esp-idf',
    [string]$IdfToolsPath = 'C:\Espressif'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
if ([IO.Path]::GetPathRoot($WorkRoot) -ne 'D:\') { throw 'Choose a work directory on D:.' }
New-Item -ItemType Directory -Force $WorkRoot, "$WorkRoot\tmp" | Out-Null
$env:TEMP = "$WorkRoot\tmp"
$env:TMP = $env:TEMP
$env:IDF_PATH = $IdfPath
$env:IDF_TOOLS_PATH = $IdfToolsPath
$env:IDF_PYTHON_ENV_PATH = "$IdfToolsPath\tools\python\v6.0\venv"
$env:IDF_PYTHON_CHECK_CONSTRAINTS = 'no'
$env:IDF_COMPONENT_CACHE_PATH = "$WorkRoot\component-cache"
$env:PATH = "$env:IDF_PYTHON_ENV_PATH\Scripts;$env:PATH"
. "$IdfPath\export.ps1"
# Seed an isolated configuration; the original DAT1/CLK0/RS3 build stays intact.
# All partitions fit below 2 MiB. A 4 MiB header also works on larger flash chips.
$seed = Get-Content -LiteralPath "$repo\sdkconfig.defaults" -Raw
$seed = $seed.Replace('CONFIG_MS51_RST_GPIO=3', 'CONFIG_MS51_RST_GPIO=4')
$seed = $seed.Replace('CONFIG_MS51_CLK_GPIO=0', 'CONFIG_MS51_CLK_GPIO=3')
$seed = $seed.Replace('CONFIG_MS51_DAT_GPIO=1', 'CONFIG_MS51_DAT_GPIO=2')
$seed = $seed.Replace('CONFIG_MS51_ANALOG_IO6=y', '# CONFIG_MS51_ANALOG_IO6 is not set')
$seed = $seed.Replace('CONFIG_MS51_NTC_50K_IO4=y', '# CONFIG_MS51_NTC_50K_IO4 is not set')
$seed += "`nCONFIG_MS51_ANALOG_IO1=y`n"
$seed = $seed.Replace('CONFIG_ESPTOOLPY_FLASHSIZE_16MB=y', 'CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y')
$seed = $seed.Replace('# Connected ESP32-C5 board has 16 MiB external flash.', '# Variant supports ESP32-C5 with at least 4 MiB flash.')
foreach ($required in @('CONFIG_MS51_RST_GPIO=4', 'CONFIG_MS51_CLK_GPIO=3', 'CONFIG_MS51_DAT_GPIO=2', 'CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y')) {
    if ($seed -notmatch ('(?m)^' + [regex]::Escape($required) + '\r?$')) { throw "Variant setting missing: $required" }
}
[IO.File]::WriteAllText("$WorkRoot\sdkconfig", $seed, [Text.UTF8Encoding]::new($false))
idf.py -C $repo -B "$WorkRoot\build" -D 'IDF_TARGET=esp32c5' -D "SDKCONFIG=$WorkRoot\sdkconfig" build
exit $LASTEXITCODE
