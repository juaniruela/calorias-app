package com.calorias.app

import android.content.Context

val DEFAULT_TIPS = listOf(
    "Bebe al menos 2 litros de agua hoy.",
    "Camina 10 minutos después de cada comida.",
    "Prioriza proteína y verdura en la cena.",
    "Sube escaleras en lugar de usar el ascensor.",
    "Dormir poco aumenta el apetito: intenta descansar 7-8 horas.",
    "Pesar los alimentos unos días ayuda a calibrar las estimaciones."
)

/** Todos los parámetros son editables desde la pantalla de Ajustes. */
data class Settings(
    val weightKg: Double = 75.0,
    val heightCm: Double = 175.0,
    val age: Int = 30,
    val male: Boolean = true,
    val strideM: Double = 0.75,
    val kcalPerKgFat: Double = 7700.0,
    val gpsIntervalSec: Int = 30,
    /** Cada cuántos segundos se guarda un punto en la ruta (si la actividad guarda rutas). */
    val routePointSec: Int = 60,
    val fastKmh: Double = 20.0,
    val veryFastKmh: Double = 50.0,
    val speedWindowMin: Int = 5,
    /** Desplazamiento mínimo entre lecturas GPS para considerar que hay movimiento (actividad mixta). */
    val moveThresholdM: Double = 15.0,
    val summaryHour: Int = 23,
    val summaryMinute: Int = 0,
    val tips: String = DEFAULT_TIPS.joinToString("\n"),
    /** Clave de Google Maps (Maps Static API) para dibujar los recorridos. La introduce el usuario en Ajustes. */
    val mapsApiKey: String = ""
)

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("calorias", Context.MODE_PRIVATE)

    var activeTypeId: Long
        get() = sp.getLong("activeTypeId", -1L)
        set(v) {
            sp.edit().putLong("activeTypeId", v).apply()
        }

    fun load(): Settings {
        val d = Settings()
        return Settings(
            weightKg = sp.getFloat("weightKg", d.weightKg.toFloat()).toDouble(),
            heightCm = sp.getFloat("heightCm", d.heightCm.toFloat()).toDouble(),
            age = sp.getInt("age", d.age),
            male = sp.getBoolean("male", d.male),
            strideM = sp.getFloat("strideM", d.strideM.toFloat()).toDouble(),
            kcalPerKgFat = sp.getFloat("kcalPerKgFat", d.kcalPerKgFat.toFloat()).toDouble(),
            gpsIntervalSec = sp.getInt("gpsIntervalSec", d.gpsIntervalSec),
            routePointSec = sp.getInt("routePointSec", d.routePointSec),
            fastKmh = sp.getFloat("fastKmh", d.fastKmh.toFloat()).toDouble(),
            veryFastKmh = sp.getFloat("veryFastKmh", d.veryFastKmh.toFloat()).toDouble(),
            speedWindowMin = sp.getInt("speedWindowMin", d.speedWindowMin),
            moveThresholdM = sp.getFloat("moveThresholdM", d.moveThresholdM.toFloat()).toDouble(),
            summaryHour = sp.getInt("summaryHour", d.summaryHour),
            summaryMinute = sp.getInt("summaryMinute", d.summaryMinute),
            tips = sp.getString("tips", d.tips) ?: d.tips,
            mapsApiKey = sp.getString("mapsApiKey", "") ?: ""
        )
    }

    fun save(s: Settings) {
        sp.edit()
            .putFloat("weightKg", s.weightKg.toFloat())
            .putFloat("heightCm", s.heightCm.toFloat())
            .putInt("age", s.age)
            .putBoolean("male", s.male)
            .putFloat("strideM", s.strideM.toFloat())
            .putFloat("kcalPerKgFat", s.kcalPerKgFat.toFloat())
            .putInt("gpsIntervalSec", s.gpsIntervalSec)
            .putInt("routePointSec", s.routePointSec)
            .putFloat("fastKmh", s.fastKmh.toFloat())
            .putFloat("veryFastKmh", s.veryFastKmh.toFloat())
            .putInt("speedWindowMin", s.speedWindowMin)
            .putFloat("moveThresholdM", s.moveThresholdM.toFloat())
            .putInt("summaryHour", s.summaryHour)
            .putInt("summaryMinute", s.summaryMinute)
            .putString("tips", s.tips)
            .putString("mapsApiKey", s.mapsApiKey)
            .apply()
    }
}
