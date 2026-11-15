package com.holo.launcher.fx

import java.util.Calendar

/** 时间/日期格式化（不依赖第三方库） */
object ClockFx {
    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun time(): String {
        val c = Calendar.getInstance()
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun seconds(): Int = Calendar.getInstance().get(Calendar.SECOND)

    fun date(): String {
        val c = Calendar.getInstance()
        return String.format("%02d.%02d.%d  %s", c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.YEAR), WEEK[c.get(Calendar.DAY_OF_WEEK) - 1])
    }
}
