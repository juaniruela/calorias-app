package com.calorias.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * Servicio en primer plano.
 * - Actividades por distancia o mixtas: GPS activo cada [Settings.gpsIntervalSec] s.
 * - Actividades por tiempo: GPS y actualizaciones de ubicación desactivados para ahorrar batería;
 *   el tiempo se cuenta con un temporizador.
 */
class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Serializa las lecturas del GPS, el temporizador y los cambios de modo. */
    private val lock = Mutex()
    private var timerJob: Job? = null
    private var gpsOn = false

    private var lastLoc: Location? = null
    private var lastTs = 0L
    private var fastSince = 0L   // inicio de velocidad > fastKmh (0 = no)
    private var verySince = 0L   // inicio de velocidad > veryFastKmh (0 = no)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val loc = r.lastLocation ?: return
            scope.launch { lock.withLock { handleFix(loc) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        store = SettingsStore(this)
        Notif.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notif.ID_TRACK, Notif.tracking(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            lock.withLock {
                val dao = AppDb.get(this@TrackingService).dao()
                if (dao.openDay() == null) {
                    stopSelf()
                    return@withLock
                }
                applyActive()
            }
        }
        if (timerJob == null) startTimer()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private suspend fun currentActive(): ActivityType? {
        val types = AppDb.get(this).dao().types()
        return types.firstOrNull { it.id == store.activeTypeId }
            ?: types.firstOrNull { it.isRest }
            ?: types.firstOrNull()
    }

    /** Enciende o apaga el GPS según la actividad activa. Las actividades por tiempo no usan GPS. */
    private suspend fun applyActive() {
        val active = currentActive()
        val wantGps = active != null && active.mode != Mode.TIME
        if (wantGps == gpsOn) return
        if (wantGps) {
            lastLoc = null
            fastSince = 0L
            verySince = 0L
            requestUpdates()
        } else {
            fused.removeLocationUpdates(callback)
        }
        gpsOn = wantGps
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val intervalMs = store.load().gpsIntervalSec * 1000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    /** Sin GPS: cuenta el tiempo de la actividad activa (por tiempo, o estática si es mixta). */
    private fun startTimer() {
        timerJob = scope.launch {
            while (true) {
                val interval = store.load().gpsIntervalSec
                delay(interval * 1000L)
                lock.withLock {
                    if (gpsOn) return@withLock
                    val dao = AppDb.get(this@TrackingService).dao()
                    val day = dao.openDay() ?: return@withLock
                    val active = currentActive() ?: return@withLock
                    when (active.mode) {
                        Mode.TIME -> dao.addActivity(day.id, active.id, interval.toLong(), 0.0, 0L)
                        Mode.MIXED -> dao.addActivity(day.id, active.id, interval.toLong(), 0.0, interval.toLong())
                        Mode.DISTANCE -> Unit
                    }
                    DayController.refreshWidget(this@TrackingService)
                }
            }
        }
    }

    /** Lectura del GPS. Debe llamarse con [lock] cogido. */
    private suspend fun handleFix(loc: Location) {
        val dao = AppDb.get(this).dao()
        val day = dao.openDay()
        if (day == null) {
            stopSelf()
            return
        }
        val s = store.load()
        val now = System.currentTimeMillis()
        val prev = lastLoc
        val maxGapMs = 3L * s.gpsIntervalSec * 1000L
        val dtMs = if (prev == null) s.gpsIntervalSec * 1000L else min(now - lastTs, maxGapMs)
        val dtSec = max(1L, dtMs / 1000)
        lastLoc = loc
        lastTs = now

        // Distancia: solo con buena precisión y descartando saltos imposibles (> 80 km/h).
        val rawDist = if (prev != null && loc.accuracy <= 50f && prev.accuracy <= 50f) {
            prev.distanceTo(loc).toDouble()
        } else 0.0
        val dist = if (rawDist <= 80.0 / 3.6 * dtSec + 20) rawDist else 0.0
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else dist / dtSec * 3.6

        // Reglas de velocidad sostenida.
        val fast = speedKmh > s.fastKmh
        val vfast = speedKmh > s.veryFastKmh
        fastSince = if (!fast) 0L else if (fastSince == 0L) now else fastSince
        verySince = if (!vfast) 0L else if (verySince == 0L) now else verySince

        val win = s.speedWindowMin * 60_000L
        val verySustained = verySince != 0L && now - verySince >= win
        val fastSustained = fastSince != 0L && now - fastSince >= win

        val types = dao.types()
        val rest = types.firstOrNull { it.isRest }
        val bike = types.firstOrNull { it.isBike }
        var active: ActivityType = types.firstOrNull { it.id == store.activeTypeId }
            ?: rest ?: types.firstOrNull() ?: return

        val target: ActivityType? = when {
            // > 50 km/h sostenido: tampoco vale ciclismo -> descanso
            verySustained && active.isBike -> rest
            // > 20 km/h sostenido: solo ciclismo
            fastSustained && !verySustained && !active.isBike -> bike ?: rest
            else -> null
        }
        target?.let {
            active = it
            store.activeTypeId = it.id
            // Si la regla cambia a una actividad por tiempo, el GPS se apaga.
            applyActive()
        }

        // Mixta: sin movimiento el tiempo cuenta como estático; con movimiento cuenta la distancia.
        val moving = dist >= s.moveThresholdM
        val addMeters = when (active.mode) {
            Mode.DISTANCE -> dist
            Mode.MIXED -> if (moving) dist else 0.0
            Mode.TIME -> 0.0
        }
        val staticSec = if (active.mode == Mode.MIXED && !moving) dtSec else 0L
        dao.addActivity(day.id, active.id, dtSec, addMeters, staticSec)
        DayController.refreshWidget(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Arranca el servicio o, si ya está activo, reaplica la actividad activa (GPS sí/no). */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }
}
