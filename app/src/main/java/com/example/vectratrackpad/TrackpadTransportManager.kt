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
     * Stop and clean up resources.
     */
    fun destroy() {
        Log.i(TAG, "Stopping TrackpadTransportManager")
        usbServer.stop()
        btManager.destroy()
    }
}
