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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Servicio en primer plano que lee el GPS cada [Settings.gpsIntervalSec] segundos,
 * acumula tiempo y distancia en la actividad activa y aplica las reglas de velocidad.
 */
class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastLoc: Location? = null
    private var lastTs = 0L
    private var fastSince = 0L   // inicio de velocidad > fastKmh (0 = no)
    private var verySince = 0L   // inicio de velocidad > veryFastKmh (0 = no)

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val loc = r.lastLocation ?: return
            scope.launch { handleFix(loc) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        store = SettingsStore(this)
        Notif.channels(this)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, Notif.ID_TRACK, Notif.tracking(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            stopSelf()
            return START_NOT_STICKY
        }
        val intervalMs = store.load().gpsIntervalSec * 1000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        return START_NOT_STICKY
    }

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
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TrackingService::class.java))
        }
    }
}
