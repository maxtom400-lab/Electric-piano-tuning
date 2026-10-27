param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { $JavaHome = 'C:\Program Files\Android\Android Studio\jbr' }
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\pitch-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -d $out `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\FftPitchDetector.java') `
    (Join-Path $root 'app\src\main\java\com\example\bluetoothmotor\PcmFrameBuffer.java') `
    (Join-Path $PSScriptRoot 'tests\FftPitchDetectorTest.java') `
    (Join-Path $PSScriptRoot 'tests\PcmFrameBufferTest.java') `
    (Join-Path $PSScriptRoot 'tests\LegacyYinBaseline.java') `
    (Join-Path $PSScriptRoot 'tests\PitchBenchmark.java')
if ($LASTEXITCODE -ne 0) { throw 'Pitch tests did not compile' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.FftPitchDetectorTest
if ($LASTEXITCODE -ne 0) { throw 'Pitch tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.PcmFrameBufferTest
if ($LASTEXITCODE -ne 0) { throw 'PCM framing tests failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $out com.example.bluetoothmotor.PitchBenchmark
if ($LASTEXITCODE -ne 0) { throw 'Pitch comparison failed' }
