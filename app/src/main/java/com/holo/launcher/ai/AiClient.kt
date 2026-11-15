package com.holo.launcher.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 极简 OpenAI 兼容客户端。使用 Thread + Handler，不引入协程。
 */
object AiClient {

    private val main = Handler(Looper.getMainLooper())

    data class Msg(val role: String, val content: String)

    /** 单轮请求，回调在主线程。 */
    fun ask(
        context: android.content.Context,
        userText: String,
        history: List<Msg> = emptyList(),
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!AiSettings.configured()) {
            onError("未配置 API Key")
            return
        }
        val endpoint = AiSettings.endpoint
        val key = AiSettings.apiKey
        val model = AiSettings.model

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                body.put("model", model)
                body.put("stream", false)

                val arr = JSONArray()
                arr.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                for (m in history) {
                    arr.put(JSONObject().put("role", m.role).put("content", m.content))
                }
                arr.put(JSONObject().put("role", "user").put("content", userText))
                body.put("messages", arr)

                val url = URL(endpoint)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 45000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer $key")

                val os = OutputStreamWriter(conn.outputStream, "UTF-8")
                os.write(body.toString())
                os.flush()
                os.close()

                val code = conn.responseCode
                if (code !in 200..299) {
                    val errStream = conn.errorStream
                    val errText = errStream?.let { readAll(it) } ?: ""
                    postError(onError, "HTTP $code " + errText.take(200))
                    return@Thread
                }

                val text = readAll(conn.inputStream)
                val reply = parseReply(text)
                if (reply.isNullOrBlank()) {
                    postError(onError, "返回内容为空")
                } else {
                    postDone(onDone, reply)
                }
            } catch (e: Exception) {
                postError(onError, (e.message ?: "网络错误").take(200))
            } finally {
                try { conn?.disconnect() } catch (e: Exception) { }
            }
        }.start()
    }

    private const val SYSTEM_PROMPT =
        "你是 Holo 桌面助手，运行在 Android 手机桌面上。" +
            "回答必须简洁，中文，尽量不超过 80 字，不要使用 Markdown 标题。" +
            "如果用户是在下指令操作手机，用一句话确认即可。"

    private fun readAll(input: java.io.InputStream): String {
        val sb = StringBuilder()
        val br = BufferedReader(InputStreamReader(input, "UTF-8"))
        var line = br.readLine()
        while (line != null) {
            sb.append(line)
            line = br.readLine()
        }
        try { br.close() } catch (e: Exception) { }
        return sb.toString()
    }

    private fun parseReply(raw: String): String? {
        return try {
            val obj = JSONObject(raw)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val first = choices.optJSONObject(0) ?: return null
            val msg = first.optJSONObject("message") ?: return null
            msg.optString("content", "")
        } catch (e: Exception) {
            null
        }
    }

    private fun postDone(cb: (String) -> Unit, text: String) {
        main.post { try { cb(text) } catch (e: Exception) { } }
    }

    private fun postError(cb: (String) -> Unit, text: String) {
        main.post { try { cb(text) } catch (e: Exception) { } }
    }
}