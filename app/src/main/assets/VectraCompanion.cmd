<# :
@echo off
title VectraTouch PC Companion
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([System.IO.File]::ReadAllText('%~f0'))"
pause
exit /b
#>

# VectraTouch — Zero-Dependency Single-File Windows PC Companion
# Supports Mouse + Keyboard input over TCP (USB & Wi-Fi)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$win32Code = 'using System; using System.Runtime.InteropServices; public class Win32Input { [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)] public static extern void mouse_event(uint dwFlags, int dx, int dy, int dwData, uint dwExtraInfo); [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, uint dwExtraInfo); public const uint MOUSEEVENTF_MOVE = 0x0001; public const uint MOUSEEVENTF_LEFTDOWN = 0x0002; public const uint MOUSEEVENTF_LEFTUP = 0x0004; public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008; public const uint MOUSEEVENTF_RIGHTUP = 0x0010; public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020; public const uint MOUSEEVENTF_MIDDLEUP = 0x0040; public const uint MOUSEEVENTF_WHEEL = 0x0800; public const uint KEYEVENTF_KEYUP = 0x0002; public const byte VK_RETURN = 0x0D; public const byte VK_BACK = 0x08; public const byte VK_TAB = 0x09; public const byte VK_ESCAPE = 0x1B; public const byte VK_DELETE = 0x2E; public const byte VK_UP = 0x26; public const byte VK_DOWN = 0x28; public const byte VK_LEFT = 0x25; public const byte VK_RIGHT = 0x27; public const byte VK_HOME = 0x24; public const byte VK_END = 0x23; public const byte VK_SHIFT = 0x10; public const byte VK_CONTROL = 0x11; public const byte VK_MENU = 0x12; }'
Add-Type -TypeDefinition $win32Code -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue

$TARGET_PORT = 53824
$DISCOVERY_PORT = 53826

# Special key code to VK mapping
$specialKeyMap = @{
    0x01 = [Win32Input]::VK_RETURN
    0x02 = [Win32Input]::VK_BACK
    0x03 = [Win32Input]::VK_TAB
    0x04 = [Win32Input]::VK_ESCAPE
    0x05 = [Win32Input]::VK_DELETE
    0x06 = [Win32Input]::VK_UP
    0x07 = [Win32Input]::VK_DOWN
    0x08 = [Win32Input]::VK_LEFT
    0x09 = [Win32Input]::VK_RIGHT
    0x0A = [Win32Input]::VK_HOME
    0x0B = [Win32Input]::VK_END
}

function Send-SpecialKey($keyCode, $metaFlags) {
    $vk = $specialKeyMap[$keyCode]
    if ($null -eq $vk) { return }
    if ($metaFlags -band 0x01) { [Win32Input]::keybd_event([Win32Input]::VK_SHIFT, 0, 0, 0) }
    if ($metaFlags -band 0x02) { [Win32Input]::keybd_event([Win32Input]::VK_CONTROL, 0, 0, 0) }
    if ($metaFlags -band 0x04) { [Win32Input]::keybd_event([Win32Input]::VK_MENU, 0, 0, 0) }
    [Win32Input]::keybd_event($vk, 0, 0, 0)
    [Win32Input]::keybd_event($vk, 0, [Win32Input]::KEYEVENTF_KEYUP, 0)
    if ($metaFlags -band 0x04) { [Win32Input]::keybd_event([Win32Input]::VK_MENU, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
    if ($metaFlags -band 0x02) { [Win32Input]::keybd_event([Win32Input]::VK_CONTROL, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
    if ($metaFlags -band 0x01) { [Win32Input]::keybd_event([Win32Input]::VK_SHIFT, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
}

function Read-Exact($stream, $count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $bytesRead = $stream.Read($buf, $read, $count - $read)
        if ($bytesRead -eq 0) { throw "Disconnected by phone host" }
        $read += $bytesRead
    }
    return $buf
}

function Show-Header {
    Clear-Host
    Write-Host "═════════════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "                  VECTRATOUCH -- PC COMPANION                    " -ForegroundColor Cyan
    Write-Host "         Phone as PC Trackpad, Mouse & Keyboard (USB & Wi-Fi)   " -ForegroundColor DarkGray
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
        
        # Check connected devices silently
        $devOutput = [string](& $adbCmd devices 2>&1)
        if ($devOutput -match "\bdevice\b" -and $devOutput -notmatch "List of devices attach\s*$") {
            Write-Host "  Android USB Device detected!" -ForegroundColor Green
        } else {
            Write-Host "  Notice: No authorized ADB device found yet. Please check:" -ForegroundColor DarkYellow
            Write-Host "    - Phone is connected via USB cable" -ForegroundColor Gray
            Write-Host "    - USB Debugging is turned ON in Developer Options" -ForegroundColor Gray
            Write-Host "    - Tap 'Allow USB Debugging' on phone screen if prompted" -ForegroundColor Gray
        }

        Write-Host "  Executing: ADB port forward (ports ${TARGET_PORT} & 8080)..." -ForegroundColor DarkGray
        try {
            # Execute ADB forward with [void] to prevent any stdout leakage into return stream
            [void](& $adbCmd forward tcp:${TARGET_PORT} tcp:${TARGET_PORT} 2>&1)
            [void](& $adbCmd forward tcp:8080 tcp:8080 2>&1)
            Write-Host "  Port Forward Active: tcp:${TARGET_PORT} -> tcp:${TARGET_PORT}" -ForegroundColor Green
        } catch {
            Write-Host "  ADB forward notice: $($_.Exception.Message)" -ForegroundColor DarkGray
        }
    } else {
        Write-Host "  ADB executable not found in PATH. Defaulting to 127.0.0.1..." -ForegroundColor DarkYellow
        Write-Host "  (Ensure ADB is installed or connect via Wi-Fi mode)" -ForegroundColor DarkGray
    }
    
    return "127.0.0.1"
}

function Start-Trackpad-Session($rawTargetIp) {
    # Sanitize and extract strictly valid IP address or hostname
    $cleanIp = "127.0.0.1"
    if ($rawTargetIp -is [array]) {
        foreach ($item in $rawTargetIp) {
            $s = [string]$item
            if ($s -match '\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b') {
                $cleanIp = $matches[1]
                break
            }
        }
    } else {
        $s = [string]$rawTargetIp
        if ($s -match '\b(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\b') {
            $cleanIp = $matches[1]
        } elseif (-not [string]::IsNullOrWhiteSpace($s)) {
            $cleanIp = $s.Trim()
        }
    }

    Show-Header
    Write-Host "  STATUS: Connecting to VectraTouch at ${cleanIp}:${TARGET_PORT}..." -ForegroundColor Cyan
    
    $client = $null
    $stream = $null

    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $client.NoDelay = $true
        $client.Connect($cleanIp, $TARGET_PORT)
        $stream = $client.GetStream()

        Show-Header
        Write-Host "  ACTIVE CONNECTION: ${cleanIp}:${TARGET_PORT}" -ForegroundColor Green
        Write-Host "  ───────────────────────────────────────────────────────────────" -ForegroundColor DarkGreen
        Write-Host "  * Move finger on phone  --  Drives PC Mouse Cursor" -ForegroundColor Gray
        Write-Host "  * Tap 1 / 2 / 3 fingers --  Left / Right / Middle Click" -ForegroundColor Gray
        Write-Host "  * Slide 2 fingers       --  Scroll Page" -ForegroundColor Gray
        Write-Host "  * Keyboard Mode         --  Type on phone → PC keyboard" -ForegroundColor Gray
        Write-Host "  ───────────────────────────────────────────────────────────────" -ForegroundColor DarkGreen
        Write-Host "  [Press Ctrl+C to disconnect and return to menu]" -ForegroundColor Yellow
        Write-Host ""

        $prevButtons = 0

        while ($client.Connected) {
            # Read first byte to determine packet type
            $headerBuf = Read-Exact $stream 1
            $header = [int]$headerBuf[0]

            if ($header -eq 0xAA) {
                # ── Keyboard Text Packet ──
                $lenBuf = Read-Exact $stream 2
                $textLen = ([int]$lenBuf[0] -shl 8) -bor [int]$lenBuf[1]
                if ($textLen -gt 0 -and $textLen -le 65535) {
                    $textBuf = Read-Exact $stream $textLen
                    $text = [System.Text.Encoding]::UTF8.GetString($textBuf)
                    [System.Windows.Forms.SendKeys]::SendWait($text.Replace("+", "{+}").Replace("^", "{^}").Replace("%", "{%}").Replace("~", "{~}").Replace("(", "{(}").Replace(")", "{)}").Replace("{", "{{").Replace("}", "}}"))
                }
            }
            elseif ($header -eq 0xAB) {
                # ── Special Key Packet ──
                $keyBuf = Read-Exact $stream 2
                Send-SpecialKey ([int]$keyBuf[0]) ([int]$keyBuf[1])
            }
            else {
                # ── Mouse Report (4 bytes total, first byte already read) ──
                $mouseBuf = Read-Exact $stream 3
                $buttons = $header

                $rawDx = [int]$mouseBuf[0]
                $rawDy = [int]$mouseBuf[1]
                $rawScroll = [int]$mouseBuf[2]

                $dx = if ($rawDx -gt 127) { $rawDx - 256 } else { $rawDx }
                $dy = if ($rawDy -gt 127) { $rawDy - 256 } else { $rawDy }
                $scroll = if ($rawScroll -gt 127) { $rawScroll - 256 } else { $rawScroll }

                if ($dx -ne 0 -or $dy -ne 0) {
                    [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_MOVE, $dx, $dy, 0, 0)
                }

                if ($scroll -ne 0) {
                    $wheelDelta = $scroll * 12
                    [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_WHEEL, 0, 0, $wheelDelta, 0)
                }

                if ($buttons -ne $prevButtons) {
                    $leftCur = ($buttons -band 1) -ne 0
                    $leftPrev = ($prevButtons -band 1) -ne 0
                    if ($leftCur -and -not $leftPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
                    } elseif (-not $leftCur -and $leftPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
                    }

                    $rightCur = ($buttons -band 2) -ne 0
                    $rightPrev = ($prevButtons -band 2) -ne 0
                    if ($rightCur -and -not $rightPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
                    } elseif (-not $rightCur -and $rightPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)
                    }

                    $midCur = ($buttons -band 4) -ne 0
                    $midPrev = ($prevButtons -band 4) -ne 0
                    if ($midCur -and -not $midPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
                    } elseif (-not $midCur -and $midPrev) {
                        [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)
                    }

                    $prevButtons = $buttons
                }
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
            if (-not [string]::IsNullOrWhiteSpace([string]$targetIp)) {
                Start-Trackpad-Session $targetIp
            }
        }
        "2" {
            $targetIp = Connect-Usb-Debugging
            Start-Trackpad-Session $targetIp
        }
        default {
            Write-Host ""
            Write-Host "  Invalid choice. Please select 1 or 2." -ForegroundColor Red
            Start-Sleep -Seconds 1
        }
    }
} while ($true)
