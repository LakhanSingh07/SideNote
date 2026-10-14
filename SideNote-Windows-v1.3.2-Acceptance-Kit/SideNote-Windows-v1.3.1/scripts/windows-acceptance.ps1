param([switch]$BuildInstaller)
$ErrorActionPreference = 'Stop'
$cargoBin = Join-Path $HOME '.cargo\bin'
if ((Test-Path $cargoBin) -and ($env:Path -notlike "*$cargoBin*")) {
    $env:Path = "$cargoBin;$env:Path"
}
$root = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $root 'acceptance-evidence'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$results = [ordered]@{}
function Run-Check([string]$name,[scriptblock]$action) {
  Write-Host "=== $name ==="
  $log = Join-Path $evidence "$name.log"
  try {
    $prevEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $action 2>&1 | Out-File -FilePath $log -Encoding utf8
    $ErrorActionPreference = $prevEAP
    if ($LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "Exit code $LASTEXITCODE" }
    $results[$name] = 'PASS'
  } catch {
    $_ | Out-File -Append -FilePath $log -Encoding utf8
    $results[$name] = 'FAIL'
    Write-Warning "$name failed. Check $log"
  }
}
Set-Location $root
foreach($tool in @('node','npm','cargo','rustc')) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    $results["prerequisite-$tool"] = 'BLOCKED'
  } else { $results["prerequisite-$tool"] = 'PASS' }
}
if ($results['prerequisite-npm'] -eq 'PASS') {
  Run-Check 'npm-install' { npm install --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm install failed' } }
  if ($results['npm-install'] -eq 'PASS') {
    Run-Check 'frontend-build' { npm run build; if ($LASTEXITCODE -ne 0) { throw 'frontend build failed' } }
  } else { $results['frontend-build'] = 'BLOCKED' }
} else { $results['npm-install']='BLOCKED'; $results['frontend-build']='BLOCKED' }
if ($results['prerequisite-cargo'] -eq 'PASS') {
  Run-Check 'rust-check' { cargo check --manifest-path src-tauri/Cargo.toml; if ($LASTEXITCODE -ne 0) { throw 'cargo check failed' } }
  Run-Check 'rust-tests' { cargo test --manifest-path src-tauri/Cargo.toml; if ($LASTEXITCODE -ne 0) { throw 'cargo test failed' } }
} else { $results['rust-check']='BLOCKED'; $results['rust-tests']='BLOCKED' }
if ($BuildInstaller) {
  if ($results['frontend-build'] -eq 'PASS' -and $results['rust-check'] -eq 'PASS') {
    Run-Check 'tauri-build' { npm run tauri build; if ($LASTEXITCODE -ne 0) { throw 'tauri build failed' } }
  } else { $results['tauri-build']='BLOCKED' }
}
$results | ConvertTo-Json | Set-Content -Path (Join-Path $evidence 'results.json') -Encoding utf8
Write-Host ($results | ConvertTo-Json)
if (@($results.Values | Where-Object { $_ -eq 'FAIL' -or $_ -eq 'BLOCKED' }).Count -gt 0) { exit 1 }
