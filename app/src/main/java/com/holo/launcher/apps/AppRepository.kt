package com.holo.launcher.apps
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
)
object AppRepository {
    @Volatile private var cache: List<AppEntry> = ArrayList()
    fun init(context: Context) {
        Thread {
            try {
                cache = loadInstalled(context)
            } catch (e: Exception) {
            }
        }.start()
    }
    fun cached(): List<AppEntry> = cache
    fun loadInstalled(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = PackageManager.GET_META_DATA
        val list = try {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
            else
                pm.queryIntentActivities(intent, flags)
        } catch (e: Exception) {
            emptyList()
        }
        val out = ArrayList<AppEntry>(list.size)
        for (ri in list) {
            try {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == context.packageName) continue
                val label = ai.loadLabel(pm)?.toString() ?: pkg
                val icon = ai.loadIcon(pm)
                out.add(AppEntry(label, pkg, ai.name ?: "", icon))
            } catch (e: Exception) {
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }
    private val iconCache = LruCache<String, Drawable>(256)
    fun iconOf(context: Context, app: AppEntry): Drawable {
        val hit = iconCache.get(app.packageName)
        if (hit != null) return hit
        val d = try {
            context.packageManager.getApplicationIcon(app.packageName)
        } catch (e: Exception) {
            app.icon
        }
        iconCache.put(app.packageName, d)
        return d
    }
    fun find(packageName: String): AppEntry? = cache.firstOrNull { it.packageName == packageName }
    fun launch(context: Context, app: AppEntry): Boolean {
        return try {
            val it = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(app.packageName, app.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                putExtra("holo_from_launcher", true)
            }
            context.startActivity(it)
            true
        } catch (e: Exception) {
            false
        }
    }
}
