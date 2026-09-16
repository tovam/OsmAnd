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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private var simulation: FlightLiveSimulation? = null
    private var simulationClock: FlightSimulationClock? = null
    private var nextSimulatedFix = 0L
    @Volatile private var simulatedStart = false
    private var startRequested = false
    @Volatile private var realHandoffPending = false
    private var simulationActive = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
        serviceScope.launch {
            FlightUiActivity.journeys.collect { worker.post { updateSimulationActivity() } }
        }
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
        if (intent?.action in listOf(STOP, MICROPHONE, SIMULATION_CONTROL, CONFIRM_AIRBORNE) &&
            (realHandoffPending || !targetsCurrentJourney(intent))) {
            if (!startRequested && !updates.value.running) stopSelf()
            return restartMode()
        }
        if (intent?.action == CONFIRM_AIRBORNE) {
            if (!startRequested && !updates.value.running) stopSelf()
            worker.post {
                if (!updates.value.running || stopping || !targetsCurrentJourney(intent)) return@post
                val sample = updates.value.latest ?: return@post
                val next = tracking.confirmAirborne(sample,
                    if (simulatedStart) sample.timestampMillis else System.currentTimeMillis(),
                    journey?.plan?.preparation ?: FlightPreparation())
                if (next != tracking) try {
                    store!!.writeState(next)
                    tracking = next
                    updates.value = updates.value.copy(tracking = next)
                } catch (e: Exception) { fail(e) }
            }
            return restartMode()
        }
        if (intent?.action == SIMULATION_CONTROL) {
            if (!updates.value.running) {
                if (!startRequested) stopSelf()
                return restartMode()
            }
            worker.post {
                if (!targetsCurrentJourney(intent)) return@post
                simulationClock?.let { clock ->
                    clock.advance(SystemClock.elapsedRealtime())
                    clock.rate = intent.getIntExtra("rate", clock.rate).coerceIn(1, 300)
                    clock.paused = intent.getBooleanExtra("paused", clock.paused)
                    updateSimulationActivity()
                }
            }
            return restartMode()
        }
        if (intent?.action == MICROPHONE) {
            val enabled = intent.getBooleanExtra("enabled", false)
            if (!state.value.running) {
                if (!startRequested) stopSelf()
                return restartMode()
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
                        (if (simulatedStart) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) or
                            if (enabled && Build.VERSION.SDK_INT >= 30)
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            else 0,
                    )
                worker.post {
                    if (!targetsCurrentJourney(intent)) return@post
                    recorder?.stop()
                    if (!simulatedStart || simulationActive) recorder?.start(enabled)
                    updates.value = updates.value.copy(microphone = enabled)
                }
            } catch (e: Exception) {
                updates.value = updates.value.copy(error = e.message)
            }
            return restartMode()
        }
        if (intent?.action == STOP) {
            worker.post { if (targetsCurrentJourney(intent)) stopRecording(FlightTrackingPhase.STOPPED) }
            return if (realHandoffPending) START_STICKY else START_NOT_STICKY
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
                if (!updates.value.running) stopSelf()
            }
            return restartMode()
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val id = intent?.getStringExtra("journey") ?: prefs.getString("active", null)
        if (id == null) {
            if (!startRequested) stopSelf()
            return if (simulatedStart || !startRequested) START_NOT_STICKY else START_STICKY
        }
        val requestedSimulation = intent?.getBooleanExtra("simulation", false) == true
        val activeSimulation = when {
            realHandoffPending -> false
            startRequested || updates.value.running -> simulatedStart
            else -> null
        }
        val disposition = FlightWorkPolicy.startDisposition(activeSimulation, requestedSimulation)
        if (disposition == FlightStartDisposition.KEEP_CURRENT)
            return if (activeSimulation == true) START_NOT_STICKY else START_STICKY
        val replaceSimulation = disposition == FlightStartDisposition.REPLACE_SIMULATION
        if (replaceSimulation) realHandoffPending = true else simulatedStart = requestedSimulation
        startRequested = true
        try {
            val notification = notification(requestedSimulation)
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(
                    NOTIFICATION,
                    notification,
                    if (requestedSimulation) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    else ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            else startForeground(NOTIFICATION, notification)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            // Never label the previous simulation's measurements with the new real journal ID.
            updates.value = FlightLiveState(journeyId = id, error = message, simulation = requestedSimulation)
            prefs.edit().putString("error", message).remove("active").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        worker.post {
            if (replaceSimulation) {
                // Serialized handoff: save the test journal before opening the real recording.
                // Keep this foreground service alive, retaining the alarm's start exemption.
                stopRecording(FlightTrackingPhase.STOPPED, endService = false)
                runCatching { recorder?.stop() }
                recorder = null
                worker.removeCallbacks(batteryTick)
                worker.removeCallbacks(simulationTick)
                FlightNetworkAccess.release(ownerToken)
                simulation = null
                simulationClock = null
                simulationActive = false
                journey = null
                store = null
                environment = FlightEnvironmentReading()
                used = null
                found = null
                lastStateSave = 0L
                simulatedStart = false
                stopping = false
                realHandoffPending = false
            }
            if (journey?.id == id) return@post
            if (journey != null) {
                updates.value =
                    updates.value.copy(error = getString(R.string.flight_live_already_running))
                return@post
            }
            try {
                journey = FlightJourneyStore(this).load(id)
                if (simulatedStart) {
                    check(journey!!.simulation) { "Simulation requires a separate test journal" }
                    simulation =
                        FlightLiveSimulation(
                            journey!!.plan,
                            journey!!.trip,
                            System.currentTimeMillis(),
                        )
                    simulationClock = FlightSimulationClock(simulation!!.beginning)
                    nextSimulatedFix = simulation!!.beginning
                    journey =
                        FlightJourneyStore(this)
                            .save(
                                journey!!.copy(
                                    plan = simulation!!.activePlan,
                                    trip = recordedFlightTrip(journey!!.name, emptyList()),
                                )
                            )
                    FlightNetworkAccess.block(ownerToken).forEach { runCatching { it() } }
                }
                store = FlightRecordingStore(this, id)
                tracking = store!!.readState().copy(slowSinceMillis = null)
                if (
                    tracking.phase == FlightTrackingPhase.LANDED ||
                        tracking.phase == FlightTrackingPhase.STOPPED
                ) {
                    prefs.edit().remove("active").apply()
                    updates.value = updates.value.copy(running = false)
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
                if (!simulatedStart) prefs.edit().putString("active", id).apply()
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
                        simulation = simulatedStart,
                        simulationPlan = simulation?.activePlan,
                    )
                if (!simulatedStart) requestGps()
                recorder =
                    FlightEnvironmentRecorder(this, worker) { reading ->
                        worker.post { environment = reading }
                    }
                if (simulatedStart) updateSimulationActivity()
                else {
                    recorder?.start(false)
                    worker.post(batteryTick)
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
                }
            } catch (e: Exception) {
                fail(e, id)
            }
        }
        return if (requestedSimulation) START_NOT_STICKY else START_STICKY
    }

    private fun restartMode() = if (realHandoffPending || (startRequested && !simulatedStart)) START_STICKY else START_NOT_STICKY

    private fun targetsCurrentJourney(intent: Intent?) =
        FlightWorkPolicy.acceptsControl(intent?.getStringExtra("journey"), updates.value.journeyId)

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
        if (stopping || journey == null || simulatedStart) return
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
            acceptSample(sample, System.currentTimeMillis(), location.elapsedRealtimeNanos / 1_000_000)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Both GPS and the simulator use this exact persistence, sampling and phase-detection path. */
    private fun acceptSample(sample: FlightSample, nowMillis: Long, fixReceivedAt: Long = SystemClock.elapsedRealtime()) {
        if (stopping) return
        try {
            val previousState = tracking
            tracking =
                tracking.accept(
                    sample,
                    nowMillis,
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
                    lastFixElapsed = fixReceivedAt,
                    error = null,
                )
            if (tracking.phase == FlightTrackingPhase.LANDED)
                stopRecording(FlightTrackingPhase.LANDED)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Real recording outlives the UI; a rehearsal runs only while its flight is being viewed. */
    private fun updateSimulationActivity() {
        if (!simulatedStart || stopping) return
        val clock = simulationClock ?: return
        val visible = journey?.id in FlightUiActivity.journeys.value
        val active = FlightWorkPolicy.recordingActive(true, visible, clock.paused)
        clock.backgroundPaused = !visible
        clock.rebase(SystemClock.elapsedRealtime())
        worker.removeCallbacks(simulationTick)
        worker.removeCallbacks(batteryTick)
        if (active != simulationActive) {
            simulationActive = active
            recorder?.stop()
            environment = FlightEnvironmentReading()
            if (active) recorder?.start(updates.value.microphone)
        }
        updates.value =
            updates.value.copy(
                simulationPaused = !active,
                simulationRate = clock.rate,
                simulationBackgroundPaused = !visible,
            )
        if (active) {
            worker.post(simulationTick)
            worker.post(batteryTick)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
            NOTIFICATION,
            notification(),
        )
    }

    private val simulationTick =
        object : Runnable {
            override fun run() {
                if (stopping || !simulationActive) return
                val scenario = simulation ?: return
                val clock = simulationClock ?: return
                try {
                    val target =
                        clock.advance(SystemClock.elapsedRealtime()).coerceAtMost(scenario.end)
                    // Feed every virtual second: accelerated playback must still test the real
                    // 30-minute rule.
                    while (nextSimulatedFix <= target && !stopping) {
                        val sample =
                            scenario
                                .sampleAt(nextSimulatedFix)
                                .copy(
                                    index = samples.size,
                                    soundDb =
                                        environment.soundDb.takeIf { updates.value.microphone },
                                    soundSpectrum =
                                        environment.soundSpectrum.takeIf {
                                            updates.value.microphone
                                        },
                                    vibrationHz = environment.vibrationHz,
                                )
                        acceptSample(sample, nextSimulatedFix)
                        nextSimulatedFix += 1000
                    }
                    updates.value =
                        updates.value.copy(simulationProgress = scenario.progress(target))
                    if (target >= scenario.end && !stopping)
                        stopRecording(FlightTrackingPhase.STOPPED)
                    if (!stopping && simulationActive) worker.postDelayed(this, 100)
                } catch (e: Exception) {
                    fail(e)
                }
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

    private fun stopRecording(phase: FlightTrackingPhase, endService: Boolean = true) {
        if (stopping) return
        stopping = true
        runCatching {
            FlightLiveSafety.finalFix(samples.lastOrNull(), updates.value.latest)?.let { sample ->
                store?.append(sample)
                samples += sample
                journey?.let { j ->
                    updates.value = updates.value.copy(trip = recordedFlightTrip(j.name, samples.toList()))
                }
            }
        }.onFailure { updates.value = updates.value.copy(error = it.message) }
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
        journey?.let { j -> runCatching { FlightScheduleManager.cancel(this, j.id) }
            .onFailure { updates.value = updates.value.copy(error = it.message) } }
        if (endService && !realHandoffPending) stopSelf()
    }

    private fun fail(e: Exception, journeyId: String? = updates.value.journeyId) {
        val failed = updates.value.takeIf { it.journeyId == journeyId }
            ?: FlightLiveState(journeyId = journeyId, simulation = simulatedStart)
        updates.value =
            failed.copy(error = e.message ?: e.javaClass.simpleName, running = false)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString("error", e.message ?: e.javaClass.simpleName)
            .remove("active")
            .apply()
        stopping = true
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background data-sync sessions; persist the rehearsal before stopping.
        worker.post { if (simulatedStart && !stopping) stopRecording(FlightTrackingPhase.STOPPED) }
    }

    override fun onDestroy() {
        stopping = true
        serviceScope.cancel()
        worker.post {
            if (!simulatedStart) {
                // Permission revocation must not skip the remaining sensor/network cleanup.
                runCatching { manager.removeUpdates(this) }
                runCatching { manager.unregisterGnssStatusCallback(satellites) }
            }
            runCatching { recorder?.stop() }
            worker.removeCallbacks(batteryTick)
            worker.removeCallbacks(simulationTick)
            FlightNetworkAccess.release(ownerToken)
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

    private fun notification(simulationNotice: Boolean = simulatedStart): Notification {
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
            .setContentTitle(
                getString(
                    if (simulationNotice) R.string.flight_immersion_title
                    else R.string.flight_live_recording
                )
            )
            .setContentText(
                getString(
                    if (simulationNotice) {
                        if (simulationActive) R.string.flight_simulation_running_notice
                        else R.string.flight_simulation_paused_notice
                    } else R.string.flight_live_background
                )
            )
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
        const val CONFIRM_AIRBORNE = "flight.confirm.airborne"
        const val SIMULATION_CONTROL = "flight.simulation.control"
        private const val CHANNEL = "flight-recording"
        private const val NOTIFICATION = 19472
        private val updates = MutableStateFlow(FlightLiveState())
        val state = updates.asStateFlow()

        fun start(context: Context, id: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlightRecordingService::class.java).putExtra("journey", id),
            )

        fun simulate(context: Context, id: String) =
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlightRecordingService::class.java)
                    .putExtra("journey", id)
                    .putExtra("simulation", true),
            )

        fun simulationControl(context: Context, rate: Int, paused: Boolean, journeyId: String?) =
            context.startService(
                Intent(context, FlightRecordingService::class.java)
                    .setAction(SIMULATION_CONTROL)
                    .putExtra("journey", journeyId)
                    .putExtra("rate", rate)
                    .putExtra("paused", paused)
            )

        fun stop(context: Context, journeyId: String?) =
            context.startService(
                Intent(context, FlightRecordingService::class.java).setAction(STOP).putExtra("journey", journeyId)
            )

        fun confirmAirborne(context: Context, journeyId: String?) = context.startService(
            Intent(context, FlightRecordingService::class.java).setAction(CONFIRM_AIRBORNE).putExtra("journey", journeyId)
        )

        fun microphone(context: Context, enabled: Boolean, journeyId: String?) =
            context.startService(
                Intent(context, FlightRecordingService::class.java)
                    .setAction(MICROPHONE)
                    .putExtra("journey", journeyId)
                    .putExtra("enabled", enabled)
            )

        private fun absAngle(a: Float, b: Float): Float {
            val d = kotlin.math.abs(a - b) % 360f
            return kotlin.math.min(d, 360f - d)
        }
    }
}
