package com.example.vectratrackpad

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * MainActivity
 * ═══════════════════════════════════════════════════════════════════════════════
 * Entry point for VectraTrackPad. Responsible for:
 *
 *   1. Runtime Bluetooth permission requests (BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)
 *   2. Immersive fullscreen mode (hides status bar, nav bar, keeps screen on)
 *   3. Instantiating BluetoothHidManager and TrackpadView
 *   4. Wiring the HID manager to the trackpad surface
 *   5. Lifecycle management (cleanup on destroy)
 *   6. Dynamic screen orientation rotation
 *
 * Uses plain android.app.Activity to avoid any AndroidX/AppCompat theme conflicts.
 * The Activity uses a single TrackpadView as its content — no XML layout needed.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "VectraMain"
        private const val PERMISSION_REQUEST_CODE = 1001
        const val PREFS_NAME = "VectraTrackpadPrefs"
        const val PREF_KEY_ORIENTATION = "phone_orientation"
        const val PREF_KEY_SENSITIVITY = "mouse_sensitivity"
    }

    private lateinit var trackpadView: TrackpadView
    private lateinit var hidManager: BluetoothHidManager
    private lateinit var transportManager: TrackpadTransportManager

    // ════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "onCreate — VectraTrackPad starting")

        // ── 0. Permit network operations across threads ─────────────────
        try {
            val policy = android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
            android.os.StrictMode.setThreadPolicy(policy)
        } catch (_: Exception) {}

        // ── 1. Apply saved orientation preference ──────────────────────
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedOrientation = prefs.getInt(PREF_KEY_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        try {
            requestedOrientation = savedOrientation
        } catch (e: Exception) {
            Log.w(TAG, "Could not set orientation: ${e.message}")
        }

        // ── 2. Keep screen awake and use full display into cutout ─────
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ── 3. Initialize components ───────────────────────────────────
        hidManager = BluetoothHidManager(this)
        transportManager = TrackpadTransportManager(this, hidManager)

        trackpadView = TrackpadView(this)
        trackpadView.hidManager = hidManager
        trackpadView.transportManager = transportManager

        // Wire orientation change callback from Settings panel
        trackpadView.orientationChangeListener = { orientation ->
            try {
                requestedOrientation = orientation
                prefs.edit().putInt(PREF_KEY_ORIENTATION, orientation).apply()
                enterImmersiveMode()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply orientation $orientation", e)
            }
        }

        // ── 4. Set the trackpad as the sole content view ───────────────
        setContentView(trackpadView)

        Log.i(TAG, "TrackpadView set as content view")

        // ── 5. Immersive fullscreen (called after contentView is attached) ─
        enterImmersiveMode()

        // ── 6. Start transport manager (USB Server + Bluetooth listener)
        transportManager.start()

        // ── 7. Request permissions ─────────────────────────────────────
        checkAndRequestPermissions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        // Re-enter immersive mode when returning to the app
        enterImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersiveMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transportManager.destroy()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Permission Handling
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Check if Bluetooth permissions are already granted.
     * If not, request them via requestPermissions().
     *
     * API 31+ requires BLUETOOTH_CONNECT and BLUETOOTH_ADVERTISE.
     * API 28-30 uses the legacy BLUETOOTH/BLUETOOTH_ADMIN (declared in manifest,
     * granted at install time — no runtime request needed).
     */
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ — granular Bluetooth permissions
            val needed = mutableListOf<String>()

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }

            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (needed.isNotEmpty()) {
                Log.i(TAG, "Requesting permissions: $needed")
                requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
            } else {
                Log.i(TAG, "All Bluetooth permissions already granted")
                initializeBluetooth()
            }
        } else {
            // API 28-30: Legacy permissions are install-time — proceed directly
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} — legacy permissions, proceeding")
            initializeBluetooth()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                Log.i(TAG, "All Bluetooth permissions granted")
                initializeBluetooth()
            } else {
                Log.w(TAG, "Some Bluetooth permissions denied")
                trackpadView.connectionStatusText = "⚠ Bluetooth permissions denied"
                Toast.makeText(
                    this,
                    "Bluetooth permissions are required for VectraTrackPad to function",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Initialize the Bluetooth HID manager after permissions are confirmed.
     */
    private fun initializeBluetooth() {
        val success = hidManager.initialize()

        if (!success) {
            trackpadView.connectionStatusText = "⚠ Bluetooth HID not supported on this device"
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Immersive Fullscreen
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enter sticky immersive fullscreen mode.
     * Hides both the status bar and navigation bar.
     * Uses WindowInsetsControllerCompat via WindowCompat for compatibility and safety.
     */
    private fun enterImmersiveMode() {
        try {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set immersive mode via WindowCompat, using fallback", e)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
}