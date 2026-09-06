#!/usr/bin/env python3
"""
VectraTouch — Python PC Companion Client
Connects via Wi-Fi (UDP auto-discovery) or USB (TCP socket port 53824).
Translates 4-byte HID reports into native OS mouse events using Win32 API / ctypes.
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

is_windows = sys.platform.startswith('win')
if is_windows:
    import ctypes

def simulate_mouse(buttons, dx, dy, scroll, prev_buttons):
    if is_windows:
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

def main():
    print("=" * 60)
    print("           VectraTouch PC Companion Client (Python)")
    print("         Phone as PC Trackpad & Mouse (USB & Wi-Fi)")
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
        print(f"  CONNECTED! Controlling PC Mouse via {phone_ip}")
        print("  Press Ctrl+C to disconnect.")
        print("=" * 60)

        prev_buttons = 0
        while True:
            data = s.recv(4)
            if not data or len(data) < 4:
                break
            buttons, dx, dy, scroll = struct.unpack("bbb", data[0:3]) + (struct.unpack("b", data[3:4])[0],)
            simulate_mouse(buttons, dx, dy, scroll, prev_buttons)
            prev_buttons = buttons

    except KeyboardInterrupt:
        print("\nDisconnected by user.")
    except Exception as e:
        print(f"\nError: {e}")

if __name__ == '__main__':
    main()
