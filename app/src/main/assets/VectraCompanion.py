#!/usr/bin/env python3
"""
VectraTouch — Python PC Companion Client
Connects via Wi-Fi (UDP auto-discovery) or USB (TCP socket port 53824).
Translates 4-byte HID reports into native OS mouse events, and handles
keyboard text (0xAA) and special key (0xAB) packets using Win32 API / ctypes.
"""

import sys
import socket
import struct
import time

TARGET_PORT = 53824
DISCOVERY_PORT = 53826

# Win32 Mouse Flags
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800

# Win32 Keyboard Flags
KEYEVENTF_KEYUP = 0x0002

# Virtual Key codes
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

is_windows = sys.platform.startswith('win')
if is_windows:
    import ctypes
    import ctypes.wintypes

    # VkKeyScanW: maps a character to a virtual-key code + shift state
    VkKeyScanW = ctypes.windll.user32.VkKeyScanW
    VkKeyScanW.argtypes = [ctypes.wintypes.WCHAR]
    VkKeyScanW.restype = ctypes.c_short


def simulate_mouse(buttons, dx, dy, scroll, prev_buttons):
    if not is_windows:
        return
    # Movement
    if dx != 0 or dy != 0:
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_MOVE, int(dx), int(dy), 0, 0)

    # Scroll
    if scroll != 0:
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(scroll * 12), 0)

    # Buttons
    if (buttons & 1) and not (prev_buttons & 1):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    elif not (buttons & 1) and (prev_buttons & 1):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)

    if (buttons & 2) and not (prev_buttons & 2):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_RIGHTDOWN, 0, 0, 0, 0)
    elif not (buttons & 2) and (prev_buttons & 2):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_RIGHTUP, 0, 0, 0, 0)

    if (buttons & 4) and not (prev_buttons & 4):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_MIDDLEDOWN, 0, 0, 0, 0)
    elif not (buttons & 4) and (prev_buttons & 4):
        ctypes.windll.user32.mouse_event(MOUSEEVENTF_MIDDLEUP, 0, 0, 0, 0)


def send_key_press(vk, scan=0):
    """Simulate a key press and release."""
    if not is_windows:
        return
    ctypes.windll.user32.keybd_event(vk, scan, 0, 0)
    ctypes.windll.user32.keybd_event(vk, scan, KEYEVENTF_KEYUP, 0)


def send_special_key(key_code, meta_flags):
    """Send a special key with optional modifier keys."""
    if not is_windows:
        return
    vk = VK_MAP.get(key_code)
    if vk is None:
        return

    # Press modifiers
    if meta_flags & 0x01:
        ctypes.windll.user32.keybd_event(VK_SHIFT, 0, 0, 0)
    if meta_flags & 0x02:
        ctypes.windll.user32.keybd_event(VK_CONTROL, 0, 0, 0)
    if meta_flags & 0x04:
        ctypes.windll.user32.keybd_event(VK_MENU, 0, 0, 0)

    # Key press + release
    send_key_press(vk)

    # Release modifiers
    if meta_flags & 0x04:
        ctypes.windll.user32.keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0)
    if meta_flags & 0x02:
        ctypes.windll.user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
    if meta_flags & 0x01:
        ctypes.windll.user32.keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0)


def send_text(text):
    """Simulate typing a text string character by character using VkKeyScanW."""
    if not is_windows:
        return
    for ch in text:
        result = VkKeyScanW(ch)
        if result == -1:
            continue  # Character not mappable
        vk = result & 0xFF
        shift_state = (result >> 8) & 0xFF

        # Press Shift if needed
        if shift_state & 0x01:
            ctypes.windll.user32.keybd_event(VK_SHIFT, 0, 0, 0)
        if shift_state & 0x02:
            ctypes.windll.user32.keybd_event(VK_CONTROL, 0, 0, 0)
        if shift_state & 0x04:
            ctypes.windll.user32.keybd_event(VK_MENU, 0, 0, 0)

        send_key_press(vk)

        if shift_state & 0x04:
            ctypes.windll.user32.keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0)
        if shift_state & 0x02:
            ctypes.windll.user32.keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, 0)
        if shift_state & 0x01:
            ctypes.windll.user32.keybd_event(VK_SHIFT, 0, KEYEVENTF_KEYUP, 0)


def recv_exact(sock, count):
    """Read exactly 'count' bytes from socket."""
    data = b''
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise ConnectionError("Disconnected by host")
        data += chunk
    return data


def main():
    print("=" * 60)
    print("       VectraTouch PC Companion Client (Python)")
    print("   Phone as PC Trackpad, Mouse & Keyboard (USB & Wi-Fi)")
    print("=" * 60)

    phone_ip = None

    # 1. Listen for UDP beacon
    print("[1/3] Scanning local network for VectraTouch beacon...")
    try:
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.settimeout(1.5)
        udp.bind(('', DISCOVERY_PORT))
        data, addr = udp.recvfrom(1024)
        if data.startswith(b"VECTRA_BEACON"):
            phone_ip = addr[0]
            print(f" -> Auto-Discovered Phone IP: {phone_ip}")
        udp.close()
    except Exception:
        pass

    # 2. Fallback IP prompt
    if not phone_ip:
        print("[2/3] Beacon not heard. Defaulting to Localhost / USB (127.0.0.1).")
        ip_input = input("Enter Phone IP Address [Press ENTER for 127.0.0.1]: ").strip()
        phone_ip = ip_input if ip_input else "127.0.0.1"

    print(f"[3/3] Connecting to VectraTouch at {phone_ip}:{TARGET_PORT}...")

    # 3. Connect TCP socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.connect((phone_ip, TARGET_PORT))
        print("=" * 60)
        print(f"  CONNECTED! Mouse & Keyboard active via {phone_ip}")
        print("  Press Ctrl+C to disconnect.")
        print("=" * 60)

        prev_buttons = 0
        while True:
            # Read first byte to determine packet type
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
                simulate_mouse(buttons, dx, dy, scroll, prev_buttons)
                prev_buttons = buttons

    except KeyboardInterrupt:
        print("\nDisconnected by user.")
    except Exception as e:
        print(f"\nError: {e}")


if __name__ == '__main__':
    main()
