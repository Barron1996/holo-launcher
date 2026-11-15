package com.holo.launcher.notification

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import java.util.ArrayList

data class HoloNote(
    val key: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val hasReply: Boolean,
    val time: Long
)

object NoteRepo {

    private val items = ArrayList<HoloNote>()
    private val lock = Any()

    @Volatile private var listener: (() -> Unit)? = null

    fun setChangeListener(l: (() -> Unit)?) { listener = l }

    fun all(): List<HoloNote> = synchronized(lock) { ArrayList(items) }

    fun count(): Int = synchronized(lock) { items.size }

    fun upsert(n: HoloNote) {
        var changed = false
        synchronized(lock) {
            val idx = items.indexOfFirst { it.key == n.key }
            if (idx >= 0) {
                items[idx] = n
                changed = true
            } else if (items.size < 60) {
                items.add(0, n)
                changed = true
            }
        }
        if (changed) listener?.invoke()
    }

    fun remove(key: String) {
        val changed = synchronized(lock) { items.removeAll { it.key == key } }
        if (changed) listener?.invoke()
    }

    fun clear() {
        synchronized(lock) { items.clear() }
        listener?.invoke()
    }

    fun isEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (TextUtils.isEmpty(flat)) return false
        val cn = ComponentName(context, HoloNotificationListener::class.java)
        return flat!!.contains(cn.flattenToString()) || flat.contains(context.packageName)
    }
}
