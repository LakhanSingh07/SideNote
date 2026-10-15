Stop-Process -Name 'sidenote-windows' -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 500

$srcExe = 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-v1.3.2-Acceptance-Kit\SideNote-Windows-v1.3.1\src-tauri\target\release\sidenote-windows.exe'
$srcSetup = 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-v1.3.2-Acceptance-Kit\SideNote-Windows-v1.3.1\src-tauri\target\release\bundle\nsis\SideNote_0.4.0_x64-setup.exe'
$srcMsi = 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-v1.3.2-Acceptance-Kit\SideNote-Windows-v1.3.1\src-tauri\target\release\bundle\msi\SideNote_0.4.0_x64_en-US.msi'

$srcDll = 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-v1.3.2-Acceptance-Kit\SideNote-Windows-v1.3.1\src-tauri\target\release\WebView2Loader.dll'

Write-Host "Copying fresh release build..."
Copy-Item -Path $srcExe -Destination 'C:\Users\Admin\AppData\Local\SideNote\sidenote-windows.exe' -Force
Copy-Item -Path $srcDll -Destination 'C:\Users\Admin\AppData\Local\SideNote\WebView2Loader.dll' -Force
Copy-Item -Path $srcDll -Destination 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\WebView2Loader.dll' -Force
Copy-Item -Path $srcExe -Destination 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\sidenote-windows.exe' -Force
Copy-Item -Path $srcSetup -Destination 'C:\Users\Admin\Desktop\SideNote-Windows-Setup.exe' -Force
Copy-Item -Path $srcSetup -Destination 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-Setup.exe' -Force
Copy-Item -Path $srcMsi -Destination 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-Setup.msi' -Force

Write-Host "Verification of deployed files:"
Get-Item 'C:\Users\Admin\AppData\Local\SideNote\sidenote-windows.exe', 'C:\Users\Admin\Desktop\SideNote-Windows-Setup.exe', 'D:\SideNote-v2.3-Antigravity-Acceptance-Package\SideNote-Windows-Setup.exe' | Select-Object FullName, LastWriteTime, Length | Format-Table -AutoSize

Write-Host "Launching updated SideNote..."
Start-Process 'C:\Users\Admin\AppData\Local\SideNote\sidenote-windows.exe'
Start-Sleep -Seconds 2
Get-Process -Name 'sidenote-windows' | Select-Object Id, ProcessName, Path
