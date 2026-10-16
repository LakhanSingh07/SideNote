param([int]$StartX, [int]$StartY, [int]$EndY)
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class ReorderMouse {
 [StructLayout(LayoutKind.Sequential)] public struct Point { public int x,y; }
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point p);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint x,uint y,uint data,UIntPtr extra);
}
'@
[void][ReorderMouse]::SetThreadDpiAwarenessContext([IntPtr](-4))
$saved = New-Object ReorderMouse+Point
[void][ReorderMouse]::GetCursorPos([ref]$saved)
try {
 [void][ReorderMouse]::SetCursorPos($StartX,$StartY)
 Start-Sleep -Milliseconds 150
 [ReorderMouse]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
 Start-Sleep -Milliseconds 80
 for ($step=1; $step -le 24; $step++) {
   [void][ReorderMouse]::SetCursorPos($StartX,[int]($StartY+($EndY-$StartY)*$step/24))
   Start-Sleep -Milliseconds 24
 }
 Start-Sleep -Milliseconds 100
 [ReorderMouse]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
 Start-Sleep -Milliseconds 200
} finally {
 [ReorderMouse]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
 [void][ReorderMouse]::SetCursorPos($saved.x,$saved.y)
}
