# SideNote Windows — Native acceptance gate

## What was actually attempted here
- Source ZIP archive checked with unzip -t: PASS.
- npm install --offline attempted: BLOCKED, @tauri-apps/api not present in local npm cache.
- cargo/rustc availability: BLOCKED, Rust toolchain missing.
- Windows runtime and two-PC testing: NOT EXECUTED (non-Windows environment).

## Run in Antigravity on actual Windows
1. Unzip project. Have Node.js LTS, Rust stable MSVC, MSVC build tools, Windows SDK and WebView2 installed.
2. From project root run `powershell -ExecutionPolicy Bypass -File scripts/windows-acceptance.ps1 -BuildInstaller`.
3. Attach `acceptance-evidence/results.json`, all `*.log`, Windows package/build output, and any crash stack traces.
4. Do not claim release PASS until the two-device test below passes.

## Two-device test (must collect logs and screenshots)
- Fresh installs A and B, independently create a note; verify offline mode works without any pairing.
- Compare public-key fingerprints using an independent side channel; approve both devices; pair with same secret and connect on a private LAN.
- Test edit propagation A->B and B->A, deletion tombstones, and intentional concurrent edits preserved for conflict review.
- Generate >1,500 changes on each device; prove all are present on the other device. Record last cursor values.
- Interrupt transport mid-batch and reconnect; verify no omissions/duplicates or cursor regressions.
- Test wrong shared secret, wrong pinned public key, unapproved peer, key rotation, and unpaired peer rejection.
- Test Windows restart, firewall prompt, app minimize/tray, multiple monitors, and no unintended network connection without user opt-in.
- Record CPU and memory during 8 parallel connections and on the 9th attempted connection.

## Release blockers
- A full Windows executable has not been built/tested in this environment.
- DPAPI migration must be exercised on real Windows user profiles and after restarts.
- Pairing requires manually verified fingerprints and a shared secret; no automated safe onboarding or secret revocation yet.
- Android sync is not implemented/validated.

## Verdict
**BLOCKED — native dependencies and Windows runtime unavailable locally.** Source ZIP integrity is not proof of compile/runtime functionality.
