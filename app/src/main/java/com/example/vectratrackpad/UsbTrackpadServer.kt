package com.example.vectratrackpad

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UsbTrackpadServer
 * ═══════════════════════════════════════════════════════════════════════════════
 * Manages low-latency USB and Wi-Fi transmission for VectraTrackPad.
 *
 * Supports:
 *   1. Zero-Latency Socket Engine (Default, No-Root):
 *      Listens on TCP port 53824 (0.0.0.0). Accessible via:
 *        - USB cable (via ADB port-forwarding: `adb forward tcp:53824 tcp:53824`)
 *        - Local Wi-Fi network (direct connection to Phone IP on port 53824)
 *      Streams identical 4-byte HID reports [buttons, dx, dy, scroll] to the PC companion.
 *
 *   2. UDP Discovery Beacon:
 *      Broadcasts periodic beacons on UDP port 53826 so the PC companion can
 *      auto-discover the phone's Wi-Fi IP address without manual typing.
 *
 *   3. Direct USB HID Gadget (/dev/hidg0, Rooted Devices):
 *      Writes raw 4-byte reports directly into /dev/hidg0 when present.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class UsbTrackpadServer(
    private val context: android.content.Context,
    private val port: Int = DEFAULT_PORT
) {
    val httpServer = CompanionHttpServer(context)

    companion object {
        private const val TAG = "VectraUsbServer"
        const val DEFAULT_PORT = 53824
        const val DISCOVERY_PORT = 53826
        private const val HIDG0_PATH = "/dev/hidg0"

        /**
         * Get the current IPv4 address of the phone on the local Wi-Fi / Hotspot network.
         */
        fun getWifiIpAddress(context: android.content.Context): String? {
            try {
                val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return String.format(
                        java.util.Locale.US,
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            } catch (_: Exception) {}

            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isUp && !iface.isLoopback) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                val host = addr.hostAddress
                                if (host != null && host != "127.0.0.1") {
                                    return host
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return null
        }
    }

    /** True if the connected client is communicating over Wi-Fi (non-localhost IP). */
    val isWifiClient: Boolean
        get() {
            val ip = connectedClientInfo ?: return false
            return ip != "127.0.0.1" && ip != "localhost" && !ip.startsWith("127.")
        }

    enum class UsbState {
        STOPPED,
        LISTENING,
        CONNECTED
    }

    interface UsbStateListener {
        fun onUsbStateChanged(state: UsbState, clientInfo: String?)
    }

    var listener: UsbStateListener? = null
        set(value) {
            field = value
            value?.onUsbStateChanged(currentState, connectedClientInfo)
        }

    var currentState: UsbState = UsbState.STOPPED
        private set(value) {
            field = value
            listener?.onUsbStateChanged(value, connectedClientInfo)
        }

    var connectedClientInfo: String? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    private var clientOutputStream: OutputStream? = null

    // Non-blocking report queue to ensure zero UI thread latency & no NetworkOnMainThreadException
    private val sendQueue = LinkedBlockingQueue<ByteArray>(256)
    private var sendThread: Thread? = null

    // Direct /dev/hidg0 file output stream if available (rooted devices)
    private var hidg0Stream: FileOutputStream? = null
    private var isHidg0Available = false

    private var acceptThread: Thread? = null
    private var discoveryThread: Thread? = null

    /**
     * Start the USB socket server and dedicated transmission thread.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        // Start HTTP companion download server
        httpServer.start()

        // Check if root ConfigFS gadget is available
        checkHidg0()

        // 1. Dedicated high-priority send thread
        sendQueue.clear()
        sendThread = Thread({
            while (isRunning.get()) {
                try {
                    val report = sendQueue.take()

                    // Dispatch via TCP socket stream
                    val stream = clientOutputStream
                    if (stream != null) {
                        try {
                            stream.write(report)

                            // Batch-drain any pending rapid packets (e.g. 120Hz gestures)
                            var drained = 0
                            while (drained < 32) {
                                val next = sendQueue.poll() ?: break
                                stream.write(next)
                                drained++
                            }
                            stream.flush()
                        } catch (e: Exception) {
                            Log.w(TAG, "USB client write error: ${e.javaClass.simpleName} - ${e.message}")
                            handleClientDisconnect(stream)
                        }
                    }

                    // Dispatch via direct /dev/hidg0 gadget if available
                    hidg0Stream?.let { hidg ->
                        try {
                            hidg.write(report)
                            hidg.flush()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error writing to /dev/hidg0: ${e.message}")
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Send thread unexpected error", e)
                }
            }
        }, "VectraUsbSendThread").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }

        // 2. Accept thread for incoming client connections
        acceptThread = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = server
                Log.i(TAG, "USB server listening on port $port")
                currentState = UsbState.LISTENING

                while (isRunning.get() && !server.isClosed) {
                    val client = server.accept()
                    // Disable Nagle's algorithm for instant packet dispatch
                    client.tcpNoDelay = true
                    client.sendBufferSize = 1024

                    val clientDesc = client.inetAddress?.hostAddress ?: "USB Host"
                    Log.i(TAG, "USB Host connected from $clientDesc")

                    var outStream: OutputStream? = null
                    synchronized(this) {
                        try {
                            activeClientSocket?.close()
                        } catch (_: Exception) {}

                        activeClientSocket = client
                        outStream = client.getOutputStream()
                        clientOutputStream = outStream
                        connectedClientInfo = clientDesc
                        sendQueue.clear()
                        currentState = UsbState.CONNECTED
                    }

                    // Send initial handshake no-op report [0, 0, 0, 0] to confirm link
                    try {
                        outStream?.write(byteArrayOf(0, 0, 0, 0))
                        outStream?.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial handshake write failed", e)
                    }

                    // Monitor client connection for disconnect in daemon thread
                    Thread({
                        monitorClient(client)
                    }, "VectraClientMonitor").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "USB Server socket error: ${e.message}")
                }
            } finally {
                handleClientDisconnect()
            }
        }, "VectraUsbAcceptThread").apply {
            isDaemon = true
            start()
        }

        // 3. UDP Discovery Beacon thread for Wi-Fi auto-detection
        discoveryThread = Thread({
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val beaconData = "VECTRA_BEACON:$port".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(beaconData, beaconData.size, broadcastAddr, DISCOVERY_PORT)

                while (isRunning.get()) {
                    try {
                        socket.send(packet)
                    } catch (_: Exception) {}
                    try {
                        Thread.sleep(1500)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Discovery beacon stopped: ${e.message}")
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }, "VectraWifiDiscovery").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Continuously reads from socket to detect when host drops connection.
     */
    private fun monitorClient(client: Socket) {
        try {
            val input = client.getInputStream()
            val buf = ByteArray(64)
            while (isRunning.get() && !client.isClosed) {
                val read = input.read(buf)
                if (read == -1) break
            }
        } catch (_: Exception) {
        } finally {
            synchronized(this) {
                if (activeClientSocket == client) {
                    Log.i(TAG, "USB Host disconnected")
                    handleClientDisconnect()
                }
            }
        }
    }

    private fun handleClientDisconnect(failedStream: OutputStream? = null) {
        synchronized(this) {
            // Ignore failure callbacks from old/stale streams that have already been replaced
            if (failedStream != null && failedStream !== clientOutputStream) {
                return
            }
            try { activeClientSocket?.close() } catch (_: Exception) {}
            activeClientSocket = null
            clientOutputStream = null
            connectedClientInfo = null
            sendQueue.clear()
            if (isRunning.get()) {
                currentState = UsbState.LISTENING
            } else {
                currentState = UsbState.STOPPED
            }
        }
    }

    /**
     * Probe for /dev/hidg0 character device.
     */
    private fun checkHidg0() {
        try {
            val file = File(HIDG0_PATH)
            if (file.exists() && file.canWrite()) {
                hidg0Stream = FileOutputStream(file)
                isHidg0Available = true
                Log.i(TAG, "Direct USB HID Gadget found at $HIDG0_PATH!")
            }
        } catch (e: Exception) {
            isHidg0Available = false
            hidg0Stream = null
        }
    }

    /**
     * Send a 4-byte mouse report over USB.
     * Enqueues the packet into the high-priority background send thread
     * with zero blocking on the UI thread.
     *
     * @param buttons  Button bitmask: 0x01=Left, 0x02=Right, 0x04=Middle
     * @param dx       Relative X movement (clamped to [-127, 127])
     * @param dy       Relative Y movement (clamped to [-127, 127])
     * @param scroll   Scroll wheel delta (clamped to [-127, 127])
     * @return true if enqueued successfully
     */
    fun sendMouseReport(buttons: Int, dx: Int, dy: Int, scroll: Int): Boolean {
        if (!isConnected()) return false

        val report = byteArrayOf(
            (buttons and 0x07).toByte(),
            dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(),
            scroll.coerceIn(-127, 127).toByte()
        )

        // Non-blocking offer — if queue full, drops oldest to preserve real-time response
        if (!sendQueue.offer(report)) {
            sendQueue.poll()
            sendQueue.offer(report)
        }
        return true
    }

    fun isConnected(): Boolean {
        val s = activeClientSocket
        return (s != null && s.isConnected && !s.isClosed) || isHidg0Available
    }

    /**
     * Stop server and release resources.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        httpServer.stop()
        sendThread?.interrupt()
        sendThread = null
        discoveryThread?.interrupt()
        discoveryThread = null
        sendQueue.clear()

        try { activeClientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { hidg0Stream?.close() } catch (_: Exception) {}

        activeClientSocket = null
        clientOutputStream = null
        serverSocket = null
        hidg0Stream = null
        connectedClientInfo = null
        currentState = UsbState.STOPPED
    }
}
