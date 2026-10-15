$env:PATH = "D:\w64devkit\bin;C:\Users\Admin\.cargo\bin;" + $env:PATH
Set-Location -Path "d:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-v1.3.2-Acceptance-Kit\SideNote-Windows-v1.3.1"
npm run tauri build
