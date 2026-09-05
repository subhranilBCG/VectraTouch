# VectraTrackPad — Zero-Dependency Windows PowerShell PC Companion
# Receives 4-byte HID packets over TCP port 53824 (Wi-Fi / USB) and simulates mouse events.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host "         VectraTrackPad PC Companion Client (PowerShell)   " -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green

# Add Win32 mouse_event P/Invoke
$win32Type = @"
using System;
using System.Runtime.InteropServices;

public class Win32Mouse {
    [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)]
    public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cButtons, uint dwExtraInfo);

    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    public const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;
}
"@
Add-Type -TypeDefinition $win32Type -ErrorAction SilentlyContinue

$TARGET_PORT = 53824
$DISCOVERY_PORT = 53826
$phoneIp = ""

# 1. Listen for UDP Discovery Beacon (1.5 sec timeout)
Write-Host "[1/3] Scanning local network for VectraTrackPad UDP beacon..." -ForegroundColor Yellow

try {
    $udpClient = New-Object System.Net.Sockets.UdpClient($DISCOVERY_PORT)
    $udpClient.Client.ReceiveTimeout = 1500
    $remoteEp = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
    
    $bytes = $udpClient.Receive([ref]$remoteEp)
    $msg = [System.Text.Encoding]::UTF8.GetString($bytes)
    if ($msg.StartsWith("VECTRA_BEACON")) {
        $phoneIp = $remoteEp.Address.ToString()
        Write-Host " -> Auto-Discovered Phone IP: $phoneIp" -ForegroundColor Green
    }
    $udpClient.Close()
} catch {
    # UDP scan timed out or failed
}

# 2. If beacon not found, fallback to localhost / user prompt
if ([string]::IsNullOrWhiteSpace($phoneIp)) {
    Write-Host "[2/3] Beacon not heard. Defaulting to Localhost / USB (127.0.0.1)." -ForegroundColor Yellow
    $userInput = Read-Host "Enter Phone IP Address [Press ENTER for 127.0.0.1]"
    if ([string]::IsNullOrWhiteSpace($userInput)) {
        $phoneIp = "127.0.0.1"
    } else {
        $phoneIp = $userInput.Trim()
    }
}

Write-Host "[3/3] Connecting to VectraTrackPad at $phoneIp:$TARGET_PORT..." -ForegroundColor Cyan

# 3. Connect TCP socket and process mouse reports
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.NoDelay = $true
    $client.Connect($phoneIp, $TARGET_PORT)
    $stream = $client.GetStream()

    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  CONNECTED! Controlling PC Mouse via $phoneIp" -ForegroundColor Green
    Write-Host "  Press Ctrl+C to disconnect." -ForegroundColor Gray
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green

    $buffer = New-Object byte[] 4
    $prevButtons = 0

    while ($client.Connected) {
        $read = 0
        while ($read -lt 4) {
            $bytesRead = $stream.Read($buffer, $read, 4 - $read)
            if ($bytesRead -eq 0) { throw "Disconnected by host" }
            $read += $bytesRead
        }

        $buttons = [int]$buffer[0]
        
        # Convert signed sbyte (-127..127)
        $dx = [int][sbyte]$buffer[1]
        $dy = [int][sbyte]$buffer[2]
        $scroll = [int][sbyte]$buffer[3]

        # Process Mouse Movement
        if ($dx -ne 0 -or $dy -ne 0) {
            [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MOVE, [uint32]$dx, [uint32]$dy, 0, 0)
        }

        # Process Scroll Wheel
        if ($scroll -ne 0) {
            # Windows wheel delta multiplier = 120 per notch
            $wheelDelta = $scroll * 12
            [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_WHEEL, 0, 0, [uint32]$wheelDelta, 0)
        }

        # Process Button Changes
        if ($buttons -ne $prevButtons) {
            # Left Button (0x01)
            if (($buttons -band 1) -and -not ($prevButtons -band 1)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
            } elseif (-not ($buttons -band 1) -and ($prevButtons -band 1)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
            }

            # Right Button (0x02)
            if (($buttons -band 2) -and -not ($prevButtons -band 2)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
            } elseif (-not ($buttons -band 2) -and ($prevButtons -band 2)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
            }

            # Middle Button (0x04)
            if (($buttons -band 4) -and -not ($prevButtons -band 4)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
            } elseif (-not ($buttons -band 4) -and ($prevButtons -band 4)) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)
            }

            $prevButtons = $buttons
        }
    }
} catch {
    Write-Host "`nError / Disconnected: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Write-Host "`nPress any key to exit..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}
