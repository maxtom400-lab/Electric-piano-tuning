param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$JavaHome = 'C:\Program Files\Android\Android Studio\jbr',
    [string]$SdkRoot = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildToolsVersion = '34.0.0'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Provide an explicit USB serial, not a network device' }
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\pitch-device'
$classes = Join-Path $out 'classes'
$dex = Join-Path $out 'dex'
New-Item -ItemType Directory -Force -Path $classes, $dex | Out-Null
$adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
& $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0) { throw 'Specified USB phone is not available/authorized' }
& (Join-Path $JavaHome 'bin\javac.exe') --release 8 -d $classes `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\FftPitchDetector.java') `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\PcmFrameBuffer.java') `
    (Join-Path $PSScriptRoot 'tests\FftPitchDetectorTest.java') `
    (Join-Path $PSScriptRoot 'tests\PcmFrameBufferTest.java') `
    (Join-Path $PSScriptRoot 'tests\LegacyYinBaseline.java') `
    (Join-Path $PSScriptRoot 'tests\DevicePitchBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'Device diagnostic compilation failed' }
$inputJar = Join-Path $out 'classes.jar'
$deviceJar = Join-Path $out 'pitch-diagnostic.jar'
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $inputJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Class archive failed' }
$env:JAVA_HOME = $JavaHome
& (Join-Path $SdkRoot "build-tools\$BuildToolsVersion\d8.bat") --min-api 23 `
    --lib (Join-Path $SdkRoot 'platforms\android-35\android.jar') --output $dex $inputJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
& (Join-Path $JavaHome 'bin\jar.exe') --create --file $deviceJar -C $dex classes.dex
if ($LASTEXITCODE -ne 0) { throw 'DEX archive failed' }
# Unique generated diagnostic only; no APK install, UI, recording, BLE or root commands.
$remote = '/data/local/tmp/piano-pitch-' + [guid]::NewGuid().ToString('N') + '.jar'
try {
    & $adb -s $Serial push $deviceJar $remote
    if ($LASTEXITCODE -ne 0) { throw 'Diagnostic transfer failed' }
    & $adb -s $Serial shell chmod 444 $remote
    if ($LASTEXITCODE -ne 0) { throw 'Could not make generated DEX read-only' }
    & $adb -s $Serial shell "CLASSPATH=$remote" app_process /system/bin com.example.bluetoothmotor.DevicePitchBenchmark
    if ($LASTEXITCODE -ne 0) { throw 'Device synthetic core diagnostic failed' }
} finally {
    # Remove only the exact temporary file created by this invocation, never recursively.
    & $adb -s $Serial shell rm $remote
}
