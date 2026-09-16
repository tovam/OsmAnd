package net.osmand.plus.plugins.flightmode

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import net.osmand.plus.R
import org.json.JSONObject

/** Each explicitly armed journal owns one alarm; reboot restores only uncompleted schedules. */
internal object FlightScheduleManager {
    private const val PREFS = "flight-schedules"
    const val START = "flight.scheduled.start"

    data class PermissionStatus(val label: Int, val granted: Boolean)

    /** Read the small device-owned alarm registry once, never once per library row. */
    fun scheduledStarts(context: Context): Map<String, FlightLocalSchedule> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (id, raw) ->
            runCatching {
                val json = JSONObject(raw as String)
                val preparation = FlightPreparation.fromJson(json) ?: return@runCatching null
                val at = json.optLong("scheduledStart", preparation.startMillis)
                if (at > 0) id to FlightLocalSchedule(at, preparation.departureOffsetMinutes) else null
            }.getOrNull()
        }.toMap()

    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun scheduled(context: Context, id: String?): FlightPreparation? {
        if (id == null) return null
        val stored =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null)
                ?: return null
        return FlightPreparation.fromJson(JSONObject(stored))
    }

    fun scheduledStart(context: Context, id: String): Long? {
        val stored =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null)
                ?: return null
        val json = JSONObject(stored)
        return json
            .optLong("scheduledStart", FlightPreparation.fromJson(json)?.startMillis ?: 0L)
            .takeIf { it > 0 }
    }

    fun permissionStatuses(context: Context): List<PermissionStatus> {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        return listOf(
            PermissionStatus(
                R.string.flight_plan_permission_gps,
                granted(Manifest.permission.ACCESS_FINE_LOCATION),
            ),
            PermissionStatus(
                R.string.flight_plan_permission_background,
                Build.VERSION.SDK_INT < 29 ||
                    granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            ),
            PermissionStatus(
                R.string.flight_plan_permission_alarm,
                Build.VERSION.SDK_INT < 31 ||
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                        .canScheduleExactAlarms(),
            ),
            PermissionStatus(
                R.string.flight_plan_permission_notifications,
                Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }

    fun missingPermissions(context: Context): List<String> = buildList {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
            add(context.getString(R.string.flight_live_need_location))
        if (
            Build.VERSION.SDK_INT >= 29 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                ) != PackageManager.PERMISSION_GRANTED
        )
            add(context.getString(R.string.flight_live_need_background))
        if (
            Build.VERSION.SDK_INT >= 31 &&
                !(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .canScheduleExactAlarms()
        )
            add(context.getString(R.string.flight_live_need_alarm))
        if (
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        )
            add(context.getString(R.string.flight_live_need_notifications))
    }

    private fun alarmIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FlightScheduleReceiver::class.java)
                .setAction(START)
                .setData(
                    Uri.fromParts(
                        "flight-schedule",
                        id.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,80}"))) },
                        null,
                    )
                )
                .putExtra("journey", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun arm(context: Context, journey: FlightJourney): Long {
        val p = requireNotNull(journey.plan.preparation)
        require(
            p.departureMillis > 0 &&
                p.arrivalMillis > p.departureMillis &&
                p.arrivalMillis > System.currentTimeMillis()
        ) {
            context.getString(R.string.flight_plan_dates_invalid)
        }
        require(
            journey.plan.stops.size >= 2 &&
                journey.plan.stops.all { it.latitude != null && it.longitude != null }
        ) {
            context.getString(R.string.flight_plan_coverage_required)
        }
        val phase = FlightRecordingStore(context, journey.id).readState().phase
        check(phase != FlightTrackingPhase.LANDED && phase != FlightTrackingPhase.STOPPED) {
            context.getString(R.string.flight_plan_repeat_completed)
        }
        check(missingPermissions(context).isEmpty()) {
            missingPermissions(context).joinToString(" · ")
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.startMillis.coerceAtLeast(System.currentTimeMillis() + 1000)
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            at,
            alarmIntent(context, journey.id),
        )
        prefs.edit().putString(journey.id, p.toJson().put("scheduledStart", at).toString()).apply()
        return at
    }

    fun cancel(context: Context, id: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(
            alarmIntent(context, id)
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }

    fun restore(context: Context) {
        if (missingPermissions(context).isNotEmpty()) return
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (id, value) ->
            runCatching {
                val p =
                    FlightPreparation.fromJson(JSONObject(value as String)) ?: return@runCatching
                val phase = FlightRecordingStore(context, id).readState().phase
                if (
                    p.arrivalMillis + 24 * 3_600_000L < now ||
                        phase == FlightTrackingPhase.LANDED ||
                        phase == FlightTrackingPhase.STOPPED
                )
                    cancel(context, id)
                else
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                        .setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            p.startMillis.coerceAtLeast(now + 60_000),
                            alarmIntent(context, id),
                        )
            }
        }
    }
}

class FlightScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == FlightScheduleManager.START) {
            val id = intent.getStringExtra("journey") ?: return
            try {
                FlightRecordingService.start(context, id)
            } catch (e: Exception) {
                context
                    .getSharedPreferences(FlightRecordingService.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString("error", e.message ?: e.javaClass.simpleName)
                    .apply()
            }
        } else {
            val pending = goAsync()
            Thread {
                    try {
                        FlightScheduleManager.restore(context.applicationContext)
                    } finally {
                        pending.finish()
                    }
                }
                .start()
        }
    }
}
