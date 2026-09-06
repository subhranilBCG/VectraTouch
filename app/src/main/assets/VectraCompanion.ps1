# VectraTouch — Zero-Dependency Windows PowerShell PC Companion
# Receives 4-byte HID packets AND keyboard input over TCP port 53824 (Wi-Fi / USB).
# Simulates mouse events and keyboard input on the PC.

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
Write-Host "            VectraTouch PC Companion (PowerShell)          " -ForegroundColor Cyan
Write-Host "      Phone as PC Trackpad, Mouse & Keyboard (USB & Wi-Fi)" -ForegroundColor DarkGray
Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green

# Add Win32 mouse_event and keybd_event P/Invoke
$win32Type = @"
using System;
using System.Runtime.InteropServices;

public class Win32Input {
    [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)]
    public static extern void mouse_event(uint dwFlags, int dx, int dy, int dwData, uint dwExtraInfo);

    [DllImport("user32.dll", CharSet = CharSet.Auto, CallingConvention = CallingConvention.StdCall)]
    public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, uint dwExtraInfo);

    public const uint MOUSEEVENTF_MOVE = 0x0001;
    public const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    public const uint MOUSEEVENTF_LEFTUP = 0x0004;
    public const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    public const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    public const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    public const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    public const uint MOUSEEVENTF_WHEEL = 0x0800;

    public const uint KEYEVENTF_KEYUP = 0x0002;

    // Virtual Key codes
    public const byte VK_RETURN = 0x0D;
    public const byte VK_BACK = 0x08;
    public const byte VK_TAB = 0x09;
    public const byte VK_ESCAPE = 0x1B;
    public const byte VK_DELETE = 0x2E;
    public const byte VK_UP = 0x26;
    public const byte VK_DOWN = 0x28;
    public const byte VK_LEFT = 0x25;
    public const byte VK_RIGHT = 0x27;
    public const byte VK_HOME = 0x24;
    public const byte VK_END = 0x23;
    public const byte VK_SHIFT = 0x10;
    public const byte VK_CONTROL = 0x11;
    public const byte VK_MENU = 0x12;  // Alt key
}
"@
Add-Type -TypeDefinition $win32Type -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.Windows.Forms -ErrorAction SilentlyContinue

$TARGET_PORT = 53824
$DISCOVERY_PORT = 53826
$phoneIp = ""

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

    # Press modifier keys if flagged
    if ($metaFlags -band 0x01) { [Win32Input]::keybd_event([Win32Input]::VK_SHIFT, 0, 0, 0) }
    if ($metaFlags -band 0x02) { [Win32Input]::keybd_event([Win32Input]::VK_CONTROL, 0, 0, 0) }
    if ($metaFlags -band 0x04) { [Win32Input]::keybd_event([Win32Input]::VK_MENU, 0, 0, 0) }

    # Key press + release
    [Win32Input]::keybd_event($vk, 0, 0, 0)
    [Win32Input]::keybd_event($vk, 0, [Win32Input]::KEYEVENTF_KEYUP, 0)

    # Release modifier keys
    if ($metaFlags -band 0x04) { [Win32Input]::keybd_event([Win32Input]::VK_MENU, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
    if ($metaFlags -band 0x02) { [Win32Input]::keybd_event([Win32Input]::VK_CONTROL, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
    if ($metaFlags -band 0x01) { [Win32Input]::keybd_event([Win32Input]::VK_SHIFT, 0, [Win32Input]::KEYEVENTF_KEYUP, 0) }
}

function Read-Exact($stream, $count) {
    $buf = New-Object byte[] $count
    $read = 0
    while ($read -lt $count) {
        $bytesRead = $stream.Read($buf, $read, $count - $read)
        if ($bytesRead -eq 0) { throw "Disconnected by host" }
        $read += $bytesRead
    }
    return $buf
}

# 1. Listen for UDP Discovery Beacon (1.5 sec timeout)
Write-Host "[1/3] Scanning local network for VectraTouch UDP beacon..." -ForegroundColor Yellow

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

Write-Host "[3/3] Connecting to VectraTouch at $phoneIp`:$TARGET_PORT..." -ForegroundColor Cyan

# 3. Connect TCP socket and process reports
try {
    $client = New-Object System.Net.Sockets.TcpClient
    $client.NoDelay = $true
    $client.Connect($phoneIp, $TARGET_PORT)
    $stream = $client.GetStream()

    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  CONNECTED! Mouse & Keyboard active via $phoneIp" -ForegroundColor Green
    Write-Host "  Press Ctrl+C to disconnect." -ForegroundColor Gray
    Write-Host "═══════════════════════════════════════════════════════════" -ForegroundColor Green

    $prevButtons = 0

    while ($client.Connected) {
        # Read first byte to determine packet type
        $headerBuf = Read-Exact $stream 1
        $header = [int]$headerBuf[0]

        if ($header -eq 0xAA) {
            # ── Keyboard Text Packet: [0xAA, len_high, len_low, ...utf8...] ──
            $lenBuf = Read-Exact $stream 2
            $textLen = ([int]$lenBuf[0] -shl 8) -bor [int]$lenBuf[1]
            if ($textLen -gt 0 -and $textLen -le 65535) {
                $textBuf = Read-Exact $stream $textLen
                $text = [System.Text.Encoding]::UTF8.GetString($textBuf)
                # Use SendKeys to simulate typing (handles all characters)
                [System.Windows.Forms.SendKeys]::SendWait($text.Replace("+", "{+}").Replace("^", "{^}").Replace("%", "{%}").Replace("~", "{~}").Replace("(", "{(}").Replace(")", "{)}").Replace("{", "{{").Replace("}", "}}"))
            }
        }
        elseif ($header -eq 0xAB) {
            # ── Special Key Packet: [0xAB, keyCode, metaFlags] ──
            $keyBuf = Read-Exact $stream 2
            $keyCode = [int]$keyBuf[0]
            $metaFlags = [int]$keyBuf[1]
            Send-SpecialKey $keyCode $metaFlags
        }
        else {
            # ── Mouse Report: [buttons(already read), dx, dy, scroll] ──
            $mouseBuf = Read-Exact $stream 3
            $buttons = $header

            $rawDx = [int]$mouseBuf[0]
            $rawDy = [int]$mouseBuf[1]
            $rawScroll = [int]$mouseBuf[2]

            $dx = if ($rawDx -gt 127) { $rawDx - 256 } else { $rawDx }
            $dy = if ($rawDy -gt 127) { $rawDy - 256 } else { $rawDy }
            $scroll = if ($rawScroll -gt 127) { $rawScroll - 256 } else { $rawScroll }

            # Process Mouse Movement
            if ($dx -ne 0 -or $dy -ne 0) {
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_MOVE, $dx, $dy, 0, 0)
            }

            # Process Scroll Wheel
            if ($scroll -ne 0) {
                $wheelDelta = $scroll * 12
                [Win32Input]::mouse_event([Win32Input]::MOUSEEVENTF_WHEEL, 0, 0, $wheelDelta, 0)
            }

            # Process Button Changes
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
    Write-Host "`nError / Disconnected: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Write-Host "`nPress any key to exit..." -ForegroundColor Gray
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}
