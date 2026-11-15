package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.sys.Theme

/**
 * 快捷舱。
 *
 * 1/3 宽、88dp 高，竖排 3 个开关位：
 *   [0] MONSTER（固定不可删，闪电图标）
 *   [1] 手电筒
 *   [2] 自定义槽位（可添加，用 + 占位）
 *
 * 右上角另有一个小齿轮，点击回调 onOpenSettings。
 */
class QuickCapsule @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cut = dp(10f)
    private val panel = ChamferPanel()

    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }
    private val pIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val themeListener: () -> Unit = { invalidate() }

    /** 自定义槽位（最多 2 个，存 label） */
    private val customSlots = ArrayList<String>()

    /** 每个开关的命中矩形 */
    private val hitRects = ArrayList<RectF>()

    private val gearRect = RectF()

    var onMonsterToggle: (() -> Unit)? = null
    var onTorchToggle: (() -> Unit)? = null
    var onCustomTap: ((Int) -> Unit)? = null
    var onAddCustom: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null

    private var torchOn = false

    init {
        setWillNotDraw(false)
        isClickable = true
        // 读回已保存的自定义槽位
        val saved = prefs().getString("quick_slots", "") ?: ""
        if (saved.isNotBlank()) {
            for (s in saved.split("|")) if (s.isNotBlank()) customSlots.add(s)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        super.onDetachedFromWindow()
    }

    fun setTorchState(on: Boolean) {
        torchOn = on
        invalidate()
    }

    fun addSlot(label: String): Boolean {
        if (customSlots.size >= 2) return false
        customSlots.add(label)
        saveSlots()
        invalidate()
        return true
    }

    fun slots(): List<String> = ArrayList(customSlots)

    private fun prefs() = context.getSharedPreferences("holo_quick", Context.MODE_PRIVATE)

    private fun saveSlots() {
        prefs().edit().putString("quick_slots", customSlots.joinToString("|")).apply()
    }

    // ---------- 布局 ----------
    private fun layoutRects() {
        hitRects.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val padTop = dp(26f)          // 上边留给齿轮
        val padBottom = dp(8f)
        val usable = h - padTop - padBottom
        val count = 2 + customSlots.size
        val cellH = usable / count
        for (i in 0 until count) {
            val top = padTop + cellH * i + dp(2f)
            val bot = padTop + cellH * (i + 1) - dp(2f)
            hitRects.add(RectF(dp(8f), top, w - dp(8f), bot))
        }
        gearRect.set(w - dp(26f), dp(6f), w - dp(6f), dp(24f))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutRects()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hitRects.isEmpty()) layoutRects()

        val w = width.toFloat()
        val h = height.toFloat()
        panel.drawThemed(canvas, RectF(0f, 0f, w, h), cut, elevated = false)

        // ---- 齿轮 ----
        drawGear(canvas)

        // ---- 开关项 ----
        for (i in hitRects.indices) {
            val r = hitRects[i]
            when (i) {
                0 -> drawMonster(canvas, r)
                1 -> drawTorch(canvas, r)
                else -> drawCustom(canvas, r, customSlots[i - 2])
            }
        }

        // 若有空位且还能再加，画一个 +
        if (customSlots.size < 2) {
            val r = RectF(dp(8f), h - dp(26f), w - dp(8f), h - dp(8f))
            // 只有 3 个以上时才显示 +，避免过挤
            if (2 + customSlots.size < 4) {
                drawPlus(canvas, r)
            }
        }
    }

    private fun drawGear(canvas: Canvas) {
        val cx = gearRect.centerX()
        val cy = gearRect.centerY()
        val rad = dp(5f)
        pIcon.color = Theme.alpha(Theme.textDim, 0.9f)
        pIcon.strokeWidth = 1.3f * density
        pIcon.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, rad, pIcon)
        canvas.drawCircle(cx, cy, rad * 0.42f, pIcon)
        // 6 根齿
        for (k in 0 until 6) {
            val a = Math.toRadians(k * 60.0)
            val x1 = cx + (rad * Math.cos(a)).toFloat()
            val y1 = cy + (rad * Math.sin(a)).toFloat()
            val x2 = cx + ((rad + dp(2.2f)) * Math.cos(a)).toFloat()
            val y2 = cy + ((rad + dp(2.2f)) * Math.sin(a)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, pIcon)
        }
    }

    private fun drawMonster(canvas: Canvas, r: RectF) {
        val on = Theme.isBerserk
        val cx = r.centerX()
        val cy = r.centerY()

        // 闪电图标
        val bolt = Path()
        val s = dp(7f)
        bolt.moveTo(cx + s * 0.15f, cy - s)
        bolt.lineTo(cx - s * 0.55f, cy + s * 0.12f)
        bolt.lineTo(cx - s * 0.05f, cy + s * 0.12f)
        bolt.lineTo(cx - s * 0.18f, cy + s)
        bolt.lineTo(cx + s * 0.55f, cy - s * 0.15f)
        bolt.lineTo(cx + s * 0.02f, cy - s * 0.15f)
        bolt.close()

        pFill.style = Paint.Style.FILL
        pFill.color = if (on) Theme.warn else Theme.alpha(Theme.textDim, 0.85f)
        canvas.drawPath(bolt, pFill)

        // 文本 MONSTER
        pText.textSize = dp(7f)
        pText.color = if (on) Theme.warn else Theme.textFaint
        canvas.drawText("MONSTER", cx, r.bottom - dp(2f), pText)

        // 开启时左侧一道竖条
        if (on) {
            pFill.color = Theme.warn
            canvas.drawRect(r.left, r.top + dp(4f), r.left + dp(1.5f), r.bottom - dp(10f), pFill)
        }
    }

    private fun drawTorch(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY() - dp(3f)
        val s = dp(6f)

        pIcon.strokeWidth = 1.5f * density
        pIcon.color = if (torchOn) Theme.accent else Theme.alpha(Theme.textDim, 0.85f)
        pIcon.style = Paint.Style.STROKE

        // 手电筒：梯形灯头 + 灯身
        val head = Path()
        head.moveTo(cx - s * 0.45f, cy - s * 0.7f)
        head.lineTo(cx + s * 0.45f, cy - s * 0.7f)
        head.lineTo(cx + s * 0.3f, cy - s * 0.2f)
        head.lineTo(cx - s * 0.3f, cy - s * 0.2f)
        head.close()
        canvas.drawPath(head, pIcon)

        pFill.style = Paint.Style.FILL
        pFill.color = pIcon.color
        canvas.drawRect(cx - s * 0.18f, cy - s * 0.2f, cx + s * 0.18f, cy + s * 0.8f, pFill)

        pText.textSize = dp(7f)
        pText.color = if (torchOn) Theme.accent else Theme.textFaint
        canvas.drawText("LIGHT", cx, r.bottom - dp(2f), pText)
    }

    private fun drawCustom(canvas: Canvas, r: RectF, label: String) {
        val cx = r.centerX()
        val cy = r.centerY()

        pIcon.strokeWidth = 1.4f * density
        pIcon.style = Paint.Style.STROKE
        pIcon.color = Theme.alpha(Theme.textDim, 0.8f)

        val s = dp(5.5f)
        // 方块 + 内部小方
        canvas.drawRect(cx - s, cy - s - dp(2f), cx + s, cy + s - dp(2f), pIcon)
        canvas.drawRect(
            cx - s * 0.4f, cy - s * 0.4f - dp(2f),
            cx + s * 0.4f, cy + s * 0.4f - dp(2f), pIcon
        )

        pText.textSize = dp(7f)
        pText.color = Theme.textFaint
        val shown = if (label.length > 6) label.substring(0, 6) else label
        canvas.drawText(shown, cx, r.bottom - dp(2f), pText)
    }

    private fun drawPlus(canvas: Canvas, r: RectF) {
        val cx = r.centerX()
        val cy = r.centerY()
        pIcon.color = Theme.alpha(Theme.textFaint, 0.9f)
        pIcon.strokeWidth = 1.2f * density
        pIcon.style = Paint.Style.STROKE
        val s = dp(4f)
        canvas.drawLine(cx - s, cy, cx + s, cy, pIcon)
        canvas.drawLine(cx, cy - s, cx, cy + s, pIcon)
    }

    // ---------- 触摸 ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true

        val x = e.x
        val y = e.y

        // 齿轮
        if (gearRect.contains(x, y) && onOpenSettings != null) {
            onOpenSettings?.invoke()
            return true
        }

        for (i in hitRects.indices) {
            if (hitRects[i].contains(x, y)) {
                when (i) {
                    0 -> onMonsterToggle?.invoke()
                    1 -> onTorchToggle?.invoke()
                    else -> onCustomTap?.invoke(i - 2)
                }
                return true
            }
        }

        // + 区域
        if (customSlots.size < 2 && 2 + customSlots.size < 4) {
            val plusRect = RectF(dp(8f), height - dp(26f), width - dp(8f), height - dp(8f))
            if (plusRect.contains(x, y)) {
                onAddCustom?.invoke()
                return true
            }
        }
        return true
    }
}