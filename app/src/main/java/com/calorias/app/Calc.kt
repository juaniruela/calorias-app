package com.calorias.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

val LOCALE_ES: Locale = Locale("es", "ES")

data class TypeLine(val type: ActivityType, val seconds: Long, val meters: Double, val kcal: Double)

data class DaySummary(
    val bmrKcal: Double,
    val activityKcal: Double,
    val intakeKcal: Double,
    val meters: Double,
    val steps: Int,
    val activeSeconds: Long,
    /** intake - (basal + actividad). Positivo = sobrante, negativo = déficit. */
    val balanceKcal: Double,
    /** Estimación de cambio de peso en gramos. Positivo = sube, negativo = baja. */
    val gramsChange: Double,
    val byType: List<TypeLine>
)

object Calc {
    /** Metabolismo basal por hora (Mifflin-St Jeor). */
    fun bmrPerHour(s: Settings): Double {
        val bmr = 10 * s.weightKg + 6.25 * s.heightCm - 5 * s.age + (if (s.male) 5 else -161)
        return bmr / 24.0
    }

    fun kcalOf(t: ActivityType, a: DayActivity, weightKg: Double): Double = when (t.mode) {
        Mode.DISTANCE -> t.factor * weightKg * a.meters / 1000.0
        Mode.TIME -> t.factor * weightKg * a.seconds / 3600.0
    }

    fun summarize(
        s: Settings,
        day: DayRecord,
        types: List<ActivityType>,
        acts: List<DayActivity>,
        food: List<FoodEntry>,
        now: Long
    ): DaySummary {
        val end = day.endedAt ?: now
        val hours = max(0.0, (end - day.startedAt) / 3_600_000.0)
        val byId = types.associateBy { it.id }
        val lines = acts.mapNotNull { a ->
            byId[a.typeId]?.let { t -> TypeLine(t, a.seconds, a.meters, kcalOf(t, a, s.weightKg)) }
        }
        val activity = lines.sumOf { it.kcal }
        val bmr = bmrPerHour(s) * hours
        val intake = food.sumOf { it.kcal }
        val balance = intake - (bmr + activity)
        val meters = lines.filter { it.type.mode == Mode.DISTANCE }.sumOf { it.meters }
        return DaySummary(
            bmrKcal = bmr,
            activityKcal = activity,
            intakeKcal = intake,
            meters = meters,
            steps = (meters / max(0.2, s.strideM)).roundToInt(),
            activeSeconds = lines.sumOf { it.seconds },
            balanceKcal = balance,
            gramsChange = balance / max(1.0, s.kcalPerKgFat) * 1000.0,
            byType = lines
        )
    }

    /** Consejo del día: rota según el día del año. */
    fun tip(s: Settings): String {
        val l = s.tips.lines().filter { it.isNotBlank() }
        return if (l.isEmpty()) "" else l[LocalDate.now().dayOfYear % l.size]
    }

    fun num(x: Double, decimals: Int = 0): String = String.format(LOCALE_ES, "%,.${decimals}f", x)

    fun gramsText(g: Double): String =
        if (g > 0) "subido ~${num(g)} g" else "bajado ~${num(-g)} g"

    fun hhmm(ms: Long): String = Instant.ofEpochMilli(ms)
        .atZone(ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}
