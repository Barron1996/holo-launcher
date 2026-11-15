package com.holo.launcher.widget
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
class WidgetHostView(context: Context) : FrameLayout(context) {
    companion object {
        private val dirty = java.util.HashSet<Int>()
        fun markDirty(id: Int) {
            synchronized(dirty) { dirty.add(id) }
        }
        fun takeDirty(): IntArray {
            synchronized(dirty) {
                val arr = dirty.toIntArray()
                dirty.clear()
                return arr
            }
        }
    }
    private var host: AppWidgetHost? = null
    private var hostView: AppWidgetHostView? = null
    private var widgetId: Int = -1
    fun attach(hostId: Int) {
        if (host == null) host = AppWidgetHost(context, hostId)
    }
    fun bind(info: AppWidgetProviderInfo, id: Int) {
        try {
            val h = host ?: return
            val hView = h.createView(context, id, info) ?: return
            hostView?.let { removeView(it) }
            hostView = hView
            widgetId = id
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            addView(hView, lp)
            hView.updateAppWidgetOptions(appWidgetOptions())
        } catch (e: Exception) {
        }
    }
    fun appWidgetOptions(): Bundle {
        val b = Bundle()
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (width / density()).toInt().coerceAtLeast(80))
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (height / density()).toInt().coerceAtLeast(80))
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (width / density()).toInt().coerceAtLeast(80))
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (height / density()).toInt().coerceAtLeast(80))
        return b
    }
    private fun density(): Float = resources.displayMetrics.density
    fun unbind() {
        try {
            hostView?.let { removeView(it) }
            if (host != null && widgetId >= 0) host!!.deleteAppWidgetId(widgetId)
        } catch (e: Exception) {
        }
        hostView = null
        widgetId = -1
    }
    fun releaseHost() {
        try {
            host?.stopListening()
        } catch (e: Exception) {
        }
    }
    fun isBound(): Boolean = hostView != null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            host?.startListening()
        } catch (e: Exception) {
        }
    }
    override fun onDetachedFromWindow() {
        try {
            host?.stopListening()
        } catch (e: Exception) {
        }
        super.onDetachedFromWindow()
    }
}
