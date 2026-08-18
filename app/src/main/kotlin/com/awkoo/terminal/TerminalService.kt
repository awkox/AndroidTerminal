package com.awkoo.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
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
 * 管理会话生命周期、通知、WakeLock 和 WifiLock。
 * 会话列表变更时自动更新通知；无会话且无锁时自动停止服务。
 */
@AndroidEntryPoint
class TerminalService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Inject
    lateinit var sessionManager: SessionManager

    /** 电源锁和 Wifi 锁始终同时获取和释放。 */
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mWifiLock: WifiManager.WifiLock? = null

    private var isStarted = false

    @SuppressLint("Wakelock")
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

            NotificationActions.WAKE_LOCK.name -> {
                Timber.d("ACTION_WAKE_LOCK intent received")
                actionAcquireWakeLock()
            }

            NotificationActions.WAKE_UNLOCK.name -> {
                Timber.d("ACTION_WAKE_UNLOCK intent received")
                actionReleaseWakeLock(true)
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

        actionReleaseWakeLock(false)

        serviceScope.cancel()

        sessionManager.clear()

        stopForeground(STOP_FOREGROUND_REMOVE)

        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /** 获取电源和 Wi-Fi WakeLock。 */
    @Synchronized
    @SuppressLint("WakelockTimeout", "BatteryLife")
    private fun actionAcquireWakeLock() {
        if (mWakeLock != null) {
            Timber.d("Ignoring acquiring WakeLocks since they are already held")
            return
        }

        Timber.d("Acquiring WakeLocks")

        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            getString(R.string.application_name) + ":service-wakelock"
        ).let {
            it.acquire()
            mWakeLock = it
        }

        // http://tools.android.com/tech-docs/lint-in-studio-2-3#TOC-WifiManager-Leak
        val wm = getSystemService(WifiManager::class.java)
        wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            getString(R.string.application_name)
        ).let {
            it.acquire()
            mWifiLock = it
        }

        updateNotification()

        Timber.d("WakeLocks acquired successfully")
    }

    /** 释放电源和 Wi-Fi WakeLock。 */
    @Synchronized
    private fun actionReleaseWakeLock(updateNotification: Boolean) {
        if (mWakeLock == null && mWifiLock == null) {
            Timber.d("Ignoring releasing WakeLocks since none are already held")
            return
        }

        Timber.d("Releasing WakeLocks")

        mWakeLock?.release()
        mWakeLock = null
        mWifiLock?.release()
        mWifiLock = null

        if (updateNotification) updateNotification()

        Timber.d("WakeLocks released successfully")
    }

    private fun buildNotification(): Notification {
        // Set notification text
        val sessionCount = sessionManager.sessionListSize
        var notificationText =
            sessionCount.toString() + " session" + (if (sessionCount == 1) "" else "s")

        val wakeLockHeld = mWakeLock != null
        if (wakeLockHeld) notificationText += " (wake lock held)"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setShowWhen(false)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(notificationText)
            .addAction(NotificationActions.EXIT.getAction(this))
            .addAction(
                if (!wakeLockHeld) NotificationActions.WAKE_LOCK.getAction(this)
                else NotificationActions.WAKE_UNLOCK.getAction(this)
            )
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
        if (mWakeLock == null && sessionManager.isSessionsListEmpty) {
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
        WAKE_LOCK(
            R.string.notification_action_wake_lock,
            android.R.drawable.ic_lock_lock
        ),
        WAKE_UNLOCK(
            R.string.notification_action_wake_unlock,
            android.R.drawable.ic_lock_idle_lock
        );

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
