package com.example.vectratrackpad

import android.content.Context
import android.util.Log

/**
 * TrackpadTransportManager
 * ═══════════════════════════════════════════════════════════════════════════════
 * Unified coordinator that dispatches trackpad HID reports across available
 * physical transports (USB Cable and Bluetooth HID).
 *
 * Supports three operational modes:
 *   • AUTO: Seamlessly routes input to USB when a wired host is connected (for
 *           minimum latency and zero wireless interference), falling back to
 *           Bluetooth HID automatically.
 *   • USB:  Input routed exclusively to USB (TCP socket or /dev/hidg0).
 *   • BT:   Input routed exclusively to Bluetooth HID Device profile.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class TrackpadTransportManager(
    private val context: Context,
    val btManager: BluetoothHidManager,
    val usbServer: UsbTrackpadServer = UsbTrackpadServer(context)
) {

    companion object {
        private const val TAG = "VectraTransport"
    }

    enum class Mode {
        AUTO,
        BLUETOOTH,
        USB,
        WIFI
    }

    enum class TransportType {
        NONE,
        BLUETOOTH,
        USB,
        WIFI
    }

    interface StatusListener {
        fun onStatusChanged(statusText: String, activeTransport: TransportType, mode: Mode)
    }

    var statusListener: StatusListener? = null
        set(value) {
            field = value
            notifyStatus()
        }

    var currentMode: Mode = Mode.AUTO
        private set(value) {
            field = value
            notifyStatus()
        }

    init {
        // Wire up Bluetooth HID state changes
        btManager.connectionStateListener = object : BluetoothHidManager.ConnectionStateListener {
            override fun onConnectionStateChanged(
                state: BluetoothHidManager.ConnectionState,
                deviceName: String?
            ) {
                notifyStatus()
            }
        }

        // Wire up USB Server state changes
        usbServer.listener = object : UsbTrackpadServer.UsbStateListener {
            override fun onUsbStateChanged(state: UsbTrackpadServer.UsbState, clientInfo: String?) {
                notifyStatus()
            }
        }
    }

    /**
     * Start transport listeners.
     */
    fun start() {
        Log.i(TAG, "Starting TrackpadTransportManager")
        usbServer.start()
        notifyStatus()
    }

    /**
     * Cycle through modes: AUTO -> BLUETOOTH -> USB -> WIFI -> AUTO.
     */
    fun cycleMode(): Mode {
        currentMode = when (currentMode) {
            Mode.AUTO -> Mode.BLUETOOTH
            Mode.BLUETOOTH -> Mode.USB
            Mode.USB -> Mode.WIFI
            Mode.WIFI -> Mode.AUTO
        }
        Log.i(TAG, "Transport mode changed to $currentMode")
        return currentMode
    }

    /**
     * Set explicit mode.
     */
    fun setMode(mode: Mode) {
        currentMode = mode
        Log.i(TAG, "Transport mode set to $currentMode")
    }

    /**
     * Send 4-byte mouse report via the currently active transport.
     *
     * @param buttons  Bitmask: 0x01=Left, 0x02=Right, 0x04=Middle
     * @param dx       Relative X movement
     * @param dy       Relative Y movement
     * @param scroll   Relative wheel scroll
     * @return true if transmitted successfully
     */
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, scroll: Int): Boolean {
        return when (currentMode) {
            Mode.USB -> {
                usbServer.sendMouseReport(buttons, dx, dy, scroll)
            }
            Mode.WIFI -> {
                usbServer.sendMouseReport(buttons, dx, dy, scroll)
            }
            Mode.BLUETOOTH -> {
                btManager.sendMouseReport(buttons, dx, dy, scroll)
            }
            Mode.AUTO -> {
                // In AUTO mode, prioritize wired USB or Wi-Fi if connected
                if (usbServer.isConnected()) {
                    usbServer.sendMouseReport(buttons, dx, dy, scroll)
                } else {
                    btManager.sendMouseReport(buttons, dx, dy, scroll)
                }
            }
        }
    }

    /**
     * Determine active transport and build user-friendly status text.
     */
    private fun notifyStatus() {
        val usbConnected = usbServer.isConnected()
        val btConnected = btManager.currentState == BluetoothHidManager.ConnectionState.CONNECTED
        val isWifi = usbServer.isWifiClient

        val (statusText, activeTransport) = when (currentMode) {
            Mode.USB -> {
                if (usbConnected) {
                    Pair("● USB Connected — ${usbServer.connectedClientInfo ?: "Host Active"}", TransportType.USB)
                } else {
                    Pair("⎚ USB Mode — Download Companion at http://127.0.0.1:8080", TransportType.NONE)
                }
            }
            Mode.WIFI -> {
                if (usbConnected) {
                    Pair("● Wi-Fi Connected — ${usbServer.connectedClientInfo ?: "PC Active"}", TransportType.WIFI)
                } else {
                    val ip = UsbTrackpadServer.getWifiIpAddress(context)
                    if (ip != null) {
                        Pair("ᯤ Download PC Companion at http://$ip:8080", TransportType.NONE)
                    } else {
                        Pair("ᯤ Wi-Fi Mode — Connect phone to Wi-Fi/Hotspot first", TransportType.NONE)
                    }
                }
            }
            Mode.BLUETOOTH -> {
                when (btManager.currentState) {
                    BluetoothHidManager.ConnectionState.CONNECTED ->
                        Pair("● BT Connected — ${btManager.connectedDeviceName ?: "device"}", TransportType.BLUETOOTH)
                    BluetoothHidManager.ConnectionState.CONNECTING ->
                        Pair("Connecting to ${btManager.connectedDeviceName ?: "device"}...", TransportType.BLUETOOTH)
                    BluetoothHidManager.ConnectionState.WAITING_FOR_HOST ->
                        Pair("ᛒ BT Mode — Pair via PC Bluetooth settings", TransportType.NONE)
                    BluetoothHidManager.ConnectionState.INITIALIZING ->
                        Pair("Initializing Bluetooth...", TransportType.NONE)
                    BluetoothHidManager.ConnectionState.DISCONNECTED ->
                        Pair("Bluetooth Disconnected", TransportType.NONE)
                }
            }
            Mode.AUTO -> {
                if (usbConnected) {
                    if (isWifi) {
                        Pair("● Wi-Fi Connected (Auto) — ${usbServer.connectedClientInfo ?: "PC Active"}", TransportType.WIFI)
                    } else {
                        Pair("● USB Connected (Auto) — ${usbServer.connectedClientInfo ?: "Host Active"}", TransportType.USB)
                    }
                } else if (btConnected) {
                    Pair("● BT Connected (Auto) — ${btManager.connectedDeviceName ?: "device"}", TransportType.BLUETOOTH)
                } else {
                    val ip = UsbTrackpadServer.getWifiIpAddress(context)
                    val urlHint = if (ip != null) "http://$ip:8080" else "USB/BT"
                    Pair("● Auto Mode — Download Companion at $urlHint", TransportType.NONE)
                }
            }
        }

        statusListener?.onStatusChanged(statusText, activeTransport, currentMode)
    }

    /**
     * Send keyboard text via the currently active transport.
     *
     * - USB/Wi-Fi: Sends text as [0xAA, len_high, len_low, ...utf8...] via TCP
     * - Bluetooth: Converts each character to HID keycodes and sends keyboard reports
     *
     * @param text  The text string to transmit
     * @return true if transmitted successfully
     */
    fun sendKeyboardText(text: String): Boolean {
        if (text.isEmpty()) return false

        return when (currentMode) {
            Mode.USB, Mode.WIFI -> {
                usbServer.sendKeyboardText(text)
            }
            Mode.BLUETOOTH -> {
                sendTextViaBtHid(text)
            }
            Mode.AUTO -> {
                if (usbServer.isConnected()) {
                    usbServer.sendKeyboardText(text)
                } else {
                    sendTextViaBtHid(text)
                }
            }
        }
    }

    /**
     * Send a special key via the currently active transport.
     *
     * @param keyCode    VectraTouch special key code (0x01=Enter, 0x02=Backspace, etc.)
     * @param metaFlags  Modifier flags (0x01=Shift, 0x02=Ctrl, 0x04=Alt)
     * @return true if transmitted successfully
     */
    fun sendSpecialKey(keyCode: Int, metaFlags: Int = 0): Boolean {
        return when (currentMode) {
            Mode.USB, Mode.WIFI -> {
                usbServer.sendSpecialKey(keyCode, metaFlags)
            }
            Mode.BLUETOOTH -> {
                sendSpecialKeyViaBtHid(keyCode, metaFlags)
            }
            Mode.AUTO -> {
                if (usbServer.isConnected()) {
                    usbServer.sendSpecialKey(keyCode, metaFlags)
                } else {
                    sendSpecialKeyViaBtHid(keyCode, metaFlags)
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Bluetooth HID Keyboard Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * USB HID keycode mapping for printable ASCII characters.
     * Each entry is Pair(hidKeyCode, requiresShift).
     */
    private val charToHidKeyCode: Map<Char, Pair<Int, Boolean>> by lazy {
        buildMap {
            // Letters a-z (HID 0x04–0x1D)
            for (c in 'a'..'z') {
                put(c, Pair(0x04 + (c - 'a'), false))
            }
            for (c in 'A'..'Z') {
                put(c, Pair(0x04 + (c - 'A'), true))
            }
            // Digits 1-9 (HID 0x1E–0x26), 0 = 0x27
            for (c in '1'..'9') {
                put(c, Pair(0x1E + (c - '1'), false))
            }
            put('0', Pair(0x27, false))
            // Shifted digit symbols
            put('!', Pair(0x1E, true))
            put('@', Pair(0x1F, true))
            put('#', Pair(0x20, true))
            put('$', Pair(0x21, true))
            put('%', Pair(0x22, true))
            put('^', Pair(0x23, true))
            put('&', Pair(0x24, true))
            put('*', Pair(0x25, true))
            put('(', Pair(0x26, true))
            put(')', Pair(0x27, true))
            // Special chars
            put('\n', Pair(0x28, false))  // Enter
            put('\t', Pair(0x2B, false))  // Tab
            put(' ', Pair(0x2C, false))   // Space
            put('-', Pair(0x2D, false))
            put('_', Pair(0x2D, true))
            put('=', Pair(0x2E, false))
            put('+', Pair(0x2E, true))
            put('[', Pair(0x2F, false))
            put('{', Pair(0x2F, true))
            put(']', Pair(0x30, false))
            put('}', Pair(0x30, true))
            put('\\', Pair(0x31, false))
            put('|', Pair(0x31, true))
            put(';', Pair(0x33, false))
            put(':', Pair(0x33, true))
            put('\'', Pair(0x34, false))
            put('"', Pair(0x34, true))
            put('`', Pair(0x35, false))
            put('~', Pair(0x35, true))
            put(',', Pair(0x36, false))
            put('<', Pair(0x36, true))
            put('.', Pair(0x37, false))
            put('>', Pair(0x37, true))
            put('/', Pair(0x38, false))
            put('?', Pair(0x38, true))
        }
    }

    /** VectraTouch special key code → HID keycode mapping */
    private val specialKeyToHid = mapOf(
        0x01 to 0x28,  // Enter
        0x02 to 0x2A,  // Backspace
        0x03 to 0x2B,  // Tab
        0x04 to 0x29,  // Escape
        0x05 to 0x4C,  // Delete
        0x06 to 0x52,  // Arrow Up
        0x07 to 0x51,  // Arrow Down
        0x08 to 0x50,  // Arrow Left
        0x09 to 0x4F,  // Arrow Right
        0x0A to 0x4A,  // Home
        0x0B to 0x4D   // End
    )

    /**
     * Convert a text string to HID keyboard reports and send via Bluetooth.
     * Each character is sent as a key-down + key-up pair.
     */
    private fun sendTextViaBtHid(text: String): Boolean {
        var success = true
        for (char in text) {
            val mapping = charToHidKeyCode[char]
            if (mapping != null) {
                val (hidKey, shift) = mapping
                val modifiers = if (shift) 0x02 else 0x00  // Left Shift = bit 1
                success = btManager.sendKeyboardReport(modifiers, intArrayOf(hidKey)) && success
                success = btManager.sendKeyRelease() && success
            }
        }
        return success
    }

    /**
     * Convert a VectraTouch special key code to a HID keycode and send via Bluetooth.
     */
    private fun sendSpecialKeyViaBtHid(keyCode: Int, metaFlags: Int): Boolean {
        val hidKey = specialKeyToHid[keyCode] ?: return false

        // Convert VectraTouch meta flags to HID modifier bitmask
        var modifiers = 0
        if (metaFlags and 0x01 != 0) modifiers = modifiers or 0x02  // Shift → Left Shift
        if (metaFlags and 0x02 != 0) modifiers = modifiers or 0x01  // Ctrl → Left Ctrl
        if (metaFlags and 0x04 != 0) modifiers = modifiers or 0x04  // Alt → Left Alt

        val press = btManager.sendKeyboardReport(modifiers, intArrayOf(hidKey))
        val release = btManager.sendKeyRelease()
        return press && release
    }

    /**
     * Stop and clean up resources.
     */
    fun destroy() {
        Log.i(TAG, "Stopping TrackpadTransportManager")
        usbServer.stop()
        btManager.destroy()
    }
}
