package com.holo.launcher.ai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * 语音输入封装。使用系统 RecognizerIntent，无需额外依赖与权限声明
 * （由系统语音服务 App 自行处理录音权限）。
 */
object VoiceInput {

    const val REQ_CODE = 8101

    /**
     * 启动系统语音识别界面。
     * @return false 表示设备上没有可用的语音识别服务。
     */
    fun start(activity: Activity, onText: (String) -> Unit): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出指令")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        return try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQ_CODE)
            // 结果回调需由 Activity 的 onActivityResult 处理，见 extract()
            pending = onText
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 供 AiConsole 在非 Activity 上下文调用时的降级路径：返回 false 提示用户打字。 */
    fun start(context: Context, onText: (String) -> Unit): Boolean {
        if (context is Activity) return start(context, onText)
        // 非 Activity 上下文无法接收 result，仍尝试打开系统识别界面但不回填
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            false
        } catch (e: Exception) {
            false
        }
    }

    /** 当前等待回调的接收者。 */
    var pending: ((String) -> Unit)? = null

    /** 在 Activity.onActivityResult 中调用。 */
    fun extract(requestCode: Int, resultCode: Int, data: Intent?): String? {
        if (requestCode != REQ_CODE) return null
        val cb = pending
        pending = null
        if (resultCode != Activity.RESULT_OK) return null
        val list = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return null
        val text = list.firstOrNull().orEmpty().trim()
        if (text.isNotEmpty()) {
            cb?.invoke(text)
            return text
        }
        return null
    }
}