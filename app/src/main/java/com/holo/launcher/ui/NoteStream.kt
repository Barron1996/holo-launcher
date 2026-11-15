package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.notification.HoloNote
import com.holo.launcher.notification.NoteRepo
import com.holo.launcher.sys.Theme

/**
 * 通知舱。
 *
 * 全宽、96dp 高。竖向最多堆 3 条，超出折叠成 "+N"。
 * 每条：左侧 2dp 强调竖条 + 应用名(10sp) + 标题(12sp) + 正文(11sp 单行省略)。
 * 有 hasReply 的条目右侧画一个细描边小胶囊，点击回调 onReply。
 * 交互：横向滑动超过 72dp 移除；点击整行回调 onOpen。
 */
class NoteStream @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val panel = ChamferPanel
    private val body = RectF()
    private val rowRect = RectF()
    private val pillRect = RectF()

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSmall = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMicro = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBar = Paint(Paint.ANTI_ALIAS_FLAG)

    private val mono: Typeface = Typeface.MONOSPACE

    private var notes: List<HoloNote> = emptyList()

    /** 手指按下时的 y 与命中的条目 index */
    private var downX = 0f
    private var downY = 0f
    private var hitIdx = -1
    private var dragX = 0f

    var onReply: ((HoloNote) -> Unit)? = null
    var onOpen: ((HoloNote) -> Unit)? = null
    var onNeedPermission: (() -> Unit)? = null

    private val themeListener: () -> Unit = { invalidate() }

    /** 每行高度 24dp（3 行 = 72dp，正好塞进 96dp 内） */
    private val rowH = dp(24f)
    private val padTop = dp(12f)
    private val padSide = dp(12f)

    init {
        setWillNotDraw(false)
        isClickable = true
        pText.textSize = dp(12f)
        pText.typeface = Typeface.DEFAULT
        pSmall.textSize = dp(10f)
        pSmall.typeface = mono
        pMicro.textSize = dp(9f)
        pMicro.typeface = mono
        pBar.style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
        NoteRepo.setChangeListener(themeListener)
        refresh()
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        NoteRepo.setChangeListener(null)
        super.onDetachedFromWindow()
    }

    fun refresh() {
        notes = NoteRepo.all()
        invalidate()
    }

    // ---------- 测量 ----------

    private fun visibleRows(): Int = minOf(notes.size, 3)

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        body.set(0f, 0f, w, h)
        panel.drawThemed(canvas, body, dp(10f), false, 2f, density)

        if (notes.isEmpty()) {
            drawEmpty(canvas, w, h)
            return
        }

        val enabled = NoteRepo.isEnabled(context)
        if (!enabled) {
            drawPermHint(canvas, w, h)
            return
        }

        val n = visibleRows()
        var y = padTop
        for (i in 0 until n) {
            val item = notes[i]
            val alpha = if (i == hitIdx) 1f - (kotlin.math.abs(dragX) / dp(240f)).coerceIn(0f, 1f) else 1f
            drawRow(canvas, item, y, w, alpha)
            y += rowH
        }

        // 超出部分折叠提示
        val rest = notes.size - n
        if (rest > 0) {
            pMicro.color = Theme.alpha(Theme.textFaint, 1f)
            pMicro.textAlign = Paint.Align.RIGHT
            canvas.drawText("+$rest", w - padSide, padTop + rowH * n + dp(14f), pMicro)
            pMicro.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawRow(canvas: Canvas, item: HoloNote, top: Float, w: Float, alpha: Float) {
        val left = padSide - dragX.coerceAtMost(0f)
        rowRect.set(left, top, w - padSide, top + rowH - dp(3f))

        // 左侧 2dp 强调竖条
        pBar.color = Theme.alpha(if (Theme.isBerserk) Theme.warn else Theme.accent, 0.75f * alpha)
        canvas.drawRect(rowRect.left, rowRect.top + dp(3f),
            rowRect.left + dp(2f), rowRect.bottom - dp(2f), pBar)

        val tx = rowRect.left + dp(8f)
        val right = rowRect.right

        // 应用名（等宽小字）
        pSmall.color = Theme.alpha(Theme.textDim, alpha)
        canvas.drawText(item.appLabel.uppercase(), tx, rowRect.top + dp(9f), pSmall)

        // 时间（右对齐）
        pMicro.color = Theme.alpha(Theme.textFaint, alpha)
        pMicro.textAlign = Paint.Align.RIGHT
        canvas.drawText(fmtTime(item.time), right, rowRect.top + dp(9f), pMicro)
        pMicro.textAlign = Paint.Align.LEFT

        // 标题（单行省略）
        pText.color = Theme.alpha(Theme.text, alpha)
        val titleMax = right - tx
        val title = if (item.title.isBlank()) item.text else item.title
        val shown = ellipsize(title, pText, titleMax)
        canvas.drawText(shown, tx, rowRect.top + dp(20f), pText)

        // 可回复标记
        if (item.hasReply) {
            val pr = dp(26f)
            pillRect.set(right - pr, rowRect.top + dp(2f), right, rowRect.top + dp(2f) + dp(11f))
            pBar.color = Theme.alpha(Theme.accent, 0.14f * alpha)
            panel.drawPanel(canvas, pillRect, dp(3f), pBar.color, Theme.alpha(Theme.accent, 0.4f * alpha))
            pMicro.color = Theme.alpha(Theme.accent, alpha)
            pMicro.textAlign = Paint.Align.CENTER
            canvas.drawText("回", pillRect.centerX(), pillRect.centerY() + dp(3.2f), pMicro)
            pMicro.textAlign = Paint.Align.LEFT
        }

        // 分隔线
        pBar.color = Theme.alpha(Theme.rule, 0.5f * alpha)
        canvas.drawRect(tx, rowRect.bottom, right, rowRect.bottom + 1f, pBar)
    }

    private fun drawEmpty(canvas: Canvas, w: Float, h: Float) {
        pSmall.color = Theme.textFaint
        pSmall.textAlign = Paint.Align.LEFT
        canvas.drawText("NOTIFICATIONS", padSide, padTop + dp(9f), pSmall)
        pText.color = Theme.textDim
        canvas.drawText("暂无通知", padSide, padTop + dp(30f), pText)

        // 装饰性空槽线，避免大片空白
        pBar.color = Theme.alpha(Theme.rule, 0.35f)
        for (i in 1..2) {
            val yy = padTop + dp(30f) + dp(16f) * i
            if (yy < h - dp(6f)) canvas.drawRect(padSide, yy, w - padSide, yy + 1f, pBar)
        }
    }

    private fun drawPermHint(canvas: Canvas, w: Float, h: Float) {
        pSmall.color = Theme.textFaint
        canvas.drawText("NOTIFICATIONS", padSide, padTop + dp(9f), pSmall)

        pText.color = Theme.warn
        canvas.drawText("通知读取权限未授予", padSide, padTop + dp(30f), pText)

        pMicro.color = Theme.textDim
        canvas.drawText("点击前往授权", padSide, padTop + dp(48f), pMicro)

        // 右侧箭头
        pBar.color = Theme.alpha(Theme.warn, 0.5f)
        val ax = w - padSide - dp(10f)
        val ay = padTop + dp(26f)
        canvas.drawRect(ax, ay, ax + dp(8f), ay + 1f, pBar)
    }

    // ---------- 工具 ----------

    /** 按宽度截断并用 … 结尾（自己实现，避免 TextUtils.ellipsize 需要 TextPaint） */
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

    private fun fmtTime(t: Long): String {
        val d = java.util.Date(t)
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
        return f.format(d)
    }

    // ---------- 触摸 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragX = 0f
                hitIdx = rowAt(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = e.x - downX
                if (hitIdx >= 0) invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (hitIdx < 0) {
                    // 空白处点击 → 若未授权，引导权限
                    if (notes.isEmpty() || !NoteRepo.isEnabled(context)) onNeedPermission?.invoke()
                } else if (kotlin.math.abs(dx) > dp(72f)) {
                    val n = notes.getOrNull(hitIdx)
                    if (n != null) {
                        NoteRepo.remove(n.key)
                        refresh()
                    }
                } else if (kotlin.math.abs(dy) < dp(12f)) {
                    val n = notes.getOrNull(hitIdx)
                    if (n != null) {
                        if (n.hasReply && dx < 0f) onReply?.invoke(n) else onOpen?.invoke(n)
                    }
                }
                dragX = 0f
                hitIdx = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragX = 0f; hitIdx = -1; invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun rowAt(y: Float): Int {
        val rel = y - padTop
        if (rel < 0) return -1
        val i = (rel / rowH).toInt()
        return if (i in 0 until visibleRows()) i else -1
    }
}