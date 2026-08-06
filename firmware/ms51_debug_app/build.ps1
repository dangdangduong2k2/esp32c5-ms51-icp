[CmdletBinding()]
param(
    [string]$BspRoot,
    [string]$SdccBin
)

$ErrorActionPreference = 'Stop'

function Test-IntelHex {
    param([string]$Path)

    $memory = @{}
    $upperAddress = 0
    $recordCount = 0
    $dataRecordCount = 0
    $eofFound = $false

    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -notmatch '^:([0-9A-Fa-f]{2})([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})([0-9A-Fa-f]*)([0-9A-Fa-f]{2})$') {
            throw "Invalid Intel HEX record: $line"
        }

        $byteCount = [Convert]::ToInt32($Matches[1], 16)
        $offset = [Convert]::ToInt32($Matches[2], 16)
        $recordType = [Convert]::ToInt32($Matches[3], 16)
        $payload = $Matches[4]

        if ($payload.Length -ne (2 * $byteCount)) {
            throw "Intel HEX length mismatch: $line"
        }

        $checksum = 0
        for ($index = 1; $index -lt $line.Length; $index += 2) {
            $checksum += [Convert]::ToInt32($line.Substring($index, 2), 16)
        }
        if (($checksum -band 0xFF) -ne 0) {
            throw "Intel HEX checksum mismatch: $line"
        }

        $data = @()
        for ($index = 0; $index -lt $byteCount; ++$index) {
            $data += [Convert]::ToInt32($payload.Substring(2 * $index, 2), 16)
        }

        switch ($recordType) {
            0 {
                for ($index = 0; $index -lt $data.Count; ++$index) {
                    $memory[$upperAddress + $offset + $index] = $data[$index]
                }
                ++$dataRecordCount
            }
            1 {
                if ($byteCount -ne 0) {
                    throw 'Invalid Intel HEX EOF record.'
                }
                $eofFound = $true
            }
            4 {
                if ($byteCount -ne 2) {
                    throw 'Invalid Intel HEX extended-address record.'
                }
                $upperAddress = ((($data[0] -shl 8) + $data[1]) -shl 16)
            }
        }
        ++$recordCount
    }

    if (-not $eofFound) {
        throw 'Intel HEX EOF record is missing.'
    }
    if (-not $memory.ContainsKey(0) -or -not $memory.ContainsKey(0x4E)) {
        throw 'Intel HEX is missing expected startup addresses.'
    }
    if ($memory[0] -ne 0x02 -or $memory[1] -ne 0x00 -or $memory[2] -ne 0x4E) {
        throw 'Unexpected reset vector in Intel HEX.'
    }
    if ($memory[0x4E] -ne 0x75 -or $memory[0x4F] -ne 0x81 -or $memory[0x50] -ne 0x70) {
        throw 'Safe stack setup (MOV SP,#0x70) is missing from Intel HEX.'
    }

    $addresses = @($memory.Keys | Sort-Object)
    if ($addresses[-1] -ge 0x8000) {
        throw 'Intel HEX exceeds the 32 KiB MS51FC0AE APROM range.'
    }

    return [PSCustomObject]@{
        Records = $recordCount
        DataRecords = $dataRecordCount
        DataBytes = $addresses.Count
        AddressRange = ('0x{0:X4}..0x{1:X4}' -f $addresses[0], $addresses[-1])
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$bspFolderName = 'MS51FC0AE_MS51XC0BE_MS51EB0AE_MS51EC0AE_MS51TC0AE_MS51PC0AE'

if ([string]::IsNullOrWhiteSpace($BspRoot)) {
    $BspRoot = Join-Path $repoRoot (Join-Path 'third_party\MS51_BSP' $bspFolderName)
}
$BspRoot = (Resolve-Path $BspRoot -ErrorAction Stop).Path

if ([string]::IsNullOrWhiteSpace($SdccBin)) {
    $scoopBin = Join-Path $env:USERPROFILE 'scoop\apps\sdcc\current\bin'
    if (Test-Path (Join-Path $scoopBin 'sdcc.exe')) {
        $SdccBin = $scoopBin
    } else {
        $SdccBin = Split-Path -Parent (Get-Command sdcc.exe -ErrorAction Stop).Source
    }
}
$SdccBin = (Resolve-Path $SdccBin -ErrorAction Stop).Path

$sdcc = Join-Path $SdccBin 'sdcc.exe'
$assembler = Join-Path $SdccBin 'sdas8051.exe'
$packihx = Join-Path $SdccBin 'packihx.exe'
foreach ($tool in @($sdcc, $assembler, $packihx)) {
    if (-not (Test-Path $tool)) {
        throw "Required SDCC tool is missing: $tool"
    }
}

$env:PATH = "$SdccBin;$env:PATH"

$buildDir = Join-Path $PSScriptRoot 'build'
$outDir = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force -Path $buildDir, $outDir | Out-Null

$commonArgs = @(
    '-mmcs51',
    '--model-small',
    '--std-sdcc11',
    '--opt-code-size',
    '--code-size', '0x8000',
    '--iram-size', '0x100',
    '-D__SDCC__',
    "-I$BspRoot\Library\Device\Include",
    "-I$BspRoot\Library\StdDriver\inc",
    "-I$repoRoot\firmware",
    "-I$PSScriptRoot\src"
)

$sources = @(
    [PSCustomObject]@{ Name = 'main'; Path = (Join-Path $PSScriptRoot 'src\main.c') },
    [PSCustomObject]@{ Name = 'board_uart1'; Path = (Join-Path $PSScriptRoot 'src\board_uart1.c') },
    [PSCustomObject]@{ Name = 'ms51_debug_telemetry'; Path = (Join-Path $repoRoot 'firmware\ms51_debug_telemetry.c') },
    [PSCustomObject]@{ Name = 'sys'; Path = (Join-Path $BspRoot 'Library\StdDriver\src\sys.c') },
    [PSCustomObject]@{ Name = 'ms51_bsp_bit_tmp'; Path = (Join-Path $PSScriptRoot 'src\ms51_bsp_bit_tmp.c') }
)

foreach ($source in $sources) {
    $object = Join-Path $buildDir ($source.Name + '.rel')
    & $sdcc @commonArgs '-c' '-o' $object $source.Path
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$startupObject = Join-Path $buildDir 'crtstart_fixed_sp.rel'
& $assembler '-plosgff' '-o' $startupObject (Join-Path $PSScriptRoot 'src\crtstart_fixed_sp.asm')
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$ihx = Join-Path $buildDir 'ms51_debug_demo.ihx'
# Keep the custom CRT last: it resolves __sdcc_gsinit_startup before SDCC scans
# its library, so the default CRT (with an unsafe low stack pointer) is omitted.
$objects = @($sources | ForEach-Object { Join-Path $buildDir ($_.Name + '.rel') }) + @($startupObject)
& $sdcc @commonArgs '--out-fmt-ihx' '-o' $ihx @objects
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$hex = Join-Path $outDir 'ms51_debug_demo.hex'
$hexLines = @(& $packihx $ihx)
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
[System.IO.File]::WriteAllLines($hex, [string[]]$hexLines, [System.Text.Encoding]::ASCII)

$summary = Test-IntelHex $hex
Write-Host "Built and validated $hex"
Write-Host ("{0} records, {1} data bytes, {2}" -f $summary.Records, $summary.DataBytes, $summary.AddressRange)
