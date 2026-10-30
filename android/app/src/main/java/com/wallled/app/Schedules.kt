package com.wallled.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The page's schedules, run by an alarm so they also work while the app is closed. The page
 * hands over its list as JSON (see `nextFire` in led-control.html, which this mirrors):
 * `{ id, at: "HH:MM", days: [0-6, Sunday = 0], action: "scene" | "wake" | "off",
 *    scene: { color: "#rrggbb", bright, effect }, minutes, on }`.
 * Only the soonest start is armed; when it fires, [ScheduleService] runs what is due and
 * arms the next one.
 */
object Schedules {
    class Schedule(
        val id: String, val time: LocalTime, val days: Set<Int>, val action: String,
        val color: Int, val bright: Int, val effect: Int, val minutes: Int,
    )

    private const val ACTION_FIRE = "com.wallled.app.SCHEDULE"

    fun save(ctx: Context, json: String) {
        prefs(ctx).edit().putString("list", json).apply()
        arm(ctx)
    }

    fun load(ctx: Context): List<Schedule> {
        val arr = try { JSONArray(prefs(ctx).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optBoolean("on") }.mapNotNull { o ->
            try {
                val scene = o.optJSONObject("scene")
                val days = o.getJSONArray("days")
                Schedule(
                    id = o.getString("id"),
                    time = LocalTime.parse(o.getString("at")),
                    days = (0 until days.length()).map { days.getInt(it) }.toSet(),
                    action = o.getString("action"),
                    color = scene?.optString("color")?.removePrefix("#")?.toIntOrNull(16) ?: 0xffffff,
                    bright = scene?.optInt("bright", 100) ?: 100,
                    effect = scene?.optInt("effect", 0) ?: 0,
                    minutes = o.optInt("minutes", 20),
                )
            } catch (e: Exception) { null }
        }
    }

    /** Next start strictly after [from]; a wake-up fade starts `minutes` before its time. */
    fun nextFire(s: Schedule, from: ZonedDateTime): ZonedDateTime? {
        val lead = if (s.action == "wake") s.minutes.toLong() else 0L
        for (d in 0L..8L) {
            val t = ZonedDateTime.of(from.toLocalDate().plusDays(d), s.time, from.zone)
            if (t.dayOfWeek.value % 7 !in s.days) continue
            val fire = t.minusMinutes(lead)
            if (fire.isAfter(from)) return fire
        }
        return null
    }

    /** Arms an alarm for the soonest start, or cancels it if there is none. */
    fun arm(ctx: Context) {
        val now = ZonedDateTime.now()
        val next = load(ctx).mapNotNull { nextFire(it, now) }.minByOrNull { it.toInstant() }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (next == null) { am.cancel(pi); return }
        val at = next.toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    /** Schedules that started in the last 10 minutes and haven't run yet, with their start (ms). */
    fun takeDue(ctx: Context): List<Pair<Schedule, Long>> {
        val now = ZonedDateTime.now()
        val p = prefs(ctx)
        val due = ArrayList<Pair<Schedule, Long>>()
        for (s in load(ctx)) {
            val fire = nextFire(s, now.minusMinutes(10)) ?: continue
            if (fire.isAfter(now.plusSeconds(30))) continue
            val at = fire.toInstant().toEpochMilli()
            if (p.getLong("ran:" + s.id, 0) == at) continue
            p.edit().putLong("ran:" + s.id, at).apply()
            due += s to at
        }
        return due
    }

    /** Brightness [t] ms into a fade of [total] ms up to [target], eased in like the page's fadeLevel. */
    fun fadeLevel(t: Long, total: Long, target: Int): Int {
        val x = (t.toDouble() / total).coerceIn(0.0, 1.0)
        return maxOf(1, Math.round(target * x * x).toInt())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("schedules", Context.MODE_PRIVATE)

    /** Starts the service when an alarm fires; re-arms after a reboot, an update or a clock change. */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            LedBle.init(ctx)
            if (intent.action == ACTION_FIRE && LedBle.hasPermissions()) {
                ctx.startForegroundService(Intent(ctx, ScheduleService::class.java))
            } else {
                arm(ctx)
            }
        }
    }
}
