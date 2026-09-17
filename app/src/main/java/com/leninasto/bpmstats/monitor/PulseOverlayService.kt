package com.leninasto.bpmstats.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.leninasto.bpmstats.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class PulseOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        HeartRateMonitorRepository.initialize(this)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
        scope.launch {
            HeartRateMonitorRepository.currentBpm.collect { bpm ->
                updateOverlay(bpm)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        overlayView?.let(windowManager::removeView)
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        val chart = MiniPulseChartView(this).apply {
            tag = CHART_TAG
            alpha = 0.52f
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
        }
        val icon = ImageView(this).apply {
            tag = ICON_TAG
            setImageResource(R.drawable.ic_ecg_heart)
            setColorFilter(0xDDFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        val label = TextView(this).apply {
            tag = LABEL_TAG
            text = "-- PPM"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(dp(7), 0, 0, 0)
        }

        content.addView(icon)
        content.addView(label)

        val root = FrameLayout(this).apply {
            background = roundedBackground(overlayBackgroundColor())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(8).toFloat()
            addView(chart, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnTouchListener(::dragOverlay)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            dp(118),
            dp(44),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        overlayView = root
        windowManager.addView(root, params)
    }

    private fun dragOverlay(view: View, event: MotionEvent): Boolean {
        val currentParams = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = currentParams.x
                initialY = currentParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentParams.x = initialX + (event.rawX - initialTouchX).roundToInt()
                currentParams.y = initialY + (event.rawY - initialTouchY).roundToInt()
                windowManager.updateViewLayout(view, currentParams)
                return true
            }
        }
        return false
    }

    private fun updateOverlay(bpm: Int) {
        val root = overlayView as? FrameLayout ?: return
        val label = root.findViewWithTag<TextView>(LABEL_TAG)
        val chart = root.findViewWithTag<MiniPulseChartView>(CHART_TAG)
        val accent = overlayColor(bpm)
        label.text = if (bpm > 0) "$bpm PPM" else "-- PPM"
        label.setTextColor(accent)
        root.background = roundedBackground(overlayBackgroundColor())
        chart.setAccentColor(accent)
        chart.addPoint(bpm)
    }

    private fun roundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
    }

    private fun overlayColor(bpm: Int): Int {
        return when {
            bpm <= 0 -> 0xFFB8BEC7.toInt()
            bpm < 60 -> 0xFF5BBEFF.toInt()
            bpm < 130 -> 0xFF42E686.toInt()
            bpm < 165 -> 0xFFFFD767.toInt()
            else -> 0xFFFF6F6F.toInt()
        }
    }

    private fun overlayBackgroundColor(): Int {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isDark) 0xB8F7F9FC.toInt() else 0xB812151B.toInt()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class MiniPulseChartView(context: Context) : View(context) {
        private val samples = ArrayDeque<Int>()
        private val path = Path()
        private val density = context.resources.displayMetrics.density
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF42E686.toInt()
            strokeWidth = density * 2.2f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x0FFFFFFF
            style = Paint.Style.FILL
        }

        fun setAccentColor(color: Int) {
            linePaint.color = color
            invalidate()
        }

        fun addPoint(bpm: Int) {
            if (bpm <= 0) return
            samples.addLast(bpm)
            while (samples.size > MAX_POINTS) samples.removeFirst()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, fillPaint)
            if (samples.size < 2) return

            val values = samples.toList()
            val min = values.minOrNull() ?: return
            val max = values.maxOrNull() ?: return
            val range = (max - min).coerceAtLeast(12)
            val left = width * 0.08f
            val right = width * 0.94f
            val top = height * 0.22f
            val bottom = height * 0.78f
            val step = (right - left) / (MAX_POINTS - 1)

            path.reset()
            values.forEachIndexed { index, bpm ->
                val x = left + step * index
                val y = bottom - ((bpm - min) / range.toFloat()) * (bottom - top)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        private companion object {
            private const val MAX_POINTS = 10
        }
    }

    companion object {
        private const val LABEL_TAG = "pulse_label"
        private const val CHART_TAG = "pulse_chart"
        private const val ICON_TAG = "pulse_icon"

        fun start(context: Context) {
            context.startService(Intent(context, PulseOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PulseOverlayService::class.java))
        }
    }
}
