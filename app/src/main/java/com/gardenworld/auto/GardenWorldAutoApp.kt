package com.gardenworld.auto

import android.app.Application
import timber.log.Timber

class GardenWorldAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.d("GardenWorldAutoApp initialized")
    }
}
