package com.holo.launcher.sys

import android.os.SystemClock

/**
 * 全局主题状态机。
 *
 * 两套配色由单一状态驱动，不维护两套布局：
 *  - NORMAL  冷灰青，静态工业感
 *  - BERSERK Monster，琥珀红提频态
 * BERSERK 分两个相位：OVERDRIVE(0~3000ms 过载闪动) -> STABLE(常驻)。
 *
 * 设计纪律：强调色只给"数据值"，不给标题、不给边框。描边一律不发光。
 */
object Theme {

    enum class Mode { NORMAL, BERSERK }

    enum class Phase { STABLE, OVERDRIVE }

    const val OVERDRIVE_MS = 3000L

    @Volatile
    var mode: Mode = Mode.NORMAL
        private set

    @Volatile
    var phase: Phase = Phase.STABLE
        private set

    private var berserkAt = 0L

    /** 颜色变化回调，View 在其中 invalidate() */
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        synchronized(listeners) {
            if (!listeners.contains(l)) listeners.add(l)
        }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(listeners) { listeners.remove(l) }
    }

    private fun notifyAllListeners() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        for (l in copy) {
            try {
                l.invoke()
            } catch (e: Exception) {
                // 单个 View 失效不影响整体
            }
        }
    }

    val isBerserk: Boolean get() = mode == Mode.BERSERK

    // ---------------- 底色与面板 ----------------

    /** 桌面底色 #06080A */
    val bg: Int get() = if (isBerserk) 0xFF0A0507.toInt() else 0xFF06080A.toInt()

    /** 面板填充 */
    fun panelFill(elevated: Boolean): Int = if (isBerserk) {
        if (elevated) 0xFF1A0E11.toInt() else 0xFF120A0C.toInt()
    } else {
        if (elevated) 0xFF12171D.toInt() else 0xFF0C1014.toInt()
    }

    /** 面板描边（1px 冷灰，不发光） */
    fun panelStroke(): Int = if (isBerserk) 0xFF3A2027.toInt() else 0xFF1F2A33.toInt()

    /** 强调描边（仅用于选中态/焦点态） */
    fun panelStrokeHi(): Int = if (isBerserk) 0xFF6E3340.toInt() else 0xFF2E3D49.toInt()

    /** 兼容旧 API */
    val panel: Int get() = panelFill(false)

    // ---------------- 文本 ----------------

    /** 主文字 #D8E4EC */
    val text: Int get() = if (isBerserk) 0xFFF2E6E2.toInt() else 0xFFD8E4EC.toInt()

    /** 次文字 #5C6B78 */
    val textDim: Int get() = if (isBerserk) 0xFFA8848A.toInt() else 0xFF5C6B78.toInt()

    /** 三级文字 #39454F */
    val textFaint: Int get() = if (isBerserk) 0xFF6B5057.toInt() else 0xFF39454F.toInt()

    // ---------------- 强调色 ----------------

    /** 强调青 #4FD1C5（只给数据值） */
    val accent: Int get() = if (isBerserk) 0xFFFF6A3D.toInt() else 0xFF4FD1C5.toInt()

    /** 强调亮色 */
    val accentBright: Int get() = if (isBerserk) 0xFFFFB08A.toInt() else 0xFF8AF0E6.toInt()

    /** 强调暗色 */
    val accentDim: Int get() = if (isBerserk) 0xFF7A3320.toInt() else 0xFF2A6E68.toInt()

    /** 半透明强调（旧 API 兼容） */
    val dim: Int get() = if (isBerserk) 0x59FF6A3D else 0x594FD1C5

    /** 分隔线 */
    val rule: Int get() = if (isBerserk) 0x33FF6A3D else 0x334FD1C5

    /** 警示琥珀 #E8A33D */
    val warn: Int get() = 0xFFE8A33D.toInt()

    /** 危险红 #D9534F */
    val danger: Int get() = 0xFFD9534F.toInt()

    // ---------------- 动效 ----------------

    /** 辉光倍数：过载期放大 */
    val glowScale: Float get() = if (isBerserk && phase == Phase.OVERDRIVE) 1.6f else 1f

    /** 环旋转倍速 */
    val spinScale: Float get() = if (isBerserk) (if (phase == Phase.OVERDRIVE) 3f else 1.8f) else 1f

    // ---------------- 文案令牌 ----------------

    val hudStatus: String
        get() = when {
            !isBerserk -> "SYS_NOMINAL"
            phase == Phase.OVERDRIVE -> "SYS_BOOST >>"
            else -> "MONSTER // ACTIVE"
        }

    val streamTitle: String
        get() = if (isBerserk) "STREAM // MONSTER" else "通知流 / NOTIFICATION STREAM"

    // ---------------- 工具 ----------------

    /**
     * 给颜色叠加 alpha。
     * @param color 原色（ARGB）
     * @param factor 0f~1f，最终 alpha = 原 alpha * factor
     */
    fun alpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24 and 0xFF) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    /** 提亮颜色（简单线性逼近白色） */
    fun lighten(color: Int, amount: Float): Int {
        val f = amount.coerceIn(0f, 1f)
        val r = (color ushr 16 and 0xFF)
        val g = (color ushr 8 and 0xFF)
        val b = (color and 0xFF)
        val nr = (r + (255 - r) * f).toInt().coerceIn(0, 255)
        val ng = (g + (255 - g) * f).toInt().coerceIn(0, 255)
        val nb = (b + (255 - b) * f).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    // ---------------- 开关 ----------------

    fun enterBerserk() {
        if (mode == Mode.BERSERK && phase == Phase.OVERDRIVE) return
        mode = Mode.BERSERK
        phase = Phase.OVERDRIVE
        berserkAt = SystemClock.elapsedRealtime()
        notifyAllListeners()
    }

    fun exitBerserk() {
        mode = Mode.NORMAL
        phase = Phase.STABLE
        berserkAt = 0L
        notifyAllListeners()
    }

    fun toggle() {
        if (isBerserk) exitBerserk() else enterBerserk()
    }

    /** 由渲染循环每帧调用，超过 3 秒则从 OVERDRIVE 落到 STABLE */
    fun tick() {
        if (mode == Mode.BERSERK && phase == Phase.OVERDRIVE &&
            SystemClock.elapsedRealtime() - berserkAt >= OVERDRIVE_MS
        ) {
            phase = Phase.STABLE
            notifyAllListeners()
        }
    }

    fun overdriveProgress(): Float {
        if (mode != Mode.BERSERK || phase != Phase.OVERDRIVE) return 1f
        val p = (SystemClock.elapsedRealtime() - berserkAt).toFloat() / OVERDRIVE_MS
        return p.coerceIn(0f, 1f)
    }
}