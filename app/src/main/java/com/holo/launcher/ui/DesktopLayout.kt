package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import com.holo.launcher.fx.GridBackdrop
import com.holo.launcher.sys.Theme
import kotlin.math.min

/**
 * 主从结构桌面布局。
 *
 * 垂直排列五段：
 *  ┌──────────────────────────────┐
 *  │ HUD 行（时间 / 状态）  48dp  │
 *  ├───────────────┬──────────────┤
 *  │ 信息舱 2/3    │ 快捷舱 1/3   │  88dp
 *  ├───────────────┴──────────────┤
 *  │ 通知舱（横滑消息流）    96dp │
 *  ├──────────────────────────────┤
 *  │ 应用舱（剩余空间，竖滑网格） │
 *  └──────────────────────────────┘
 *                              AI 球右下浮层（不在本容器内）
 *
 * 所有间距与切角严格遵循设计规范：面板 20dp 圆角对应 10dp 切角。
 */
class DesktopLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ViewGroup(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private fun dp(v: Float) = v * density

    /** 外边距 16dp */
    private val margin = dp(16f)

    /** 段间距 12dp */
    private val gap = dp(12f)

    /** HUD 行高 48dp */
    private val hudH = dp(48f)

    /** 信息舱/快捷舱行高 88dp */
    private val infoH = dp(88f)

    /** 通知舱高 96dp */
    private val noteH = dp(96f)

    /** 面板切角 10dp */
    private val cut = dp(10f)

    /** 顶栏额外留白（刘海），由外部 insets 设置 */
    private var insetTop = 0

    /** 底部额外留白（导航栏），由外部 insets 设置 */
    private var insetBottom = 0

    /** AI 球占用高度，应用舱底部让位 */
    private var aiReserve = dp(72f)

    private val gridPaint = Paint()

    private val themeListener: () -> Unit = { invalidate() }

    // 子 View 索引约定
    private val hudIdx = 0
    private val infoIdx = 1
    private val quickIdx = 2
    private val noteIdx = 3
    private val appsIdx = 4

    /** 背景网格：作为独立 View 由外部 attach，此处只画背景色 */
    private var backdrop: GridBackdrop? = null

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        super.onDetachedFromWindow()
    }

    /** 由 Activity 在应用 insets 后调用 */
    fun setInsets(top: Int, bottom: Int) {
        if (insetTop == top && insetBottom == bottom) return
        insetTop = top
        insetBottom = bottom
        requestLayout()
    }

    fun setAiReserve(px: Int) {
        if (aiReserve == px.toFloat()) return
        aiReserve = px.toFloat()
        requestLayout()
    }

    fun attachBackdrop(v: GridBackdrop) {
        backdrop = v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)

        val contentW = (w - margin * 2).toInt().coerceAtLeast(1)
        val infoW = (contentW * 2f / 3f).toInt()
        val quickW = contentW - infoW - gap.toInt()

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            when (i) {
                hudIdx -> c.measure(
                    MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(hudH.toInt(), MeasureSpec.EXACTLY)
                )

                infoIdx -> c.measure(
                    MeasureSpec.makeMeasureSpec(infoW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(infoH.toInt(), MeasureSpec.EXACTLY)
                )

                quickIdx -> c.measure(
                    MeasureSpec.makeMeasureSpec(quickW.coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(infoH.toInt(), MeasureSpec.EXACTLY)
                )

                noteIdx -> c.measure(
                    MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(noteH.toInt(), MeasureSpec.EXACTLY)
                )

                appsIdx -> {
                    val used = margin + hudH + gap + infoH + gap + noteH + gap
                    val avail = (h - used - insetBottom - margin - aiReserve).toInt()
                    c.measure(
                        MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(avail.coerceAtLeast(1), MeasureSpec.EXACTLY)
                    )
                }

                else -> c.measure(
                    MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
            }
        }

        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val contentW = (r - l - margin * 2).toInt().coerceAtLeast(1)
        val infoW = (contentW * 2f / 3f).toInt()
        val quickW = contentW - infoW - gap.toInt()

        var y = insetTop + margin

        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            when (i) {
                hudIdx -> {
                    c.layout(margin.toInt(), y.toInt(), (margin + contentW).toInt(), (y + hudH).toInt())
                    y += hudH + gap
                }

                infoIdx -> {
                    c.layout(margin.toInt(), y.toInt(), (margin + infoW).toInt(), (y + infoH).toInt())
                }

                quickIdx -> {
                    val x = margin + infoW + gap
                    c.layout(x.toInt(), y.toInt(), (x + quickW).toInt(), (y + infoH).toInt())
                    y += infoH + gap
                }

                noteIdx -> {
                    c.layout(margin.toInt(), y.toInt(), (margin + contentW).toInt(), (y + noteH).toInt())
                    y += noteH + gap
                }

                appsIdx -> {
                    val hApp = c.measuredHeight.toFloat()
                    c.layout(margin.toInt(), y.toInt(), (margin + contentW).toInt(), (y + hApp).toInt())
                }

                else -> {
                    val hc = c.measuredHeight.toFloat()
                    c.layout(margin.toInt(), y.toInt(), (margin + contentW).toInt(), (y + hc).toInt())
                    y += hc + gap
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // 底色
        canvas.drawColor(Theme.bg)

        // 背景网格（若未外挂 GridBackdrop，则在此自绘，省一个 View 层级）
        if (backdrop == null) {
            drawGrid(canvas, width.toFloat(), height.toFloat())
        }

        // 给面板之间留出的"分缝"画 1px 冷光线，强化结构感
        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f
        gridPaint.color = Theme.alpha(Theme.accent, 0.06f)
        val contentW = (width - margin * 2).toInt().coerceAtLeast(1)
        val infoW = (contentW * 2f / 3f).toInt()
        val yInfo = insetTop + margin + hudH + gap

        // 信息舱与快捷舱之间的竖向分隔
        val sepX = margin + infoW + gap / 2f
        canvas.drawLine(sepX, yInfo + cut, sepX, yInfo + infoH - cut, gridPaint)

        super.onDraw(canvas)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        val minor = dp(12f)
        val major = dp(60f)

        gridPaint.style = Paint.Style.STROKE
        gridPaint.strokeWidth = 1f

        gridPaint.color = Theme.alpha(Theme.accent, 0.030f)
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += minor
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += minor
        }

        gridPaint.color = Theme.alpha(Theme.accent, 0.060f)
        x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += major
        }
        y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += major
        }
    }

    /** 供 InfoCapsule 展开时计算可用高度 */
    fun expandedPanelRect(): RectF {
        val w = width.toFloat()
        val top = insetTop + margin + hudH + gap
        val maxH = (height - top - insetBottom - margin - aiReserve)
        val targetH = min(maxH, height * 0.58f)
        val left = dp(24f)
        return RectF(left, top, w - left, top + targetH)
    }
}