package com.holo.launcher.ai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.holo.launcher.fx.ChamferPanel
import com.holo.launcher.sys.Theme

/**
 * AI 控制台：覆盖式面板，包含历史对话、输入区、语音按钮。
 * 不是一个条，而是一块可收起的控制面板。
 */
class AiConsole(context: Context) : FrameLayout(context) {

    private val panelPaint = ChamferPanel()
    private val bgView = ConsoleBg(context)

    private val listArea: LinearLayout
    private val scrollHost: android.widget.ScrollView
    private val input: EditText
    private val sendBtn: TextView
    private val voiceBtn: TextView
    private val statusText: TextView

    private val history = ArrayList<AiClient.Msg>()

    var onClose: (() -> Unit)? = null
    var pendingText: String = ""

    init {
        setWillNotDraw(false)
        setBackgroundColor(0x00000000)
        addView(bgView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(14), dp(16), dp(10))

        // 顶部标题行
        val head = LinearLayout(context)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(context)
        title.text = "AI // CONSOLE"
        title.textSize = 10f
        title.letterSpacing = 0.16f
        title.setTextColor(Theme.accent)
        title.typeface = Typeface.MONOSPACE
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = TextView(context)
        close.text = "收起 ▾"
        close.textSize = 11f
        close.setTextColor(Theme.textDim)
        close.setPadding(dp(12), dp(4), dp(4), dp(4))
        close.setOnClickListener { onClose?.invoke() }
        head.addView(close)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 状态行
        statusText = TextView(context)
        statusText.textSize = 9f
        statusText.typeface = Typeface.MONOSPACE
        statusText.setTextColor(Theme.textFaint)
        statusText.text = if (AiSettings.configured()) "ONLINE" else "LOCAL ONLY · 未配置 KEY"
        root.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 对话滚动区
        scrollHost = android.widget.ScrollView(context)
        listArea = LinearLayout(context)
        listArea.orientation = LinearLayout.VERTICAL
        scrollHost.addView(listArea, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lpScroll = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lpScroll.topMargin = dp(8)
        root.addView(scrollHost, lpScroll)

        // 输入区
        val inputRow = LinearLayout(context)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(0, dp(8), 0, 0)

        input = EditText(context)
        input.hint = "输入指令…"
        input.textSize = 13f
        input.setTextColor(Theme.text)
        input.setHintTextColor(Theme.textFaint)
        input.background = null
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.maxLines = 3
        input.setPadding(dp(10), dp(6), dp(10), dp(6))
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        voiceBtn = mkPill("说", Theme.accent)
        voiceBtn.setOnClickListener { startVoice() }
        run { val lp = LinearLayout.LayoutParams(dp(40), dp(34)); lp.leftMargin = dp(6); inputRow.addView(voiceBtn, lp) }

        sendBtn = mkPill("发", Theme.accent)
        sendBtn.setOnClickListener { submit() }
        run { val lp = LinearLayout.LayoutParams(dp(40), dp(34)); lp.leftMargin = dp(6); inputRow.addView(sendBtn, lp) }

        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (pendingText.isNotBlank()) {
            input.setText(pendingText)
            input.setSelection(input.text.length)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mkPill(label: String, color: Int): TextView {
        val t = TextView(context)
        t.text = label
        t.textSize = 12f
        t.gravity = Gravity.CENTER
        t.setTextColor(color)
        val bg = android.graphics.drawable.GradientDrawable()
        bg.setColor(0)
        bg.setStroke(dp(1), Theme.alpha(color, 0.55f))
        bg.cornerRadius = dp(6).toFloat()
        t.background = bg
        return t
    }

    private fun startVoice() {
        val a = context
        val ok = VoiceInput.start(a) { text ->
            if (text.isNotBlank()) {
                input.setText(text)
                input.setSelection(input.text.length)
                submit()
            }
        }
        if (!ok) {
            toast("语音输入不可用，请直接打字")
        }
    }

    private fun toast(msg: String) {
        try {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
        }
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addBubble(text, true)

        // 先走本地指令
        val local = AiCommands.handle(context, text)
        if (local.handled) {
            addBubble(local.reply, false)
            if (local.reply.contains("MONSTER")) {
                // 状态变化后通知外界
                post { }
            }
            return
        }

        if (!AiSettings.configured()) {
            addBubble("未配置 AI 服务，只能执行本地指令。可在设置中填写 API Key。", false)
            return
        }

        val thinking = addBubble("…", false)
        AiClient.ask(
            context, text, history,
            onDone = { reply ->
                thinking.text = reply
                history.add(AiClient.Msg("user", text))
                history.add(AiClient.Msg("assistant", reply))
                if (history.size > 12) {
                    while (history.size > 12) history.removeAt(0)
                }
                scrollToEnd()
            },
            onError = { err ->
                thinking.text = "请求失败：" + err
                scrollToEnd()
            }
        )
    }

    private fun scrollToEnd() {
        scrollHost.post { scrollHost.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(text: String, mine: Boolean): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = 13f
        t.setTextColor(if (mine) Theme.text else Theme.textDim)
        t.setLineSpacing(0f, 1.3f)
        t.setPadding(dp(10), dp(7), dp(10), dp(7))

        val bg = android.graphics.drawable.GradientDrawable()
        bg.cornerRadius = dp(8).toFloat()
        bg.setColor(if (mine) Theme.alpha(Theme.accent, 0.10f) else Theme.alpha(Theme.panelStroke(), 0.9f))
        t.background = bg

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        t.layoutParams = lp

        val wrap = LinearLayout(context)
        wrap.orientation = LinearLayout.HORIZONTAL
        wrap.gravity = if (mine) Gravity.END else Gravity.START
        wrap.addView(t)
        listArea.addView(wrap)
        scrollToEnd()
        return t
    }

    /** 面板背景：切角玻璃 + 顶部强调扫描线。 */
    private inner class ConsoleBg(c: Context) : View(c) {
        private val r = RectF()
        private val accentBar = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cut = dp(12).toFloat()
            r.set(0f, 0f, w, h)
            panelPaint.drawPanel(
                canvas, r, cut,
                Theme.alpha(Theme.panelFill(true), 0.97f),
                Theme.alpha(Theme.panelStrokeHi(), 0.8f),
                0, 0f, 1f
            )
            // 顶部 2dp 强调线
            accentBar.color = Theme.alpha(Theme.accent, 0.7f)
            canvas.drawRect(dp(2).toFloat(), 0f, w - dp(2), dp(2).toFloat(), accentBar)
        }
    }
}