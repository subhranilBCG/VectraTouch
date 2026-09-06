<# :
@echo off
title VectraTouch PC Companion
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([System.IO.File]::ReadAllText('%~f0'))"
pause
exit /b
#>

# VectraTouch — Zero-Dependency Single-File Windows PC Companion
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$win32Code = 'using System; using System.Runtime.InteropServices; public class Win32Mouse { [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)] public static extern void mouse_event(uint dwFlags, int dx, int dy, int dwData, uint dwExtraInfo); public const uint MOUSEEVENTF_MOVE = 0x0001; public const uint MOUSEEVENTF_LEFTDOWN = 0x0002; public const uint MOUSEEVENTF_LEFTUP = 0x0004; public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008; public const uint MOUSEEVENTF_RIGHTUP = 0x0010; public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020; public const uint MOUSEEVENTF_MIDDLEUP = 0x0040; public const uint MOUSEEVENTF_WHEEL = 0x0800; }'
Add-Type -TypeDefinition $win32Code -ErrorAction SilentlyContinue

$TARGET_PORT = 53824
$DISCOVERY_PORT = 53826

function Show-Header {
    Clear-Host
    Write-Host "═════════════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "                  VECTRATOUCH -- PC COMPANION                    " -ForegroundColor Cyan
    Write-Host "             Phone as PC Trackpad & Mouse (USB & Wi-Fi)          " -ForegroundColor DarkGray
    Write-Host "═════════════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host ""
}

function Main-Menu {
    Show-Header
    Write-Host "  SELECT CONNECTION METHOD:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "    [1] Wi-Fi Connect (Auto-Discover / LAN)" -ForegroundColor Green
    Write-Host "    [2] USB Debugging Connect (ADB Zero-Latency)" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "─────────────────────────────────────────────────────────────────" -ForegroundColor DarkGreen
    
    $choice = Read-Host "  Select option [1-2]"
    return $choice.Trim()
}

function Connect-Wifi {
    Write-Host ""
    Write-Host "[1/2] Scanning local network for VectraTouch UDP beacon..." -ForegroundColor Yellow
    $foundIp = ""
    try {
        $udpClient = New-Object System.Net.Sockets.UdpClient
        $udpClient.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::Socket, [System.Net.Sockets.SocketOptionName]::ReuseAddress, $true)
        $udpClient.Client.Bind((New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, $DISCOVERY_PORT)))
        $udpClient.Client.ReceiveTimeout = 2000
        $remoteEp = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)
        
        $bytes = $udpClient.Receive([ref]$remoteEp)
        $msg = [System.Text.Encoding]::UTF8.GetString($bytes)
        if ($msg.StartsWith("VECTRA_BEACON")) {
            $foundIp = $remoteEp.Address.ToString()
            Write-Host "  Auto-Discovered Phone IP: ${foundIp}" -ForegroundColor Green
        }
        $udpClient.Close()
    } catch {
        Write-Host "  UDP beacon scan timed out." -ForegroundColor DarkGray
    }

    if ([string]::IsNullOrWhiteSpace($foundIp)) {
        Write-Host ""
        $userInput = Read-Host "  Enter Phone IP Address (shown on phone status bar)"
        if (-not [string]::IsNullOrWhiteSpace($userInput)) {
            $foundIp = $userInput.Trim()
        }
    }
    return $foundIp
}

function Connect-Usb-Debugging {
    Write-Host ""
    Write-Host "[1/2] Setting up USB Debugging port forward via ADB..." -ForegroundColor Yellow
    
    $adbCmd = ""
    if (Get-Command "adb" -ErrorAction SilentlyContinue) {
        $adbCmd = "adb"
    } else {
        $possiblePaths = @(
            "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
            "C:\Program Files\Android\platform-tools\adb.exe",
            "C:\platform-tools\adb.exe"
        )
        foreach ($p in $possiblePaths) {
            if (Test-Path $p) {
                $adbCmd = $p
                break
            }
        }
    }

    if ($adbCmd -ne "") {
        Write-Host "  ADB executable found: $adbCmd" -ForegroundColor Green
        
        # Check connected devices
        $devOutput = & $adbCmd devices 2>&1
        $deviceLines = $devOutput | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }
        
        if ($deviceLines) {
            Write-Host "  Android USB Device detected!" -ForegroundColor Green
        } else {
            Write-Host "  Notice: No authorized ADB device found yet. Please check:" -ForegroundColor DarkYellow
            Write-Host "    - Phone is connected via USB cable" -ForegroundColor Gray
            Write-Host "    - USB Debugging is turned ON in Developer Options" -ForegroundColor Gray
            Write-Host "    - Tap 'Allow USB Debugging' on phone screen if prompted" -ForegroundColor Gray
        }

        Write-Host "  Executing: ADB port forward (ports ${TARGET_PORT} & 8080)..." -ForegroundColor DarkGray
        try {
            # Capture output into variables so it is NOT piped into function return stream
            $fwd1 = & $adbCmd forward tcp:${TARGET_PORT} tcp:${TARGET_PORT} 2>&1 | Out-String
            $fwd2 = & $adbCmd forward tcp:8080 tcp:8080 2>&1 | Out-String
            Write-Host "  Port Forward Active: tcp:${TARGET_PORT} -> tcp:${TARGET_PORT}" -ForegroundColor Green
        } catch {
            Write-Host "  ADB forward warning: $($_.Exception.Message)" -ForegroundColor Red
        }
    } else {
        Write-Host "  ADB executable not found in PATH. Defaulting to 127.0.0.1..." -ForegroundColor DarkYellow
        Write-Host "  (Ensure ADB is installed or connect via Wi-Fi mode)" -ForegroundColor DarkGray
    }
    
    return "127.0.0.1"
}

function Start-Trackpad-Session([string]$targetIp) {
    Show-Header
    Write-Host "  STATUS: Connecting to VectraTouch at ${targetIp}:${TARGET_PORT}..." -ForegroundColor Cyan
    
    $client = $null
    $stream = $null

    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.NoDelay = $true
        $client.Connect($targetIp, $TARGET_PORT)
        $stream = $client.GetStream()

        Show-Header
        Write-Host "  ACTIVE CONNECTION: ${targetIp}:${TARGET_PORT}" -ForegroundColor Green
        Write-Host "  ───────────────────────────────────────────────────────────────" -ForegroundColor DarkGreen
        Write-Host "  * Move finger on phone  --  Drives PC Mouse Cursor" -ForegroundColor Gray
        Write-Host "  * Tap 1 / 2 / 3 fingers --  Left / Right / Middle Click" -ForegroundColor Gray
        Write-Host "  * Slide 2 fingers       --  Scroll Page" -ForegroundColor Gray
        Write-Host "  ───────────────────────────────────────────────────────────────" -ForegroundColor DarkGreen
        Write-Host "  [Press Ctrl+C to disconnect and return to menu]" -ForegroundColor Yellow
        Write-Host ""

        $buffer = New-Object byte[] 4
        $prevButtons = 0

        while ($client.Connected) {
            $read = 0
            while ($read -lt 4) {
                $bytesRead = $stream.Read($buffer, $read, 4 - $read)
                if ($bytesRead -eq 0) { throw "Disconnected by phone host" }
                $read += $bytesRead
            }

            $buttons = [int]$buffer[0]
            
            # Safe two's complement conversion from byte (0..255) to signed integer (-128..127)
            $rawDx = [int]$buffer[1]
            $rawDy = [int]$buffer[2]
            $rawScroll = [int]$buffer[3]

            $dx = if ($rawDx -gt 127) { $rawDx - 256 } else { $rawDx }
            $dy = if ($rawDy -gt 127) { $rawDy - 256 } else { $rawDy }
            $scroll = if ($rawScroll -gt 127) { $rawScroll - 256 } else { $rawScroll }

            # Process Mouse Movement
            if ($dx -ne 0 -or $dy -ne 0) {
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MOVE, $dx, $dy, 0, 0)
            }

            # Process Scroll Wheel
            if ($scroll -ne 0) {
                $wheelDelta = $scroll * 12
                [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_WHEEL, 0, 0, $wheelDelta, 0)
            }

            # Process Button Changes
            if ($buttons -ne $prevButtons) {
                $leftCur = ($buttons -band 1) -ne 0
                $leftPrev = ($prevButtons -band 1) -ne 0
                if ($leftCur -and -not $leftPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                } elseif (-not $leftCur -and $leftPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
                }

                $rightCur = ($buttons -band 2) -ne 0
                $rightPrev = ($prevButtons -band 2) -ne 0
                if ($rightCur -and -not $rightPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
                } elseif (-not $rightCur -and $rightPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
                }

                $midCur = ($buttons -band 4) -ne 0
                $midPrev = ($prevButtons -band 4) -ne 0
                if ($midCur -and -not $midPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
                } elseif (-not $midCur -and $midPrev) {
                    [Win32Mouse]::mouse_event([Win32Mouse]::MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)
                }

                $prevButtons = $buttons
            }
        }
    } catch {
        Write-Host ""
        Write-Host "  Disconnected / Error: $($_.Exception.Message)" -ForegroundColor Red
    } finally {
        if ($null -ne $stream) { try { $stream.Close() } catch {} }
        if ($null -ne $client) { try { $client.Close() } catch {} }
        Write-Host ""
        Write-Host "  Press ENTER to return to menu..." -ForegroundColor DarkGray
        $null = Read-Host
    }
}

# ════════════════════════════════════════════════════════════════════════
# Main Loop
# ════════════════════════════════════════════════════════════════════════
do {
    $choice = Main-Menu

    switch ($choice) {
        "1" {
            $targetIp = Connect-Wifi
            if (-not [string]::IsNullOrWhiteSpace($targetIp)) {
                Start-Trackpad-Session -targetIp $targetIp
            }
        }
        "2" {
            $targetIp = Connect-Usb-Debugging
            Start-Trackpad-Session -targetIp $targetIp
        }
        default {
            Write-Host ""
            Write-Host "  Invalid choice. Please select 1 or 2." -ForegroundColor Red
            Start-Sleep -Seconds 1
        }
    }
} while ($true)
