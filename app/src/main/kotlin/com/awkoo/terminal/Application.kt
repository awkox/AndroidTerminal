package com.awkoo.terminal

import android.app.Application
import com.awkoo.terminal.core.TimberLogTree
import com.awkoo.terminal.constants.LogLevel
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject

/**
 * Hilt Application 入口。
 *
 * 初始化 Timber 日志并根据用户偏好动态调整日志级别。
 */
@HiltAndroidApp
class Application : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Inject
    lateinit var preferences: AppPreferences

    override fun onCreate() {
        super.onCreate()

        val timberTree = TimberLogTree(LogLevel.VERBOSE)
        preferences.logLevel
            .onEach { timberTree.level = it }
            .launchIn(scope)
        Timber.plant(timberTree)

        Timber.i("Starting")
    }
}
