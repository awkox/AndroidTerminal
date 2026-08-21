package com.awkoo.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.awkoo.terminal.core.SessionManager
import com.awkoo.terminal.core.ShellInfo
import com.awkoo.terminal.core.TerminalSession
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 终端前台服务。
 *
 * 管理会话生命周期、通知
 * 会话列表变更时自动更新通知；无会话且无锁时自动停止服务。
 */
@AndroidEntryPoint
class TerminalService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Inject
    lateinit var sessionManager: SessionManager

    private var isStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")

        // 防止服务已启动但 onCreate() 未被调用的情况，再次执行
        setupNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            null -> {}

            NotificationActions.EXIT.name -> {
                Timber.d("ACTION_STOP_SERVICE intent received")
                stopSelf()
            }

            else -> Timber.e("Invalid action: \"${intent.action}\"")
        }

        if (!isStarted) {
            sessionManager.sessionList
                .onEach { updateNotification() }
                .launchIn(serviceScope)

            isStarted = true
            isRunning = true
        }

        // 如果服务已被杀死，无需自动重启 — 等待用户下次启动
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Timber.v("onDestroy")

        serviceScope.cancel()

        sessionManager.clear()

        stopForeground(STOP_FOREGROUND_REMOVE)

        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun buildNotification(): Notification {
        // Set notification text
        val sessionCount = sessionManager.sessionListSize
        var notificationText =
            sessionCount.toString() + " session" + (if (sessionCount == 1) "" else "s")

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(notificationText)
            .addAction(NotificationActions.EXIT.getAction(this))
            .build()
    }

    private fun setupNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** 更新前台服务通知。 */
    @Synchronized
    private fun updateNotification() {
        if (sessionManager.isSessionsListEmpty) {
            // 用户禁用所有锁且无会话运行时，退出服务
            stopSelf()
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "TerminalService"
        private const val NOTIFICATION_CHANNEL_NAME = "TerminalService"
        private const val NOTIFICATION_ID = 1
        var isRunning = false
    }


    private enum class NotificationActions(
        @param:StringRes val titleId: Int,
        @param:DrawableRes val iconId: Int
    ) {
        EXIT(
            R.string.notification_action_exit,
            android.R.drawable.ic_delete
        ),
        fun getAction(service: Context): NotificationCompat.Action {
            val intent = Intent(service, TerminalService::class.java)
                .apply { action = this@NotificationActions.name }
            val pendingIntent = PendingIntent.getService(
                service,
                this.ordinal,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            val action = NotificationCompat.Action.Builder(
                this.iconId,
                service.getString(this.titleId),
                pendingIntent
            ).build()
            return action
        }
    }
}
