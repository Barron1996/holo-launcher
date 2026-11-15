package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.holo.launcher.apps.AppEntry
import com.holo.launcher.apps.AppRepository
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.sys.Theme

/**
 * 应用舱。
 *
 * 占剩余全部空间。4 列 × N 行网格，每页最多 4×5=20 个。
 * 每格：44dp 图标 + 下方 10sp 标签（单行省略）。
 * 左右滑动翻页，底部画页码点。长按回调 onLongPress(entry)。
 */
class AppCapsule @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val panel = ChamferPanel
    private val body = RectF()
    private val iconRect = Rect()

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pTag = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mono: Typeface = Typeface.MONOSPACE

    private var apps: List<AppEntry> = emptyList()

    private var page = 0
    private var pageCount = 1

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragX = 0f
    private var hitIndex = -1

    var onOpen: ((AppEntry) -> Unit)? = null
    var onLongPress: ((AppEntry) -> Unit)? = null

    private val themeListener: () -> Unit = { invalidate() }

    private val cols = 4
    private val rows = 5
    private val perPage = cols * rows

    private val padSide = dp(14f)
    private val padTop = dp(14f)
    private val padBottom = dp(20f)   // 给页码点留位
    private val labelH = dp(16f)

    init {
        setWillNotDraw(false)
        isClickable = true
        pText.textSize = dp(10f)
        pText.typeface = Typeface.DEFAULT
        pText.textAlign = Paint.Align.CENTER
        pTag.textSize = dp(9f)
        pTag.typeface = mono
        pBar.style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
        reload()
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        super.onDetachedFromWindow()
    }

    fun reload() {
        apps = AppRepository.cached()
        pageCount = if (apps.isEmpty()) 1 else ((apps.size + perPage - 1) / perPage)
        if (page >= pageCount) page = (pageCount - 1).coerceAtLeast(0)
        invalidate()
    }

    private fun pageItems(): List<AppEntry> {
        if (apps.isEmpty()) return emptyList()
        val s = page * perPage
        val e = minOf(s + perPage, apps.size)
        if (s >= e) return emptyList()
        return apps.subList(s, e)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        body.set(0f, 0f, w, h)
        panel.drawThemed(canvas, body, dp(10f), false, 2f, density)

        val items = pageItems()
        if (items.isEmpty()) {
            drawEmpty(canvas, w, h)
            return
        }

        val gridW = w - padSide * 2
        val gridH = h - padTop - padBottom
        val cw = gridW / cols
        val ch = gridH / rows

        for (i in items.indices) {
            val r = i / cols
            val c = i % cols
            val cx = padSide + c * cw
            val cy = padTop + r * ch
            drawApp(canvas, items[i], cx, cy, cw, ch, i == hitIndex)
        }

        // 翻页时轻微滑动视差
        if (kotlin.math.abs(dragX) > 1f) {
            pBar.color = Theme.alpha(Theme.accent, 0.03f)
            val edgeW = dp(10f)
            if (dragX < 0f) canvas.drawRect(w - edgeW, padTop, w, h - padBottom, pBar)
            else canvas.drawRect(0f, padTop, edgeW, h - padBottom, pBar)
        }

        drawPageDots(canvas, w, h)
    }

    private fun drawApp(
        canvas: Canvas,
        app: AppEntry,
        x: Float, y: Float,
        cw: Float, ch: Float,
        pressed: Boolean
    ) {
        val iconSize = minOf(cw - dp(12f), dp(44f), ch - labelH - dp(10f))
        val iconCx = x + cw / 2f
        val iconCy = y + (ch - labelH) / 2f

        // 按下时在图标下垫一块极淡的方底 —— 这是"按下去"的唯一反馈，不加缩放
        if (pressed) {
            val hb = RectF(
                iconCx - iconSize / 2f - dp(3f),
                iconCy - iconSize / 2f - dp(3f),
                iconCx + iconSize / 2f + dp(3f),
                iconCy + iconSize / 2f + dp(3f)
            )
            pBar.color = Theme.alpha(Theme.accent, 0.10f)
            panel.drawPanel(canvas, hb, dp(6f), pBar.color, Theme.panelStroke())
        }

        val d: Drawable = AppRepository.iconOf(context, app)
        val half = (iconSize / 2f).toInt()
        iconRect.set(
            (iconCx - half).toInt(),
            (iconCy - half).toInt(),
            (iconCx + half).toInt(),
            (iconCy + half).toInt()
        )
        d.setBounds(iconRect)
        d.draw(canvas)

        // 标签
        pText.color = if (pressed) Theme.text else Theme.textDim
        val label = ellipsize(app.label, pText, cw - dp(4f))
        canvas.drawText(label, iconCx, y + ch - dp(4f), pText)
    }

    private fun drawPageDots(canvas: Canvas, w: Float, h: Float) {
        if (pageCount <= 1) {
            // 单页时画一条细刻度，保持"仪表"气质
            pBar.color = Theme.alpha(Theme.rule, 0.6f)
            val cy = h - dp(10f)
            canvas.drawRect(w / 2f - dp(12f), cy, w / 2f + dp(12f), cy + 1f, pBar)
            return
        }
        val gap = dp(6f)
        val totalW = pageCount * gap * 2 - gap
        var cx = w / 2f - totalW / 2f
        val cy = h - dp(10f)
        for (i in 0 until pageCount) {
            pBar.color = if (i == page) Theme.accent else Theme.alpha(Theme.textFaint, 0.9f)
            val rw = if (i == page) gap * 1.8f else gap
            canvas.drawRect(cx, cy, cx + rw, cy + dp(1.5f), pBar)
            cx += gap * 2
        }
    }

    private fun drawEmpty(canvas: Canvas, w: Float, h: Float) {
        pTag.color = Theme.textFaint
        pTag.textAlign = Paint.Align.LEFT
        canvas.drawText("APPS", padSide, padTop + dp(9f), pTag)
        pText.color = Theme.textDim
        canvas.drawText("正在载入应用列表…", w / 2f, h / 2f, pText)

        pBar.color = Theme.alpha(Theme.rule, 0.35f)
        val cy = h / 2f + dp(14f)
        canvas.drawRect(w / 2f - dp(30f), cy, w / 2f + dp(30f), cy + 1f, pBar)
    }

    private fun ellipsize(src: String, paint: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (paint.measureText(src) <= maxW) return src
        val dots = "…"
        val dotsW = paint.measureText(dots)
        var w = 0f
        var i = 0
        while (i < src.length) {
            val cw = paint.measureText(src, i, i + 1)
            if (w + cw + dotsW > maxW) break
            w += cw
            i++
        }
        return if (i <= 0) "" else src.substring(0, i) + dots
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragX = 0f
                downTime = System.currentTimeMillis()
                hitIndex = indexAt(e.x, e.y)
                if (hitIndex >= 0) invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = e.x - downX
                if (kotlin.math.abs(dragX) > dp(10f) && hitIndex >= 0) {
                    hitIndex = -1
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val dt = System.currentTimeMillis() - downTime
                val items = pageItems()

                if (kotlin.math.abs(dx) > dp(56f) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    // 翻页
                    if (dx < 0f) {
                        if (page < pageCount - 1) { page++; invalidate() }
                    } else {
                        if (page > 0) { page--; invalidate() }
                    }
                } else if (hitIndex >= 0 && hitIndex < items.size &&
                    kotlin.math.abs(dx) < dp(12f) && kotlin.math.abs(dy) < dp(12f)
                ) {
                    val app = items[hitIndex]
                    if (dt >= 500L) onLongPress?.invoke(app) else onOpen?.invoke(app)
                } else if (hitIndex >= 0 && hitIndex < items.size && dt >= 500L) {
                    onLongPress?.invoke(items[hitIndex])
                }

                dragX = 0f
                hitIndex = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragX = 0f; hitIndex = -1; invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun indexAt(x: Float, y: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        val gridW = w - padSide * 2
        val gridH = h - padTop - padBottom
        if (x < padSide || x > w - padSide) return -1
        if (y < padTop || y > padTop + gridH) return -1
        val cw = gridW / cols
        val ch = gridH / rows
        val c = ((x - padSide) / cw).toInt().coerceIn(0, cols - 1)
        val r = ((y - padTop) / ch).toInt().coerceIn(0, rows - 1)
        val idx = r * cols + c
        return if (idx < pageItems().size) idx else -1
    }
}