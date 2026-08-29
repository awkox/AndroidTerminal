package com.awkoo.terminal

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Hilt Application 入口。
 */
@HiltAndroidApp
class Application : Application() {
    @Inject
    lateinit var preferences: AppPreferences

    override fun onCreate() {
        super.onCreate()
    }
}
