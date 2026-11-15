package com.holo.launcher.fx

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.holo.launcher.sys.Theme

/**
 * 全息辉光绘制工具。
 * 核心思想：同一形状绘制三层 —— 外层宽模糊（低 alpha）、中层窄模糊、内层亮芯。
 *
 * 颜色全部改为从 [Theme] 动态读取：狂暴态切换时所有渲染自动换色。
 */
object Glow {

    val CYAN: Int get() = Theme.accent
    val CYAN_BRIGHT: Int get() = Theme.accentBright
    val DIM: Int get() = Theme.dim
    val PANEL_FILL: Int get() = Theme.panel
    val TEXT: Int get() = Theme.text
    val TEXT_DIM: Int get() = Theme.textDim
    val BG: Int get() = Theme.bg
    val RULE: Int get() = Theme.rule

    /** 给 View 套一层全息玻璃背景（圆角 + 辉光边框） */
    fun panel(v: android.view.View, color: Int, radiusPx: Float) {
        v.background = PanelDrawable(color, radiusPx)
    }

    private class PanelDrawable(private val accent: Int, private val radius: Float) :
        android.graphics.drawable.Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(20, Color.red(accent), Color.green(accent), Color.blue(accent))
        }
        private val glow = Glow.paint(accent, 2f, 10f, 70)
        private val core = Glow.paint(accent, 1f, 0f, 130)
        override fun draw(c: Canvas) {
            val r = RectF(1f, 1f, bounds.width().toFloat() - 1f, bounds.height().toFloat() - 1f)
            c.drawRoundRect(r, radius, radius, fill)
            c.drawRoundRect(r, radius, radius, glow)
            c.drawRoundRect(r, radius, radius, core)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun paint(color: Int, strokeW: Float, blur: Float, alpha: Int = 255): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        p.strokeWidth = strokeW
        p.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        return p
    }

    fun fill(color: Int, alpha: Int = 255, blur: Float = 0f): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.FILL
        p.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        return p
    }

    /** 文字画笔（等宽字体，科幻感） */
    fun textPaint(color: Int, sizePx: Float, bold: Boolean = false, alpha: Int = 255): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        p.textSize = sizePx
        p.typeface = if (bold)
            Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        else Typeface.MONOSPACE
        return p
    }

    /** 画一条带辉光的线 */
    fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float,
             color: Int, width: Float, glow: Float = width * 8f, coreAlpha: Int = 255) {
        c.drawLine(x1, y1, x2, y2, paint(color, width * 3f, glow, (coreAlpha * 0.25f).toInt()))
        c.drawLine(x1, y1, x2, y2, paint(color, width * 1.4f, glow * 0.4f, (coreAlpha * 0.5f).toInt()))
        c.drawLine(x1, y1, x2, y2, paint(color, width, 0f, coreAlpha))
    }

    /** 画一个带辉光的圆环 */
    fun ring(c: Canvas, cx: Float, cy: Float, r: Float, color: Int,
             width: Float, alpha: Float = 1f) {
        if (r <= 0f) return
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawCircle(cx, cy, r, paint(color, width * 3f, width * 7f, (a * 0.22f).toInt()))
        c.drawCircle(cx, cy, r, paint(color, width * 1.4f, width * 2.5f, (a * 0.55f).toInt()))
        c.drawCircle(cx, cy, r, paint(color, width, 0f, a))
    }

    /** 画圆角矩形面板（玻璃拟态） */
    fun panel(c: Canvas, rect: RectF, radius: Float, fillColor: Int, fillAlpha: Int,
              borderColor: Int, borderAlpha: Int, glow: Float = 6f) {
        val r = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(fillAlpha, Color.red(fillColor), Color.green(fillColor), Color.blue(fillColor))
        }
        c.drawRoundRect(rect, radius, radius, r)
        c.drawRoundRect(rect, radius, radius, paint(borderColor, 2f, glow, (borderAlpha * 0.5f).toInt()))
        c.drawRoundRect(rect, radius, radius, paint(borderColor, 1f, 0f, borderAlpha))
    }

    /** 画四角括号装饰 */
    fun cornerBrackets(c: Canvas, rect: RectF, len: Float, color: Int, alpha: Int, width: Float = 1.5f) {
        val p = paint(color, width, width * 3f, alpha)
        val l = rect.left; val t = rect.top; val rr = rect.right; val b = rect.bottom
        c.drawLine(l, t + len, l, t, p); c.drawLine(l, t, l + len, t, p)
        c.drawLine(rr - len, t, rr, t, p); c.drawLine(rr, t, rr, t + len, p)
        c.drawLine(l, b - len, l, b, p); c.drawLine(l, b, l + len, b, p)
        c.drawLine(rr - len, b, rr, b, p); c.drawLine(rr, b, rr, b - len, p)
    }
}