package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.holo.launcher.fx.ClockFx
import com.holo.launcher.fx.Glow
import kotlin.math.sin

/** 全屏 HUD 装饰层：扫描线、网格地板、角标、环境粒子 */
class HudOverlay(context: Context) : View(context) {

    private val d = resources.displayMetrics.density
    private val cyan = Color.parseColor("#FF4FD8FF")
    private val cyanBright = Color.parseColor("#FFB8F4FF")
    private val dim = Color.parseColor("#FF1B5A7A")

    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6FA8C4")
        textSize = 9f * d
        textAlign = Paint.Align.LEFT
    }

    private var scanY = 0f
    private val particles = ArrayList<FloatArray>()
    private var frame = 0

    init {
        setWillNotDraw(false)
        for (i in 0 until 26) {
            particles.add(floatArrayOf((Math.random() * 1000).toFloat(), (Math.random() * 1000).toFloat(),
                (0.4f + Math.random().toFloat() * 0.8f)))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        drawGridFloor(canvas, w, h)
        drawScanLine(canvas, w, h)
        drawParticles(canvas, w, h)
        drawFrame(canvas, w, h)
        drawTelemetryText(canvas, w, h)
        if (frame++ % 2 == 0) invalidate()
    }

    /** 透视网格地板：横向靠上密集、越往下越疏，并逐行滚动 */
    private fun drawGridFloor(canvas: Canvas, w: Float, h: Float) {
        val horizon = h * 0.46f
        val bottom = h
        val p = Glow.paint(dim, 1f * d, 2f * d, 55)
        val cols = 14
        for (i in 0..cols) {
            val f = i.toFloat() / cols
            val xBot = w * (f - 0.5f) * 2.2f + w * 0.5f
            canvas.drawLine(w * 0.5f, horizon, xBot, bottom, p)
        }
        val shift = (System.currentTimeMillis() % 1600L) / 1600f
        val rows = 9
        for (i in 0 until rows) {
            val t = (i + shift) / rows
            val y = horizon + (bottom - horizon) * (t * t)
            canvas.drawLine(0f, y, w, y, p)
        }
    }

    private fun drawScanLine(canvas: Canvas, w: Float, h: Float) {
        scanY += h / 220f
        if (scanY > h) scanY = 0f
        scanPaint.color = Color.argb(16, 120, 230, 255)
        canvas.drawRect(0f, scanY - 22f * d, w, scanY, scanPaint)
        scanPaint.color = Color.argb(90, 180, 245, 255)
        canvas.drawRect(0f, scanY - 1f * d, w, scanY, scanPaint)
    }

    private fun drawParticles(canvas: Canvas, w: Float, h: Float) {
        for (pt in particles) {
            pt[1] -= pt[2]
            if (pt[1] < -20f) { pt[0] = (Math.random() * w).toFloat(); pt[1] = h + 20f }
            val a = (60 + 120 * sin((pt[1] / h) * Math.PI.toFloat())).toInt().coerceIn(0, 255)
            Glow.fill(cyan, a, 4f * d).let { canvas.drawCircle(pt[0], pt[1], 1.4f * d, it) }
        }
    }

    /** 屏幕四周 HUD 边框与四角括号 */
    private fun drawFrame(canvas: Canvas, w: Float, h: Float) {
        val pad = 10f * d
        val rect = RectF(pad, pad, w - pad, h - pad)
        Glow.cornerBrackets(canvas, rect, 26f * d, cyanBright, 150, 1.4f * d)
        // 顶部双细线
        Glow.line(canvas, pad + 40f * d, pad, w - pad - 40f * d, pad, dim, 1f * d, 4f * d, 110)
        Glow.line(canvas, pad + 40f * d, h - pad, w - pad - 40f * d, h - pad, dim, 1f * d, 4f * d, 110)
    }

    private fun drawTelemetryText(canvas: Canvas, w: Float, h: Float) {
        textPaint.alpha = 130
        canvas.drawText("HOLO-OS / SHELL v1.0", 22f * d, h - 16f * d, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("LINK STABLE  " + ClockFx.time(), w - 22f * d, h - 16f * d, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }
}
