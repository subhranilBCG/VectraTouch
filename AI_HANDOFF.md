# VectraTrackPad — AI Handoff Document
> Last updated: 2026-09-04T13:59 IST

## Project Overview

**VectraTrackPad** is an Android application (API 28+) that turns a phone into a driverless Bluetooth HID mouse. It pairs directly with a PC/Mac via the `BluetoothHidDevice` API — no host-side software needed.

### Core Mechanic: 3-Finger Spatial Sorting
Instead of fixed tap zones, finger roles are assigned dynamically based on relative X-coordinates:
- **Index** (leftmost X) → Left Click (`0x01`)
- **Anchor** (median X) → Cursor Movement (dx, dy)
- **Ring** (rightmost X) → Right Click (`0x02`)

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                      MainActivity                      │
│  • Runtime permissions                                 │
│  • Immersive fullscreen                                │
│  • Instantiates TrackpadTransportManager               │
└──────────────┬───────────────────┬─────────────────────┘
               │                   │
┌──────────────▼──────────┐ ┌──────▼─────────────────────┐
│ TrackpadTransportManager│ │        TrackpadView        │
│  • Quad-mode transport  │ │ • Multi-touch MotionEvent  │
│  • AUTO / BT / USB/WIFI │ │ • Spatial X-sort mechanic  │
│  • State aggregation    │◄┤ • 6-state gesture machine  │
└──────┬───────────┬──────┘ │ • [AUTO|BT|USB|WIFI] Tabs  │
       │           │        │ • [ ⚙ ] Settings Modal     │
┌──────▼──────┐ ┌──▼───────────┐│ • Dim Background HUD Guide │
│BluetoothHid │ │UsbTrackpad   ││ • Canvas rendering         │
│Manager      │ │Server (53824)│└────────────────────────────┘
└─────────────┘ └──┬───────────┘
                   │
           ┌───────┴────────┐
           ▼                ▼
     USB Cable        Wi-Fi (LAN)
   (ADB Forward)     (UDP Beacon 53826)
```

### File Map

| File | Purpose |
|---|---|
| `TrackpadTransportManager.kt` | Coordinates physical transports (USB, Wi-Fi, Bluetooth HID), manages AUTO/BT/USB/WIFI modes |
| `UsbTrackpadServer.kt` | Zero-latency TCP socket server (port 53824) + UDP discovery beacon (port 53826) + embedded HTTP server manager |
| `CompanionHttpServer.kt` | Embedded HTTP web server (port 8080) serving offline landing page and PC companion scripts from assets |
| `BluetoothHidManager.kt` | Bluetooth HID Device profile proxy, SDP record, connection lifecycle, report sending |
| `TrackpadView.kt` | Custom View with multi-touch processing, spatial sorting, gesture machine, 4-mode switcher, dim HUD guide with vector finger illustrations (`☝`, `☝☝`, `☝☝☝`), settings modal |
| `MainActivity.kt` | Permission flow, fullscreen, component wiring, orientation listener, lifecycle |
| `AndroidManifest.xml` | Bluetooth + Internet + Wi-Fi permissions, dynamic orientation config, fullscreen activity |
| `assets/index.html` | Dark-theme landing page served at `http://<PHONE_IP>:8080` for downloading PC companion without internet |
| `assets/VectraCompanion.cmd` | 1-click Windows batch launcher wrapping zero-dependency PowerShell client |
| `assets/VectraCompanion.ps1` | Zero-dependency PowerShell companion using Win32 `user32.dll` `mouse_event` |
| `assets/VectraCompanion.py` | Standalone Python companion script for Mac / Linux / Python users |

---

## Implementation Log

### 2026-09-04 — Initial Implementation (Complete)

**What was done:**
1. ✅ Raised `minSdk` from 24 → 28 (required for BluetoothHidDevice API)
2. ✅ Removed Jetpack Compose entirely — replaced with Canvas-based custom View
3. ✅ Updated `build.gradle.kts` (root + app) — removed Compose plugin, dependencies
4. ✅ Updated `colors.xml` — VectraTrackPad palette (deep slate, teal, light aqua)
5. ✅ Updated `themes.xml` — fullscreen dark Material theme
6. ✅ Updated `AndroidManifest.xml` — Bluetooth permissions, landscape, fullscreen
7. ✅ Deleted `ui/theme/` directory (Compose theme files)
8. ✅ Created `BluetoothHidManager.kt`:
   - Standard USB HID mouse report descriptor (3 buttons + scroll)
   - Profile proxy acquisition via `BluetoothAdapter.getProfileProxy()`
   - `BluetoothHidDevice.Callback` for connection events
   - `sendMouseReport(buttons, dx, dy, scroll)` with value clamping
   - Observable `ConnectionState` enum with listener interface
9. ✅ Created `TrackpadView.kt`:
   - `SparseArray<TouchPoint>` for pointer tracking across lifecycle
   - X-coordinate spatial sorting for 1/2/3+ finger configurations
   - 6-state gesture machine: IDLE, CURSOR_MOVE, LEFT_HELD, RIGHT_HELD, SCROLL_MODE, CORNER_MACRO
   - Corner macro zones (4 × RectF) — bottom corners = middle click, top = placeholder
   - Touch slop filtering via `ViewConfiguration.getScaledTouchSlop()`
   - Scroll dead zone + accumulator for sub-pixel scroll precision
   - Canvas rendering: dot grid, corner zones, touch pointers with role labels, gesture pill
10. ✅ Created `MainActivity.kt`:
    - Runtime permission request via `ActivityResultContracts.RequestMultiplePermissions`
    - Immersive sticky fullscreen (API 30+ `WindowInsetsController` + legacy fallback)
    - `FLAG_KEEP_SCREEN_ON`
    - Connection state listener → status text on trackpad

- ✅ **APK Build Generation**:
  - Debug APK: [VectraTrackPad.apk](file:///d:/VectraTrackPad/VectraTrackPad.apk) (Root copy)
  - Output Path: [app-debug.apk](file:///d:/VectraTrackPad/app/build/outputs/apk/debug/app-debug.apk)
  - Release Path: [app-release-unsigned.apk](file:///d:/VectraTrackPad/app/build/outputs/apk/release/app-release-unsigned.apk)
- ✅ Fixed crash on launch (`NullPointerException` on `DecorView.getWindowInsetsController()`): re-ordered `setContentView(trackpadView)` before `enterImmersiveMode()` and updated `enterImmersiveMode()` to use `WindowCompat.getInsetsController()` with safety fallbacks.
- ✅ Added USB transport engine on port `53824` with dedicated high-priority send thread and `LinkedBlockingQueue` to eliminate `NetworkOnMainThreadException`.
- ✅ Added interactive Canvas Mode Switcher pill (`[ AUTO | BT | USB ]`) with transport dots and dynamic status subtitles.
- ✅ Added PC Host companion (`host/vectra_usb_host.py` & `host/run_usb_windows.bat`) with Single-Instance Lock on port `53825`.
- ✅ Resolved Right-Click / Ring Finger bug: dynamic temporal & spatial role assignment assigns `FingerRole.RING` to touches placed to the right of the anchor, plus added native 2-finger tap right-click with haptics.
- ✅ Removed all 4 corner buttons / macro zones for an uninterrupted edge-to-edge trackpad experience.
- ✅ Added 3-finger simultaneous tap for Middle Click (`0x04`).
- ✅ Added top Settings button `[ ⚙ ]` with padded touch targets (-16dp hit area).
- ✅ Created glassmorphic Settings modal dialog with:
  - Mouse Sensitivity controls (`[-]`/`[+]` steppers, touchable progress bar, presets `0.8x`, `1.4x`, `2.0x`, `2.8x`), live-applied and persisted to `SharedPreferences`.
  - Phone Orientation controls (`Landscape`, `Rev Land`, `Portrait`, `Auto Sensor`), dynamic rotation without activity recreation via `android:configChanges`.
  - `[ DONE ]`, `[ ✕ ]`, and tap-outside dismissal.
  - Cutout edge-to-edge rendering via `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`.
- ✅ Changed color theme to **Matte Black & Cyber Green** (`#0A0D0B` / `#00E676` / `#E8FCE8`).
- ✅ Added dim background gesture quick guide overlay on canvas behind touch pointers (1 finger, 2 fingers, 3 fingers cards).
- ✅ Added **Wi-Fi Connection mode** alongside USB and Bluetooth (`[ AUTO | BT | USB | WIFI ]`):
  - In-app socket server accepts direct TCP connection on `0.0.0.0:53824`.
  - Added UDP auto-discovery beacon on port `53826` (`VECTRA_BEACON:53824`).
  - Added `isWifiClient` detection and phone IP display on the status bar.
  - Enhanced host companion (`host/vectra_usb_host.py`) with `--wifi` flag, auto-discovery UDP listener, and IP input fallback.
  - Added 1-click launcher `host/run_wifi_windows.bat`.

---

## Gesture State Machine

```
                 ┌─────────┐
          ┌──────┤  IDLE   ├─────────────┐
          │      └────┬────┘             │
          │           │                  │
    1 finger     2 fingers          3 fingers
          │           │                  │
          ▼           ├──────────┐       ▼
   ┌──────────┐       ▼          ▼ ┌───────────┐
   │ CURSOR   │  ┌──────────┐ ┌────┴──────┐    │ SCROLL    │◄── Both flanks
   │ MOVE     │  │ LEFT     │ │ RIGHT     │    │ MODE      │    held
   └──────────┘  │ HELD/TAP │ │ HELD/TAP  │    └───────────┘
                 └──────────┘ └───────────┘
                 (tap/drag L) (tap/drag R)
```

## HID Report Format

| Byte | Field | Values |
|---|---|---|
| 0 | Buttons | `0x01` = Left, `0x02` = Right, `0x04` = Middle |
| 1 | X Delta | `-127` to `127` (relative) |
| 2 | Y Delta | `-127` to `127` (relative) |
| 3 | Scroll | `-127` to `127` (wheel) |

---

## Known Caveats & Edge Cases

1. **Device compatibility**: Not all Android devices expose the HID Device Bluetooth profile. The `getProfileProxy()` call may fail silently.
2. **Single HID registration**: Only one app can hold the HID Device profile at a time. If another app (e.g., a Bluetooth keyboard app) is registered, `registerApp()` will fail.
3. **SDP timing**: There can be a delay between `registerApp()` and the device appearing as discoverable to hosts.
4. **Pointer ID stability**: Android guarantees pointer IDs are stable within a gesture, but they can be reused across gestures. The `SparseArray` handles this correctly.
5. **High-frequency moves**: `ACTION_MOVE` batches multiple samples. Currently we only process the latest position per pointer. Historical samples via `getHistorical*()` could improve tracking.

---

## Color Palette (Matte Black & Cyber Green)

| Token | Hex | Usage |
|---|---|---|
| Matte Black | `#0A0D0B` | Canvas background, status/nav bars |
| Neon Green Accent | `#00E676` | Touch pointers, active tabs, dialog borders |
| Mint / Light Green | `#E8FCE8` | Active text labels, values, highlights |
| Clean White | `#FFFFFF` | Status text |
| Dim Grid | `#141D16` | Subtle matte grid dots |
| Sage / Wireframe | `#8AA894` | Dim background instruction guide text & cards |
| Card / Modal Dark | `#0D130F` | Glassmorphic settings card & modal overlay |
