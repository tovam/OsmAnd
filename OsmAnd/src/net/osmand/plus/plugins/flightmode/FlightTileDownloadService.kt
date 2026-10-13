package net.osmand.plus.plugins.flightmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.osmand.plus.OsmandApplication
import net.osmand.plus.R

/** User-requested downloads belong to a foreground service, not a visible tab or ViewModel. */
class FlightTileDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = linkedSetOf<String>()
    private var task: Job? = null
    private var activeId: String? = null
    private val preferences by lazy { getSharedPreferences("flight-tile-downloads", MODE_PRIVATE) }
    private val store by lazy { FlightJourneyStore(this) }
    private val repository by lazy { FlightTerrainRepository(application as OsmandApplication) }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        queue.addAll(preferences.getStringSet("pending", emptySet()).orEmpty())
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.flight_download_background), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ID)
        if (intent?.action == PAUSE) {
            if (id != null) {
                queue.remove(id)
                publish(id, (updates.value[id] ?: FlightTerrainStatus()).copy(phase = FlightTerrainPhase.PAUSED, bytesPerSecond = 0))
                persist()
                if (activeId == id) task?.cancel()
            }
            if (activeId == null) startNext()
        } else {
            if (id != null) {
                queue.add(id)
                mutableUpdates.value = mutableUpdates.value + (id to FlightTerrainStatus(phase = FlightTerrainPhase.DOWNLOADING))
            }
            persist()
            val notification = notification(id ?: queue.firstOrNull(), FlightTerrainStatus(phase = FlightTerrainPhase.DOWNLOADING))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
            startNext()
        }
        return START_REDELIVER_INTENT
    }

    private fun persist() {
        preferences.edit().putStringSet("pending", queue.toSet()).apply()
    }

    private fun startNext() {
        if (activeId != null || task?.isActive == true) return
        val id = queue.firstOrNull() ?: run { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        activeId = id
        task = scope.launch {
            try {
                val journey = withContext(Dispatchers.IO) { store.load(id) }
                val assets = journey.offlineRequest
                val requests = assets.terrainTiles.map { FlightOfflineRequest(it, false, 0) } +
                    assets.standardSatelliteTiles.map { FlightOfflineRequest(it, true, 0) }
                val quote = FlightOfflineQuote(requests.sortedBy { it.tile.zoom },
                    journey.plan.stops.mapNotNull { s -> s.latitude?.let { lat -> s.longitude?.let { lat to it } } },
                    journey.plan.preparation?.bands.orEmpty())
                require(requests.isNotEmpty()) { "No saved tile request" }
                publish(id, FlightTerrainStatus(phase = FlightTerrainPhase.DOWNLOADING,
                    requestedTiles = quote.terrainCount, requestedSatelliteTiles = quote.satelliteCount))
                while (isActive) {
                    if (!connected()) {
                        publish(id, (updates.value[id] ?: FlightTerrainStatus()).copy(
                            phase = FlightTerrainPhase.DOWNLOADING, bytesPerSecond = 0,
                            message = getString(R.string.flight_download_waiting_network)))
                        delay(30_000)
                        continue
                    }
                    val result = repository.preloadPrepared(quote) { publish(id, it) }
                    withContext(Dispatchers.IO) { store.updateVerifiedOfflineAssets(id, assets, result.assets) }
                    publish(id, result.status)
                    if (result.status.phase == FlightTerrainPhase.READY) break
                    if (result.status.message == getString(R.string.flight_plan_low_space)) break
                    // Server throttling and transient outages keep the request queued.
                    publish(id, result.status.copy(phase = FlightTerrainPhase.DOWNLOADING, bytesPerSecond = 0))
                    delay(30_000)
                }
                queue.remove(id)
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(id, (updates.value[id] ?: FlightTerrainStatus()).copy(phase = FlightTerrainPhase.ERROR,
                    bytesPerSecond = 0, message = e.message))
                queue.remove(id)
                persist()
            } finally {
                activeId = null
                task = null
                if (scope.isActive) startNext()
            }
        }
    }

    private fun connected(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun publish(id: String, status: FlightTerrainStatus) {
        mutableUpdates.value = mutableUpdates.value + (id to status)
        if (activeId == id) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(id, status))
    }

    private fun notification(id: String?, status: FlightTerrainStatus): android.app.Notification {
        val pause = Intent(this, FlightTileDownloadService::class.java).setAction(PAUSE).putExtra(ID, id)
        val action = PendingIntent.getService(this, 0, pause, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_action_import)
            .setContentTitle(getString(R.string.flight_download_background))
            .setContentText(status.message ?: getString(R.string.flight_download_background))
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(status.requestedTiles + status.requestedSatelliteTiles,
                status.availableTiles + status.satelliteTiles, status.requestedTiles + status.requestedSatelliteTiles == 0)
            .addAction(0, getString(R.string.flight_plan_pause_label), action).build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        activeId?.let { publish(it, (updates.value[it] ?: FlightTerrainStatus()).copy(
            phase = FlightTerrainPhase.PAUSED, bytesPerSecond = 0, message = getString(R.string.flight_download_system_paused))) }
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "flight-tile-downloads"
        private const val NOTIFICATION = 7834
        private const val ID = "journey_id"
        private const val PAUSE = "pause"
        private val mutableUpdates = MutableStateFlow<Map<String, FlightTerrainStatus>>(emptyMap())
        internal val updates = mutableUpdates.asStateFlow()

        internal fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(context, Intent(context, FlightTileDownloadService::class.java).putExtra(ID, id))
        }

        internal fun pause(context: Context, id: String) {
            if (updates.value[id]?.phase == FlightTerrainPhase.DOWNLOADING)
                context.startService(Intent(context, FlightTileDownloadService::class.java).setAction(PAUSE).putExtra(ID, id))
        }
    }
}
