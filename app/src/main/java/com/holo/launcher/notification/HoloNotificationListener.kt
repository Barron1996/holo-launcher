package com.holo.launcher.notification
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
class HoloNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var connected: Boolean = false
        private var instance: HoloNotificationListener? = null
        fun reply(context: Context, key: String, text: String): Boolean {
            val s = instance ?: return false
            return try {
                s.sendReply(key, text)
            } catch (e: Exception) {
                false
            }
        }
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        instance = this
        try {
            for (sbn in activeNotifications) handle(sbn)
        } catch (e: Exception) {
        }
    }
    override fun onListenerDisconnected() {
        connected = false
        instance = null
        super.onListenerDisconnected()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handle(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        try {
            NoteRepo.remove(sbn.key)
        } catch (e: Exception) {
        }
    }
    private fun handle(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            if (sbn.packageName == packageName) return
            if (sbn.isOngoing) return
            val extras: Bundle = n.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            if (title.isEmpty() && text.isEmpty()) return
            val label = loadLabel(sbn.packageName)
            val reply = hasReplyAction(n)
            NoteRepo.upsert(
                HoloNote(
                    key = sbn.key,
                    appLabel = label,
                    title = title,
                    text = text,
                    hasReply = reply,
                    time = sbn.postTime
                )
            )
        } catch (e: Exception) {
        }
    }
    private fun hasReplyAction(n: Notification): Boolean {
        val actions = n.actions ?: return false
        for (a in actions) {
            if (a == null) continue
            val ri = a.remoteInputs
            if (ri != null && ri.isNotEmpty()) return true
        }
        return false
    }
    private fun sendReply(key: String, text: String): Boolean {
        val list = activeNotifications ?: return false
        var target: StatusBarNotification? = null
        for (sbn in list) {
            if (sbn.key == key) {
                target = sbn
                break
            }
        }
        val sbn = target ?: return false
        val actions = sbn.notification?.actions ?: return false
        for (a in actions) {
            if (a == null) continue
            val ri = a.remoteInputs
            if (ri == null || ri.isEmpty()) continue
            try {
                // 关键：必须向【原始】通知 action 的 PendingIntent 投递结果。
                // 之前实现用 PendingIntent.getBroadcast 新建一个，与应用的接收器无关，
                // 回复必然失败。正确做法：把 RemoteInput 结果塞进 Intent 的 extras，
                // 然后通过原 actionIntent 的 IntentSender 发送。
                val pi = a.actionIntent ?: continue
                val b = Bundle()
                for (r in ri) {
                    b.putCharSequence(r.resultKey, text)
                }
                val fill = android.content.Intent()
                android.app.RemoteInput.addResultsToIntent(ri, fill, b)
                // 只走一条通道，避免重复触发：优先 IntentSender.sendIntent
                val sender = pi.intentSender
                sender.sendIntent(this, 0, fill, null, null)
                return true
            } catch (e: Exception) {
                // 兜底：部分 ROM 对空 Intent 敏感，改用 pi.send 带 fill
                try {
                    val pi2 = a.actionIntent ?: return false
                    val b2 = Bundle()
                    for (r in ri) b2.putCharSequence(r.resultKey, text)
                    val fill2 = android.content.Intent()
                    android.app.RemoteInput.addResultsToIntent(ri, fill2, b2)
                    pi2.send(this, 0, fill2)
                    return true
                } catch (e2: Exception) {
                }
            }
        }
        return false
    }
    private fun loadLabel(pkg: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
