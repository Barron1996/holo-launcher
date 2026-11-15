package com.holo.launcher.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.holo.launcher.sys.Theme

/**
 * 背板网格。
 *
 * 作用：给面板之间露出的缝隙提供质感，避免"贴纸感"。
 * 规则：
 *  - 60dp 大格 + 12dp 小格
 *  - alpha 极低（大格 6%、小格 3%），只在暗背景上隐约可见
 *  - 不用渐变、不用发光
 */
class GridBackdrop @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    /** 大格边长 px */
    private val majorStep = 60f * density

    /** 小格边长 px */
    private val minorStep = 12f * density

    private val majorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val minorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val themeListener: () -> Unit = { invalidate() }

    init {
        Theme.addListener(themeListener)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val accent = Theme.accent

        // 小格：极淡
        minorPaint.color = Theme.alpha(accent, 0.030f)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, minorPaint)
            x += minorStep
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, minorPaint)
            y += minorStep
        }

        // 大格：稍亮一点点
        majorPaint.color = Theme.alpha(accent, 0.060f)
        x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, majorPaint)
            x += majorStep
        }
        y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, majorPaint)
            y += majorStep
        }
    }
}
