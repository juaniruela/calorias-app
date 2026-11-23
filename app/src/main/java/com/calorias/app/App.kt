package com.calorias.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notif.channels(this)
        val s = SettingsStore(this).load()
        SummaryWorker.schedule(this, s.summaryHour, s.summaryMinute)
    }
}
