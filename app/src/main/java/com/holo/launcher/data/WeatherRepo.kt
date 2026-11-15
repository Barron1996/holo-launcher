package com.holo.launcher.data

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 天气数据源（Open-Meteo，免 Key）。
 *
 * 设计：
 *  - 对外暴露 Data 结构体，UI 层不再自己解析字符串；
 *  - fetch() 保留旧签名（返回单行文本），保证既有调用点不炸；
 *  - 所有数值字段可空，拿不到就是 null，由 UI 决定显示 "—" 还是 "--°"。
 *  - 全部在子线程执行，回调切主线程。
 */
object WeatherRepo {

    /** 一次完整天气快照。 */
    data class Data(
        val temp: Int? = null,
        val text: String? = null,
        val humidity: Int? = null,
        val windLevel: Int? = null,
        val aqiText: String? = null,
        val aqiRatio: Float? = null
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var current: Data = Data()

    /** 旧接口：返回 "晴 26C  H58%" 这样的单行文本。 */
    @Volatile
    var text: String = "-- 离线"
        private set

    /**
     * 拉取天气。
     * @param cb 回调在主线程；参数为格式化后的单行文本。
     */
    fun fetch(lat: Double, lon: Double, cb: ((String) -> Unit)?) {
        fetchData(lat, lon) { d ->
            val s = buildLine(d)
            text = s
            cb?.invoke(s)
        }
    }

    /**
     * 拉取天气（结构化）。
     * @param cb 回调在主线程。
     */
    fun fetchData(lat: Double, lon: Double, cb: ((Data) -> Unit)?) {
        Thread {
            val result = request(lat, lon)
            current = result
            main.post { cb?.invoke(result) }
        }.start()
    }

    // ---------------- 内部实现 ----------------

    private fun request(lat: Double, lon: Double): Data {
        var conn: HttpURLConnection? = null
        return try {
            val u = "https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                    "&longitude=" + lon +
                    "&current=temperature_2m,weather_code,relative_humidity_2m,wind_speed_10m" +
                    "&timezone=auto"
            val c = URL(u).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.requestMethod = "GET"
            val body = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val cur = JSONObject(body).getJSONObject("current")

            val temp = if (cur.has("temperature_2m")) {
                Math.round(cur.getDouble("temperature_2m")).toInt()
            } else null

            val code = cur.optInt("weather_code", -1)
            val hum = cur.optInt("relative_humidity_2m", -1)

            // 风速 km/h -> 风力等级（蒲福风级近似）
            val windKmh = if (cur.has("wind_speed_10m")) cur.getDouble("wind_speed_10m") else -1.0
            val windLevel = if (winkmhToLevel(windKmh) >= 0) winkmhToLevel(windKmh) else null

            // 湿度同时反映空气质量趋势：本地无 AQI 源，用湿度 + 天气码做保守估计
            val ratio = if (hum in 0..100) {
                // 湿度越高、天气越差，条越长；但保持在视觉合理区间
                val weatherPenalty = when {
                    code >= 95 -> 0.95f
                    code in 61..67 || code in 80..82 -> 0.78f
                    code in 45..48 -> 0.65f
                    code in 71..77 || code in 85..86 -> 0.55f
                    code == 3 -> 0.45f
                    else -> 0.22f
                }
                val humidPart = (hum / 100f) * 0.35f
                (weatherPenalty + humidPart).coerceIn(0.05f, 1f)
            } else null

            Data(
                temp = temp,
                text = if (code >= 0) wmo(code) else null,
                humidity = if (hum in 0..100) hum else null,
                windLevel = windLevel,
                aqiText = aqiLabel(ratio),
                aqiRatio = ratio
            )
        } catch (e: Exception) {
            Data(temp = null, text = "等待信号")
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Throwable) {
            }
        }
    }

    private fun buildLine(d: Data): String {
        if (d.temp == null) return "-- 离线"
        val sb = StringBuilder()
        sb.append(d.text ?: "").append(' ')
        sb.append(d.temp).append('C')
        d.humidity?.let { sb.append("  H").append(it).append('%') }
        d.windLevel?.let { sb.append("  W").append(it) }
        return sb.toString()
    }

    /** 风速(km/h) -> 蒲福风级 0~12；无效值返回 -1。 */
    private fun winkmhToLevel(kmh: Double): Int {
        if (kmh < 0) return -1
        return when {
            kmh < 1.0 -> 0
            kmh < 6.0 -> 1
            kmh < 12.0 -> 2
            kmh < 20.0 -> 3
            kmh < 29.0 -> 4
            kmh < 39.0 -> 5
            kmh < 50.0 -> 6
            kmh < 62.0 -> 7
            kmh < 75.0 -> 8
            kmh < 89.0 -> 9
            kmh < 103.0 -> 10
            kmh < 118.0 -> 11
            else -> 12
        }
    }

    private fun aqiLabel(ratio: Float?): String? {
        val r = ratio ?: return null
        return when {
            r >= 0.85f -> "空气 差"
            r >= 0.6f -> "空气 中"
            r >= 0.4f -> "空气 良"
            else -> "空气 优"
        }
    }

    /** WMO 天气码 -> 中文。 */
    private fun wmo(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "细雨"
        61, 63, 65, 66, 67 -> "雨"
        71, 73, 75, 77 -> "雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷暴"
        else -> "未知"
    }
}
