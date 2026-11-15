package com.holo.launcher.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.holo.launcher.sys.Theme

/**
 * 切角面板绘制工具。
 *
 * 设计规则（"科技感不塑料感"的核心）：
 *  - 左上、右下切 45 度斜角；左下、右上为直角
 *  - 底色是极暗灰阶实色，不用渐变、不用半透明白
 *  - 描边 1px 冷灰，不发光、不加 blur
 *  - 硬投影：偏移 2dp 的纯色块，不做模糊
 *
 * 本对象只做绘制计算，不持有状态，可被任意 View 调用。
 */
object ChamferPanel {

    /** 切角大小（px）。默认按 10dp 由调用方换算后传入。 */
    const val DEFAULT_CUT_DP = 10f

    /**
     * 构建切角路径。
     * 上左 + 下右切角，上右 + 下左直角。
     */
    fun buildPath(r: RectF, cut: Float): Path {
        val c = cut.coerceAtLeast(0f)
        val p = Path()
        // 从左中偏上开始：左上斜角起点
        p.moveTo(r.left + c, r.top)
        // 上边 -> 右上角（直角）
        p.lineTo(r.right, r.top)
        // 右边向下
        p.lineTo(r.right, r.bottom - c)
        // 右下斜角
        p.lineTo(r.right - c, r.bottom)
        // 下边 -> 左下角（直角）
        p.lineTo(r.left, r.bottom)
        // 左边向上
        p.lineTo(r.left, r.top + c)
        p.close()
        return p
    }

    /**
     * 绘制完整面板：投影 -> 底色 -> 描边。
     * @param shadowOffset 投影偏移 px，传 0 表示不画投影
     */
    fun drawPanel(
        canvas: Canvas,
        r: RectF,
        cut: Float,
        fillColor: Int,
        strokeColor: Int,
        shadowColor: Int = 0xFF000000.toInt(),
        shadowOffset: Float = 0f,
        strokeWidth: Float = 1f
    ) {
        // 1) 硬投影：整体平移绘制，不做 blur（这是"不塑料"的关键）
        if (shadowOffset > 0f) {
            val sr = RectF(r)
            sr.offset(shadowOffset, shadowOffset)
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.style = Paint.Style.FILL
            sp.color = shadowColor
            canvas.drawPath(buildPath(sr, cut), sp)
        }

        val path = buildPath(r, cut)

        // 2) 实色底
        val fp = Paint(Paint.ANTI_ALIAS_FLAG)
        fp.style = Paint.Style.FILL
        fp.color = fillColor
        canvas.drawPath(path, fp)

        // 3) 细描边
        val st = Paint(Paint.ANTI_ALIAS_FLAG)
        st.style = Paint.Style.STROKE
        st.strokeWidth = strokeWidth
        st.color = strokeColor
        canvas.drawPath(path, st)
    }

    /**
     * 只画描边（用于叠加层或高亮态）。
     */
    fun drawOutline(
        canvas: Canvas,
        r: RectF,
        cut: Float,
        color: Int,
        strokeWidth: Float = 1f
    ) {
        val st = Paint(Paint.ANTI_ALIAS_FLAG)
        st.style = Paint.Style.STROKE
        st.strokeWidth = strokeWidth
        st.color = color
        canvas.drawPath(buildPath(r, cut), st)
    }

    /**
     * 绘制目标面板的默认配色（从 Theme 取，Monster 模式自动换色）。
     */
    fun drawThemed(
        canvas: Canvas,
        r: RectF,
        cut: Float,
        elevated: Boolean = false,
        shadowDp: Float = 0f,
        density: Float = 1f
    ) {
        drawPanel(
            canvas = canvas,
            r = r,
            cut = cut,
            fillColor = Theme.panelFill(elevated),
            strokeColor = Theme.panelStroke(),
            shadowOffset = shadowDp * density
        )
    }
}
