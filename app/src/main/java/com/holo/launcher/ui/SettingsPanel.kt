package com.holo.launcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.sys.PermGuide
import com.holo.launcher.sys.Theme

/**
 * 全屏设置面板：系统权限 / AI 配置 / Monster 说明 / 关于。
 * 不由 MainActivity 的布局托管，独立作为一个覆盖层 View。
 */
class SettingsPanel(context: Context) : View(context) {

    private val panel = ChamferPanel

    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textSize = sp(9f)
        letterSpacing = 0.18f
    }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pDesc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
    }
    private val pValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    private val pRule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }

    private val rowRect = RectF()
    private var pressedIdx = -1
    private var downX = 0f
    private var downY = 0f

    var onClose: (() -> Unit)? = null
    var onAiConfig: (() -> Unit)? = null
    var onMonsterInfo: (() -> Unit)? = null

    private data class Row(
        val title: String,
        val desc: String,
        val action: () -> Unit
    )

    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun rows(): List<Row> {
        val c = context
        val granted = PermGuide.isNotificationGranted(c)
        return listOf(
            Row("通知读取权限", if (granted) "已授予 · 通知可流入桌面" else "未授予 · 通知舱为空", {
                PermGuide.notification(c)
            }),
            Row("悬浮窗权限", "用于展开面板与 AI 控制台上层显示", { PermGuide.overlay(c) }),
            Row("使用情况访问", "读取当前前台应用，用于小部件与统计", { PermGuide.usage(c) }),
            Row("位置权限", "天气与位置胶囊需要", { PermGuide.location(c) }),
            Row("设为默认桌面", "按 Home 键回到 Holo", { PermGuide.home(c) }),
            Row("电池优化白名单", "避免后台被清理，通知监听更稳定", { PermGuide.battery(c) }),
            Row("AI 服务", "配置 API Key 后启用自然语言与语音指令", { onAiConfig?.invoke() ?: Unit }),
            Row("Monster 模式", "高频刷新与更强动效，可在 QUICK 舱首槽开关", {
                onMonsterInfo?.invoke() ?: Unit
            })
        )
    }

    /** 权限状态会变化，重新进入面板时刷新。 */
    fun refresh() {
        rowsCacheRef.clear()
        invalidate()
    }

    private val rowsCacheRef = ArrayList<Row>()

    private fun currentRows(): List<Row> {
        if (rowsCacheRef.isEmpty()) rowsCacheRef.addAll(rows())
        return rowsCacheRef
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Theme.bg)

        val side = dp(18f)
        val top = dp(24f)

        // 顶部标题
        pLabel.color = Theme.accent
        canvas.drawText("SYSTEM // CONTROL", side, top + sp(9f), pLabel)

        pTitle.color = Theme.text
        canvas.drawText("设置", side, top + sp(9f) + dp(34f), pTitle)

        // 关闭按钮区域（右上）
        pLabel.color = Theme.textDim
        pLabel.textAlign = Paint.Align.RIGHT
        canvas.drawText("关闭 ✕", w - side, top + sp(9f), pLabel)
        pLabel.textAlign = Paint.Align.LEFT

        // 分隔线
        var y = top + sp(9f) + dp(50f)
        pRule.color = Theme.alpha(Theme.rule, 0.9f)
        canvas.drawLine(side, y, w - side, y, pRule)

        y += dp(10f)

        val list = currentRows()
        val rowH = dp(52f)
        val cut = dp(8f)

        for (i in list.indices) {
            rowRect.set(side, y, w - side, y + rowH)

            // 行底：按下时极淡高亮
            val fillColor = when {
                pressedIdx == i -> Theme.alpha(Theme.accent, 0.10f)
                i % 2 == 0 -> Theme.alpha(Theme.panelFill(false), 0.55f)
                else -> 0
            }
            if (fillColor != 0) {
                panel.drawPanel(
                    canvas, rowRect, cut, fillColor,
                    Theme.alpha(Theme.panelStroke(), 0.7f), 0, 0f, 1f
                )
            }

            val tx = side + dp(12f)
            pTitle.color = Theme.text
            pTitle.textSize = sp(13f)
            canvas.drawText(list[i].title, tx, y + dp(21f), pTitle)

            pDesc.color = Theme.textDim
            canvas.drawText(list[i].desc, tx, y + dp(38f), pDesc)

            pValue.color = Theme.textFaint
            canvas.drawText("›", w - side - dp(12f), y + dp(30f), pValue)

            y += rowH + dp(6f)
            if (y > h - dp(20f)) break
        }

        // 底部版本信息
        pLabel.color = Theme.textFaint
        canvas.drawText("HOLO SHELL v2.0", side, h - dp(18f), pLabel)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val side = dp(18f)
        val top = dp(24f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                // 右上关闭
                if (event.x > w - dp(80f) && event.y < top + dp(40f)) {
                    pressedIdx = -2
                    invalidate()
                    return true
                }
                pressedIdx = indexAt(event.x, event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedIdx >= 0 && indexAt(event.x, event.y) != pressedIdx) {
                    pressedIdx = -1
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(event.x - downX)
                val dy = Math.abs(event.y - downY)
                val idx = pressedIdx
                pressedIdx = -1
                invalidate()

                if (dx > dp(14f) || dy > dp(14f)) {
                    // 下滑关闭
                    if (dy > dp(90f) && event.y > downY) onClose?.invoke()
                    return true
                }
                if (idx == -2) {
                    onClose?.invoke()
                    return true
                }
                if (idx >= 0) {
                    val list = currentRows()
                    if (idx < list.size) {
                        list[idx].action.invoke()
                    }
                    return true
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedIdx = -1
                invalidate()
                return true
            }
        }
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        val w = width.toFloat()
        val side = dp(18f)
        val top = dp(24f)
        if (x < side || x > w - side) return -1

        var cy = top + sp(9f) + dp(50f) + dp(10f)
        val rowH = dp(52f)
        val list = currentRows()
        for (i in list.indices) {
            if (y >= cy && y <= cy + rowH) return i
            cy += rowH + dp(6f)
        }
        return -1
    }
}
