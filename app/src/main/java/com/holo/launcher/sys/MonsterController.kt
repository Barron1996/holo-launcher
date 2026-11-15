package com.holo.launcher.sys

import android.content.Context
import android.os.Build
import java.io.File

/**
 * vivo/iQOO Monster 模式控制器。
 *
 * 现实约束：Monster 是厂商私有性能调度开关，无公开 API。
 * 因此本控制器采用三级降级：
 *
 *  1. 通过特权通道（ROOT / SHIZUKU）执行厂商命令，命中即生效。
 *  2. 命中失败时，把探测结果写入 /sdcard/HoloLauncher/probe.txt，
 *     供人工研判该机型的真实入口，再针对性适配。
 *  3. 无论特权通道是否可用，Theme 的视觉切换始终生效
 *     （即“视觉狂暴”永远可用，“真实提频”依赖特权通道）。
 *
 * 说明：本类不做任何形式的能力欺骗——拿不到真实通道时，
 * report 会明确告知“仅视觉模式，未实际提频”。
 */
object MonsterController {

    private const val PROBE_DIR = "/sdcard/HoloLauncher"
    private const val PROBE_FILE = "$PROBE_DIR/probe.txt"

    /** 探测/切换结果。 */
    data class Result(
        val realBoost: Boolean,
        val detail: String,
        val logPath: String? = null
    )

    /** 上一次切换的结果，供 UI 显示。 */
    @Volatile
    var last: Result = Result(false, "未执行")

    /**
     * 尝试开启/关闭 Monster。
     * @param on true=开启，false=关闭
     */
    fun setMonster(context: Context, on: Boolean): Result {
        if (!Privilege.enabled) {
            val r = Result(false, "特权通道未开启：仅视觉切换，未实际提频")
            last = r
            return r
        }
        if (Privilege.level == Privilege.Level.NONE) {
            Privilege.probe()
            if (Privilege.level == Privilege.Level.NONE) {
                val r = Result(false, "无可用特权通道（${Privilege.note}）：仅视觉切换")
                last = r
                return r
            }
        }

        // 1) 厂商已知命令候选，逐个尝试，命中即返回。
        for (cmd in candidates(on)) {
            val out = Privilege.exec(cmd)
            if (out.ok && isHit(out.output)) {
                val r = Result(true, "$cmd\n→ ${out.output.trim()}", null)
                last = r
                return r
            }
        }

        // 2) 全部未命中 → 生成探测报告，供后续针对性适配。
        val log = writeProbe(context)
        val r = Result(
            false,
            "未命中厂商入口：仅视觉切换。探测报告已写入 ${log ?: PROBE_FILE}",
            log
        )
        last = r
        return r
    }

    /** 已知/常见候选命令。 */
    private fun candidates(on: Boolean): List<String> {
        val v = if (on) "1" else "0"
        return listOf(
            // vivo 私有 broadcast（部分机型有效）
            "am broadcast -a com.vivo.permission.monster.$v",
            // 通用性能服务
            "cmd power set-monster $v",
            "cmd performance set-mode ${if (on) "monster" else "normal"}",
            // 私有 secure 键
            "settings put secure monster_mode $v",
            "settings put system monster_mode $v",
            "settings put global monster_mode $v"
        )
    }

    /** 判定输出是否表示成功。 */
    private fun isHit(out: String): Boolean {
        if (out.isBlank()) return false
        val s = out.lowercase()
        if (s.contains("error") || s.contains("exception")) return false
        if (s.contains("unknown") || s.contains("no such")) return false
        if (s.contains("not found") || s.contains("permission denial")) return false
        if (s.contains("unable") || s.contains("failed")) return false
        if (s.contains("exception")) return false
        return s.contains("result=0") || s.contains("broadcast completed") ||
            s.contains("ok") || s.contains("success") || s.contains("result code: 0")
    }

    // ---------------- 探测报告 ----------------

    private val PROBE_CMDS = linkedMapOf(
        "id" to "id",
        "sdk" to "getprop ro.build.version.sdk",
        "brand" to "getprop ro.product.brand",
        "model" to "getprop ro.product.model",
        "sys_perf_prop" to "getprop | grep -i -E 'perf|monster|boost' | head -30",
        "settings_global_monster" to "settings list global | grep -i monster",
        "settings_system_monster" to "settings list system | grep -i monster",
        "settings_secure_monster" to "settings list secure | grep -i monster",
        "cmd_list" to "cmd -l | grep -i -E 'perf|monster|power|thermal'",
        "service_list" to "service list | grep -i -E 'perf|monster|power|thermal'",
        "dumpsys_power" to "dumpsys power | grep -i -E 'monster|perf|mode' | head -20",
        "dumpsys_activity_broadcasts" to "dumpsys activity broadcasts | grep -i monster | head -10",
        "cmd_power_help" to "cmd power 2>&1 | head -20",
        "ps_vivo" to "ps -A | grep -i -E 'vivo|bbk|perf' | head -20"
    )

    /** 生成探测报告并写入 /sdcard/HoloLauncher/probe.txt。返回文件路径或 null。 */
    fun writeProbe(context: Context): String? {
        return try {
            File(PROBE_DIR).mkdirs()
            val sb = StringBuilder()
            sb.append("=== HOLO LAUNCHER :: MONSTER PROBE ===\n")
            sb.append("time=").append(System.currentTimeMillis()).append('\n')
            sb.append("device=").append(Build.BRAND).append('/').append(Build.MODEL).append('\n')
            sb.append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
            sb.append("privilege.enabled=").append(Privilege.enabled).append('\n')
            sb.append("privilege.level=").append(Privilege.level).append('\n')
            sb.append("privilege.note=").append(Privilege.note).append('\n')
            sb.append('\n')

            for ((label, cmd) in PROBE_CMDS) {
                sb.append("----- [").append(label).append("] ").append(cmd).append('\n')
                val r = Privilege.exec(cmd)
                sb.append("ok=").append(r.ok).append(" level=").append(r.usedLevel).append('\n')
                val out = r.output.trim()
                sb.append(if (out.isEmpty()) "(empty)" else out).append('\n')
                sb.append('\n')
            }

            val f = File(PROBE_FILE)
            f.writeText(sb.toString(), Charsets.UTF_8)
            PROBE_FILE
        } catch (e: Throwable) {
            null
        }
    }

    /** 读取已生成的报告（用于 UI 内查看，最多 8000 字符）。 */
    fun readProbe(): String {
        return try {
            val f = File(PROBE_FILE)
            if (!f.exists()) "尚未生成探测报告"
            else f.readText(Charsets.UTF_8).take(8000)
        } catch (e: Throwable) {
            "读取失败：${e.message}"
        }
    }
}