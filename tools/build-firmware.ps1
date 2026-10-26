param([string]$ArduinoCli = "$env:LOCALAPPDATA\Programs\Arduino IDE\resources\app\lib\backend\resources\arduino-cli.exe")
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ArduinoCli -PathType Leaf)) {
    throw 'Arduino CLI not found; pass -ArduinoCli with its installed path.'
}
$root = Split-Path -Parent $PSScriptRoot
# Compile only. Never selects a serial port, connects to a device, or uploads.
& $ArduinoCli compile --fqbn 'esp32:esp32:esp32c3:CDCOnBoot=cdc' `
    --build-path (Join-Path $root 'build\esp32c3') `
    (Join-Path $root 'firmware\esp32_c3_piano_motor')
if ($LASTEXITCODE -ne 0) { throw 'ESP32-C3 firmware compilation failed' }
