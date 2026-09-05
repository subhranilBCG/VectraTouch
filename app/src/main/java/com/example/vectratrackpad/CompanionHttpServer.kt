package com.example.vectratrackpad

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CompanionHttpServer
 * ═══════════════════════════════════════════════════════════════════════════════
 * Embedded HTTP Web Server running directly on the Android device (default port 8080).
 *
 * Serves an offline web page and downloadable PC Companion scripts directly from
 * APK assets (`app/src/main/assets/`) over local Wi-Fi, Hotspot, or USB tethering.
 *
 * NO INTERNET CONNECTION REQUIRED.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class CompanionHttpServer(
    private val context: Context,
    val httpPort: Int = DEFAULT_HTTP_PORT
) {

    companion object {
        private const val TAG = "VectraHttpServer"
        const val DEFAULT_HTTP_PORT = 8080
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Synchronized
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        serverThread = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("0.0.0.0", httpPort))
                serverSocket = server
                Log.i(TAG, "Companion HTTP Web Server listening on http://0.0.0.0:$httpPort")

                while (isRunning.get() && !server.isClosed) {
                    val client = server.accept()
                    Thread({
                        handleRequest(client)
                    }, "VectraHttpWorker").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "HTTP Server error: ${e.message}")
                }
            }
        }, "VectraHttpServerThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleRequest(client: Socket) {
        try {
            client.soTimeout = 5000
            val input = client.getInputStream().bufferedReader()
            val output = client.getOutputStream()

            val requestLine = input.readLine() ?: return
            Log.d(TAG, "HTTP Request: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                send405(output)
                return
            }

            val path = parts[1].substringBefore("?")

            when {
                path == "/" || path == "/index.html" -> {
                    serveAsset(output, "index.html", "text/html; charset=utf-8", isDownload = false)
                }
                path == "/download/VectraCompanion.cmd" -> {
                    serveAsset(output, "VectraCompanion.cmd", "application/octet-stream", isDownload = true)
                }
                path == "/download/VectraCompanion.ps1" -> {
                    serveAsset(output, "VectraCompanion.ps1", "application/x-powershell", isDownload = true)
                }
                path == "/download/VectraCompanion.py" -> {
                    serveAsset(output, "VectraCompanion.py", "text/x-python", isDownload = true)
                }
                else -> {
                    send404(output)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client request handling error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun serveAsset(output: OutputStream, assetName: String, contentType: String, isDownload: Boolean) {
        try {
            val assetStream = context.assets.open(assetName)
            val bytes = assetStream.readBytes()
            assetStream.close()

            val headers = StringBuilder()
            headers.append("HTTP/1.1 200 OK\r\n")
            headers.append("Content-Type: $contentType\r\n")
            headers.append("Content-Length: ${bytes.size}\r\n")
            headers.append("Connection: close\r\n")
            if (isDownload) {
                headers.append("Content-Disposition: attachment; filename=\"$assetName\"\r\n")
            }
            headers.append("\r\n")

            output.write(headers.toString().toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to serve asset $assetName: ${e.message}")
            send404(output)
        }
    }

    private fun send404(output: OutputStream) {
        val body = "404 Not Found"
        val headers = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        try {
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(body.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun send405(output: OutputStream) {
        val body = "405 Method Not Allowed"
        val headers = "HTTP/1.1 405 Method Not Allowed\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n"
        try {
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(body.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverThread?.interrupt()
        serverThread = null
        serverSocket = null
    }
}
