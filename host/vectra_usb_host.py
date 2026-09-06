#!/usr/bin/env python3
"""
VectraTouch USB & Wi-Fi Host Companion
===============================================================================
Receives high-frequency trackpad HID packets from the phone over a USB cable
(via ADB port-forwarding or USB Tethering) or Wi-Fi LAN, and dispatches native OS mouse input.

Features:
  - Zero Dependencies on Windows: Uses Win32 user32.mouse_event directly
  - Auto ADB Forwarding: Configures port-forwarding automatically
  - Robust Codepage Support: Pure ASCII console output, cp1252-safe
  - Automatic Reconnection: Handles phone unplug / re-plug cleanly
  - Sub-millisecond latency with TCP_NODELAY

HID Packet Format (4 bytes):
  Byte 0: Button bitmask (0x01=Left, 0x02=Right, 0x04=Middle)
  Byte 1: Relative X (-127 to 127)
  Byte 2: Relative Y (-127 to 127)
  Byte 3: Wheel Scroll (-127 to 127)
===============================================================================
"""

import sys
import os
import socket
import struct
import time
import subprocess

# Safe encoding for Windows consoles
if sys.platform.startswith("win"):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

DEFAULT_PORT = 53824
WHEEL_DELTA = 120

# -- Windows Win32 Mouse Dispatcher -------------------------------------------
is_windows = sys.platform.startswith("win")

if is_windows:
    import ctypes

    MOUSEEVENTF_MOVE = 0x0001
    MOUSEEVENTF_LEFTDOWN = 0x0002
    MOUSEEVENTF_LEFTUP = 0x0004
    MOUSEEVENTF_RIGHTDOWN = 0x0008
    MOUSEEVENTF_RIGHTUP = 0x0010
    MOUSEEVENTF_MIDDLEDOWN = 0x0020
    MOUSEEVENTF_MIDDLEUP = 0x0040
    MOUSEEVENTF_WHEEL = 0x0800

    user32 = ctypes.windll.user32
    last_buttons = 0

    def dispatch_mouse(buttons: int, dx: int, dy: int, scroll: int):
        global last_buttons

        # 1. Cursor Movement
        if dx != 0 or dy != 0:
            user32.mouse_event(MOUSEEVENTF_MOVE, int(dx), int(dy), 0, 0)

        # 2. Button transitions
        btn_flags = 0
        if (buttons & 0x01) and not (last_buttons & 0x01):
            btn_flags |= MOUSEEVENTF_LEFTDOWN
        elif not (buttons & 0x01) and (last_buttons & 0x01):
            btn_flags |= MOUSEEVENTF_LEFTUP

        if (buttons & 0x02) and not (last_buttons & 0x02):
            btn_flags |= MOUSEEVENTF_RIGHTDOWN
        elif not (buttons & 0x02) and (last_buttons & 0x02):
            btn_flags |= MOUSEEVENTF_RIGHTUP

        if (buttons & 0x04) and not (last_buttons & 0x04):
            btn_flags |= MOUSEEVENTF_MIDDLEDOWN
        elif not (buttons & 0x04) and (last_buttons & 0x04):
            btn_flags |= MOUSEEVENTF_MIDDLEUP

        if btn_flags:
            user32.mouse_event(btn_flags, 0, 0, 0, 0)

        # 3. Wheel Scroll
        if scroll != 0:
            user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(scroll * WHEEL_DELTA), 0)

        last_buttons = buttons

    KEYEVENTF_KEYUP = 0x0002
    import ctypes.wintypes
    VkKeyScanW = user32.VkKeyScanW
    VkKeyScanW.argtypes = [ctypes.wintypes.WCHAR]
    VkKeyScanW.restype = ctypes.c_short

    VK_MAP = {
        0x01: 0x0D,  # Enter
        0x02: 0x08,  # Backspace
        0x03: 0x09,  # Tab
        0x04: 0x1B,  # Escape
        0x05: 0x2E,  # Delete
        0x06: 0x26,  # Arrow Up
        0x07: 0x28,  # Arrow Down
        0x08: 0x25,  # Arrow Left
        0x09: 0x27,  # Arrow Right
        0x0A: 0x24,  # Home
        0x0B: 0x23,  # End
    }
    VK_SHIFT = 0x10
    VK_CONTROL = 0x11
    VK_MENU = 0x12  # Alt

    def send_key_press(vk, scan=0):
        user32.keybd_event(vk, scan, 0, 0)
        user32.keybd_event(vk, scan, KEYEVENTF_KEYUP, 0)

    def send_special_key(key_code: int, meta_flags: int):
        vk = VK_MAP.get(key_code)
        if vk is None:
            return
        if meta_flags & 0x01:
            user32.keybd_event(VK_SHIFT, 0, 0, 0)
        if meta_flags & 0x02:
            user32.keybd_event(VK_CONTROL, 0, 0, 0)
        if meta_flags & 0x04:
            user32.keybd_event(VK_MENU, 0, 0, 0)

        send_key_press(vk)

        if meta_flags & 0x04:
            user32.keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0)
        if meta_flags & 0x02:
            user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
        if meta_flags & 0x01:
            user32.keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0)

    def send_text(text: str):
        for ch in text:
            result = VkKeyScanW(ch)
            if result == -1:
                continue
            vk = result & 0xFF
            shift_state = (result >> 8) & 0xFF
            if shift_state & 0x01:
                user32.keybd_event(VK_SHIFT, 0, 0, 0)
            if shift_state & 0x02:
                user32.keybd_event(VK_CONTROL, 0, 0, 0)
            if shift_state & 0x04:
                user32.keybd_event(VK_MENU, 0, 0, 0)

            send_key_press(vk)

            if shift_state & 0x04:
                user32.keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0)
            if shift_state & 0x02:
                user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
            if shift_state & 0x01:
                user32.keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0)

else:
    # Fallback for macOS / Linux using pynput if installed
    try:
        from pynput.mouse import Button, Controller
        mouse = Controller()
        last_buttons = 0

        def dispatch_mouse(buttons: int, dx: int, dy: int, scroll: int):
            global last_buttons
            if dx != 0 or dy != 0:
                mouse.move(dx, dy)
            if (buttons & 0x01) and not (last_buttons & 0x01):
                mouse.press(Button.left)
            elif not (buttons & 0x01) and (last_buttons & 0x01):
                mouse.release(Button.left)
            if (buttons & 0x02) and not (last_buttons & 0x02):
                mouse.press(Button.right)
            elif not (buttons & 0x02) and (last_buttons & 0x02):
                mouse.release(Button.right)
            if (buttons & 0x04) and not (last_buttons & 0x04):
                mouse.press(Button.middle)
            elif not (buttons & 0x04) and (last_buttons & 0x04):
                mouse.release(Button.middle)
            if scroll != 0:
                mouse.scroll(0, scroll)
            last_buttons = buttons
    except ImportError:
        print("[!] On non-Windows platforms, please install pynput: pip install pynput")
        def dispatch_mouse(buttons: int, dx: int, dy: int, scroll: int):
            pass

    def send_special_key(key_code: int, meta_flags: int):
        pass

    def send_text(text: str):
        pass


def find_adb():
    """Locate adb executable from PATH or standard Android SDK locations."""
    import shutil
    adb = shutil.which("adb")
    if adb:
        return adb

    local_app_data = os.environ.get("LOCALAPPDATA", "")
    if local_app_data:
        candidate = os.path.join(local_app_data, "Android", "Sdk", "platform-tools", "adb.exe")
        if os.path.isfile(candidate):
            return candidate

    for drive in ["C", "D", "E"]:
        candidate = f"{drive}:\\Android\\platform-tools\\adb.exe"
        if os.path.isfile(candidate):
            return candidate

    return "adb"


def setup_adb_forward(port: int):
    """Run `adb forward tcp:<port> tcp:<port>` to route USB traffic."""
    adb_path = find_adb()
    try:
        res = subprocess.run([adb_path, "devices"], capture_output=True, text=True, timeout=3)
        devices = []
        if res.returncode == 0:
            for line in res.stdout.strip().splitlines()[1:]:
                parts = line.strip().split()
                if len(parts) >= 2 and parts[1] == "device":
                    devices.append(parts[0])

        if not devices:
            return False

        # Prioritize physical USB connection (non-network)
        target_device = devices[0]
        for dev in devices:
            if "._tcp" not in dev and ":" not in dev:
                target_device = dev
                break

        cmd = [adb_path, "-s", target_device, "forward", f"tcp:{port}", f"tcp:{port}"]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        if result.returncode == 0:
            print(f"[+] ADB forward configured for {target_device}: tcp:{port} -> tcp:{port}")
            return True
        else:
            print(f"[i] ADB forward notice: {result.stderr.strip()}")
    except Exception as e:
        pass
    return False


_instance_lock_socket = None

def acquire_single_instance_lock(lock_port: int = DEFAULT_PORT + 1) -> bool:
    """Ensure only one instance of the USB host companion runs at a time."""
    global _instance_lock_socket
    try:
        _instance_lock_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        _instance_lock_socket.bind(("127.0.0.1", lock_port))
        _instance_lock_socket.listen(1)
        return True
    except socket.error:
        return False


DISCOVERY_PORT = 53826


def discover_phone_ip(timeout: float = 3.5) -> str:
    """Listen for UDP discovery beacon from VectraTouch app on the local network."""
    print(f"[*] Scanning local Wi-Fi for VectraTouch (UDP port {DISCOVERY_PORT})...")
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.bind(("", DISCOVERY_PORT))
        s.settimeout(timeout)
        data, addr = s.recvfrom(1024)
        text = data.decode("utf-8", errors="ignore")
        if text.startswith("VECTRA_BEACON"):
            phone_ip = addr[0]
            print(f"[+] Discovered VectraTouch phone at {phone_ip}!")
            return phone_ip
    except (socket.timeout, TimeoutError):
        print("[-] Auto-discovery timed out (UDP broadcast might be filtered by router).")
    except Exception as e:
        print(f"[!] Discovery notice: {e}")
    finally:
        try:
            s.close()
        except Exception:
            pass
    return None


def recv_exact(sock: socket.socket, count: int) -> bytes:
    """Read exactly 'count' bytes from socket."""
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise ConnectionResetError("Socket closed by phone")
        data += chunk
    return data


def run_host(host: str = "127.0.0.1", port: int = DEFAULT_PORT, is_wifi: bool = False):
    title_mode = "Wi-Fi" if is_wifi else "USB"
    print("=" * 60)
    print(f"  VectraTouch -- {title_mode} Host Companion")
    print("=" * 60)

    if not acquire_single_instance_lock(port + 1):
        print("\n[!] WARNING: Another instance of VectraTouch is ALREADY RUNNING!")
        print("    Only one companion window can run at a time.")
        print("    Please close any other open VectraTouch windows first.")
        print("=" * 60)
        sys.exit(1)

    if is_wifi:
        if host in ("127.0.0.1", "localhost", "auto", ""):
            discovered = discover_phone_ip()
            if discovered:
                host = discovered
            else:
                try:
                    host = input("\n[?] Enter Phone IP (displayed on phone status bar): ").strip()
                except (EOFError, KeyboardInterrupt):
                    sys.exit(0)
                if not host:
                    print("[!] No IP entered. Exiting.")
                    sys.exit(1)
        print(f"[*] Connecting via Wi-Fi to VectraTouch at {host}:{port}...")
    else:
        setup_adb_forward(port)
        print(f"[*] Connecting via USB cable to VectraTouch on {host}:{port}...")

    retry_count = 0
    while True:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            s.settimeout(2.5)
            s.connect((host, port))
            s.settimeout(None)

            transport_label = "Wi-Fi" if is_wifi else "USB"
            print(f"\n[+] Connected to VectraTouch over {transport_label} ({host}:{port})!")
            print("[*] Trackpad & Keyboard active. Move fingers on phone to control PC cursor.")
            print("[*] Press Ctrl+C to exit.\n")
            retry_count = 0

            while True:
                header = recv_exact(s, 1)
                ptype = header[0]

                if ptype == 0xAA:
                    # Keyboard text packet: [0xAA, len_high, len_low, ...utf8...]
                    len_buf = recv_exact(s, 2)
                    text_len = (len_buf[0] << 8) | len_buf[1]
                    if 0 < text_len <= 65535:
                        text_buf = recv_exact(s, text_len)
                        text = text_buf.decode('utf-8', errors='replace')
                        send_text(text)

                elif ptype == 0xAB:
                    # Special key packet: [0xAB, keyCode, metaFlags]
                    key_buf = recv_exact(s, 2)
                    send_special_key(key_buf[0], key_buf[1])

                else:
                    # Mouse report: [buttons(=ptype), dx, dy, scroll]
                    mouse_buf = recv_exact(s, 3)
                    buttons = ptype
                    dx, dy, scroll = struct.unpack("bbb", mouse_buf)
                    try:
                        dispatch_mouse(buttons, dx, dy, scroll)
                    except Exception:
                        pass

        except (socket.error, ConnectionResetError, socket.timeout) as e:
            now_str = time.strftime("%H:%M:%S")
            retry_count += 1
            if retry_count == 1:
                print(f"[-] [{now_str}] Connection closed ({type(e).__name__}). Waiting for phone app...")
            time.sleep(1.5)
            if not is_wifi:
                setup_adb_forward(port)
        except KeyboardInterrupt:
            print(f"\n[*] Exiting VectraTouch {title_mode} Host. Goodbye!")
            break
        except Exception as e:
            print(f"\n[!] Unexpected error: {e}")
            time.sleep(2)


if __name__ == "__main__":
    args = sys.argv[1:]
    is_wifi = False
    host_ip = "127.0.0.1"
    port_num = DEFAULT_PORT

    if "--wifi" in args or "-w" in args:
        is_wifi = True
        args = [a for a in args if a not in ("--wifi", "-w")]

    if len(args) >= 1:
        host_ip = args[0]
        # If user passes an IP that is not 127.0.0.1 or localhost, assume Wi-Fi
        if host_ip not in ("127.0.0.1", "localhost"):
            is_wifi = True

    if len(args) >= 2:
        try:
            port_num = int(args[1])
        except ValueError:
            pass

    run_host(host_ip, port_num, is_wifi)
