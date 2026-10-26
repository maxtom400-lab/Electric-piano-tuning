param([string]$LlvmBin = "$env:LOCALAPPDATA\Android\Sdk\ndk\26.1.10909125\toolchains\llvm\prebuilt\windows-x86_64\bin")
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\safety-tests'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& (Join-Path $LlvmBin 'clang++.exe') --target=wasm32 -ffreestanding -fno-exceptions -fno-rtti -std=c++11 -Wall -Wextra -Werror -O2 -c `
    (Join-Path $PSScriptRoot 'tests\motor_safety_core_test.cpp') -o (Join-Path $out 'motor-core.o')
if ($LASTEXITCODE -ne 0) { throw 'Motor safety core compilation failed' }
& (Join-Path $LlvmBin 'ld.lld.exe') -flavor wasm --no-entry --export=run_safety_tests --export=safety_checks `
    (Join-Path $out 'motor-core.o') -o (Join-Path $out 'motor-core.wasm')
if ($LASTEXITCODE -ne 0) { throw 'Motor safety core link failed' }
& node (Join-Path $PSScriptRoot 'tests\run-motor-safety.mjs') (Join-Path $out 'motor-core.wasm')
if ($LASTEXITCODE -ne 0) { throw 'Motor safety core tests failed' }
& (Join-Path $LlvmBin 'clang++.exe') --target=wasm32 -ffreestanding -fno-exceptions -fno-rtti -std=c++11 -Wall -Wextra -Werror -O2 -c `
    (Join-Path $PSScriptRoot 'tests\motor_protocol_test.cpp') -o (Join-Path $out 'motor-protocol.o')
if ($LASTEXITCODE -ne 0) { throw 'Motor protocol compilation failed' }
& (Join-Path $LlvmBin 'ld.lld.exe') -flavor wasm --no-entry --export=run_protocol_tests --export=protocol_checks `
    (Join-Path $out 'motor-protocol.o') -o (Join-Path $out 'motor-protocol.wasm')
if ($LASTEXITCODE -ne 0) { throw 'Motor protocol link failed' }
& node (Join-Path $PSScriptRoot 'tests\run-motor-safety.mjs') (Join-Path $out 'motor-protocol.wasm') protocol
if ($LASTEXITCODE -ne 0) { throw 'Motor protocol tests failed' }
