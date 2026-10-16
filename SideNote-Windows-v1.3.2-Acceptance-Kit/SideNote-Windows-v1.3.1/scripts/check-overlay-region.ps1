$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class DockProbe {
  public delegate bool EnumCallback(IntPtr hwnd, IntPtr data);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumCallback callback, IntPtr data);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder title, int length);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern int GetWindowRgn(IntPtr hwnd, IntPtr region);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
  [DllImport("gdi32.dll")] public static extern IntPtr CreateRectRgn(int l,int t,int r,int b);
  [DllImport("gdi32.dll")] public static extern bool PtInRegion(IntPtr region,int x,int y);
  [DllImport("gdi32.dll")] public static extern bool DeleteObject(IntPtr obj);
  [DllImport("user32.dll")] public static extern IntPtr WindowFromPoint(Point pt);
  [DllImport("user32.dll")] public static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
  [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point pt);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
  [StructLayout(LayoutKind.Sequential)] public struct Rect { public int left,top,right,bottom; }
  [StructLayout(LayoutKind.Sequential)] public struct Point { public int x,y; public Point(int a,int b) { x=a; y=b; } }
}
'@
[void][DockProbe]::SetThreadDpiAwarenessContext([IntPtr](-4))
Add-Type -AssemblyName System.Drawing
function Save-DockCapture([string]$name, $bounds) {
  $captureWidth = [Math]::Min(560, $bounds.right-$bounds.left)
  $captureTop = [Math]::Max($bounds.top, $bounds.top+[int]($layout.collapsed.panelBounds[1]*$layout.collapsed.scale)-20)
  $captureHeight = [Math]::Min(680, $bounds.bottom-$captureTop)
  $bitmap = New-Object System.Drawing.Bitmap($captureWidth, $captureHeight)
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  try {
    $graphics.CopyFromScreen($bounds.right-$captureWidth, $captureTop, 0, 0, $bitmap.Size)
    $bitmap.Save((Join-Path $PSScriptRoot "../acceptance-evidence/overlay/$name.png"))
  } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
$appProcesses = @(Get-Process sidenote-windows -ErrorAction Stop)
if ($appProcesses.Count -ne 1) { throw "Expected one SideNote process; found $($appProcesses.Count)" }
$script:dockHandle = [IntPtr]::Zero
$expectedProcessId = $appProcesses[0].Id
[DockProbe]::EnumWindows({ param($windowHandle, $data)
  $windowProcessId = [uint32]0
  [void][DockProbe]::GetWindowThreadProcessId($windowHandle, [ref]$windowProcessId)
  $title = [Text.StringBuilder]::new(256)
  [void][DockProbe]::GetWindowText($windowHandle, $title, 256)
  if ($windowProcessId -eq $expectedProcessId -and $title.ToString() -eq 'SideNote' -and [DockProbe]::IsWindowVisible($windowHandle)) {
    $script:dockHandle = $windowHandle
  }
  return $true
}, [IntPtr]::Zero) | Out-Null
if ($script:dockHandle -eq [IntPtr]::Zero) { throw 'Visible dock not found' }
$regionHandle = [DockProbe]::CreateRectRgn(0,0,0,0)
$savedCursor = [DockProbe+Point]::new(0,0)
[void][DockProbe]::GetCursorPos([ref]$savedCursor)
try {
  if ([DockProbe]::GetWindowRgn($script:dockHandle, $regionHandle) -gt 0) { throw 'Dock painting surface is still cropped by a native region' }
  $windowRect = [DockProbe+Rect]::new()
  [void][DockProbe]::GetWindowRect($script:dockHandle, [ref]$windowRect)
  $gapX = [int](($windowRect.right - $windowRect.left) / 2)
  foreach ($gapY in @(5, ($windowRect.bottom-$windowRect.top-5))) {
    [void][DockProbe]::SetCursorPos($windowRect.left+$gapX, $windowRect.top+$gapY)
    Start-Sleep -Milliseconds 120
    $underlyingWindow = [DockProbe]::WindowFromPoint([DockProbe+Point]::new($windowRect.left+$gapX, $windowRect.top+$gapY))
    if ([DockProbe]::GetAncestor($underlyingWindow, 2) -eq $script:dockHandle) { throw 'Blank gap still intercepts desktop input' }
  }
  $layout = Get-Content (Join-Path $PSScriptRoot '../acceptance-evidence/overlay/results.json') -Raw | ConvertFrom-Json
  $control = $layout.collapsed.controls[0]
  $controlX = [int](($control[0]+$control[2]) / 2 * $layout.collapsed.scale)
  $controlY = [int](($control[1]+$control[3]) / 2 * $layout.collapsed.scale)
  [void][DockProbe]::SetCursorPos($windowRect.left+$controlX, $windowRect.top+$controlY)
  Start-Sleep -Milliseconds 250
  $noteWindow = [DockProbe]::WindowFromPoint([DockProbe+Point]::new($windowRect.left+$controlX, $windowRect.top+$controlY))
  if ([DockProbe]::GetAncestor($noteWindow, 2) -ne $script:dockHandle) { throw 'Visible note does not accept mouse input' }
  Save-DockCapture 'native-hover' $windowRect

  # Crossing the visible popup gutter must not hand input to the application behind it.
  $bridge = $layout.opened.bridgeBounds
  $bridgeX = $windowRect.left + [int](($bridge[0]+$bridge[2])/2*$layout.opened.scale)
  $bridgeY = $windowRect.top + [int](($bridge[1]+$bridge[3])/2*$layout.opened.scale)
  [void][DockProbe]::SetCursorPos($bridgeX, $bridgeY)
  Start-Sleep -Milliseconds 250
  $bridgeWindow = [DockProbe]::WindowFromPoint([DockProbe+Point]::new($bridgeX, $bridgeY))
  if ([DockProbe]::GetAncestor($bridgeWindow, 2) -ne $script:dockHandle) { throw 'Popup gutter lost mouse input while crossing to the note' }

  # Exercise real mouse capture and return-to-dock drop without resizing the canvas.
  $grip = $layout.opened.gripBounds
  $gripX = $windowRect.left + [int](($grip[0]+$grip[2])/2*$layout.opened.scale)
  $gripY = $windowRect.top + [int](($grip[1]+$grip[3])/2*$layout.opened.scale)
  [void][DockProbe]::SetCursorPos($gripX, $gripY)
  Start-Sleep -Milliseconds 100
  [DockProbe]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
  Start-Sleep -Milliseconds 200
  [void][DockProbe]::SetCursorPos($gripX-100, $gripY)
  Start-Sleep -Milliseconds 180
  $dragRect = [DockProbe+Rect]::new()
  [void][DockProbe]::GetWindowRect($script:dockHandle, [ref]$dragRect)
  if (($dragRect.right-$dragRect.left) -ne ($windowRect.right-$windowRect.left) -or $dragRect.top -ne $windowRect.top -or $dragRect.left -ne $windowRect.left) { throw 'Real drag moved or resized the sidebar canvas' }
  Save-DockCapture 'native-drag' $windowRect
  [void][DockProbe]::SetCursorPos($gripX, $gripY)
  Start-Sleep -Milliseconds 80
  [DockProbe]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
  Start-Sleep -Milliseconds 500
  $restoredRect = [DockProbe+Rect]::new()
  [void][DockProbe]::GetWindowRect($script:dockHandle, [ref]$restoredRect)
  if (($restoredRect.right-$restoredRect.left) -ne ($windowRect.right-$windowRect.left)) { throw 'Real drag did not restore the dock canvas' }
  [ordered]@{status='PASS';singleProcess=$true;paintingSurfaceUncropped=$true;blankAreaReachesUnderlyingWindow=$true;visibleNoteAcceptsInput=$true;popupGutterAcceptsCrossing=$true;realMouseDragKeepsCanvasFixed=$true} | ConvertTo-Json
} finally {
  [DockProbe]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
  [void][DockProbe]::SetCursorPos($savedCursor.x, $savedCursor.y)
  [void][DockProbe]::DeleteObject($regionHandle)
}
