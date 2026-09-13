package net.osmand.plus.plugins.flightmode

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.osmand.plus.R

/** GPS, persistence and stop detection outlive every map/photo screen. All disk work is serial. */
class FlightRecordingService : Service(), LocationListener {
    private val thread = HandlerThread("FlightRecorder")
    private lateinit var worker: Handler
    private lateinit var manager: LocationManager
    private var store: FlightRecordingStore? = null
    private var journey: FlightJourney? = null
    private var samples = mutableListOf<FlightSample>()
    private var battery = mutableListOf<FlightBatteryPoint>()
    private var tracking = FlightTrackingState()
    private var environment = FlightEnvironmentReading()
    private var recorder: FlightEnvironmentRecorder? = null
    private var used: Int? = null
    private var found: Int? = null
    @Volatile private var stopping = false
    private val ownerToken = Any()
    private var recordingPolicy = FlightRecordingPolicy()
    private var lastStateSave = 0L
    private val satellites =
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                found = status.satelliteCount
                used = (0 until status.satelliteCount).count { status.usedInFix(it) }
            }
        }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        currentOwner = ownerToken
        thread.start()
        worker = Handler(thread.looper)
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= 26)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        getString(R.string.flight_live_recording),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MICROPHONE) {
            val enabled = intent.getBooleanExtra("enabled", false)
            if (!state.value.running) {
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                if (enabled)
                    check(
                        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(
                        NOTIFICATION,
                        notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                            if (enabled && Build.VERSION.SDK_INT >= 30)
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            else 0,
                    )
                worker.post {
                    recorder?.stop()
                    recorder?.start(enabled)
                    updates.value = updates.value.copy(microphone = enabled)
                }
            } catch (e: Exception) {
                updates.value = updates.value.copy(error = e.message)
            }
            return START_STICKY
        }
        if (intent?.action == STOP) {
            worker.post { stopRecording(FlightTrackingPhase.STOPPED) }
            return START_NOT_STICKY
        }
        if (intent?.action == POLICY) {
            worker.post {
                recordingPolicy =
                    FlightRecordingPolicy(
                        intent.getFloatExtra("distance", 1000f).coerceIn(100f, 8000f),
                        intent.getFloatExtra("interval", 20f).coerceIn(1f, 120f),
                        intent.getFloatExtra("turn", 2f).coerceIn(1f, 10f),
                        intent.getFloatExtra("deviation", 2f).coerceIn(1f, 10f),
                    )
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putFloat("distance", recordingPolicy.cruisePointDistanceMeters)
                    .putFloat("interval", recordingPolicy.maximumStraightIntervalSeconds)
                    .putFloat("turn", recordingPolicy.turnAcceleration)
                    .putFloat("deviation", recordingPolicy.routeDeviationAcceleration)
                    .apply()
                updates.value = updates.value.copy(policy = recordingPolicy)
            }
            return START_STICKY
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = intent?.getStringExtra("journey") ?: prefs.getString("active", null)
        if (id == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(
                    NOTIFICATION,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            else startForeground(NOTIFICATION, notification)
        } catch (e: Exception) {
            updates.value = updates.value.copy(journeyId = id, error = e.message, running = false)
            prefs.edit().putString("error", e.message).remove("active").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        worker.post {
            if (journey?.id == id) return@post
            if (journey != null) {
                updates.value =
                    updates.value.copy(error = getString(R.string.flight_live_already_running))
                return@post
            }
            try {
                journey = FlightJourneyStore(this).load(id)
                store = FlightRecordingStore(this, id)
                tracking = store!!.readState().copy(slowSinceMillis = null)
                if (
                    tracking.phase == FlightTrackingPhase.LANDED ||
                        tracking.phase == FlightTrackingPhase.STOPPED
                ) {
                    stopSelf()
                    return@post
                }
                val recorded = store!!.samplesAndBattery()
                samples = recorded.first.toMutableList()
                battery = recorded.second.toMutableList()
                if (tracking.baselineAltitude == null)
                    tracking =
                        tracking.copy(
                            baselineAltitude = samples.firstNotNullOfOrNull { it.altitudeMeters }
                        )
                prefs.edit().putString("active", id).apply()
                recordingPolicy =
                    FlightRecordingPolicy(
                        prefs.getFloat("distance", 1000f).coerceIn(100f, 8000f),
                        prefs.getFloat("interval", 20f).coerceIn(1f, 120f),
                        prefs.getFloat("turn", 2f).coerceIn(1f, 10f),
                        prefs.getFloat("deviation", 2f).coerceIn(1f, 10f),
                    )
                updates.value =
                    FlightLiveState(
                        id,
                        recordedFlightTrip(journey!!.name, samples.toList()),
                        samples.lastOrNull(),
                        tracking,
                        battery.toList(),
                        running = true,
                        policy = recordingPolicy,
                    )
                requestGps()
                recorder =
                    FlightEnvironmentRecorder(this, worker) { reading ->
                            worker.post { environment = reading }
                        }
                        .also { it.start(false) }
                worker.post(batteryTick)
            } catch (e: Exception) {
                fail(e)
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestGps() {
        check(
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        ) {
            getString(R.string.flight_live_need_location)
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            updates.value = updates.value.copy(error = getString(R.string.flight_live_gps_disabled))
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.looper)
        manager.registerGnssStatusCallback(satellites, worker)
    }

    override fun onLocationChanged(location: android.location.Location) {
        if (stopping || journey == null) return
        try {
            val age = SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000
            if (age !in 0..15_000) return
            val prior = updates.value.latest
            if (prior != null && location.time <= prior.timestampMillis) return
            // Use the same MSL/geoid correction as the rest of OsmAnd, rather than mixing an
            // ellipsoidal Android altitude with sea-level terrain and imported GPX altitudes.
            val corrected =
                net.osmand.plus.OsmAndLocationProvider.convertLocation(
                    location,
                    applicationContext as net.osmand.plus.OsmandApplication,
                )
            val sample =
                FlightSample(
                    samples.size,
                    0,
                    location.time,
                    location.latitude,
                    location.longitude,
                    corrected.altitude.takeIf { corrected.hasAltitude() },
                    location.speed.takeIf { location.hasSpeed() },
                    location.bearing.takeIf { location.hasBearing() }
                        ?: prior?.let {
                            FlightTrackMath.bearingBetween(
                                it.latitude,
                                it.longitude,
                                location.latitude,
                                location.longitude,
                            )
                        },
                    location.accuracy.takeIf { location.hasAccuracy() },
                    satellitesUsed = used,
                    satellitesFound = found,
                    soundDb = environment.soundDb.takeIf { updates.value.microphone },
                    soundSpectrum = environment.soundSpectrum.takeIf { updates.value.microphone },
                    vibrationHz = environment.vibrationHz,
                )
            val previousState = tracking
            tracking =
                tracking.accept(
                    sample,
                    System.currentTimeMillis(),
                    journey!!.plan.preparation ?: FlightPreparation(),
                )
            val elapsed = SystemClock.elapsedRealtime()
            if (
                previousState.phase != tracking.phase ||
                    previousState.baselineAltitude != tracking.baselineAltitude ||
                    previousState.slowSinceMillis != tracking.slowSinceMillis ||
                    elapsed - lastStateSave >= 30_000
            ) {
                store!!.writeState(tracking)
                lastStateSave = elapsed
            }
            val previous = samples.lastOrNull()
            val delta =
                previous?.let { (sample.timestampMillis - it.timestampMillis) / 1000f }
                    ?: Float.POSITIVE_INFINITY
            val turn =
                if (previous?.bearingDegrees != null && sample.bearingDegrees != null && delta > 0)
                    absAngle(previous.bearingDegrees, sample.bearingDegrees) / delta
                else 0f
            val deviation =
                FlightRouteHypothesis.distanceToPlanKm(journey!!.plan, sample)
                    ?.times(1000)
                    ?.toFloat() ?: 0f
            val interval =
                recordingPolicy.intervalSeconds(sample.speedMetersPerSecond ?: 0f, turn, deviation)
            val record = delta >= interval || tracking.phase == FlightTrackingPhase.LANDED
            if (record) {
                store!!.append(sample)
                samples += sample
            }
            updates.value =
                updates.value.copy(
                    trip =
                        if (record) recordedFlightTrip(journey!!.name, samples.toList())
                        else updates.value.trip,
                    latest = sample,
                    tracking = tracking,
                    lastFixElapsed = SystemClock.elapsedRealtime(),
                    error = null,
                )
            if (tracking.phase == FlightTrackingPhase.LANDED)
                stopRecording(FlightTrackingPhase.LANDED)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private val batteryTick =
        object : Runnable {
            override fun run() {
                if (stopping) return
                try {
                    registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
                        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        if (level >= 0 && scale > 0) {
                            val item =
                                FlightBatteryPoint(
                                    System.currentTimeMillis(),
                                    level * 100f / scale,
                                    b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                                )
                            store?.append(item)
                            battery += item
                            updates.value = updates.value.copy(battery = battery.toList())
                        }
                    }
                } catch (e: Exception) {
                    fail(e)
                    return
                }
                worker.postDelayed(this, 60_000)
            }
        }

    private fun stopRecording(phase: FlightTrackingPhase) {
        if (stopping) return
        stopping = true
        tracking = tracking.copy(phase = phase, slowSinceMillis = null)
        runCatching { store?.writeState(tracking) }
            .onFailure { updates.value = updates.value.copy(error = it.message) }
        // Merge the append-only recording into the current metadata, retaining photo edits made by
        // the UI.
        journey?.let { j ->
            runCatching {
                    FlightJourneyStore(this).update(j.id) {
                        it.copy(
                            updatedAtMillis = System.currentTimeMillis(),
                            plan =
                                it.plan.copy(
                                    preparation = it.plan.preparation?.copy(automatic = false)
                                ),
                        )
                    }
                }
                .onFailure { updates.value = updates.value.copy(error = it.message) }
        }
        updates.value = updates.value.copy(running = false, tracking = tracking)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("active").apply()
        journey?.let { FlightScheduleManager.cancel(this, it.id) }
        stopSelf()
    }

    private fun fail(e: Exception) {
        updates.value =
            updates.value.copy(error = e.message ?: e.javaClass.simpleName, running = false)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("error", e.message ?: e.javaClass.simpleName)
            .remove("active")
            .apply()
        stopping = true
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        worker.post {
            manager.removeUpdates(this)
            manager.unregisterGnssStatusCallback(satellites)
            recorder?.stop()
            worker.removeCallbacks(batteryTick)
            thread.quitSafely()
            if (currentOwner === ownerToken) updates.value = updates.value.copy(running = false)
        }
        super.onDestroy()
    }

    override fun onProviderDisabled(provider: String) {
        updates.value = updates.value.copy(error = getString(R.string.flight_live_gps_disabled))
    }

    override fun onProviderEnabled(provider: String) {
        updates.value = updates.value.copy(error = null)
    }

    @Deprecated("Legacy callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun notification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open =
            launch?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        // Stop is intentionally in the app behind confirmation, not an accidental notification tap.
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_gps_info)
            .setContentTitle(getString(R.string.flight_live_recording))
            .setContentText(getString(R.string.flight_live_background))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile private var currentOwner: Any? = null
        const val PREFS = "flight-recording-service"
        const val STOP = "flight.stop"
        const val POLICY = "flight.policy"
        const val MICROPHONE = "flight.microphone"
        private const val CHANNEL = "flight-recording"
        private const val NOTIFICATION = 19472
        private val updates = MutableStateFlow(FlightLiveState())
        val state = updates.asStateFlow()

        fun start(context: Context, id: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlightRecordingService::class.java).putExtra("journey", id),
            )

        fun stop(context: Context) =
            context.startService(
                Intent(context, FlightRecordingService::class.java).setAction(STOP)
            )

        fun microphone(context: Context, enabled: Boolean) =
            context.startService(
                Intent(context, FlightRecordingService::class.java)
                    .setAction(MICROPHONE)
                    .putExtra("enabled", enabled)
            )

        private fun absAngle(a: Float, b: Float): Float {
            val d = kotlin.math.abs(a - b) % 360f
            return kotlin.math.min(d, 360f - d)
        }
    }
}
