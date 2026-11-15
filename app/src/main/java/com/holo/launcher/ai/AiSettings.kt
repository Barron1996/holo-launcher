package com.holo.launcher.ai

import android.content.Context

/**
 * AI 配置持久化。全部字段可选，未配置时 AiClient 走本地指令。
 */
object AiSettings {

    private const val PREF = "holo_ai"

    var endpoint: String = "https://api.deepseek.com/chat/completions"
    var apiKey: String = ""
    var model: String = "deepseek-chat"

    /** 是否已经配置了可用 Key。 */
    fun configured(): Boolean = apiKey.isNotBlank()

    /** 一次性弹窗是否已被用户消除。 */
    fun hintDismissed(context: Context): Boolean =
        prefs(context).getBoolean("hint_dismissed", false)

    fun dismissHint(context: Context) {
        prefs(context).edit().putBoolean("hint_dismissed", true).apply()
    }

    fun load(context: Context) {
        val sp = prefs(context)
        endpoint = sp.getString("endpoint", endpoint) ?: endpoint
        apiKey = sp.getString("api_key", "") ?: ""
        model = sp.getString("model", model) ?: model
    }

    fun save(context: Context) {
        prefs(context).edit()
            .putString("endpoint", endpoint)
            .putString("api_key", apiKey)
            .putString("model", model)
            .apply()
    }

    fun clearKey(context: Context) {
        apiKey = ""
        prefs(context).edit().putString("api_key", "").apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}