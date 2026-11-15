package com.holo.launcher.hud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager

/**
 * HUD 遥测数据源。
 *
 * 设计原则：
 *  - 所有取值都做了「拿不到就降级为占位符」处理，绝不让异常冒到渲染层。
 *  - 不主动申请任何危险权限；READ_PHONE_STATE 未授权时信号强度显示 "--"。
 *  - 每秒采样一次，数据变更才回调，避免无意义重绘。
 *
 * 在 vivo ROM 上，WiFi RSSI 在无定位权限时可能返回 -127（无效值），
 * 此处按 -127 / 0 视为无效，降级为 "----"。
 */
object Telemetry {

    /** 一次快照。全部为可显示字符串，避免 UI 层再格式化。 */
    data class Snapshot(
        val wifiSsid: String = "--",
        val wifiRssi: Int = -127,
        val wifiBars: Int = 0,
        val mobileType: String = "--",
        val mobileLevel: Int = 0,
        val netDownKbps: Int = 0,
        val netUpKbps: Int = 0,
        val operator: String = "--",
        val batteryPct: Int = 0,
        val batteryCharging: Boolean = false,
        val memUsedPct: Int = 0,
        // ---------- 扩展字段（InfoCapsule 展开态使用） ----------
        val gpsLat: Double? = null,
        val gpsLon: Double? = null,
        val gpsAcc: Float? = null,
        val gpsSats: Int? = null,
        val gpsPlace: String? = null,
        val batteryTempC: Float? = null,
        val cpuTempC: Float? = null,
        val batteryVoltMv: Int? = null,
        val batteryHealth: String? = null,
        val storagePct: Int = -1,
        val uptimeText: String? = null
    )

    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var listener: ((Snapshot) -> Unit)? = null

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L

    private var wifiMgr: WifiManager? = null
    private var telMgr: TelephonyManager? = null

    /** 启动采样（幂等）。callback 在主线程回调。 */
    fun start(context: Context, callback: (Snapshot) -> Unit) {
        val app = context.applicationContext
        listener = callback
        if (started) return
        started = true

        try {
            wifiMgr = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (e: Throwable) {
            wifiMgr = null
        }
        try {
            telMgr = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        } catch (e: Throwable) {
            telMgr = null
        }

        appCtxRef = app
        startLocation(app)
        lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        lastAt = System.currentTimeMillis()

        val cm = try {
            app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Throwable) {
            null
        }

        registerSignalListener(telMgr)

        val task = object : Runnable {
            override fun run() {
                snapshot = sample(app, cm)
                listener?.invoke(snapshot)
                main.postDelayed(this, 1000L)
            }
        }
        main.post(task)
    }

    fun stop() {
        started = false
        main.removeCallbacksAndMessages(null)
        @Suppress("DEPRECATION")
        try {
            telMgr?.listen(null, PhoneStateListener.LISTEN_NONE)
        } catch (e: Throwable) {
        }
        listener = null
    }

    // ---------------- 各数据源 ----------------

    private fun sample(app: Context, cm: ConnectivityManager?): Snapshot {
        val s = Snapshot(
            wifiSsid = readSsid(),
            wifiRssi = readRssi(),
            mobileType = readMobileType(cm),
            mobileLevel = mobileLevel,
            netDownKbps = 0,
            netUpKbps = 0,
            operator = readOperator(),
            batteryPct = readBatteryPct(app),
            batteryCharging = readCharging(app),
            memUsedPct = readMemPct(),
            gpsLat = gpsLat,
            gpsLon = gpsLon,
            gpsAcc = gpsAcc,
            gpsSats = gpsSats,
            gpsPlace = gpsPlace,
            batteryTempC = readBatteryTemp(app),
            cpuTempC = readCpuTemp(),
            batteryVoltMv = readBatteryVolt(app),
            batteryHealth = readBatteryHealth(app),
            storagePct = readStoragePct(),
            uptimeText = readUptime()
        )

        // 网速差分
        val nowRx = TrafficStats.getTotalRxBytes()
        val nowTx = TrafficStats.getTotalTxBytes()
        val now = System.currentTimeMillis()
        val dt = (now - lastAt).coerceAtLeast(1L)
        var down = 0
        var up = 0
        if (nowRx >= 0 && lastRx >= 0) {
            down = (((nowRx - lastRx) * 1000L) / dt).coerceAtLeast(0L).toInt()
        }
        if (nowTx >= 0 && lastTx >= 0) {
            up = (((nowTx - lastTx) * 1000L) / dt).coerceAtLeast(0L).toInt()
        }
        lastRx = if (nowRx >= 0) nowRx else lastRx
        lastTx = if (nowTx >= 0) nowTx else lastTx
        lastAt = now

        val wifiBars = rssiToBars(s.wifiRssi)
        return s.copy(
            netDownKbps = down,
            netUpKbps = up,
            wifiBars = wifiBars,
            mobileLevel = mobileLevel
        )
    }

    private fun readSsid(): String {
        return try {
            val info = wifiMgr?.connectionInfo ?: return "--"
            @Suppress("DEPRECATION")
            val ssid = info.ssid ?: return "--"
            // 无定位权限时部分 ROM 返回 <unknown ssid>
            if (ssid == "<unknown ssid>" || ssid.isBlank()) "--" else ssid.trim('"')
        } catch (e: Throwable) {
            "--"
        }
    }

    private fun readRssi(): Int {
        return try {
            @Suppress("DEPRECATION")
            val v = wifiMgr?.connectionInfo?.rssi ?: -127
            // -127 / 0 / 正数为无效值
            if (v == 0 || v > 0) -127 else v
        } catch (e: Throwable) {
            -127
        }
    }

    /** RSSI(dBm) → 0~4 格。 */
    private fun rssiToBars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        rssi >= -90 -> 1
        else -> 0
    }

    private fun readMobileType(cm: ConnectivityManager?): String {
        return try {
            if (cm == null) return "--"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps == null) return "--"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WLAN"
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "--"
            when {
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED) -> "5G"
                Build.VERSION.SDK_INT >= 29 && caps.linkDownstreamBandwidthKbps >= 40000 -> "5G"
                else -> when (telMgr?.dataNetworkType ?: 0) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                    else -> "4G"
                }
            }
        } catch (e: Throwable) {
            "--"
        }
    }

    private fun readOperator(): String {
        return try {
            val name = telMgr?.networkOperatorName
            if (name.isNullOrBlank()) "--" else name
        } catch (e: Throwable) {
            "--"
        }
    }

    private fun readBatteryPct(app: Context): Int {
        return try {
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (v < 0) -1 else v.coerceIn(0, 100)
        } catch (e: Throwable) {
            -1
        }
    }

    private fun readCharging(app: Context): Boolean {
        return try {
            val intent = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Throwable) {
            false
        }
    }

    /** 系统内存占用百分比，读 /proc/meminfo，失败返回 -1。 */
    private fun readMemPct(): Int {
        return try {
            val text = java.io.File("/proc/meminfo").readText()
            var total = 0L
            var avail = 0L
            for (line in text.lineSequence()) {
                if (line.startsWith("MemTotal:")) total = parseKb(line)
                else if (line.startsWith("MemAvailable:")) avail = parseKb(line)
                if (total > 0 && avail > 0) break
            }
            if (total <= 0L) -1
            else (((total - avail) * 100L) / total).toInt().coerceIn(0, 100)
        } catch (e: Throwable) {
            -1
        }
    }

    private fun parseKb(line: String): Long {
        val digits = line.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    // ---------------- GPS / 定位（可选，无权限时保持 null） ----------------
    @Volatile private var gpsLat: Double? = null
    @Volatile private var gpsLon: Double? = null
    @Volatile private var gpsAcc: Float? = null
    @Volatile private var gpsSats: Int? = null
    @Volatile private var gpsPlace: String? = null
    private var locMgr: android.location.LocationManager? = null
    @Volatile private var appCtxRef: Context? = null
    private var lastLocAt = 0L

    /** 尝试启动一次定位（无权限/无服务则静默失败）。 */
    private fun startLocation(app: Context) {
        try {
            if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return
            if (locMgr == null) {
                locMgr = app.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            }
            val lm = locMgr ?: return
            val prov = when {
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ->
                    android.location.LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ->
                    android.location.LocationManager.NETWORK_PROVIDER
                else -> return
            }
            val last = try { lm.getLastKnownLocation(prov) } catch (e: Throwable) { null }
            if (last != null) applyLocation(last)
        } catch (e: Throwable) {
        }
    }

    private fun applyLocation(loc: android.location.Location?) {
        if (loc == null) return
        gpsLat = loc.latitude
        gpsLon = loc.longitude
        gpsAcc = if (loc.hasAccuracy()) loc.accuracy else null
        gpsPlace = try {
            // 无 Geocoder 服务（国内 ROM 常见）时降级为 null
            val ctx = appCtxRef ?: return
            val gcd = android.location.Geocoder(ctx, java.util.Locale.CHINA)
            @Suppress("DEPRECATION")
            val list = gcd.getFromLocation(loc.latitude, loc.longitude, 1)
            val a = list?.firstOrNull()
            if (a == null) null else {
                val parts = ArrayList<String>()
                a.locality?.let { parts.add(it) }
                a.subLocality?.let { parts.add(it) }
                if (parts.isEmpty()) a.featureName?.let { parts.add(it) }
                if (parts.isEmpty()) null else parts.joinToString("·")
            }
        } catch (e: Throwable) {
            null
        }
        lastLocAt = System.currentTimeMillis()
    }

    // ---------------- 信号强度监听 ----------------

    @Volatile
    private var mobileLevel: Int = 0

    private fun registerSignalListener(tm: TelephonyManager?) {
        if (tm == null) return
        try {
            val l = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    if (signalStrength == null) return
                    mobileLevel = try {
                        when {
                            Build.VERSION.SDK_INT >= 29 -> signalStrength.level.coerceIn(0, 4)
                            else -> {
                                @Suppress("DEPRECATION")
                                val gsm = signalStrength.gsmSignalStrength
                                if (gsm <= 0 || gsm == 99) 0 else ((gsm - 2) * 4 / 30).coerceIn(0, 4)
                            }
                        }
                    } catch (e: Throwable) {
                        0
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
            }
        } catch (e: Throwable) {
            mobileLevel = 0
        }
    }

    // ---------------- 扩展数据源 ----------------

    /** 电池温度（°C），读 ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE（0.1°C）。 */
    private fun readBatteryTemp(app: Context): Float? {
        return try {
            val i = app.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t == Int.MIN_VALUE || t <= 0) null else t / 10f
        } catch (e: Throwable) {
            null
        }
    }

    /** 电池电压（mV）。 */
    private fun readBatteryVolt(app: Context): Int? {
        return try {
            val i = app.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            val v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            if (v <= 0) null else v
        } catch (e: Throwable) {
            null
        }
    }

    /** 电池健康：优 / 良 / 一般 / 需更换。 */
    private fun readBatteryHealth(app: Context): String? {
        return try {
            val i = app.registerReceiver(
                null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "优"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
                BatteryManager.BATTERY_HEALTH_DEAD -> "衰竭"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
                BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "需更换"
                else -> "良"
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** CPU 温度：扫描 thermal_zone，取 type 含 cpu/soc 的最大值。失败返回 null。 */
    private fun readCpuTemp(): Float? {
        return try {
            val base = java.io.File("/sys/class/thermal")
            val zones = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
            var best = 0f
            for (z in zones) {
                val type = try {
                    java.io.File(z, "type").readText().trim().lowercase()
                } catch (e: Throwable) {
                    continue
                }
                if (!(type.contains("cpu") || type.contains("soc") || type.contains("tsens"))) continue
                val raw = try {
                    java.io.File(z, "temp").readText().trim().toFloatOrNull() ?: continue
                } catch (e: Throwable) {
                    continue
                }
                val c = if (raw > 1000f) raw / 1000f else raw
                if (c > 0f && c < 150f && c > best) best = c
            }
            if (best > 0f) best else null
        } catch (e: Throwable) {
            null
        }
    }

    /** 存储已用百分比（/data），失败返回 -1。 */
    private fun readStoragePct(): Int {
        return try {
            val st = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            val total = st.blockCountLong * st.blockSizeLong
            val avail = st.availableBlocksLong * st.blockSizeLong
            if (total <= 0L) -1
            else (((total - avail) * 100L) / total).toInt().coerceIn(0, 100)
        } catch (e: Throwable) {
            -1
        }
    }

    /** 系统运行时长，如 "3天4小时" / "2小时13分" / "18分钟"。 */
    private fun readUptime(): String? {
        return try {
            val ms = android.os.SystemClock.elapsedRealtime()
            val min = ms / 60000L
            val h = min / 60L
            val d = h / 24L
            when {
                d > 0L -> d.toString() + "天" + (h % 24L) + "小时"
                h > 0L -> h.toString() + "小时" + (min % 60L) + "分"
                else -> min.toString() + "分钟"
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 网速格式化：B/s → "1.2M" / "856K" / "12B" */
    fun fmtSpeed(bps: Int): String {
        val v = bps.toLong()
        return when {
            v >= 1024L * 1024L -> String.format("%.1fM", v / 1048576.0)
            v >= 1024L -> String.format("%dK", v / 1024L)
            v > 0 -> "${v}B"
            else -> "0"
        }
    }
}