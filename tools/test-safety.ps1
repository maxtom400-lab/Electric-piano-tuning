param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\safety-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -d $out `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\TuningResponseSafety.java') `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\SafeMotorSession.java') `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\PianoStrikeGate.java') `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\DialGeometry.java') `
    (Join-Path $PSScriptRoot 'tests\SafeMotorSessionTest.java') `
    (Join-Path $PSScriptRoot 'tests\RatchetSessionTest.java') `
    (Join-Path $PSScriptRoot 'tests\PianoStrikeGateTest.java') `
    (Join-Path $PSScriptRoot 'tests\DialGeometryTest.java') `
    (Join-Path $PSScriptRoot 'tests\TuningResponseSafetyTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Safety test compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.TuningResponseSafetyTest
if ($LASTEXITCODE -ne 0) { throw 'Safety tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.SafeMotorSessionTest
if ($LASTEXITCODE -ne 0) { throw 'SAFE_V1 session tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.RatchetSessionTest
if ($LASTEXITCODE -ne 0) { throw 'Ratchet session tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.PianoStrikeGateTest
if ($LASTEXITCODE -ne 0) { throw 'Acoustic strike gate tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.DialGeometryTest $root
if ($LASTEXITCODE -ne 0) { throw 'Dial geometry and supplied asset tests failed' }
& node (Join-Path $PSScriptRoot 'tests\production-wiring.mjs')
if ($LASTEXITCODE -ne 0) { throw 'Production wiring source contracts failed' }
