package com.buzzness.buzzlearn

import android.app.Application
import com.buzzness.buzzlearn.data.AppContainer

class BuzzLearnApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
