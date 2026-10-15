$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'C:\Users\Admin\AppData\Local\SideNote\sidenote-windows.exe'
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$proc = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEndAsync()
$stderr = $proc.StandardError.ReadToEndAsync()
$proc.WaitForExit(5000)
Write-Output "ExitCode: $($proc.ExitCode)"
Write-Output "STDOUT:"
Write-Output $stdout.Result
Write-Output "STDERR:"
Write-Output $stderr.Result
