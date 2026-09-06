package com.example.vectratrackpad

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * BluetoothHidManager
 * ═══════════════════════════════════════════════════════════════════════════════
 * Manages the Bluetooth HID Device profile lifecycle for VectraTrackPad.
 *
 * Responsibilities:
 *   1. Acquires the BluetoothHidDevice profile proxy from the system
 *   2. Registers an SDP record advertising a standard 3-button mouse with scroll
 *   3. Handles connection state changes (pairing, connecting, disconnecting)
 *   4. Exposes sendMouseReport() for the TrackpadView to dispatch HID input
 *   5. Provides observable connection state via ConnectionStateListener
 *
 * HID Report Format (4 bytes):
 *   Byte 0: Button bitmask  — Bit0=Left(0x01), Bit1=Right(0x02), Bit2=Middle(0x04)
 *   Byte 1: Relative X      — [-127, 127]
 *   Byte 2: Relative Y      — [-127, 127]
 *   Byte 3: Scroll wheel    — [-127, 127] (positive = up, negative = down)
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "VectraHidManager"

        /**
         * Combined USB HID Report Descriptor for a 3-button relative mouse with
         * vertical scroll wheel AND a standard 104-key keyboard.
         * Uses Report IDs to multiplex both devices over a single HID channel.
         *
         * Report ID 1 — Mouse (4 bytes):
         *   - 3 button bits + 5 bits padding
         *   - 8-bit relative X
         *   - 8-bit relative Y
         *   - 8-bit relative wheel
         *
         * Report ID 2 — Keyboard (8 bytes):
         *   - 8-bit modifier keys (Ctrl/Shift/Alt/GUI × Left/Right)
         *   - 8-bit reserved (0x00)
         *   - 6 × 8-bit key codes (simultaneous keys)
         */
        private val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            // ════════════════════════════════════════════════════════════
            // Report ID 1 — Mouse
            // ════════════════════════════════════════════════════════════
            0x05, 0x01,        // Usage Page (Generic Desktop Controls)
            0x09, 0x02,        // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), 0x01, //   Report ID (1)
            0x09, 0x01,        //   Usage (Pointer)
            0xA1.toByte(), 0x00, //   Collection (Physical)

            // ── Button fields (3 buttons) ──────────────────────────────
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (Button 1 — Left)
            0x29, 0x03,        //     Usage Maximum (Button 3 — Middle)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x95.toByte(), 0x03, //     Report Count (3)
            0x75, 0x01,        //     Report Size (1 bit)
            0x81.toByte(), 0x02, //     Input (Data, Variable, Absolute)

            // ── Padding (5 bits to fill the byte) ──────────────────────
            0x95.toByte(), 0x01, //     Report Count (1)
            0x75, 0x05,        //     Report Size (5 bits)
            0x81.toByte(), 0x01, //     Input (Constant) — padding

            // ── Movement + Scroll fields ───────────────────────────────
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x09, 0x38,        //     Usage (Wheel)
            0x15, 0x81.toByte(), //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8 bits)
            0x95.toByte(), 0x03, //     Report Count (3 — X, Y, Wheel)
            0x81.toByte(), 0x06, //     Input (Data, Variable, Relative)

            0xC0.toByte(),      //   End Collection (Physical)
            0xC0.toByte(),      // End Collection (Application)

            // ════════════════════════════════════════════════════════════
            // Report ID 2 — Keyboard
            // ════════════════════════════════════════════════════════════
            0x05, 0x01,        // Usage Page (Generic Desktop Controls)
            0x09, 0x06,        // Usage (Keyboard)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), 0x02, //   Report ID (2)

            // ── Modifier keys (8 bits) ─────────────────────────────────
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, 0xE0.toByte(), //   Usage Minimum (Left Control — 0xE0)
            0x29, 0xE7.toByte(), //   Usage Maximum (Right GUI — 0xE7)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1 bit)
            0x95.toByte(), 0x08, //   Report Count (8)
            0x81.toByte(), 0x02, //   Input (Data, Variable, Absolute)

            // ── Reserved byte ──────────────────────────────────────────
            0x95.toByte(), 0x01, //   Report Count (1)
            0x75, 0x08,        //   Report Size (8 bits)
            0x81.toByte(), 0x01, //   Input (Constant) — reserved padding

            // ── Key codes (6 simultaneous keys) ────────────────────────
            0x95.toByte(), 0x06, //   Report Count (6)
            0x75, 0x08,        //   Report Size (8 bits)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x65,        //   Logical Maximum (101)
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, 0x65,        //   Usage Maximum (101)
            0x81.toByte(), 0x00, //   Input (Data, Array)

            0xC0.toByte()       // End Collection (Application)
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public State & Listener
    // ════════════════════════════════════════════════════════════════════════

    /** Observable connection states for the UI layer. */
    enum class ConnectionState {
        INITIALIZING,
        DISCONNECTED,
        WAITING_FOR_HOST,
        CONNECTING,
        CONNECTED
    }

    /** Listener interface for connection state changes. */
    interface ConnectionStateListener {
        fun onConnectionStateChanged(state: ConnectionState, deviceName: String?)
    }

    var connectionStateListener: ConnectionStateListener? = null
        set(value) {
            field = value
            // Immediately emit current state to new listener
            value?.onConnectionStateChanged(currentState, connectedDeviceName)
        }

    var currentState: ConnectionState = ConnectionState.INITIALIZING
        private set(value) {
            field = value
            connectionStateListener?.onConnectionStateChanged(value, connectedDeviceName)
        }

    var connectedDeviceName: String? = null
        private set

    // ════════════════════════════════════════════════════════════════════════
    // Internal Bluetooth References
    // ════════════════════════════════════════════════════════════════════════

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? =
        bluetoothManager?.adapter

    /** The HID Device profile proxy — null until the system connects it. */
    private var hidDevice: BluetoothHidDevice? = null

    /** The currently connected host device (PC/Mac). */
    private var hostDevice: BluetoothDevice? = null

    /** Whether our SDP record is registered with the Bluetooth stack. */
    private var isAppRegistered = false

    /** Executor for HID callbacks — single thread to avoid race conditions. */
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    // ════════════════════════════════════════════════════════════════════════
    // Profile Proxy Listener
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Called by the system when the BluetoothHidDevice profile proxy
     * becomes available or is disconnected.
     */
    private val profileServiceListener = object : BluetoothProfile.ServiceListener {

        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return

            Log.i(TAG, "HID Device profile proxy connected")
            hidDevice = proxy as BluetoothHidDevice
            registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return

            Log.w(TAG, "HID Device profile proxy disconnected")
            hidDevice = null
            hostDevice = null
            isAppRegistered = false
            currentState = ConnectionState.DISCONNECTED
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HID Device Callback
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Handles HID-specific events from the Bluetooth stack:
     * app registration, host connections, and report requests.
     */
    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "onAppStatusChanged: registered=$registered, device=${pluggedDevice?.name}")
            isAppRegistered = registered

            if (registered) {
                currentState = ConnectionState.WAITING_FOR_HOST

                // If a device was already plugged in when we registered, use it
                if (pluggedDevice != null) {
                    hostDevice = pluggedDevice
                    connectedDeviceName = pluggedDevice.name ?: pluggedDevice.address
                    currentState = ConnectionState.CONNECTED
                }
            } else {
                currentState = ConnectionState.DISCONNECTED
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val deviceName = device?.name ?: device?.address ?: "Unknown"
            Log.i(TAG, "onConnectionStateChanged: device=$deviceName, state=$state")

            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    hostDevice = device
                    connectedDeviceName = deviceName
                    currentState = ConnectionState.CONNECTED
                    Log.i(TAG, "✓ Connected to: $deviceName")
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    currentState = ConnectionState.CONNECTING
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    hostDevice = null
                    connectedDeviceName = null
                    currentState = ConnectionState.WAITING_FOR_HOST
                    Log.i(TAG, "✗ Disconnected from: $deviceName")
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    // Transitional — no UI update needed
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            Log.d(TAG, "onGetReport: type=$type, id=$id, bufferSize=$bufferSize")
            // Respond with an empty report — the host may request this during init
            hidDevice?.replyReport(device, type, id, ByteArray(4))
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            Log.d(TAG, "onSetReport: type=$type, id=$id, data=${data?.contentToString()}")
            // Acknowledge — no action needed for a mouse
        }

        override fun onInterruptData(device: BluetoothDevice?, reportId: Byte, data: ByteArray?) {
            Log.d(TAG, "onInterruptData: reportId=$reportId")
            // Not expected for an input-only mouse device
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Initialize the HID manager. Call this after Bluetooth permissions
     * have been granted.
     *
     * Acquires the BluetoothHidDevice profile proxy from the system.
     * The actual SDP registration happens asynchronously in [profileServiceListener].
     */
    @Suppress("MissingPermission")
    fun initialize(): Boolean {
        try {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                Log.e(TAG, "BluetoothAdapter is null — device does not support Bluetooth")
                currentState = ConnectionState.DISCONNECTED
                return false
            }

            if (!adapter.isEnabled) {
                Log.e(TAG, "Bluetooth is not enabled")
                currentState = ConnectionState.DISCONNECTED
                return false
            }

            currentState = ConnectionState.INITIALIZING
            Log.i(TAG, "Requesting HID Device profile proxy...")

            val success = adapter.getProfileProxy(
                context,
                profileServiceListener,
                BluetoothProfile.HID_DEVICE
            )

            if (!success) {
                Log.e(TAG, "Failed to get HID Device profile proxy — device may not support HID Device role")
                currentState = ConnectionState.DISCONNECTED
            }

            return success
        } catch (e: Exception) {
            Log.e(TAG, "Exception during HID initialize", e)
            currentState = ConnectionState.DISCONNECTED
            return false
        }
    }

    /**
     * Send a 4-byte mouse HID report to the connected host.
     * Uses Report ID 1 (mouse) for the combined HID descriptor.
     *
     * @param buttons  Button bitmask: 0x01=Left, 0x02=Right, 0x04=Middle
     * @param dx       Relative X movement (clamped to [-127, 127])
     * @param dy       Relative Y movement (clamped to [-127, 127])
     * @param scroll   Scroll wheel delta (clamped to [-127, 127])
     * @return true if the report was sent successfully
     */
    @Suppress("MissingPermission")
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, scroll: Int): Boolean {
        val device = hostDevice ?: return false
        val hid = hidDevice ?: return false

        if (!isAppRegistered) return false

        // Pack the 4-byte HID report with clamped values
        val report = byteArrayOf(
            (buttons and 0x07).toByte(),                          // Byte 0: Buttons
            dx.coerceIn(-127, 127).toByte(),                      // Byte 1: X delta
            dy.coerceIn(-127, 127).toByte(),                      // Byte 2: Y delta
            scroll.coerceIn(-127, 127).toByte()                   // Byte 3: Scroll
        )

        return try {
            hid.sendReport(device, 1, report)  // Report ID 1 = Mouse
        } catch (e: Exception) {
            Log.e(TAG, "Exception during sendReport (mouse)", e)
            false
        }
    }

    /**
     * Send an 8-byte keyboard HID report to the connected host.
     * Uses Report ID 2 (keyboard) for the combined HID descriptor.
     *
     * @param modifiers  Modifier bitmask: Bit0=LCtrl, Bit1=LShift, Bit2=LAlt,
     *                   Bit3=LGUI, Bit4=RCtrl, Bit5=RShift, Bit6=RAlt, Bit7=RGUI
     * @param keyCodes   Array of up to 6 HID key codes (USB HID Usage Table)
     * @return true if the report was sent successfully
     */
    @Suppress("MissingPermission")
    fun sendKeyboardReport(modifiers: Int, keyCodes: IntArray): Boolean {
        val device = hostDevice ?: return false
        val hid = hidDevice ?: return false

        if (!isAppRegistered) return false

        // Pack the 8-byte HID keyboard report
        val report = ByteArray(8)
        report[0] = (modifiers and 0xFF).toByte()   // Modifier keys
        report[1] = 0x00                             // Reserved
        for (i in 0 until minOf(keyCodes.size, 6)) {
            report[2 + i] = (keyCodes[i] and 0xFF).toByte()
        }

        return try {
            hid.sendReport(device, 2, report)  // Report ID 2 = Keyboard
        } catch (e: Exception) {
            Log.e(TAG, "Exception during sendReport (keyboard)", e)
            false
        }
    }

    /**
     * Send a key release (all-zeros keyboard report) to the connected host.
     * Must be called after each key press to simulate key-up.
     */
    @Suppress("MissingPermission")
    fun sendKeyRelease(): Boolean {
        return sendKeyboardReport(0, IntArray(0))
    }

    /**
     * Clean up all Bluetooth resources. Call from Activity.onDestroy().
     */
    @Suppress("MissingPermission")
    fun destroy() {
        Log.i(TAG, "Destroying BluetoothHidManager")

        try {
            hidDevice?.unregisterApp()
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering HID app", e)
        }

        try {
            val adapter = bluetoothAdapter
            if (adapter != null && hidDevice != null) {
                adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing profile proxy", e)
        }

        hidDevice = null
        hostDevice = null
        isAppRegistered = false
        connectedDeviceName = null
        currentState = ConnectionState.DISCONNECTED

        callbackExecutor.shutdownNow()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internal Helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Registers our app with the Bluetooth stack using the mouse SDP record
     * and HID report descriptor.
     */
    @Suppress("MissingPermission")
    private fun registerHidApp() {
        val hid = hidDevice ?: run {
            Log.e(TAG, "Cannot register — HID proxy is null")
            return
        }

        // Build the SDP settings that describe our device to hosts
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "VectraTrackPad",                        // Device name
            "3-Finger Spatial Bluetooth Trackpad",   // Description
            "VectraTrackPad",                        // Provider
            BluetoothHidDevice.SUBCLASS1_MOUSE,      // Subclass: Mouse
            MOUSE_REPORT_DESCRIPTOR                  // HID Report Descriptor
        )

        // QoS settings — null uses system defaults (sufficient for mouse input)
        val qosOut = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,    // Token rate (bytes/sec)
            9,      // Token bucket size
            0,      // Peak bandwidth
            11250,  // Latency (μs) — ~11ms for responsive mouse
            BluetoothHidDeviceAppQosSettings.MAX
        )

        try {
            val success = hid.registerApp(
                sdpSettings,
                null,       // Incoming QoS — null for defaults
                qosOut,     // Outgoing QoS — optimized for low-latency mouse reports
                callbackExecutor,
                hidCallback
            )

            Log.i(TAG, "registerApp result: $success")

            if (!success) {
                Log.e(TAG, "Failed to register HID app — another app may hold the profile")
                currentState = ConnectionState.DISCONNECTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during registerApp", e)
            currentState = ConnectionState.DISCONNECTED
        }
    }
}
