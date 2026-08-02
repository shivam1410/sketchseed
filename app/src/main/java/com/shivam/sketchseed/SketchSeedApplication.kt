package com.shivam.sketchseed

import android.app.Application

class SketchSeedApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
