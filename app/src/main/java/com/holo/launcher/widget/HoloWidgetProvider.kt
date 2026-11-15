package com.holo.launcher.widget
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
class HoloWidgetProvider : AppWidgetProvider() {
    companion object {
        fun refreshAll(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                if (mgr == null) return
                val cn = ComponentName(context, HoloWidgetProvider::class.java)
                val ids = mgr.getAppWidgetIds(cn)
                if (ids == null || ids.isEmpty()) return
                val intent = Intent(context, HoloWidgetProvider::class.java)
                intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            } catch (e: Exception) {
            }
        }
        fun ids(context: Context): IntArray {
            return try {
                val mgr = AppWidgetManager.getInstance(context) ?: return IntArray(0)
                mgr.getAppWidgetIds(ComponentName(context, HoloWidgetProvider::class.java)) ?: IntArray(0)
            } catch (e: Exception) {
                IntArray(0)
            }
        }
    }
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            try {
                WidgetHostView.markDirty(id)
            } catch (e: Exception) {
            }
        }
    }
}
