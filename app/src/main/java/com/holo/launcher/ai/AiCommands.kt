package com.holo.launcher.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 本地指令解析器：无 AI Key 时也能处理常见操作。
 * 返回 [Result] 表示是否已处理以及给用户的回执文本。
 */
object AiCommands {

    data class Result(val handled: Boolean, val reply: String)

    private val NONE = Result(false, "")

    fun handle(context: Context, raw: String): Result {
        val q = raw.trim()
        if (q.isEmpty()) return NONE
        val lower = q.lowercase()

        // ---- 打开应用：打开微信 / 启动 XX ----
        val openPrefix = listOf("打开", "启动", "运行", "open ", "launch ")
        for (p in openPrefix) {
            if (lower.startsWith(p)) {
                val name = q.substring(p.length).trim()
                if (name.isNotEmpty()) {
                    val ok = launchByName(context, name)
                    return if (ok) Result(true, "已打开 $name")
                    else Result(true, "没有找到「$name」")
                }
            }
        }

        // ---- Monster ----
        if (lower.contains("monster") || q.contains("狂暴") || q.contains("提频")) {
            val on = !com.holo.launcher.sys.Theme.isBerserk
            if (on) com.holo.launcher.sys.Theme.enterBerserk()
            else com.holo.launcher.sys.Theme.exitBerserk()
            return Result(true, if (on) "MONSTER 已开启" else "MONSTER 已关闭")
        }

        // ---- 设置类跳转 ----
        when {
            lower.contains("wifi") || lower.contains("无线") -> {
                jump(context, Intent(Settings.ACTION_WIFI_SETTINGS))
                return Result(true, "已打开 WLAN 设置")
            }
            lower.contains("蓝牙") || lower.contains("bluetooth") -> {
                jump(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                return Result(true, "已打开蓝牙设置")
            }
            lower.contains("流量") || lower.contains("数据") -> {
                jump(context, Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
                return Result(true, "已打开流量设置")
            }
            lower.contains("通知") -> {
                jump(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return Result(true, "已打开通知读取权限")
            }
            lower.contains("设置") || lower.contains("setting") -> {
                jump(context, Intent(Settings.ACTION_SETTINGS))
                return Result(true, "已打开系统设置")
            }
        }

        // ---- 打电话 ----
        if (lower.startsWith("打电话") || lower.startsWith("呼叫") || lower.startsWith("call ")) {
            val num = q.replace(Regex("[^0-9+]"), "")
            if (num.length >= 3) {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return Result(true, "已拨号 $num")
                } catch (e: Exception) {
                    return Result(true, "无法拨号")
                }
            }
            return Result(true, "号码不完整")
        }

        // ---- 搜索 ----
        val searchPrefix = listOf("搜索", "查一下", "搜 ", "search ")
        for (p in searchPrefix) {
            if (lower.startsWith(p)) {
                val kw = q.substring(p.length).trim()
                if (kw.isNotEmpty()) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(kw)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        return Result(true, "已搜索「$kw」")
                    } catch (e: Exception) {
                        return Result(true, "无法打开浏览器")
                    }
                }
            }
        }

        // ---- 时间 ----
        if (q.contains("几点") || lower.contains("时间")) {
            val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            return Result(true, "现在是 " + f.format(java.util.Date()))
        }

        return NONE
    }

    private fun jump(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // 忽略，系统可能限制
        }
    }

    private fun launchByName(context: Context, name: String): Boolean {
        val apps = try {
            com.holo.launcher.apps.AppRepository.cached()
        } catch (e: Exception) {
            emptyList<com.holo.launcher.apps.AppEntry>()
        }
        val target = apps.firstOrNull {
            it.label.equals(name, ignoreCase = true) || it.label.contains(name)
        } ?: return false
        return try {
            com.holo.launcher.apps.AppRepository.launch(context, target)
            true
        } catch (e: Exception) {
            false
        }
    }
}