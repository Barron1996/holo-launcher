package com.holo.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.holo.launcher.ai.AiConsole
import com.holo.launcher.apps.AppEntry
import com.holo.launcher.apps.AppRepository
import com.holo.launcher.data.WeatherRepo
import com.holo.launcher.fx.ClockFx
import com.holo.launcher.fx.GridBackdrop
import com.holo.launcher.hud.Telemetry
import com.holo.launcher.notification.HoloNote
import com.holo.launcher.notification.HoloNotificationListener
import com.holo.launcher.notification.NoteRepo
import com.holo.launcher.sys.PermGuide
import com.holo.launcher.sys.Theme
import com.holo.launcher.ui.AppCapsule
import com.holo.launcher.ui.DesktopLayout
import com.holo.launcher.ui.HudOverlay
import com.holo.launcher.ui.InfoCapsule
import com.holo.launcher.ui.NotePopup
import com.holo.launcher.ui.NoteStream
import com.holo.launcher.ui.QuickCapsule
import com.holo.launcher.ui.SettingsPanel
import com.holo.launcher.widget.HoloWidgetProvider
import com.holo.launcher.widget.WidgetHostView

/**
 * Holo 桌面主入口。
 *
 * 结构（从下到上）：
 *   root(FrameLayout)
 *     ├ GridBackdrop        背景网格
 *     ├ HudOverlay          四角角标
 *     ├ DesktopLayout       五段主从布局
 *     │   ├ [0] HUD 行     时间 / 日期 / Monster 状态
 *     │   ├ [1] InfoCapsule
 *     │   ├ [2] QuickCapsule   ← Monster 入口在槽 0，永远可见
 *     │   ├ [3] NoteStream
 *     │   └ [4] AppCapsule
 *     ├ WidgetHostView      小部件宿主（可选，浮在中部）
 *     ├ SettingsPanel       设置面板（覆盖层）
 *     └ AiConsole           AI 控制台（覆盖层）
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var backdrop: GridBackdrop
    private lateinit var hudFx: HudOverlay
    private lateinit var layout: DesktopLayout

    private lateinit var hudRow: LinearLayout
    private lateinit var hudClock: TextView
    private lateinit var hudMeta: TextView
    private lateinit var info: InfoCapsule
    private lateinit var quick: QuickCapsule
    private lateinit var notes: NoteStream
    private lateinit var apps: AppCapsule

    private lateinit var widgetHost: WidgetHostView
    private lateinit var settings: SettingsPanel
    private lateinit var console: AiConsole

    private val hostId = 8801
    private var topInset = 0
    private var bottomInset = 0
    private var appsLoaded = false
    private var lastWeatherAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            refreshClock()
            main.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏 + 透明栏：依赖系统原生"一次下滑出状态栏、二次下滑通知栏"
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(Theme.bg)
        setContentView(root)

        backdrop = GridBackdrop(this)
        root.addView(backdrop, fullLp())

        hudFx = HudOverlay(this)
        root.addView(hudFx, fullLp())

        layout = DesktopLayout(this)
        root.addView(layout, fullLp())
        layout.attachBackdrop(backdrop)

        buildChildren()

        widgetHost = WidgetHostView(this)
        widgetHost.attach(hostId)
        root.addView(widgetHost, widgetLp())
        widgetHost.visibility = View.GONE

        settings = SettingsPanel(this)
        settings.visibility = View.GONE
        root.addView(settings, fullLp())

        console = AiConsole(this)
        console.visibility = View.GONE
        root.addView(console, fullLp())

        wire()
        listenInsets()
        startTelemetry()
        loadApps()
        loadWeather(force = true)
    }

    // ---------------- 子视图 ----------------

    private fun buildChildren() {
        // [0] HUD 行
        hudRow = LinearLayout(this)
        hudRow.orientation = LinearLayout.HORIZONTAL
        hudRow.gravity = Gravity.CENTER_VERTICAL
        hudRow.setPadding(dp(2), 0, dp(2), 0)

        hudClock = TextView(this)
        hudClock.setTextColor(Theme.text)
        hudClock.textSize = 15f
        hudClock.typeface = android.graphics.Typeface.MONOSPACE
        hudClock.letterSpacing = 0.04f
        hudClock.includeFontPadding = false

        hudMeta = TextView(this)
        hudMeta.setTextColor(Theme.textDim)
        hudMeta.textSize = 9f
        hudMeta.typeface = android.graphics.Typeface.MONOSPACE
        hudMeta.letterSpacing = 0.10f
        hudMeta.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        hudMeta.includeFontPadding = false

        hudRow.addView(
            hudClock,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        hudRow.addView(
            hudMeta,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        layout.addView(hudRow)

        // [1] 信息舱
        info = InfoCapsule(this)
        layout.addView(info)

        // [2] 快捷舱 —— Monster 永远在槽 0
        quick = QuickCapsule(this)
        layout.addView(quick)

        // [3] 通知舱
        notes = NoteStream(this)
        notes.visibility = View.GONE
        layout.addView(notes)

        // [4] 应用舱
        apps = AppCapsule(this)
        layout.addView(apps)
    }

    // ---------------- 接线 ----------------

    private fun wire() {
        // --- 快捷舱 ---
        quick.onMonsterToggle = { toggleMonster() }
        quick.onTorchToggle = { toggleTorch() }
        quick.onCustomTap = { idx -> onQuickCustom(idx) }
        quick.onAddCustom = { addQuickCustom() }
        quick.onOpenSettings = { openSettings() }

        // --- 通知舱 ---
        notes.onOpen = { n -> openAppOf(n) }
        notes.onReply = { n -> openReply(n) }
        notes.onNeedPermission = { PermGuide.notification(this) }
        NoteRepo.setChangeListener {
            main.post {
                notes.refresh()
                notes.visibility = if (NoteRepo.count() > 0) View.VISIBLE else View.GONE
            }
        }

        // --- 应用舱 ---
        apps.onOpen = { e -> launchApp(e) }
        apps.onLongPress = { e -> showAppInfo(e) }

        // --- 信息舱 ---
        info.onRequestExpand = { expanded ->
            // 展开时把 AI 球区域让位，收起时恢复
            layout.setAiReserve(
                if (expanded) (resources.displayMetrics.heightPixels * 0.58f).toInt() else dp(72)
            )
        }

        // --- 设置面板 ---
        settings.onClose = { settings.visibility = View.GONE }
        settings.onAiConfig = {
            settings.visibility = View.GONE
            openAiConsole()
        }
        settings.onMonsterInfo = {
            settings.visibility = View.GONE
            toggleMonster()
        }

        // --- AI 控制台 ---
        console.onClose = { console.visibility = View.GONE }

        // --- 主题变化统一重绘 ---
        Theme.addListener {
            main.post { repaintAll() }
        }
    }

    // ---------------- insets ----------------

    private fun listenInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val top = insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                ).top
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                topInset = top
                bottomInset = bottom
            } else {
                @Suppress("DEPRECATION")
                topInset = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottomInset = insets.systemWindowInsetBottom
            }
            // HUD 行不贴近物理顶端，留一点呼吸感
            layout.setInsets(topInset + dp(6), bottomInset + dp(10))
            applyWidgetLp()
            insets
        }
        root.requestApplyInsets()
    }

    // ---------------- 时钟 ----------------

    private fun refreshClock() {
        hudClock.text = ClockFx.time()
        val berserk = if (Theme.isBerserk) "MONSTER // " else ""
        hudMeta.text = berserk + ClockFx.date().uppercase()
        hudMeta.setTextColor(if (Theme.isBerserk) Theme.warn else Theme.textDim)
    }

    // ---------------- 数据 ----------------

    private fun startTelemetry() {
        Telemetry.start(this) { snap ->
            main.post {
                info.setSnapshot(snap)
                // 定位得到的坐标可用于天气；避免频繁请求，5 分钟一次
                val now = System.currentTimeMillis()
                if (snap.gpsLat != null && snap.gpsLon != null && now - lastWeatherAt > 5 * 60 * 1000L) {
                    lastWeatherAt = now
                    WeatherRepo.fetchData(snap.gpsLat, snap.gpsLon) { d ->
                        main.post { info.setWeather(d) }
                    }
                }
            }
        }
    }

    private fun loadWeather(force: Boolean = false) {
        if (!force) return
        // Telemetry 尚未拿到坐标时用北京兜底，拿到 GPS 后会被覆盖
        WeatherRepo.fetchData(39.9042, 116.4074) { d ->
            main.post { info.setWeather(d) }
        }
    }

    private fun loadApps() {
        if (appsLoaded) {
            apps.reload()
            return
        }
        Thread {
            val list = AppRepository.loadInstalled(this)
            main.post {
                appsLoaded = true
                apps.reload()
            }
        }.start()
    }

    // ---------------- 动作 ----------------

    private fun toggleMonster() {
        Theme.toggle()
        refreshClock()
        repaintAll()
        apps.invalidate()
    }

    private fun toggleTorch() {
        val cm = getSystemService(android.content.Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
        if (cm == null) return
        try {
            val id = cm.cameraIdList.firstOrNull { cid ->
                val ch = cm.getCameraCharacteristics(cid)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            val ch = cm.getCameraCharacteristics(id)
            val avail = ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!avail) return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
            quick.setTorchState(torchOn)
        } catch (e: Exception) {
            // 无权限或无闪光灯：静默
        }
    }

    private var torchOn = false

    private fun onQuickCustom(idx: Int) {
        val slots = quick.slots()
        if (idx < 0 || idx >= slots.size) return
        val label = slots[idx]
        val entry = AppRepository.cached().firstOrNull { it.label == label }
        if (entry != null) {
            launchApp(entry)
        } else {
            // 不是应用名，当作系统快捷项
            when (label) {
                "WIFI" -> PermGuide.wifi(this)
                else -> openSettings()
            }
        }
    }

    private fun addQuickCustom() {
        val list = AppRepository.cached()
        if (list.isEmpty()) return
        val names = HashSet<String>()
        for (s in quick.slots()) names.add(s)
        val pick = list.firstOrNull { !names.contains(it.label) } ?: return
        val ok = quick.addSlot(pick.label)
        if (ok) quick.invalidate()
    }

    private fun launchApp(e: AppEntry) {
        AppRepository.launch(this, e)
    }

    private fun showAppInfo(e: AppEntry) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + e.packageName)
                )
            )
        } catch (ex: Exception) {
        }
    }

    private fun openAppOf(n: HoloNote) {
        // HoloNote 只有 appLabel，没有包名；按 label 在已缓存应用里找同名应用
        try {
            val target = n.appLabel.trim()
            val hit = AppRepository.cached().firstOrNull {
                it.label.equals(target, ignoreCase = true)
            }
            if (hit != null && AppRepository.launch(this, hit)) return
        } catch (e: Exception) {
        }
        // 找不到就退化为打开通知使用权设置，让用户自己进应用
        try {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    private fun openReply(n: HoloNote) {
        NotePopup.show(this, n, { text ->
            val ok = HoloNotificationListener.reply(this, n.key, text)
            if (!ok) toastShort("回复失败，通知可能已失效")
        }, {
        })
    }
    private fun toastShort(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        settings.refresh()
        settings.visibility = View.VISIBLE
        settings.bringToFront()
    }

    private fun openAiConsole() {
        // 语音由 AiConsole 内部 startVoice() 自行发起，这里只负责显示
        console.visibility = View.VISIBLE
        console.bringToFront()
    }

    // ---------------- 小部件 ----------------

    private fun widgetLp(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(dp(300), dp(160))
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = topInset + dp(150)
        return lp
    }

    private fun applyWidgetLp() {
        if (!::widgetHost.isInitialized) return
        val lp = widgetHost.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.topMargin = topInset + dp(150)
        widgetHost.layoutParams = lp
    }

    private fun bindFirstWidget() {
        try {
            if (widgetHost.isBound()) return
            val mgr = AppWidgetManager.getInstance(this) ?: return
            val list = mgr.installedProviders
            if (list == null || list.isEmpty()) return
            val host = AppWidgetHost(this, hostId + 1)
            val id = host.allocateAppWidgetId()
            var target: AppWidgetProviderInfo? = null
            for (p in list) {
                if (p.widgetCategory == AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) {
                    target = p
                    break
                }
            }
            if (target == null) target = list[0]
            val tgt = target ?: return
            val ok = try {
                mgr.bindAppWidgetIdIfAllowed(id, tgt.provider)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                widgetHost.bind(tgt, id)
                widgetHost.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
        }
    }

    // ---------------- 重绘 ----------------

    private fun repaintAll() {
        try {
            root.setBackgroundColor(Theme.bg)
            backdrop.invalidate()
            hudFx.invalidate()
            layout.invalidate()
            hudClock.setTextColor(Theme.text)
            refreshClock()
            info.invalidate()
            quick.invalidate()
            notes.invalidate()
            apps.invalidate()
        } catch (e: Exception) {
        }
    }

    // ---------------- 工具 ----------------

    private fun fullLp(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 生命周期 ----------------

    override fun onResume() {
        super.onResume()
        appsLoaded = false
        loadApps()
        notes.refresh()
        notes.visibility = if (NoteRepo.count() > 0) View.VISIBLE else View.GONE
        loadWeather(force = System.currentTimeMillis() - lastWeatherAt > 10 * 60 * 1000L)
        bindFirstWidget()
        main.removeCallbacks(clockTick)
        main.post(clockTick)
        repaintAll()
        try {
            HoloWidgetProvider.refreshAll(this)
        } catch (e: Exception) {
        }
        // 首次进入：若未授予通知读取权限，给一次可消除的提示
        if (!PermGuide.isNotificationGranted(this)) {
            main.postDelayed({ notes.visibility = View.VISIBLE }, 800L)
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(clockTick)
    }

    override fun onDestroy() {
        main.removeCallbacks(clockTick)
        try {
            Telemetry.stop()
        } catch (e: Exception) {
        }
        try {
            widgetHost.releaseHost()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            console.visibility == View.VISIBLE -> console.visibility = View.GONE
            settings.visibility == View.VISIBLE -> settings.visibility = View.GONE
            else -> {
                // 桌面不退出：拦截返回键
            }
        }
    }
}