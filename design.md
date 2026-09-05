# VectraTrackPad — System Architecture & Design Specification
> **Document Version**: 2.0.0  
> **Last Updated**: 2026-09-04  
> **Status**: Living Design Specification  

---

## 1. Executive Summary & Vision

**VectraTrackPad** is a high-performance, low-latency virtual trackpad system that transforms an Android smartphone into an ergonomic, multi-gesture trackpad for PCs and Macs.

### Core Pillars
1. **Zero-Hardware Friction (Driverless Bluetooth HID)**: Works natively as a physical Bluetooth mouse without requiring any client software, drivers, or companion apps on the host computer.
2. **Zero-Latency Wired & Wireless Options (USB & Wi-Fi)**: For competitive tasks, gaming, or interference-heavy environments, provides direct USB cable (via ADB port-forwarding or `/dev/hidg0`) and local Wi-Fi transmission with sub-millisecond packet latency.
3. **Spatial Sorting Touch Mechanics**: Eliminates cramped, static on-screen buttons. Instead, finger roles are calculated dynamically based on relative horizontal (X) coordinates across the entire display.
4. **Distraction-Free Cyber Aesthetics**: Engineered with a deep matte black canvas, cyber green glowing accents, a high-tech matrix dot grid, and dim watermark gesture HUD cards that guide users without obstructing input.

---

## 2. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             Android Smartphone                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                MainActivity                                 │
│  • Immersive sticky fullscreen (API 30+ WindowInsetsControllerCompat)       │
│  • Edge-to-edge display cutout handling (SHORT_EDGES)                       │
│  • Dynamic orientation controller (Landscape / Rev Land / Portrait / Auto)  │
│  • Permission lifecycle (BLUETOOTH_CONNECT, ADVERTISE, INTERNET, WIFI)      │
├──────────────────────────────────────┬──────────────────────────────────────┤
│                                      ▼                                      │
│                      TrackpadTransportManager                               │
│  • Coordinates physical transports: AUTO | BT | USB | WIFI                  │
│  • Dynamic fallback & priority routing (USB/WIFI prioritized over BT)       │
│  • Real-time connection status aggregation                                  │
├───────────────────┬──────────────────┴───────────────────┬──────────────────┤
│                   ▼                                      ▼                  │
│         BluetoothHidManager                    UsbTrackpadServer            │
│  • Android BluetoothHidDevice API       • TCP Server (0.0.0.0:53824)        │
│  • 4-byte USB HID Mouse SDP record      • Non-blocking LinkedBlockingQueue  │
│  • Native OS pairing (PC / Mac / Linux) • Dedicated high-priority thread    │
│  • Zero host-side software required     • UDP Beacon on port 53826          │
│                                         • Direct /dev/hidg0 kernel gadget   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                TrackpadView                                 │
│  • Zero-allocation custom Canvas engine (60Hz / 120Hz smooth rendering)     │
│  • Spatial X-sorting gesture engine (Index / Anchor / Ring roles)           │
│  • 6-state gesture state machine (Tap, Drag, Hold, 2-Finger, 3-Finger)      │
│  • Dim background watermark HUD instruction cards                           │
│  • In-canvas glassmorphic Settings modal (Sensitivity & Orientation)        │
│  • Mode switcher pill: [ AUTO | BT | USB | WIFI ] + [ ⚙ ]                   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        ▼                              ▼                              ▼
 ᛒ Bluetooth HID                ⎚ USB Cable                     ᯤ Local Wi-Fi
 (Native OS Mouse)              (ADB Forward 53824)             (LAN TCP 53824)
        │                              │                              │
        ▼                              ▼                              ▼
┌──────────────┐             ┌────────────────────────────────────────────────┐
│ Host PC / Mac│             │             Windows Host Companion             │
│ (Driverless) │             │  • vectra_usb_host.py / run_wifi_windows.bat   │
│              │             │  • Native Win32 user32.mouse_event injection   │
│              │             │  • Single-instance port lock (53825)           │
│              │             │  • Auto UDP beacon discovery on port 53826     │
└──────────────┘             └────────────────────────────────────────────────┘
```

---

## 3. Multi-Transport Subsystem Architecture

VectraTrackPad provides three physical transports unified under `TrackpadTransportManager`:

### A. Transport 1: Bluetooth HID Device (Driverless Mode)
* **API**: Android `BluetoothHidDevice` (API 28+).
* **SDP Record**: Registers a standard USB HID Mouse profile (`SUBCLASS1_MOUSE`) with a 4-byte relative mouse descriptor:
  * Byte 0: Button bitmask (`0x01`=Left, `0x02`=Right, `0x04`=Middle).
  * Byte 1: Relative X movement (`-127` to `127`).
  * Byte 2: Relative Y movement (`-127` to `127`).
  * Byte 3: Relative vertical wheel scroll (`-127` to `127`).
* **Host Compatibility**: Windows 10/11, macOS, Linux, ChromeOS, iOS, Android. The host computer recognizes the phone as a physical Bluetooth peripheral. No software or companion script is required.

### B. Transport 2: USB Socket Engine (Zero-Latency Wired Mode)
* **Mechanism**: TCP Socket Server listening on `0.0.0.0:53824` coupled with ADB port-forwarding (`adb forward tcp:53824 tcp:53824`).
* **Concurrency**: Packets are offered to a thread-safe `LinkedBlockingQueue<ByteArray>(256)`. A dedicated background thread (`VectraUsbSendThread`) running at `Thread.MAX_PRIORITY` drains and writes packets with `TCP_NODELAY` enabled.
* **UI Thread Isolation**: Touch input dispatch on the Android UI thread is completely non-blocking (`offer()`), preventing any `NetworkOnMainThreadException` or frame drops.
* **Kernel Gadget Fallback**: On rooted devices with ConfigFS USB Gadget support, the server automatically detects `/dev/hidg0` and writes raw 4-byte reports directly into the Linux USB gadget node.

### C. Transport 3: Wi-Fi Socket Engine (Wireless LAN Mode)
* **Mechanism**: The same high-speed TCP socket server on `0.0.0.0:53824` accepts incoming connections from any computer on the local subnet.
* **UDP Auto-Discovery Beacon**:
  * A lightweight daemon thread broadcasts `"VECTRA_BEACON:53824"` every 1.5 seconds over UDP port `53826` to `255.255.255.255`.
  * The PC companion listens on UDP port `53826`, auto-discovers the phone's IP address, and establishes the TCP connection without requiring the user to type IP addresses manually.
* **Client IP Differentiation**: The server checks the remote IP. If `127.0.0.1`, it classifies the client as USB (ADB forward); if any other LAN IP (`192.168.x.x`), it classifies it as Wi-Fi.

### D. Transport Coordinator (`TrackpadTransportManager`)
* Coordinates four selectable modes:
  1. **`AUTO`**: Intelligently routes mouse reports to wired USB or local Wi-Fi if connected; falls back to Bluetooth HID automatically.
  2. **`BT`**: Locks input transmission strictly to the Bluetooth HID profile.
  3. **`USB`**: Locks input transmission strictly to the USB socket/gadget engine.
  4. **`WIFI`**: Locks input transmission strictly to the local Wi-Fi network.
* Maintains unified status text displayed on the HUD status line.

---

## 4. Gesture Interaction Model & Spatial Sorting

VectraTrackPad avoids fixed on-screen corner buttons or small touch zones. The entire surface functions as an edge-to-edge trackpad through spatial coordinate analysis.

```
       Index Finger                 Anchor Finger                 Ring Finger
       (Leftmost X)                   (Median X)                 (Rightmost X)
           ●                              ●                            ●
           │                              │                            │
           ▼                              ▼                            ▼
      LEFT CLICK                    CURSOR MOTION                 RIGHT CLICK
     (Button 0x01)                  (dx, dy move)                (Button 0x02)
```

### A. Dynamic Spatial-Temporal Finger Roles
When multiple fingers touch the screen, their roles are dynamically assigned using both spatial sorting and temporal anchor locking:
1. **Anchor Pointer**:
   * The primary moving finger that dictates cursor movement.
   * If a single finger touches, it is automatically the Anchor.
   * If additional fingers land, the anchor is preserved, and the relative X positions of the other fingers determine secondary actions.
2. **Index Pointer (Leftmost X)**:
   * Placed to the left of the Anchor.
   * Tap or hold generates **Left Click** (`0x01`).
   * Can be held down while moving the Anchor to perform **Left Drag & Drop** (e.g., selecting text, moving windows).
3. **Ring Pointer (Rightmost X)**:
   * Placed to the right of the Anchor.
   * Tap or hold generates **Right Click** (`0x02`).
   * Can be held down while moving the Anchor to perform **Right Drag**.

### B. Natural Multi-Touch Gestures
* **1-Finger Drag**: Smooth, high-precision cursor movement with dynamic sensitivity scaling.
* **1-Finger Tap**: Instant Left Click (`0x01`).
* **2-Finger Vertical Drag**: Smooth scroll wheel emulation with sub-pixel accumulator and dead zone filtering.
* **2-Finger Tap**: Contextual Right Click (`0x02`) with haptic feedback.
* **3-Finger Simultaneous Tap**: Middle Click (`0x04`) with haptic feedback.
* **Dual-Flank Hold**: Holding both Index and Ring fingers triggers locked scrolling mode.

### C. Gesture State Machine

```
                            ┌──────────────┐
                            │     IDLE     │
                            └──────┬───────┘
                                   │
              ┌────────────────────┼────────────────────┐
              ▼ (1 Finger)         ▼ (2 Fingers)        ▼ (3 Fingers)
     ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
     │   CURSOR_MOVE   │  │  SCROLL_MODE    │  │  MIDDLE_CLICK   │
     │  (Anchor drags) │  │  (2-finger drag)│  │   (3-finger tap)│
     └────────┬────────┘  └─────────────────┘  └─────────────────┘
              │
      ┌───────┴───────┐
      ▼               ▼
┌───────────┐   ┌───────────┐
│ LEFT_HELD │   │RIGHT_HELD │
│(Index tap/│   │(Ring tap/ │
│ drag lock)│   │ drag lock)│
└───────────┘   └───────────┘
```

---

## 5. UI / UX Design System

The visual design language is built on a **Matte Black & Cyber Neon Green** aesthetic inspired by modern cybernetic interfaces and glassmorphic HUDs.

### A. Color Tokens

| Token | Hex Value | Role / Usage |
|---|---|---|
| `COLOR_MATTE_BLACK` | `#0B0E0C` | Deep matte canvas background, system bars |
| `COLOR_GREEN` | `#00E676` | Primary cyber neon accent, active tabs, touch pointers |
| `COLOR_LIGHT_GREEN` | `#E8FCE8` | High-contrast text labels, sensitivity readout, highlights |
| `COLOR_WHITE` | `#FFFFFF` | Status text subtitles |
| `COLOR_DIM_GRID` | `#141D16` | Subtle matrix dot grid across trackpad surface |
| `COLOR_BORDER_GREEN`| `#1E3A27` | Glassmorphic card borders, pill strokes |
| `COLOR_PILL_BG` | `#0F1511` | Header pill container background |
| `COLOR_HUD_TEXT` | `#8AA894` | Dim watermark guide labels and card strokes |

### B. Layout Components
1. **Top Header Pill (`[ AUTO | BT | USB | WIFI ]`)**:
   * Centered pill container with 4 equal-width tab buttons.
   * Active tab renders a glowing cyber green capsule with high-contrast text.
   * Each tab features a live status dot indicating whether that transport is currently linked.
   * Expanded touch targets (`-16dp` inset expansion) for effortless finger navigation.
2. **Settings Button (`[ ⚙ ]`)**:
   * Placed immediately adjacent to the mode pill with identical height and radius.
   * Tap opens the in-canvas glassmorphic Settings modal dialog.
3. **Status Line**:
   * Clean typography centered below the header pill showing live connection state (e.g. `● Wi-Fi Connected — 192.168.1.10` or `ᯤ Wi-Fi Mode — Connect PC to 192.168.1.15:53824`).
4. **Dim Background HUD Gesture Guide**:
   * 3 watermarked, semi-transparent HUD instruction cards (`1 FINGER`, `2 FINGERS`, `3 FINGERS`) rendered directly onto the canvas.
   * Low opacity (`alpha ~30` card wireframe, `alpha ~85` text) ensures zero distraction during active cursor tracking.
   * Dynamically adapts between **Landscape** (3 horizontal cards) and **Portrait** (3 stacked vertical cards).
5. **Active Pointer Rings & Labels**:
   * Renders pulsating neon green circles around active fingers with floating role labels (`ANCHOR`, `LEFT (INDEX)`, `RIGHT (RING)`).

### C. In-Canvas Glassmorphic Settings Modal Dialog
* **Mouse Sensitivity Controls**:
  * Live multiplier readout (range: `0.4x` to `3.6x`).
  * `[-]` and `[+]` precision stepper buttons.
  * Interactive touchable progress bar with cyber green fill.
  * 4 quick preset pills: `0.8x Slow`, `1.4x Mid`, `2.0x Fast`, `2.8x Max`.
  * Saved instantly to `SharedPreferences` (`"VectraTrackpadPrefs"`).
* **Phone Orientation Controls**:
  * 4 one-tap orientation options: `⤺ Land`, `⤻ Rev Land`, `▯ Portrait`, `⟳ Auto`.
  * Changes device orientation on-the-fly without activity destruction via `android:configChanges`.
* **Dismissal**:
  * Top `[ ✕ ]` button, bottom `[ DONE ]` pill, or tapping anywhere outside the dialog.

---

## 6. Host Companion Architecture (PC Side)

Located in `host/`, the Windows companion bridges USB and Wi-Fi streams to the Windows OS input subsystem:

### A. Component Overview
* **`host/vectra_usb_host.py`**:
  * Pure Python 3 script with **zero non-standard dependencies** on Windows.
  * Interacts directly with `ctypes.windll.user32.mouse_event` for sub-millisecond mouse injection.
  * Supports command-line flags:
    * `python vectra_usb_host.py` (Default: USB mode via ADB forward)
    * `python vectra_usb_host.py --wifi` (Wi-Fi mode with UDP auto-discovery)
    * `python vectra_usb_host.py 192.168.1.5` (Direct IP connection)
* **Single-Instance Mutex**:
  * Binds to local port `53825` (`DEFAULT_PORT + 1`) to ensure only one companion instance runs at a time, preventing duplicate mouse inputs.
* **1-Click Windows Launchers**:
  * `host/run_usb_windows.bat`: One-click batch launcher for USB mode.
  * `host/run_wifi_windows.bat`: One-click batch launcher for Wi-Fi mode.

---

## 7. Data Protocol & Packet Specifications

### A. 4-Byte HID Mouse Report Format
Streamed continuously over TCP socket or Bluetooth interrupt channel:

```
┌──────────────┬──────────────┬──────────────┬──────────────┐
│    Byte 0    │    Byte 1    │    Byte 2    │    Byte 3    │
│ Button Mask  │   Delta X    │   Delta Y    │ Wheel Delta  │
└──────────────┴──────────────┴──────────────┴──────────────┘
```
* **Byte 0 (Buttons)**:
  * Bit 0 (`0x01`): Left Mouse Button (1 = Down, 0 = Up)
  * Bit 1 (`0x02`): Right Mouse Button (1 = Down, 0 = Up)
  * Bit 2 (`0x04`): Middle Mouse Button (1 = Down, 0 = Up)
  * Bits 3–7: Reserved (0)
* **Byte 1 (Delta X)**: Signed 8-bit integer (`-127` to `127`) representing relative horizontal movement.
* **Byte 2 (Delta Y)**: Signed 8-bit integer (`-127` to `127`) representing relative vertical movement.
* **Byte 3 (Wheel Delta)**: Signed 8-bit integer (`-127` to `127`) representing relative wheel scroll ticks.

### B. UDP Auto-Discovery Beacon
* **Protocol**: UDP Broadcast to `255.255.255.255:53826`.
* **Payload**: UTF-8 string `"VECTRA_BEACON:53824"`.
* **Cadence**: Broadcast every 1500ms when the server is active.

---

## 8. Android Specifications & Permissions

* **Min SDK**: API 28 (Android 9.0 Pie) — Required for `BluetoothHidDevice` support.
* **Target SDK**: API 34 (Android 14)
* **Declared Permissions**:
  * `android.permission.BLUETOOTH` & `BLUETOOTH_ADMIN` (API 28–30)
  * `android.permission.BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN` (API 31+)
  * `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
* **Window Properties**:
  * `FLAG_KEEP_SCREEN_ON`: Prevents device from sleeping during trackpad use.
  * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`: Renders true edge-to-edge through camera notches.

---

## 9. Project Directory & File Catalog

```
d:/VectraTrackPad/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # App manifest, permissions & activity config
│   │   ├── java/com/example/vectratrackpad/
│   │   │   ├── MainActivity.kt          # Lifecycle, orientation & permission management
│   │   │   ├── TrackpadView.kt          # Custom Canvas trackpad view & gesture engine
│   │   │   ├── TrackpadTransportManager.kt # Multi-transport coordinator
│   │   │   ├── UsbTrackpadServer.kt     # TCP & UDP socket engine for USB & Wi-Fi
│   │   │   └── BluetoothHidManager.kt   # Bluetooth HID Device profile proxy & SDP
│   │   └── res/
│   │       ├── values/
│   │       │   ├── colors.xml           # Design tokens (matte black, cyber green)
│   │       │   ├── strings.xml          # App strings & branding
│   │       │   └── themes.xml           # Fullscreen dark theme
│   └── build.gradle.kts                 # Android module build script
├── host/
│   ├── vectra_usb_host.py               # Python companion (USB & Wi-Fi input driver)
│   ├── run_usb_windows.bat              # 1-click batch launcher for USB mode
│   └── run_wifi_windows.bat             # 1-click batch launcher for Wi-Fi mode
├── AI_HANDOFF.md                        # Active session state & engineering progress
└── design.md                            # Comprehensive system architecture & design spec
```
