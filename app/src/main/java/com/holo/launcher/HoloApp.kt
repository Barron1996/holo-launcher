package com.holo.launcher
import android.app.Application
import com.holo.launcher.apps.AppRepository
import com.holo.launcher.notification.NoteRepo
class HoloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        _instance = this
        try {
            AppRepository.init(this)
        } catch (e: Exception) {
        }
    }
    companion object {
        @Volatile private var _instance: HoloApp? = null
        val instance: HoloApp get() = _instance!!
        fun notes() = NoteRepo
    }
}
