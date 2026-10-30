package com.wallled.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Runs due schedules in the background: connects to the strips this phone has used before
 * (or reuses the page's links if the app is open), sends the scene, and for a wake-up fade
 * raises the brightness step by step. Stops once everything is done.
 */
class ScheduleService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var jobs = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        LedBle.init(this)
        val due = Schedules.takeDue(this)
        Schedules.arm(this)
        if (due.isEmpty()) {
            if (jobs == 0) finish()
            return START_NOT_STICKY
        }

        val longest = due.maxOf { (s, _) -> if (s.action == "wake") s.minutes else 0 }
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WallLED:schedule")
            .apply { setReferenceCounted(false) }.also { wakeLock = it }
        lock.acquire((longest + 2) * 60_000L)

        for ((s, at) in due) {
            jobs++
            Thread {
                try { perform(s, at) } catch (e: InterruptedException) { }
                main.post { if (--jobs == 0) finish() }
            }.start()
        }
        return START_NOT_STICKY
    }

    private fun perform(s: Schedules.Schedule, at: Long) {
        val strips = LedBle.knownAddresses()
        if (strips.isEmpty()) return
        LedBle.ensure(strips)
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !LedBle.readyAddresses.containsAll(strips)) Thread.sleep(250)
        if (LedBle.readyAddresses.isEmpty()) return

        when (s.action) {
            "off" -> send(frame(0x7b, 0x00, 0x04, 0x00, 0x00, 0x00, 0xff, 0x00, 0xbf))
            "scene" -> { scene(s); bright(s.bright) }
            "wake" -> {
                val total = s.minutes * 60_000L
                var ready = emptySet<String>()
                var level = -1
                while (true) {
                    // Also catches a strip that connects late or reconnects mid-fade.
                    if (!ready.containsAll(LedBle.readyAddresses)) { scene(s); level = -1 }
                    ready = LedBle.readyAddresses
                    val t = System.currentTimeMillis() - at
                    val l = Schedules.fadeLevel(t, total, s.bright)
                    if (l != level) { bright(l); level = l }
                    if (t >= total) break
                    Thread.sleep(5_000)
                    LedBle.ensure(strips)
                }
            }
        }
        Thread.sleep(1_000) // let the last writes go out
    }

    private fun scene(s: Schedules.Schedule) {
        send(frame(0x7b, 0x00, 0x04, 0x01, 0x00, 0x00, 0xff, 0x00, 0xbf))
        if (s.effect > 0) send(frame(0x7b, 0xff, 0x03, s.effect, 0xff, 0xff, 0xff, 0xff, 0xbf))
        else send(frame(0x7b, 0xff, 0x07, s.color shr 16 and 0xff, s.color shr 8 and 0xff, s.color and 0xff, 0x00, 0xff, 0xbf))
    }

    private fun bright(pct: Int) = send(frame(0x7b, 0xff, 0x01, pct * 32 / 100, pct, 0x00, 0xff, 0xff, 0xbf))

    private fun send(bytes: ByteArray) {
        LedBle.writeAll(bytes)
        Thread.sleep(60)
    }

    private fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun finish() {
        LedBle.closeBackground()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Schedules", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Wall LED")
            .setContentText("Running a schedule")
            .build()
    }

    private companion object {
        const val CHANNEL = "schedules"
        const val NOTIFICATION = 1
    }
}
