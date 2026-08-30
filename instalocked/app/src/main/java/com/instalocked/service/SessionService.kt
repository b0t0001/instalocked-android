package com.instalocked.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import com.instalocked.store.Store

/**
 * Holds the countdown for an unlocked session.
 *
 * This is a foreground service on purpose. Motorola's battery manager is
 * aggressive about background processes, and a killed timer would mean a
 * session that never ends. The persistent notification is the price of the
 * timer being trustworthy.
 */
class SessionService : Service() {

    companion object {
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SCREEN = "screen"
        private const val CHANNEL = "instalocked_session"
        private const val NOTIF_ID = 41

        fun start(ctx: Context, minutes: Int, screen: String) {
            val i = Intent(ctx, SessionService::class.java)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_SCREEN, screen)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }

    private var timer: CountDownTimer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 5) ?: 5
        val screen = intent?.getStringExtra(EXTRA_SCREEN) ?: "REELS_CONSUME"

        createChannel()
        startForeground(NOTIF_ID, buildNotification(minutes * 60L))

        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        GuardService.instance?.onSessionStarted(endsAt)
        Store.appendSession(this, screen, minutes)

        timer?.cancel()
        timer = object : CountDownTimer(minutes * 60_000L, 1_000L) {
            override fun onTick(msLeft: Long) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotification(msLeft / 1000L))
            }

            override fun onFinish() {
                GuardService.instance?.onSessionEnded()
                stopForeground(true)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Session timer", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(secondsLeft: Long): Notification {
        val text = "%d:%02d remaining".format(secondsLeft / 60, secondsLeft % 60)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setContentTitle("Scrolling session")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }
}
