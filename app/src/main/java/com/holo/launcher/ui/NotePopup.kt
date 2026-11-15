package com.holo.launcher.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.holo.launcher.notification.HoloNote
import com.holo.launcher.notification.NoteRepo
import com.holo.launcher.sys.Theme

/**
 * 通知详情浮层（同时承担内联回复）。
 *
 * 不用 RadioGroup/RingView 那套自绘，直接摆 Android 原生控件但套上 Holo 配色，
 * 这样能直接复用系统输入法（中英文、语音输入、剪切板全部可用）。
 */
object NotePopup {

    /**
     * 弹一个可回复的浮层。
     * @param onSend 用户点发送时回调（正文已 trim），由调用方负责真正发出去
     */
    fun show(
        context: Context,
        note: HoloNote,
        onSend: ((String) -> Unit)?,
        onDismiss: (() -> Unit)? = null
    ) {
        val d = Dialog(context)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val pad = (context.resources.displayMetrics.density * 16f).toInt()

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.setBackgroundColor(Theme.panel)

        // 顶部标识
        val tag = TextView(context)
        tag.text = note.appLabel.uppercase() + "  //  " + fmtTime(note.time)
        tag.setTextColor(Theme.textDim)
        tag.textSize = 10f
        tag.letterSpacing = 0.1f
        tag.setPadding(0, 0, 0, pad / 2)
        root.addView(tag)

        // 标题
        val title = TextView(context)
        title.text = if (note.title.isBlank()) note.appLabel else note.title
        title.setTextColor(Theme.text)
        title.textSize = 15f
        title.setPadding(0, 0, 0, pad / 2)
        root.addView(title)

        // 正文（可滚动）
        val body = TextView(context)
        body.text = note.text
        body.setTextColor(Theme.textDim)
        body.textSize = 13f
        body.setLineSpacing(0f, 1.25f)
        body.setPadding(0, 0, 0, pad / 2)
        body.maxHeight = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
        root.addView(body, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        var input: EditText? = null
        if (note.hasReply && onSend != null) {
            input = EditText(context)
            input.hint = "回复…"
            input.setTextColor(Theme.text)
            input.setHintTextColor(Theme.textFaint)
            input.textSize = 13f
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            input.setSingleLine(false)
            input.maxLines = 4
            input.setBackgroundColor(Color.TRANSPARENT)
            input.setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = pad / 2
            root.addView(input, lp)
        }

        // 按钮行
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = pad / 2
        root.addView(row, rowLp)

        fun mkBtn(label: String, color: Int, onClick: () -> Unit): Button {
            val b = Button(context)
            b.text = label
            b.textSize = 12f
            b.isAllCaps = false
            b.setTextColor(color)
            b.setBackgroundColor(Color.TRANSPARENT)
            b.setOnClickListener { onClick() }
            return b
        }

        row.addView(mkBtn("清除", Theme.textDim) {
            try { NoteRepo.remove(note.key) } catch (e: Exception) {}
            d.dismiss()
            onDismiss?.invoke()
        })

        row.addView(mkBtn("关闭", Theme.textDim) {
            d.dismiss()
            onDismiss?.invoke()
        })

        if (input != null && onSend != null) {
            row.addView(mkBtn("发送", Theme.accent) {
                val txt = input.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty()) {
                    try { onSend.invoke(txt) } catch (e: Exception) {}
                    d.dismiss()
                    onDismiss?.invoke()
                }
            })
        }

        val wrap = FrameLayout(context)
        wrap.setBackgroundColor(Theme.panelStroke())
        wrap.setPadding(1, 1, 1, 1)
        wrap.addView(root)

        d.setContentView(wrap)
        d.setCancelable(true)

        val win: Window? = d.window
        win?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val attrs = win?.attributes
        if (attrs != null) {
            attrs.dimAmount = 0.6f
            win.attributes = attrs
        }
        d.show()

        // 显示后调宽度为屏宽的 88%
        val w = win
        if (w != null) {
            val screenW = context.resources.displayMetrics.widthPixels
            w.setLayout((screenW * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        if (input != null) {
            input.requestFocus()
        }
    }

    private fun fmtTime(t: Long): String {
        return try {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
        } catch (e: Exception) {
            "--:--"
        }
    }
}