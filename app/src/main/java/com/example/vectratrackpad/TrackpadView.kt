package com.example.vectratrackpad

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TrackpadView
 * ═══════════════════════════════════════════════════════════════════════════════
 * A full-screen custom View that acts as a multi-touch trackpad surface.
 *
 * FEATURES:
 *   • Full Uninterrupted Canvas: Entire screen is an edge-to-edge trackpad surface.
 *   • 3-Finger Dynamic Spatial Sorting:
 *       - Index  (leftmost X)  → Left Click / Left Drag (0x01)
 *       - Anchor (median X)    → Cursor Movement (dx, dy)
 *       - Ring   (rightmost X) → Right Click / Right Drag (0x02)
 *   • Laptop-Style Gestures:
 *       - 1-finger tap → Left Click (0x01)
 *       - 2-finger tap → Right Click (0x02)
 *       - 3-finger tap → Middle Click (0x04)
 *       - 2-finger drag / 3-finger flank hold → Smooth Scroll Wheel
 *   • Integrated Top Bar:
 *       - [ AUTO | BT | USB ] Transport Mode Switcher
 *       - [ ⚙ ] In-canvas Glassmorphic Settings Dialog
 *   • Settings Controls:
 *       - Adjustable Mouse Sensitivity (0.4x - 3.6x stepper & presets)
 *       - Phone Orientation Rotation (Landscape, Rev Landscape, Portrait, Auto)
 *       - SharedPreferences persistence
 * ═══════════════════════════════════════════════════════════════════════════════
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ════════════════════════════════════════════════════════════════════════
    // Constants & Configuration
    // ════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "VectraTrackpad"

        // ── Gesture tuning ─────────────────────────────────────────────
        const val DEFAULT_CURSOR_SENSITIVITY = 1.6f
        private const val SCROLL_SENSITIVITY = 0.35f
        private const val SCROLL_DEAD_ZONE = 4f
        private const val TAP_TIMEOUT_MS = 280L
        private const val TAP_SLOP_DP = 12f

        // ── Visual constants ───────────────────────────────────────────
        private const val TOUCH_POINTER_RADIUS_DP = 18f
        private const val TOUCH_RING_RADIUS_DP = 28f
        private const val GRID_DOT_SPACING_DP = 40f
        private const val GRID_DOT_RADIUS_DP = 1.2f

        // ── Colors (Matte Black & Cyber Green Theme) ───────────────────
        private const val COLOR_MATTE_BLACK = 0xFF0A0D0B.toInt()    // Deep matte obsidian black
        private const val COLOR_GREEN = 0xFF00E676.toInt()          // Vibrant Neon / Cyber Green
        private const val COLOR_LIGHT_GREEN = 0xFFE8FCE8.toInt()    // Crisp Mint / Light Green
        private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
        private const val COLOR_DIM_GRID = 0xFF141D16.toInt()       // Subtle matte dark green grid
        private const val COLOR_BORDER_GREEN = 0x8000E676.toInt()   // 50% Alpha Green Stroke

        // Aliases for compatibility
        private const val COLOR_DEEP_SLATE = COLOR_MATTE_BLACK
        private const val COLOR_TEAL = COLOR_GREEN
        private const val COLOR_LIGHT_AQUA = COLOR_LIGHT_GREEN
        private const val COLOR_BORDER_TEAL = COLOR_BORDER_GREEN
    }

    /** Active gesture state machine states. */
    private enum class GestureState {
        IDLE,
        CURSOR_MOVE,
        LEFT_HELD,
        RIGHT_HELD,
        SCROLL_MODE
    }

    /** Role assigned to a finger via spatial X-sorting. */
    private enum class FingerRole {
        ANCHOR,       // Cursor driver (median X)
        INDEX,        // Left click (X < anchor)
        RING          // Right click (X > anchor)
    }

    /** Tracks a single touch pointer's state across its lifecycle. */
    private data class TouchPoint(
        val id: Int,
        var currentX: Float,
        var currentY: Float,
        val startX: Float,
        val startY: Float,
        var prevX: Float,
        var prevY: Float,
        val downTime: Long,
        var hasSurpassedSlop: Boolean = false,
        var role: FingerRole = FingerRole.ANCHOR
    )

    // ════════════════════════════════════════════════════════════════════════
    // External Dependencies & Preferences
    // ════════════════════════════════════════════════════════════════════════

    /** Reference to the transport coordinator (manages both Bluetooth and USB). */
    var transportManager: TrackpadTransportManager? = null
        set(value) {
            field = value
            value?.statusListener = object : TrackpadTransportManager.StatusListener {
                override fun onStatusChanged(
                    statusText: String,
                    activeTransport: TrackpadTransportManager.TransportType,
                    mode: TrackpadTransportManager.Mode
                ) {
                    post {
                        connectionStatusText = statusText
                        currentActiveTransport = activeTransport
                        currentMode = mode
                        invalidate()
                    }
                }
            }
        }

    /** Reference to the HID manager — set by MainActivity after construction. */
    var hidManager: BluetoothHidManager? = null

    /** Current transport mode (AUTO, BLUETOOTH, USB). */
    var currentMode: TrackpadTransportManager.Mode = TrackpadTransportManager.Mode.AUTO
        set(value) {
            field = value
            invalidate()
        }

    /** Currently active physical transport (NONE, BLUETOOTH, USB). */
    var currentActiveTransport: TrackpadTransportManager.TransportType =
        TrackpadTransportManager.TransportType.NONE
        set(value) {
            field = value
            invalidate()
        }

    /** Connection status text displayed at the top of the surface. */
    var connectionStatusText: String = "Initializing..."
        set(value) {
            field = value
            invalidate()
        }

    /** Orientation change listener wired to MainActivity. */
    var orientationChangeListener: ((Int) -> Unit)? = null

    /** Dynamic cursor sensitivity multiplier (persisted in SharedPreferences). */
    var cursorSensitivity: Float = DEFAULT_CURSOR_SENSITIVITY
        set(value) {
            field = (roundToInt10(value)).coerceIn(0.4f, 3.6f)
            savePreferences()
            invalidate()
        }

    /** Current screen orientation mode. */
    var currentOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        private set

    /** Whether the Settings Modal Dialog is currently displayed. */
    var isSettingsOpen: Boolean = false
        private set(value) {
            field = value
            invalidate()
        }

    /** Whether the Keyboard Mode overlay is currently active. */
    var isKeyboardModeActive: Boolean = false
        set(value) {
            field = value
            keyboardModeListener?.invoke(value)
            invalidate()
        }

    /** Callback to MainActivity to show/hide the soft keyboard IME. */
    var keyboardModeListener: ((Boolean) -> Unit)? = null

    init {
        try {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            cursorSensitivity = prefs.getFloat(MainActivity.PREF_KEY_SENSITIVITY, DEFAULT_CURSOR_SENSITIVITY)
            currentOrientation = prefs.getInt(MainActivity.PREF_KEY_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        } catch (_: Exception) {}
    }

    private fun savePreferences() {
        try {
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(MainActivity.PREF_KEY_SENSITIVITY, cursorSensitivity)
                .putInt(MainActivity.PREF_KEY_ORIENTATION, currentOrientation)
                .apply()
        } catch (_: Exception) {}
    }

    private fun roundToInt10(value: Float): Float {
        return (value * 10f).roundToInt() / 10f
    }

    /**
     * Dispatch 4-byte mouse report via transportManager if set,
     * or fallback to hidManager directly.
     */
    private fun sendReport(buttons: Int, dx: Int, dy: Int, scroll: Int): Boolean {
        val tm = transportManager
        return if (tm != null) {
            tm.sendMouseReport(buttons, dx, dy, scroll)
        } else {
            hidManager?.sendMouseReport(buttons, dx, dy, scroll) ?: false
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internal State
    // ════════════════════════════════════════════════════════════════════════

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapSlopPx = TAP_SLOP_DP * density

    /** All currently active touch pointers, keyed by pointer ID. */
    private val activePointers = SparseArray<TouchPoint>()

    /** Current gesture state. */
    private var gestureState = GestureState.IDLE

    /** Current button bitmask being held down. */
    private var currentButtons = 0

    /** Scroll accumulator for sub-pixel precision. */
    private var scrollAccumulator = 0f

    /** Timestamp of last multi-finger tap to prevent duplicate triggers. */
    private var lastMultiFingerTapTime = 0L

    // ════════════════════════════════════════════════════════════════════════
    // Layout Bounds
    // ════════════════════════════════════════════════════════════════════════

    // ── Top Bar Bounds ──────────────────────────────────────────────────
    private val modePillRect = RectF()
    private val modeTabRects = Array(4) { RectF() }
    private val settingsBtnRect = RectF()

    // ── Settings Modal Dialog Bounds ────────────────────────────────────
    private val settingsDialogRect = RectF()
    private val settingsCloseBtnRect = RectF()
    private val settingsDoneBtnRect = RectF()
    private val sensMinusBtnRect = RectF()
    private val sensPlusBtnRect = RectF()
    private val sensProgressBarRect = RectF()
    private val sensPresetRects = Array(4) { RectF() }
    private val orientOptionRects = Array(4) { RectF() }
    private val keyboardModeBtnRect = RectF()

    // ── Keyboard Mode Indicator Bar Bounds ─────────────────────────────
    private val keyboardBarRect = RectF()
    private val keyboardCloseBtnRect = RectF()

    // ════════════════════════════════════════════════════════════════════════
    // Paint Objects (pre-allocated)
    // ════════════════════════════════════════════════════════════════════════

    private val paintBackground = Paint().apply {
        color = COLOR_MATTE_BLACK
        style = Paint.Style.FILL
    }

    private val paintGridDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_DIM_GRID
        style = Paint.Style.FILL
    }

    private val paintTouchPointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 210
    }

    private val paintTouchRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        alpha = 110
    }

    private val paintStatusText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        alpha = 190
    }

    private val paintRoleLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        alpha = 210
    }

    private val paintStatePill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 45
    }

    private val paintStatePillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        alpha = 230
    }

    // ── Mode Switcher & Settings Top Bar Paints ─────────────────────────
    private val paintPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0F1511.toInt()
        style = Paint.Style.FILL
    }

    private val paintPillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BORDER_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val paintTabActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 75
    }

    private val paintTabActiveStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        alpha = 220
    }

    private val paintTabTextActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val paintTabTextInactive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF95ABA0.toInt()
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        alpha = 160
    }

    private val paintTransportDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val paintSettingsIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 13.5f * density
        textAlign = Paint.Align.CENTER
    }

    // ── Settings Dialog Paints ──────────────────────────────────────────
    private val paintModalBackdrop = Paint().apply {
        color = 0xD9050906.toInt() // 85% deep matte black overlay
        style = Paint.Style.FILL
    }

    private val paintDialogCard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D130F.toInt()
        style = Paint.Style.FILL
    }

    private val paintDialogBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        alpha = 190
    }

    private val paintDialogTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 13.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val paintDialogClose = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val paintSectionLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        textSize = 10f * density
        isFakeBoldText = true
        letterSpacing = 0.06f
        textAlign = Paint.Align.LEFT
    }

    private val paintValueBadge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 12f * density
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }

    private val paintDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF17291D.toInt()
        strokeWidth = 1f * density
    }

    private val paintStepperBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF121B15.toInt()
        style = Paint.Style.FILL
    }

    private val paintStepperBtnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BORDER_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
    }

    private val paintStepperText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val paintProgressTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101712.toInt()
        style = Paint.Style.FILL
    }

    private val paintProgressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 230
    }

    private val paintDoneBtn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 50
    }

    private val paintDoneBtnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        alpha = 180
    }

    private val paintDoneText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // ── Dim Background Gesture Instructions Paints ───────────────────────
    private val paintInstHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        textSize = 9.5f * density
        alpha = 65
        isFakeBoldText = true
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }

    private val paintInstCardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 10
    }

    private val paintInstCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        alpha = 32
    }

    private val paintInstTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        textSize = 10f * density
        alpha = 110
        isFakeBoldText = true
        letterSpacing = 0.05f
        textAlign = Paint.Align.LEFT
    }

    private val paintInstText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8AA894.toInt()
        textSize = 9f * density
        alpha = 95
        textAlign = Paint.Align.LEFT
    }

    private val paintInstDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 95
    }

    private val paintFingerCapsule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.FILL
        alpha = 70
    }

    private val paintFingerCapsuleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        alpha = 160
    }

    private val paintFingerRipple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        alpha = 120
    }

    private val paintFingerTag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LIGHT_GREEN
        textSize = 7.5f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        alpha = 180
    }

    // ════════════════════════════════════════════════════════════════════════
    // Layout
    // ════════════════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wf = w.toFloat()
        val hf = h.toFloat()
        computeTopBar(wf, hf)
        computeSettingsLayout(wf, hf)
    }

    private fun computeTopBar(w: Float, h: Float) {
        val pillWidth = min(w * 0.72f, 256f * density)
        val pillHeight = 28f * density
        val btnWidth = 32f * density
        val gap = 7f * density
        val totalWidth = pillWidth + gap + btnWidth

        val startX = (w - totalWidth) / 2f
        val topY = 10f * density

        modePillRect.set(startX, topY, startX + pillWidth, topY + pillHeight)

        val tabWidth = pillWidth / 4f
        for (i in 0..3) {
            val tabX = startX + (i * tabWidth)
            modeTabRects[i].set(
                tabX + 2f * density,
                topY + 2f * density,
                tabX + tabWidth - 2f * density,
                topY + pillHeight - 2f * density
            )
        }

        val btnX = startX + pillWidth + gap
        settingsBtnRect.set(btnX, topY, btnX + btnWidth, topY + pillHeight)
    }

    private fun computeSettingsLayout(w: Float, h: Float) {
        val dw = min(w * 0.90f, 430f * density)
        val dh = min(h * 0.88f, 320f * density)  // Taller to fit keyboard button
        val dx = (w - dw) / 2f
        val dy = (h - dh) / 2f

        settingsDialogRect.set(dx, dy, dx + dw, dy + dh)

        // Close button (top right)
        settingsCloseBtnRect.set(
            dx + dw - 38f * density,
            dy + 6f * density,
            dx + dw - 8f * density,
            dy + 36f * density
        )

        // Sensitivity Stepper & Progress Bar
        val sensY = dy + 68f * density
        val stepperH = 26f * density
        val btnW = 34f * density

        sensMinusBtnRect.set(dx + 16f * density, sensY, dx + 16f * density + btnW, sensY + stepperH)
        sensPlusBtnRect.set(dx + dw - 16f * density - btnW, sensY, dx + dw - 16f * density, sensY + stepperH)

        val barX1 = sensMinusBtnRect.right + 8f * density
        val barX2 = sensPlusBtnRect.left - 8f * density
        sensProgressBarRect.set(barX1, sensY + 6f * density, barX2, sensY + stepperH - 6f * density)

        // Sensitivity Preset Pills (4 tabs)
        val presetY = dy + 104f * density
        val presetH = 24f * density
        val presetPad = 16f * density
        val presetTotalW = dw - (presetPad * 2)
        val presetItemW = presetTotalW / 4f

        for (i in 0..3) {
            val px = dx + presetPad + (i * presetItemW)
            sensPresetRects[i].set(
                px + 2f * density,
                presetY,
                px + presetItemW - 2f * density,
                presetY + presetH
            )
        }

        // Orientation Option Pills (4 tabs)
        val orientY = dy + 158f * density
        val orientH = 28f * density
        val orientPad = 16f * density
        val orientTotalW = dw - (orientPad * 2)
        val orientItemW = orientTotalW / 4f

        for (i in 0..3) {
            val ox = dx + orientPad + (i * orientItemW)
            orientOptionRects[i].set(
                ox + 2f * density,
                orientY,
                ox + orientItemW - 2f * density,
                orientY + orientH
            )
        }

        // Keyboard Mode Button (wide pill between orientation and done)
        val kbY = dy + 202f * density
        val kbH = 30f * density
        val kbW = dw - (32f * density)
        val kbX = dx + (dw - kbW) / 2f
        keyboardModeBtnRect.set(kbX, kbY, kbX + kbW, kbY + kbH)

        // Done button (bottom center)
        val doneW = 100f * density
        val doneH = 26f * density
        val doneX = dx + (dw - doneW) / 2f
        val doneY = dy + dh - doneH - 12f * density
        settingsDoneBtnRect.set(doneX, doneY, doneX + doneW, doneY + doneH)

        // Keyboard Mode Indicator Bar (bottom strip when active)
        val barHeight = 36f * density
        keyboardBarRect.set(0f, h - barHeight, w, h)
        val closeBtnSize = 28f * density
        keyboardCloseBtnRect.set(
            w - closeBtnSize - 10f * density,
            h - barHeight + (barHeight - closeBtnSize) / 2f,
            w - 10f * density,
            h - barHeight + (barHeight + closeBtnSize) / 2f
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Touch Handling
    // ════════════════════════════════════════════════════════════════════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val x = event.x
        val y = event.y

        // ── 0. Intercept keyboard mode bar close button ─────────────────
        if (isKeyboardModeActive && actionMasked == MotionEvent.ACTION_DOWN) {
            val closeHitRect = RectF(keyboardCloseBtnRect).apply { inset(-12f * density, -12f * density) }
            if (closeHitRect.contains(x, y)) {
                isKeyboardModeActive = false
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }
        }

        // ── 1. Intercept touches when Settings Dialog is open ────────────
        if (isSettingsOpen) {
            if (actionMasked == MotionEvent.ACTION_DOWN) {
                android.util.Log.d("TrackpadSettings", "Touch down at ($x, $y) dialogRect=$settingsDialogRect")
                // Close button / Done button
                val closeHitRect = RectF(settingsCloseBtnRect).apply { inset(-14f * density, -14f * density) }
                val doneHitRect = RectF(settingsDoneBtnRect).apply { inset(-8f * density, -10f * density) }
                if (closeHitRect.contains(x, y) || doneHitRect.contains(x, y)) {
                    android.util.Log.d("TrackpadSettings", "Closed via close/done button")
                    isSettingsOpen = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }
                // Tap Outside
                if (!settingsDialogRect.contains(x, y)) {
                    android.util.Log.d("TrackpadSettings", "Closed via tap outside")
                    isSettingsOpen = false
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                // Sensitivity Minus
                val minusHitRect = RectF(sensMinusBtnRect).apply { inset(-10f * density, -10f * density) }
                if (minusHitRect.contains(x, y)) {
                    cursorSensitivity = roundToInt10(cursorSensitivity - 0.1f)
                    android.util.Log.d("TrackpadSettings", "Sens minus -> $cursorSensitivity")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                // Sensitivity Plus
                val plusHitRect = RectF(sensPlusBtnRect).apply { inset(-10f * density, -10f * density) }
                if (plusHitRect.contains(x, y)) {
                    cursorSensitivity = roundToInt10(cursorSensitivity + 0.1f)
                    android.util.Log.d("TrackpadSettings", "Sens plus -> $cursorSensitivity")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                // Sensitivity Progress Bar tap
                if (sensProgressBarRect.contains(x, y)) {
                    val fraction = ((x - sensProgressBarRect.left) / sensProgressBarRect.width()).coerceIn(0f, 1f)
                    cursorSensitivity = roundToInt10(0.4f + fraction * 3.2f)
                    android.util.Log.d("TrackpadSettings", "Sens progress -> $cursorSensitivity")
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                // Sensitivity Presets: [ 0.8x | 1.4x | 2.0x | 2.8x ]
                val presetValues = floatArrayOf(0.8f, 1.4f, 2.0f, 2.8f)
                for (i in 0..3) {
                    val hitRect = RectF(sensPresetRects[i]).apply { inset(-2f * density, -8f * density) }
                    if (hitRect.contains(x, y)) {
                        cursorSensitivity = presetValues[i]
                        android.util.Log.d("TrackpadSettings", "Sens preset $i -> $cursorSensitivity (rect=${sensPresetRects[i]})")
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        return true
                    }
                }

                // Orientation options: [ Landscape | Rev Land | Portrait | Auto ]
                val orientModes = intArrayOf(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR
                )
                for (i in 0..3) {
                    val hitRect = RectF(orientOptionRects[i]).apply { inset(-2f * density, -8f * density) }
                    if (hitRect.contains(x, y)) {
                        val newOrient = orientModes[i]
                        currentOrientation = newOrient
                        savePreferences()
                        orientationChangeListener?.invoke(newOrient)
                        android.util.Log.d("TrackpadSettings", "Orient option $i -> $newOrient (rect=${orientOptionRects[i]})")
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                        return true
                    }
                }

                // Keyboard Mode Button
                val kbHitRect = RectF(keyboardModeBtnRect).apply { inset(-4f * density, -8f * density) }
                if (kbHitRect.contains(x, y)) {
                    android.util.Log.d("TrackpadSettings", "Keyboard mode activated")
                    isSettingsOpen = false
                    isKeyboardModeActive = true
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    return true
                }

                android.util.Log.d("TrackpadSettings", "Touch inside dialog not consumed by any control")
            }
            return true
        }

        // ── 2. Intercept touches on Top Bar (Settings button & Mode switcher) ─
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            // Settings button [ ⚙ ] (with generous touch padding)
            val settingsHitRect = RectF(settingsBtnRect).apply { inset(-16f * density, -16f * density) }
            if (settingsHitRect.contains(x, y)) {
                android.util.Log.d("TrackpadSettings", "Gear button tapped at ($x, $y)")
                isSettingsOpen = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                return true
            }

            // Mode switcher pill [ AUTO | BT | USB | WIFI ]
            val modeHitRect = RectF(modePillRect).apply { inset(0f, -8f * density) }
            if (modeHitRect.contains(x, y)) {
                for (i in 0..3) {
                    val tabHitRect = RectF(modeTabRects[i]).apply { inset(0f, -8f * density) }
                    if (tabHitRect.contains(x, y)) {
                        val newMode = when (i) {
                            0 -> TrackpadTransportManager.Mode.AUTO
                            1 -> TrackpadTransportManager.Mode.BLUETOOTH
                            2 -> TrackpadTransportManager.Mode.USB
                            else -> TrackpadTransportManager.Mode.WIFI
                        }
                        transportManager?.setMode(newMode)
                        currentMode = newMode
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        invalidate()
                        return true
                    }
                }
                return true
            }
        }

        // ── 3. Multi-touch trackpad gestures ─────────────────────────────
        when (actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(event, actionIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                handlePointerMove(event)
            }

            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> {
                handlePointerUp(event, actionIndex)
            }

            MotionEvent.ACTION_CANCEL -> {
                handleCancel()
            }
        }

        invalidate()
        return true
    }

    /**
     * Handle a new finger touching the surface.
     */
    private fun handlePointerDown(event: MotionEvent, actionIndex: Int) {
        val pointerId = event.getPointerId(actionIndex)
        val x = event.getX(actionIndex)
        val y = event.getY(actionIndex)
        val now = SystemClock.uptimeMillis()

        val tp = TouchPoint(
            id = pointerId,
            currentX = x, currentY = y,
            startX = x, startY = y,
            prevX = x, prevY = y,
            downTime = now
        )
        activePointers.put(pointerId, tp)

        // Assign spatial roles and update gesture state
        assignRoles()
        updateGestureState()
    }

    /**
     * Handle finger movement across the surface.
     */
    private fun handlePointerMove(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            val tp = activePointers.get(pointerId) ?: continue

            tp.prevX = tp.currentX
            tp.prevY = tp.currentY
            tp.currentX = event.getX(i)
            tp.currentY = event.getY(i)

            if (!tp.hasSurpassedSlop) {
                val dx = abs(tp.currentX - tp.startX)
                val dy = abs(tp.currentY - tp.startY)
                if (dx > touchSlop || dy > touchSlop) {
                    tp.hasSurpassedSlop = true
                }
            }
        }

        assignRoles()
        processMovement()
    }

    /**
     * Handle a finger lifting off the surface.
     */
    private fun handlePointerUp(event: MotionEvent, actionIndex: Int) {
        val pointerId = event.getPointerId(actionIndex)
        val tp = activePointers.get(pointerId) ?: return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - tp.downTime
        val dist = hypot(tp.currentX - tp.startX, tp.currentY - tp.startY)
        val wasTap = elapsed < TAP_TIMEOUT_MS && dist < tapSlopPx

        val activeList = getActivePointers()
        val count = activeList.size

        // ── 1. Check for 3-finger simultaneous tap → Middle Click (0x04) ──
        var handledMultiTap = false
        if (count == 3 && wasTap) {
            val allTapped = activeList.all {
                val e = now - it.downTime
                val d = hypot(it.currentX - it.startX, it.currentY - it.startY)
                e < TAP_TIMEOUT_MS && d < tapSlopPx
            }
            if (allTapped) {
                Log.i(TAG, "Three-finger tap detected -> Middle Click")
                sendReport(0x04, 0, 0, 0)
                sendReport(0x00, 0, 0, 0)
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                lastMultiFingerTapTime = now
                handledMultiTap = true
            }
        }

        // ── 2. Check for 2-finger simultaneous tap → Right Click (0x02) ──
        if (!handledMultiTap && count == 2 && wasTap) {
            val other = activeList.firstOrNull { it.id != tp.id }
            if (other != null) {
                val otherElapsed = now - other.downTime
                val otherDist = hypot(other.currentX - other.startX, other.currentY - other.startY)
                val otherWasTap = otherElapsed < TAP_TIMEOUT_MS && otherDist < tapSlopPx
                val downDiff = abs(tp.downTime - other.downTime)
                if (otherWasTap && downDiff < 140L) {
                    Log.i(TAG, "Two-finger tap detected -> Right Click")
                    sendReport(0x02, 0, 0, 0)
                    sendReport(0x00, 0, 0, 0)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    lastMultiFingerTapTime = now
                    handledMultiTap = true
                }
            }
        }

        // ── 3. Single finger & Spatial tap events ─────────────────────────
        if (!handledMultiTap && (now - lastMultiFingerTapTime > 200L)) {
            when (tp.role) {
                FingerRole.INDEX -> {
                    if (wasTap) {
                        Log.i(TAG, "Index finger tap -> Left Click")
                        sendReport(0x01, 0, 0, 0)
                        sendReport(0x00, 0, 0, 0)
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                    if (currentButtons and 0x01 != 0) {
                        currentButtons = currentButtons and 0x01.inv()
                        sendReport(currentButtons, 0, 0, 0)
                    }
                }
                FingerRole.RING -> {
                    if (wasTap) {
                        Log.i(TAG, "Ring finger tap -> Right Click")
                        sendReport(0x02, 0, 0, 0)
                        sendReport(0x00, 0, 0, 0)
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    if (currentButtons and 0x02 != 0) {
                        currentButtons = currentButtons and 0x02.inv()
                        sendReport(currentButtons, 0, 0, 0)
                    }
                }
                FingerRole.ANCHOR -> {
                    if (wasTap && count == 1) {
                        Log.i(TAG, "Single anchor tap -> Left Click")
                        sendReport(0x01, 0, 0, 0)
                        sendReport(0x00, 0, 0, 0)
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
            }
        }

        activePointers.remove(pointerId)
        scrollAccumulator = 0f

        assignRoles()
        updateGestureState()

        if (activePointers.size() == 0) {
            if (currentButtons != 0) {
                currentButtons = 0
                sendReport(0, 0, 0, 0)
            }
            gestureState = GestureState.IDLE
        }
    }

    private fun handleCancel() {
        if (currentButtons != 0) {
            currentButtons = 0
            sendReport(0, 0, 0, 0)
        }
        activePointers.clear()
        scrollAccumulator = 0f
        gestureState = GestureState.IDLE
    }

    // ════════════════════════════════════════════════════════════════════════
    // Spatial Role Sorting & Gesture Machine
    // ════════════════════════════════════════════════════════════════════════

    private fun assignRoles() {
        val list = getActivePointers()
        if (list.isEmpty()) return

        list.sortBy { it.currentX }

        when (list.size) {
            1 -> {
                list[0].role = FingerRole.ANCHOR
            }
            2 -> {
                val f0 = list[0] // Leftmost
                val f1 = list[1] // Rightmost

                val timeDiff = f1.downTime - f0.downTime
                val thresholdMs = 75L

                when {
                    timeDiff > thresholdMs -> {
                        // f0 was down first -> f0 is anchor, f1 (right) is ring
                        f0.role = FingerRole.ANCHOR
                        f1.role = FingerRole.RING
                    }
                    timeDiff < -thresholdMs -> {
                        // f1 was down first -> f1 is anchor, f0 (left) is index
                        f0.role = FingerRole.INDEX
                        f1.role = FingerRole.ANCHOR
                    }
                    else -> {
                        // Simultaneous landing
                        if (f0.role == FingerRole.ANCHOR && f1.role != FingerRole.ANCHOR) {
                            f1.role = FingerRole.RING
                        } else if (f1.role == FingerRole.ANCHOR && f0.role != FingerRole.ANCHOR) {
                            f0.role = FingerRole.INDEX
                        } else {
                            f0.role = FingerRole.INDEX
                            f1.role = FingerRole.RING
                        }
                    }
                }
            }
            else -> {
                list[0].role = FingerRole.INDEX
                list[list.size - 1].role = FingerRole.RING
                for (i in 1 until list.size - 1) {
                    list[i].role = FingerRole.ANCHOR
                }
            }
        }
    }

    private fun updateGestureState() {
        val list = getActivePointers()
        if (list.isEmpty()) {
            gestureState = GestureState.IDLE
            return
        }

        val hasIndex = list.any { it.role == FingerRole.INDEX }
        val hasRing = list.any { it.role == FingerRole.RING }

        gestureState = when {
            hasIndex && hasRing -> {
                currentButtons = 0
                scrollAccumulator = 0f
                GestureState.SCROLL_MODE
            }
            hasIndex -> {
                currentButtons = 0x01
                GestureState.LEFT_HELD
            }
            hasRing -> {
                currentButtons = 0x02
                GestureState.RIGHT_HELD
            }
            else -> {
                currentButtons = 0
                GestureState.CURSOR_MOVE
            }
        }
    }

    private fun processMovement() {
        val anchor = getAnchorPointer() ?: getActivePointers().firstOrNull() ?: return

        val rawDx = anchor.currentX - anchor.prevX
        val rawDy = anchor.currentY - anchor.prevY

        when (gestureState) {
            GestureState.CURSOR_MOVE -> {
                if (!anchor.hasSurpassedSlop) return
                val dx = (rawDx * cursorSensitivity).roundToInt()
                val dy = (rawDy * cursorSensitivity).roundToInt()
                if (dx != 0 || dy != 0) {
                    sendReport(0, dx, dy, 0)
                }
            }

            GestureState.LEFT_HELD -> {
                if (!anchor.hasSurpassedSlop) return
                val dx = (rawDx * cursorSensitivity).roundToInt()
                val dy = (rawDy * cursorSensitivity).roundToInt()
                sendReport(0x01, dx, dy, 0)
            }

            GestureState.RIGHT_HELD -> {
                if (!anchor.hasSurpassedSlop) return
                val dx = (rawDx * cursorSensitivity).roundToInt()
                val dy = (rawDy * cursorSensitivity).roundToInt()
                sendReport(0x02, dx, dy, 0)
            }

            GestureState.SCROLL_MODE -> {
                if (abs(rawDy) < SCROLL_DEAD_ZONE) return
                scrollAccumulator += rawDy * SCROLL_SENSITIVITY
                val scrollUnits = scrollAccumulator.toInt()
                if (scrollUnits != 0) {
                    sendReport(0, 0, 0, -scrollUnits)
                    scrollAccumulator -= scrollUnits
                }
            }

            GestureState.IDLE -> {}
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Pointer Utilities
    // ════════════════════════════════════════════════════════════════════════

    private fun getActivePointers(): MutableList<TouchPoint> {
        val list = ArrayList<TouchPoint>(activePointers.size())
        for (i in 0 until activePointers.size()) {
            list.add(activePointers.valueAt(i))
        }
        return list
    }

    private fun getAnchorPointer(): TouchPoint? {
        for (i in 0 until activePointers.size()) {
            val tp = activePointers.valueAt(i)
            if (tp.role == FingerRole.ANCHOR) return tp
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════════════
    // Canvas Rendering
    // ════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        // ── 1. Matte Black Background ──────────────────────────────────
        canvas.drawColor(COLOR_MATTE_BLACK)

        // ── 2. Subtle Dot Grid ─────────────────────────────────────────
        drawDotGrid(canvas, w, h)

        // ── 3. Dim Background Instructions ─────────────────────────────
        drawBackgroundInstructions(canvas, w, h)

        // ── 4. Active Touch Pointers ───────────────────────────────────
        if (!isSettingsOpen) {
            drawTouchPointers(canvas)
        }

        // ── 5. Top Bar (Mode Pill + Settings Button) ───────────────────
        drawTopBar(canvas)

        // ── 6. Status Subtitle ─────────────────────────────────────────
        drawStatusBar(canvas, w)

        // ── 7. Gesture State Pill ──────────────────────────────────────
        if (!isSettingsOpen) {
            drawGestureStatePill(canvas, w, h)
        }

        // ── 8. Settings Modal Dialog (when open) ───────────────────────
        if (isSettingsOpen) {
            drawSettingsDialog(canvas, w, h)
        }

        // ── 9. Keyboard Mode Indicator Bar (when active) ────────────────
        if (isKeyboardModeActive) {
            drawKeyboardBar(canvas, w, h)
        }
    }

    private fun drawDotGrid(canvas: Canvas, w: Float, h: Float) {
        val spacing = GRID_DOT_SPACING_DP * density
        val radius = GRID_DOT_RADIUS_DP * density

        var x = spacing
        while (x < w) {
            var y = spacing
            while (y < h) {
                canvas.drawCircle(x, y, radius, paintGridDot)
                y += spacing
            }
            x += spacing
        }
    }

    // ── Dim Background Gesture Instructions ───────────────────────────────
    private fun drawBackgroundInstructions(canvas: Canvas, w: Float, h: Float) {
        val isLandscape = w > h
        val radius = 10f * density

        if (isLandscape) {
            // ── Landscape: 3 Horizontal HUD Cards in Lower Center ─────────
            val centerY = h * 0.54f
            val headerY = centerY - 42f * density
            canvas.drawText("GESTURE QUICK GUIDE", w / 2f, headerY, paintInstHeader)

            val cardW = min(w * 0.285f, 192f * density)
            val cardH = 62f * density
            val gap = 12f * density
            val totalW = 3 * cardW + 2 * gap
            val startX = (w - totalW) / 2f
            val cardY = centerY - 26f * density

            // Card 1: 1 FINGER
            drawInstCard(
                canvas, startX, cardY, cardW, cardH, radius,
                fingerCount = 1, title = "1 FINGER",
                line1 = "Slide  › Move Cursor",
                line2 = "Tap    › Left Click"
            )

            // Card 2: 2 FINGERS
            drawInstCard(
                canvas, startX + cardW + gap, cardY, cardW, cardH, radius,
                fingerCount = 2, title = "2 FINGERS",
                line1 = "Slide  › Scroll Wheel ↕",
                line2 = "Tap    › Right Click"
            )

            // Card 3: 3 FINGERS
            drawInstCard(
                canvas, startX + 2 * (cardW + gap), cardY, cardW, cardH, radius,
                fingerCount = 3, title = "3 FINGERS",
                line1 = "Tap    › Middle Click",
                line2 = "Hold L/R › Drag & Drop"
            )
        } else {
            // ── Portrait: 3 Stacked HUD Cards Centered ─────────────────────
            val centerY = h * 0.50f
            val headerY = centerY - 105f * density
            canvas.drawText("GESTURE QUICK GUIDE", w / 2f, headerY, paintInstHeader)

            val cardW = min(w * 0.86f, 275f * density)
            val cardH = 54f * density
            val gap = 10f * density
            val totalH = 3 * cardH + 2 * gap
            val startX = (w - cardW) / 2f
            val startY = centerY - (totalH / 2f) + 8f * density

            drawInstCard(
                canvas, startX, startY, cardW, cardH, radius,
                fingerCount = 1, title = "1 FINGER",
                line1 = "Slide  › Move Cursor",
                line2 = "Tap    › Left Click"
            )
            drawInstCard(
                canvas, startX, startY + (cardH + gap), cardW, cardH, radius,
                fingerCount = 2, title = "2 FINGERS",
                line1 = "Slide  › Scroll Wheel ↕",
                line2 = "Tap    › Right Click"
            )
            drawInstCard(
                canvas, startX, startY + 2 * (cardH + gap), cardW, cardH, radius,
                fingerCount = 3, title = "3 FINGERS",
                line1 = "Tap    › Middle Click",
                line2 = "Hold L/R › Drag & Drop"
            )
        }
    }

    private fun drawInstCard(
        canvas: Canvas,
        left: Float,
        top: Float,
        cardW: Float,
        cardH: Float,
        radius: Float,
        fingerCount: Int,
        title: String,
        line1: String,
        line2: String
    ) {
        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, radius, radius, paintInstCardBg)
        canvas.drawRoundRect(rect, radius, radius, paintInstCardStroke)

        // 1. Draw Vector Finger Illustration on left side of card
        val graphicCenterX = left + 24f * density
        val graphicCenterY = top + (cardH / 2f)
        drawFingerGraphic(canvas, graphicCenterX, graphicCenterY, fingerCount)

        // 2. Draw Title
        val textX = left + 46f * density
        val titleY = top + 17f * density
        canvas.drawText(title, textX, titleY, paintInstTitle)

        // 3. Line 1 & Line 2 instructions
        val l1Y = top + 33f * density
        val l2Y = top + 47f * density
        canvas.drawText(line1, textX, l1Y, paintInstText)
        canvas.drawText(line2, textX, l2Y, paintInstText)
    }

    private fun drawFingerGraphic(canvas: Canvas, cx: Float, cy: Float, mode: Int) {
        val fw = 8f * density
        val fh = 17f * density
        val r = 4f * density

        when (mode) {
            1 -> {
                // 1 Finger: Single capsule + concentric touch ripple
                val rect = RectF(cx - fw / 2f, cy - fh / 2f, cx + fw / 2f, cy + fh / 2f)
                canvas.drawRoundRect(rect, r, r, paintFingerCapsule)
                canvas.drawRoundRect(rect, r, r, paintFingerCapsuleStroke)
                canvas.drawCircle(cx, cy - fh / 4f, 7.5f * density, paintFingerRipple)
            }
            2 -> {
                // 2 Fingers: Two capsules side-by-side + vertical scroll indicators
                val gap = 5.5f * density
                val x1 = cx - gap
                val x2 = cx + gap
                val r1 = RectF(x1 - fw / 2f, cy - fh / 2f, x1 + fw / 2f, cy + fh / 2f)
                val r2 = RectF(x2 - fw / 2f, cy - fh / 2f, x2 + fw / 2f, cy + fh / 2f)
                canvas.drawRoundRect(r1, r, r, paintFingerCapsule)
                canvas.drawRoundRect(r1, r, r, paintFingerCapsuleStroke)
                canvas.drawRoundRect(r2, r, r, paintFingerCapsule)
                canvas.drawRoundRect(r2, r, r, paintFingerCapsuleStroke)

                // Scroll indicators
                canvas.drawText("▲", cx, cy - fh / 2f - 1f * density, paintFingerTag)
                canvas.drawText("▼", cx, cy + fh / 2f + 6.5f * density, paintFingerTag)
            }
            3 -> {
                // 3 Fingers: Three capsules side-by-side with L, C, R tags
                val gap = 8.5f * density
                val x1 = cx - gap
                val x2 = cx
                val x3 = cx + gap

                val r1 = RectF(x1 - fw / 2f, cy - fh / 2f, x1 + fw / 2f, cy + fh / 2f)
                val r2 = RectF(x2 - fw / 2f, cy - fh / 2f, x2 + fw / 2f, cy + fh / 2f)
                val r3 = RectF(x3 - fw / 2f, cy - fh / 2f, x3 + fw / 2f, cy + fh / 2f)

                canvas.drawRoundRect(r1, r, r, paintFingerCapsule)
                canvas.drawRoundRect(r1, r, r, paintFingerCapsuleStroke)
                canvas.drawRoundRect(r2, r, r, paintFingerCapsule)
                canvas.drawRoundRect(r2, r, r, paintFingerCapsuleStroke)
                canvas.drawRoundRect(r3, r, r, paintFingerCapsule)
                canvas.drawRoundRect(r3, r, r, paintFingerCapsuleStroke)

                canvas.drawText("L", x1, cy + (paintFingerTag.textSize / 3f), paintFingerTag)
                canvas.drawText("•", x2, cy + (paintFingerTag.textSize / 3f), paintFingerTag)
                canvas.drawText("R", x3, cy + (paintFingerTag.textSize / 3f), paintFingerTag)
            }
        }
    }

    private fun drawTouchPointers(canvas: Canvas) {
        val pointerRadius = TOUCH_POINTER_RADIUS_DP * density
        val ringRadius = TOUCH_RING_RADIUS_DP * density

        for (i in 0 until activePointers.size()) {
            val tp = activePointers.valueAt(i)
            val x = tp.currentX
            val y = tp.currentY

            val pointerColor = when (tp.role) {
                FingerRole.ANCHOR -> COLOR_GREEN
                FingerRole.INDEX -> Color.argb(235, 105, 240, 174)   // Mint / Light Green
                FingerRole.RING -> Color.argb(235, 0, 230, 118)     // Neon Cyber Green
            }

            paintTouchPointer.color = pointerColor
            paintTouchPointer.alpha = 200
            canvas.drawCircle(x, y, pointerRadius, paintTouchPointer)

            paintTouchRing.color = pointerColor
            paintTouchRing.alpha = 80
            canvas.drawCircle(x, y, ringRadius, paintTouchRing)

            val roleText = when (tp.role) {
                FingerRole.ANCHOR -> if (gestureState == GestureState.SCROLL_MODE) "SCROLL" else "CURSOR"
                FingerRole.INDEX -> "L-CLICK"
                FingerRole.RING -> "R-CLICK"
            }
            canvas.drawText(roleText, x, y - ringRadius - 8 * density, paintRoleLabel)
        }

        paintTouchPointer.color = COLOR_GREEN
        paintTouchPointer.alpha = 200
    }

    private fun drawTopBar(canvas: Canvas) {
        val radius = modePillRect.height() / 2f

        // ── A. Mode Pill Background & Outline ──────────────────────────
        canvas.drawRoundRect(modePillRect, radius, radius, paintPillBg)
        canvas.drawRoundRect(modePillRect, radius, radius, paintPillStroke)

        val tabTitles = arrayOf("AUTO", "BT", "USB", "WIFI")
        val activeIndex = when (currentMode) {
            TrackpadTransportManager.Mode.AUTO -> 0
            TrackpadTransportManager.Mode.BLUETOOTH -> 1
            TrackpadTransportManager.Mode.USB -> 2
            TrackpadTransportManager.Mode.WIFI -> 3
        }

        val tabRadius = modeTabRects[0].height() / 2f

        for (i in 0..3) {
            val rect = modeTabRects[i]
            val isActive = (i == activeIndex)

            if (isActive) {
                canvas.drawRoundRect(rect, tabRadius, tabRadius, paintTabActive)
                canvas.drawRoundRect(rect, tabRadius, tabRadius, paintTabActiveStroke)
            }

            val textPaint = if (isActive) paintTabTextActive else paintTabTextInactive
            val textY = rect.centerY() + (textPaint.textSize / 3f)
            val title = tabTitles[i]

            val showDot = when (i) {
                0 -> currentActiveTransport != TrackpadTransportManager.TransportType.NONE
                1 -> currentActiveTransport == TrackpadTransportManager.TransportType.BLUETOOTH
                2 -> currentActiveTransport == TrackpadTransportManager.TransportType.USB
                3 -> currentActiveTransport == TrackpadTransportManager.TransportType.WIFI
                else -> false
            }

            if (showDot) {
                paintTransportDot.color = if (isActive) COLOR_LIGHT_AQUA else COLOR_TEAL
                val dotRadius = 2.5f * density
                val dotX = rect.centerX() - (textPaint.measureText(title) / 2f) - 5f * density
                canvas.drawCircle(dotX, rect.centerY(), dotRadius, paintTransportDot)
            }

            canvas.drawText(title, rect.centerX(), textY, textPaint)
        }

        // ── B. Settings Button [ ⚙ ] ────────────────────────────────────
        val btnRadius = settingsBtnRect.height() / 2f
        canvas.drawRoundRect(settingsBtnRect, btnRadius, btnRadius, paintPillBg)
        canvas.drawRoundRect(settingsBtnRect, btnRadius, btnRadius, paintPillStroke)

        val iconY = settingsBtnRect.centerY() + (paintSettingsIcon.textSize / 3f)
        canvas.drawText("⚙", settingsBtnRect.centerX(), iconY, paintSettingsIcon)
    }

    private fun drawStatusBar(canvas: Canvas, w: Float) {
        val statusY = modePillRect.bottom + 15f * density
        canvas.drawText(connectionStatusText, w / 2f, statusY, paintStatusText)
    }

    private fun drawGestureStatePill(canvas: Canvas, w: Float, h: Float) {
        if (gestureState == GestureState.IDLE) return

        val text = when (gestureState) {
            GestureState.CURSOR_MOVE -> "MOVE"
            GestureState.LEFT_HELD -> "L-DRAG"
            GestureState.RIGHT_HELD -> "R-DRAG"
            GestureState.SCROLL_MODE -> "SCROLL"
            GestureState.IDLE -> return
        }

        val textWidth = paintStatePillText.measureText(text)
        val pillPadH = 16f * density
        val pillPadV = 6f * density
        val pillWidth = textWidth + pillPadH * 2
        val pillHeight = paintStatePillText.textSize + pillPadV * 2
        val pillX = (w - pillWidth) / 2f
        val pillY = h - 30f * density - pillHeight

        val pillRect = RectF(pillX, pillY, pillX + pillWidth, pillY + pillHeight)
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, paintStatePill)

        val textY = pillY + pillPadV + paintStatePillText.textSize * 0.82f
        canvas.drawText(text, w / 2f, textY, paintStatePillText)
    }

    // ── Glassmorphic Settings Modal Dialog ──────────────────────────────
    private fun drawSettingsDialog(canvas: Canvas, w: Float, h: Float) {
        // 1. Semi-transparent dark modal backdrop
        canvas.drawRect(0f, 0f, w, h, paintModalBackdrop)

        val cardRect = settingsDialogRect
        val cardRadius = 14f * density

        // 2. Dialog Card background & glowing border
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, paintDialogCard)
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, paintDialogBorder)

        // 3. Header: "⚙  SETTINGS" & Close button [ ✕ ]
        val titleX = cardRect.left + 18f * density
        val titleY = cardRect.top + 25f * density
        canvas.drawText("⚙  SETTINGS", titleX, titleY, paintDialogTitle)

        val closeX = settingsCloseBtnRect.centerX()
        val closeY = settingsCloseBtnRect.centerY() + (paintDialogClose.textSize / 3f)
        canvas.drawText("✕", closeX, closeY, paintDialogClose)

        // Divider
        val divY = cardRect.top + 38f * density
        canvas.drawLine(cardRect.left + 16f * density, divY, cardRect.right - 16f * density, divY, paintDivider)

        // 4. Section 1: MOUSE SENSITIVITY
        val sensLabelY = cardRect.top + 57f * density
        canvas.drawText("MOUSE SENSITIVITY", titleX, sensLabelY, paintSectionLabel)

        val valBadgeText = String.format("%.1fx", cursorSensitivity)
        canvas.drawText(valBadgeText, cardRect.right - 18f * density, sensLabelY, paintValueBadge)

        // Stepper: [-] Button
        val btnRadius = 6f * density
        canvas.drawRoundRect(sensMinusBtnRect, btnRadius, btnRadius, paintStepperBtnBg)
        canvas.drawRoundRect(sensMinusBtnRect, btnRadius, btnRadius, paintStepperBtnStroke)
        canvas.drawText("−", sensMinusBtnRect.centerX(), sensMinusBtnRect.centerY() + (paintStepperText.textSize / 3f), paintStepperText)

        // Stepper: Progress Bar
        val barRadius = sensProgressBarRect.height() / 2f
        canvas.drawRoundRect(sensProgressBarRect, barRadius, barRadius, paintProgressTrack)

        val progressFrac = ((cursorSensitivity - 0.4f) / (3.6f - 0.4f)).coerceIn(0.04f, 1f)
        val fillW = sensProgressBarRect.width() * progressFrac
        val fillRect = RectF(sensProgressBarRect.left, sensProgressBarRect.top, sensProgressBarRect.left + fillW, sensProgressBarRect.bottom)
        canvas.drawRoundRect(fillRect, barRadius, barRadius, paintProgressFill)

        // Stepper: [+] Button
        canvas.drawRoundRect(sensPlusBtnRect, btnRadius, btnRadius, paintStepperBtnBg)
        canvas.drawRoundRect(sensPlusBtnRect, btnRadius, btnRadius, paintStepperBtnStroke)
        canvas.drawText("+", sensPlusBtnRect.centerX(), sensPlusBtnRect.centerY() + (paintStepperText.textSize / 3f), paintStepperText)

        // Sensitivity Preset Pills
        val presetLabels = arrayOf("0.8x Slow", "1.4x Mid", "2.0x Fast", "2.8x Max")
        val presetVals = floatArrayOf(0.8f, 1.4f, 2.0f, 2.8f)

        for (i in 0..3) {
            val r = sensPresetRects[i]
            val isPresetActive = abs(cursorSensitivity - presetVals[i]) < 0.05f

            if (isPresetActive) {
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintTabActive)
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintTabActiveStroke)
            } else {
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintPillBg)
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintPillStroke)
            }

            val pTextPaint = if (isPresetActive) paintTabTextActive else paintTabTextInactive
            canvas.drawText(presetLabels[i], r.centerX(), r.centerY() + (pTextPaint.textSize / 3f), pTextPaint)
        }

        // 5. Section 2: ORIENTATION
        val orientLabelY = cardRect.top + 148f * density
        canvas.drawText("PHONE ORIENTATION", titleX, orientLabelY, paintSectionLabel)

        val orientLabels = arrayOf("⤺ Land", "⤻ Rev Land", "▯ Portrait", "⟳ Auto")
        val orientValues = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        )

        for (i in 0..3) {
            val r = orientOptionRects[i]
            val isOrientActive = (currentOrientation == orientValues[i])

            if (isOrientActive) {
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintTabActive)
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintTabActiveStroke)
            } else {
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintPillBg)
                canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paintPillStroke)
            }

            val oTextPaint = if (isOrientActive) paintTabTextActive else paintTabTextInactive
            canvas.drawText(orientLabels[i], r.centerX(), r.centerY() + (oTextPaint.textSize / 3f), oTextPaint)
        }

        // 6. Section 3: KEYBOARD MODE Button
        val kbLabelY = cardRect.top + 196f * density
        canvas.drawText("PC KEYBOARD", titleX, kbLabelY, paintSectionLabel)

        val kbRadius = keyboardModeBtnRect.height() / 2f
        canvas.drawRoundRect(keyboardModeBtnRect, kbRadius, kbRadius, paintPillBg)
        canvas.drawRoundRect(keyboardModeBtnRect, kbRadius, kbRadius, paintPillStroke)

        val kbTextPaint = Paint(paintTabTextActive).apply {
            textSize = 11f * density
            letterSpacing = 0.06f
        }
        canvas.drawText(
            "⌨  OPEN KEYBOARD MODE",
            keyboardModeBtnRect.centerX(),
            keyboardModeBtnRect.centerY() + (kbTextPaint.textSize / 3f),
            kbTextPaint
        )

        // 7. Section 4: DONE Button
        val doneRadius = settingsDoneBtnRect.height() / 2f
        canvas.drawRoundRect(settingsDoneBtnRect, doneRadius, doneRadius, paintDoneBtn)
        canvas.drawRoundRect(settingsDoneBtnRect, doneRadius, doneRadius, paintDoneBtnStroke)
        canvas.drawText("DONE", settingsDoneBtnRect.centerX(), settingsDoneBtnRect.centerY() + (paintDoneText.textSize / 3f), paintDoneText)
    }

    // ── Keyboard Mode Indicator Bar ─────────────────────────────────────
    private fun drawKeyboardBar(canvas: Canvas, w: Float, h: Float) {
        // Semi-transparent green bar at bottom
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0D130F.toInt()
            style = Paint.Style.FILL
        }
        val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GREEN
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
            alpha = 150
        }

        canvas.drawRect(keyboardBarRect, barPaint)
        canvas.drawLine(
            keyboardBarRect.left, keyboardBarRect.top,
            keyboardBarRect.right, keyboardBarRect.top,
            barBorderPaint
        )

        // Keyboard icon + label
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_GREEN
            textSize = 11f * density
            textAlign = Paint.Align.LEFT
            isFakeBoldText = true
            letterSpacing = 0.06f
        }
        canvas.drawText(
            "⌨  KEYBOARD MODE ACTIVE",
            16f * density,
            keyboardBarRect.centerY() + (labelPaint.textSize / 3f),
            labelPaint
        )

        // Close button [✕]
        val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_LIGHT_GREEN
            textSize = 13f * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText(
            "✕",
            keyboardCloseBtnRect.centerX(),
            keyboardCloseBtnRect.centerY() + (closePaint.textSize / 3f),
            closePaint
        )
    }
}
