package com.holo.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.holo.launcher.data.WeatherRepo
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.fx.ClockFx
import com.holo.launcher.hud.Telemetry
import com.holo.launcher.sys.Theme
import java.util.Locale
import kotlin.math.min

/**
 * 信息舱。
 *
 * 收起态（88dp 高，2/3 宽）：
 *   左：时间（大号等宽）+ 日期（小字）
 *   右：天气图标 + 温度 + 湿度 + 风力 + 空气质量条
 *
 * 展开态（约 58% 屏高）：
 *   三分区纵向排列 —— 连接区 / 定位区 / 环境区
 *   动画两段：① 180ms 边框外扩+扫描线 ② 220ms 面板放大+逐行淡入
 *   关闭：300ms 反向；横滑关闭；内部纵向滚动。
 */
class InfoCapsule @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val cut = dp(10f)

    private val panel = ChamferPanel()

    // ---------- 画笔 ----------
    private val pTime = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val pDate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.1f
    }
    private val pLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.12f
    }
    private val pValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val pLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ---------- 状态 ----------
    private var expanded = false
    private var progress = 0f
    private var scanY = 0f
    private var animator: ValueAnimator? = null
    private val themeListener: () -> Unit = { invalidate() }

    private var snap: Telemetry.Snapshot? = null
    private var weather: WeatherRepo.Data? = null

    /** 展开区域的绝对矩形（由父布局提供） */
    private val expandRect = RectF()

    /** 展开态滚动偏移 */
    private var scrollY = 0f
    private var maxScroll = 0f
    private var downY = 0f
    private var downX = 0f
    private var dragging = false

    var onRequestExpand: ((Boolean) -> Unit)? = null

    init {
        setWillNotDraw(false)
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Theme.addListener(themeListener)
    }

    override fun onDetachedFromWindow() {
        Theme.removeListener(themeListener)
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    fun setSnapshot(s: Telemetry.Snapshot) {
        snap = s
        if (expanded) invalidate()
    }

    fun setWeather(w: WeatherRepo.Data?) {
        weather = w
        invalidate()
    }

    fun setExpandRect(r: RectF) {
        expandRect.set(r)
    }

    val isExpanded: Boolean get() = expanded

    fun toggle() {
        if (expanded) collapse() else expand()
    }

    fun expand() {
        if (expanded) return
        expanded = true
        scrollY = 0f
        animateTo(1f, 400L)
        onRequestExpand?.invoke(true)
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        animateTo(0f, 300L)
        onRequestExpand?.invoke(false)
    }

    private fun animateTo(target: Float, dur: Long) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(progress, target)
        a.duration = dur
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { v ->
            progress = v.animatedValue as Float
            scanY = (progress * height).coerceIn(0f, height.toFloat())
            invalidate()
        }
        a.start()
        animator = a
    }

    // ---------- 触摸 ----------
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (expanded) {
                    if (kotlin.math.abs(dy) > dp(6f) && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                        dragging = true
                        scrollY = (scrollY - (dy - lastScrollDy)).coerceIn(0f, maxScroll)
                        lastScrollDy = dy
                        invalidate()
                    }
                } else {
                    if (kotlin.math.abs(dy) > dp(8f)) dragging = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX
                val dy = e.y - downY
                lastScrollDy = 0f
                if (expanded) {
                    // 横滑关闭
                    if (kotlin.math.abs(dx) > dp(60f) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        collapse()
                    }
                } else {
                    if (!dragging || (dy < -dp(20f) && kotlin.math.abs(dy) > kotlin.math.abs(dx))) {
                        expand()
                    }
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private var lastScrollDy = 0f

    // ---------- 绘制 ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0.01f) {
            drawCompact(canvas)
        } else {
            drawExpanded(canvas, progress)
        }
    }

    private fun drawCompact(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = RectF(0f, 0f, w, h)
        panel.drawThemed(canvas, r, cut, elevated = false)

        val padL = dp(14f)
        val now = ClockFx.time()
        val sec = ClockFx.seconds()

        // 时间
        pTime.textSize = dp(30f)
        pTime.color = Theme.text
        val timeW = pTime.measureText(now)
        canvas.drawText(now, padL, h * 0.52f, pTime)

        // 秒（小字，弱化）
        pTime.textSize = dp(12f)
        pTime.color = Theme.textFaint
        canvas.drawText(sec, padL + timeW + dp(4f), h * 0.52f, pTime)

        // 日期
        pDate.textSize = dp(9.5f)
        pDate.color = Theme.textDim
        canvas.drawText(ClockFx.date(), padL, h * 0.78f, pDate)

        // ---- 右侧天气区 ----
        val wx = w * 0.52f
        val ww = w - wx - dp(14f)

        // 温度
        val t = weather?.temp
        pValue.textSize = dp(26f)
        pValue.color = Theme.text
        val tempStr = if (t != null) "$t°" else "--°"
        canvas.drawText(tempStr, wx, h * 0.50f, pValue)
        val tempW = pValue.measureText(tempStr)

        // 天气文案
        pLabel.textSize = dp(9.5f)
        pLabel.color = Theme.textDim
        val desc = weather?.text ?: "等待信号"
        canvas.drawText(desc, wx + tempW + dp(6f), h * 0.48f, pLabel)

        // 湿度 / 风力 / 空气质量 一行小字
        pLabel.textSize = dp(9f)
        pLabel.color = Theme.textFaint
        val extras = buildString {
            weather?.humidity?.let { append("RH ").append(it).append("%  ") }
            weather?.windLevel?.let { append("W ").append(it).append("级  ") }
            val aqi = weather?.aqiText
            if (aqi != null) append(aqi)
        }.trim()
        if (extras.isNotEmpty()) {
            canvas.drawText(extras, wx, h * 0.76f, pLabel)
        }

        // 空气质量条
        val aq = weather?.aqiRatio
        if (aq != null) {
            val barY = h * 0.84f
            val barW = ww
            pFill.color = Theme.alpha(Theme.textFaint, 0.35f)
            canvas.drawRect(wx, barY, wx + barW, barY + dp(2f), pFill)
            pFill.color = if (aq > 0.66f) Theme.warn else Theme.accent
            canvas.drawRect(wx, barY, wx + barW * aq.coerceIn(0f, 1f), barY + dp(2f), pFill)
        }
    }

    private fun drawExpanded(canvas: Canvas, p: Float) {
        val bw = width.toFloat()
        val bh = height.toFloat()

        // 目标尺寸：展开后的面板矩形（父容器给的位置 + 我们的目标尺寸）
        val targetW = expandRect.width()
        val targetH = expandRect.height()
        val scale = 0.6f + 0.4f * p
        val curW = bw + (targetW - bw) * p
        val curH = bh + (targetH - bh) * p
        val cx = bw / 2f
        val anchorTop = 0f
        val r = RectF(cx - curW / 2f, anchorTop, cx + curW / 2f, anchorTop + curH)

        // 面板本体：随进度提高"抬升"层级
        panel.drawThemed(canvas, r, cut, elevated = p > 0.5f)

        canvas.save()
        canvas.clipRect(r)

        // 扫描线：展开过程中扫过
        if (p < 0.999f) {
            pLine.color = Theme.alpha(Theme.accent, 0.28f * (1f - p))
            val sy = anchorTop + curH * (0.15f + 0.7f * p)
            canvas.drawLine(r.left, sy, r.right, sy, pLine)
        }

        val padL = r.left + dp(16f)
        val contentW = r.width() - dp(32f)
        var y = r.top + dp(18f)

        val alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val rowAlpha = alpha

        // ---------- 标题行 ----------
        pLabel.textSize = dp(9.5f)
        pLabel.color = Theme.alpha(Theme.textDim, rowAlpha)
        canvas.drawText("SYSTEM TELEMETRY", padL, y + dp(10f), pLabel)

        // 关闭提示
        pLabel.textAlign = Paint.Align.RIGHT
        pLabel.color = Theme.alpha(Theme.textFaint, rowAlpha)
        canvas.drawText("← 右滑关闭", r.right - dp(16f), y + dp(10f), pLabel)
        pLabel.textAlign = Paint.Align.LEFT
        y += dp(22f)

        pLine.color = Theme.alpha(Theme.accent, 0.15f * rowAlpha)
        canvas.drawLine(padL, y, r.right - dp(16f), y, pLine)
        y += dp(14f)

        // ---------- 滚动区 ----------
        canvas.save()
        canvas.clipRect(r.left, y, r.right, r.bottom - dp(8f))
        y -= scrollY

        val s = snap

        // ===== 连接区 =====
        y = section(canvas, padL, y, contentW, "连接 / LINK", rowAlpha)

        y = row(canvas, padL, y, contentW, "WiFi", s?.wifiSsid ?: "—", rowAlpha, true)
        y = row(
            canvas, padL, y, contentW, "信号",
            if (s != null && s.wifiSsid != null && s.wifiSsid != "—") "${s.wifiBars}/4  ${s.wifiRssi} dBm" else "—",
            rowAlpha, false
        )
        y = row(canvas, padL, y, contentW, "移动网络", s?.mobileType ?: "—", rowAlpha, true)
        y = row(canvas, padL, y, contentW, "运营商", s?.operator ?: "—", rowAlpha, false)
        y = row(
            canvas, padL, y, contentW, "下行",
            if (s != null) Telemetry.fmtSpeed(s.netDownKbps * 1000) else "—", rowAlpha, true
        )
        y = row(
            canvas, padL, y, contentW, "上行",
            if (s != null) Telemetry.fmtSpeed(s.netUpKbps * 1000) else "—", rowAlpha, false
        )

        y += dp(6f)

        // ===== 定位区 =====
        y = section(canvas, padL, y, contentW, "定位 / GEO", rowAlpha)
        val lat = s?.gpsLat
        val lon = s?.gpsLon
        y = row(
            canvas, padL, y, contentW, "坐标",
            if (lat != null && lon != null) String.format(Locale.US, "%.5f, %.5f", lat, lon) else "—",
            rowAlpha, true
        )
        y = row(canvas, padL, y, contentW, "位置", s?.gpsPlace ?: "—", rowAlpha, false)
        y = row(
            canvas, padL, y, contentW, "精度",
            if (s?.gpsAcc != null && s.gpsAcc >= 0f) String.format(Locale.US, "±%.0f m", s.gpsAcc) else "—",
            rowAlpha, true
        )
        y = row(
            canvas, padL, y, contentW, "卫星",
            if (s?.gpsSats != null && s.gpsSats > 0) "${s.gpsSats}" else "—",
            rowAlpha, false
        )

        y += dp(6f)

        // ===== 环境区 =====
        y = section(canvas, padL, y, contentW, "环境 / ENV", rowAlpha)
        y = row(
            canvas, padL, y, contentW, "电池温度",
            if (s?.batteryTempC != null) String.format(Locale.US, "%.1f °C", s.batteryTempC) else "—",
            rowAlpha, true
        )
        y = row(
            canvas, padL, y, contentW, "CPU 温度",
            if (s?.cpuTempC != null && s.cpuTempC > 0f) String.format(Locale.US, "%.1f °C", s.cpuTempC) else "--",
            rowAlpha, false
        )
        y = row(
            canvas, padL, y, contentW, "电压",
            if (s?.batteryVoltMv != null && s.batteryVoltMv > 0) "${s.batteryVoltMv} mV" else "—",
            rowAlpha, true
        )
        y = row(canvas, padL, y, contentW, "电池健康", s?.batteryHealth ?: "—", rowAlpha, false)
        y = row(
            canvas, padL, y, contentW, "内存",
            if (s != null) "${s.memUsedPct}%" else "—", rowAlpha, true
        )
        y = row(
            canvas, padL, y, contentW, "存储",
            if (s != null && s.storagePct >= 0) "${s.storagePct}%" else "—", rowAlpha, false
        )
        y = row(canvas, padL, y, contentW, "运行时长", s?.uptimeText ?: "—", rowAlpha, true)

        y += dp(28f)
        maxScroll = (y + scrollY - (r.bottom - dp(8f))).coerceAtLeast(0f)

        canvas.restore()
        canvas.restore()

        // 滚动指示
        if (maxScroll > 0f) {
            val trackTop = r.top + dp(60f)
            val trackBot = r.bottom - dp(10f)
            val trackH = trackBot - trackTop
            if (trackH > 0) {
                val ratio = trackH / (trackH + maxScroll)
                val knobH = (trackH * ratio).coerceAtLeast(dp(24f))
                val knobY = trackTop + (trackH - knobH) * (scrollY / maxScroll).coerceIn(0f, 1f)
                pFill.color = Theme.alpha(Theme.accent, 0.25f * rowAlpha)
                canvas.drawRect(r.right - dp(5f), knobY, r.right - dp(3f), knobY + knobH, pFill)
            }
        }
    }

    /** 分区标题 */
    private fun section(canvas: Canvas, x: Float, y: Float, w: Float, title: String, a: Float): Float {
        pLabel.textSize = dp(9f)
        pLabel.color = Theme.alpha(Theme.accent, 0.85f * a)
        canvas.drawText(title, x, y + dp(9f), pLabel)
        pLine.color = Theme.alpha(Theme.accent, 0.10f * a)
        val ty = y + dp(16f)
        canvas.drawLine(x, ty, x + w, ty, pLine)
        return ty + dp(12f)
    }

    /** 一行 标签 + 值 */
    private fun row(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        label: String,
        value: String,
        a: Float,
        strong: Boolean
    ): Float {
        pLabel.textSize = dp(11f)
        pLabel.color = Theme.alpha(Theme.textDim, a)
        canvas.drawText(label, x, y + dp(11f), pLabel)

        pValue.textSize = dp(12f)
        pValue.color = if (strong) Theme.alpha(Theme.text, a) else Theme.alpha(Theme.textDim, a)
        pValue.textAlign = Paint.Align.RIGHT
        canvas.drawText(value, x + w, y + dp(11f), pValue)
        pValue.textAlign = Paint.Align.LEFT

        return y + dp(22f)
    }
}