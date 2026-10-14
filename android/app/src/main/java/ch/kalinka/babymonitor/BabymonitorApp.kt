package ch.kalinka.babymonitor

import android.app.Application
import ch.kalinka.babymonitor.log.AppLog

class BabymonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.start(this)
    }
}
