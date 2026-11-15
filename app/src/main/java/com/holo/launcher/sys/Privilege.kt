package com.holo.launcher.sys

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 特权通道，按能力从高到低降级：ROOT -> SHIZUKU -> NONE。
 *
 * ROOT:    通过 `su -c` 执行，uid=0。
 * SHIZUKU: 通过反射调用 rikka.shizuku.Shizuku.newProcess(String[], String[], String)，
 *          以 shell(2000) 身份执行。刻意使用反射而非编译期依赖，
 *          避免未安装 Shizuku 时类加载失败（NoClassDefFoundError）。
 *
 * 本类只负责探测与执行，不负责 UI 提示。
 */
object Privilege {

    enum class Level { NONE, SHIZUKU, ROOT }

    @Volatile
    var level: Level = Level.NONE
        private set

    @Volatile
    var note: String = "未检测"
        private set

    @Volatile
    var enabled: Boolean = false
        private set

    /** 开关特权通道。开启时立即探测一次。 */
    fun setEnabled(on: Boolean) {
        enabled = on
        if (on) {
            probe()
        } else {
            level = Level.NONE
            note = "特权通道已关闭"
        }
    }

    /** 探测一次当前可用通道：ROOT 优先，其次 Shizuku。 */
    @Synchronized
    fun probe(): Level {
        val rootOut = runRaw(arrayOf("su", "-c", "id"))
        if (rootOut.contains("uid=0")) {
            level = Level.ROOT
            note = "ROOT 可用"
            return level
        }

        val shizukuOut = runShizuku("id")
        if (shizukuOut != null &&
            (shizukuOut.contains("uid=2000") || shizukuOut.contains("uid=0"))
        ) {
            level = Level.SHIZUKU
            note = "Shizuku 已授权（shell）"
            return level
        }

        level = Level.NONE
        note = when {
            shizukuOut == null -> "未检测到可用 Shizuku 服务"
            shizukuOut.startsWith("ERR:") -> "Shizuku 调用异常：${shizukuOut.removePrefix("ERR:")}"
            else -> "Shizuku 未授权给本应用"
        }
        return level
    }

    /** 以最高可用权限执行命令。未开启特权通道时拒绝执行。 */
    fun exec(cmd: String): ExecResult {
        if (!enabled) return ExecResult(false, "特权通道未开启", level)
        if (level == Level.NONE) probe()
        return when (level) {
            Level.ROOT -> ExecResult(true, runRaw(arrayOf("su", "-c", cmd)), Level.ROOT)
            Level.SHIZUKU -> {
                val out = runShizuku(cmd)
                if (out == null) ExecResult(false, "Shizuku 调用失败", Level.SHIZUKU)
                else if (out.startsWith("ERR:")) ExecResult(false, out, Level.SHIZUKU)
                else ExecResult(true, out, Level.SHIZUKU)
            }
            Level.NONE -> ExecResult(false, "无可用特权通道：$note", Level.NONE)
        }
    }

    // ---------------- ROOT ----------------

    private fun runRaw(argv: Array<String>): String {
        var p: Process? = null
        return try {
            val pb = ProcessBuilder(*argv)
            pb.redirectErrorStream(true)
            p = pb.start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(p.inputStream, "UTF-8")).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
            p.waitFor()
            sb.toString()
        } catch (e: Exception) {
            "ERR:${e.message}\n"
        } finally {
            try { p?.destroy() } catch (e: Exception) { }
        }
    }

    // ---------------- SHIZUKU ----------------

    @Volatile
    private var shizukuBinderOk = false

    @Volatile
    private var shizukuChecked = false

    private fun ensureShizuku(): Boolean {
        if (shizukuChecked) return shizukuBinderOk
        shizukuChecked = true
        shizukuBinderOk = try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val m = cls.getMethod("pingBinder")
            m.invoke(null) as? Boolean ?: false
        } catch (e: Throwable) {
            false
        }
        return shizukuBinderOk
    }

    /**
     * 通过 Shizuku 执行命令。
     * 目标方法：static Process newProcess(String[] cmd, String[] env, String dir)
     */
    private fun runShizuku(cmd: String): String? {
        if (!ensureShizuku()) return null
        return try {
            val cls = Class.forName("rikka.shizuku.Shizuku")
            val m = cls.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val argv: Array<String> = arrayOf("sh", "-c", cmd)
            val args = arrayOf<Any?>(argv, null, null)
            val proc = m.invoke(null, *args) as? Process ?: return null
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream, "UTF-8")).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
            proc.waitFor()
            sb.toString()
        } catch (e: Throwable) {
            "ERR:${e.message}"
        }
    }

    data class ExecResult(val ok: Boolean, val output: String, val usedLevel: Level)
}
